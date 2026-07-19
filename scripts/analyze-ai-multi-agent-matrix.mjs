#!/usr/bin/env node

import {mkdir, readFile, readdir, stat, writeFile} from 'node:fs/promises';
import {basename, join, resolve} from 'node:path';
import {evaluateMultiAgentCase} from './ai-multi-agent-evaluator.mjs';

function parseArgs(argv) {
  const args = {
    input: resolve('experiments', 'ai-multi-agent-matrix', 'output'),
    output: resolve('/tmp', 'ai-multi-agent-matrix-analysis')
  };
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    const value = argv[index + 1];
    if (key === '--input') args.input = resolve(value), index += 1;
    else if (key === '--output') args.output = resolve(value), index += 1;
    else if (key === '--help') {
      process.stdout.write(
        'Usage: node scripts/analyze-ai-multi-agent-matrix.mjs ' +
        '[--input RUN_OR_OUTPUT_DIRECTORY] [--output ANALYSIS_DIRECTORY]\n' +
        'Re-evaluates saved case.json, response JSON, and ATIF without provider calls.\n'
      );
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${key}`);
    }
  }
  return args;
}

async function exists(path) {
  try {
    await stat(path);
    return true;
  } catch {
    return false;
  }
}

async function json(path) {
  return JSON.parse(await readFile(path, 'utf8'));
}

async function runDirectories(input) {
  if (await exists(join(input, 'run.json'))) return [input];
  const entries = await readdir(input, {withFileTypes: true});
  const directories = [];
  for (const entry of entries) {
    const candidate = join(input, entry.name);
    if (entry.isDirectory() && await exists(join(candidate, 'run.json'))) {
      directories.push(candidate);
    }
  }
  return directories.sort();
}

async function caseDirectories(runDirectory) {
  const entries = await readdir(runDirectory, {withFileTypes: true});
  const directories = [];
  for (const entry of entries) {
    const directory = join(runDirectory, entry.name);
    if (entry.isDirectory() && await exists(join(directory, 'case.json'))) {
      directories.push(directory);
    }
  }
  return directories.sort();
}

async function responses(caseDirectory) {
  const names = (await readdir(caseDirectory))
    .filter(name => /^response-\d+\.json$/.test(name))
    .sort();
  const values = [];
  for (const name of names) values.push(await json(join(caseDirectory, name)));
  return values;
}

function confirmationRequestCount(items) {
  const ids = new Set();
  for (const item of items) {
    for (const event of item?.events || []) {
      if (event?.subtype === 'mutation_confirmation_required'
        && event?.metadata?.confirmationRequestId) {
        ids.add(event.metadata.confirmationRequestId);
      }
    }
  }
  return ids.size;
}

function confirmationEvidence(items) {
  const evidence = [];
  const seen = new Set();
  for (const item of items) {
    for (const event of item?.events || []) {
      const id = event?.metadata?.confirmationRequestId;
      if (event?.subtype !== 'mutation_confirmation_required' || !id || seen.has(id)) continue;
      seen.add(id);
      evidence.push({
        confirmationRequestId: id,
        conversationId: item.conversationId,
        toolName: event.metadata.toolName,
        argumentsSummary: event.metadata.argumentsSummary
      });
    }
  }
  return evidence;
}

async function approvalDecisions(caseDirectory) {
  const names = (await readdir(caseDirectory))
    .filter(name => /^decision-\d+\.json$/.test(name)).sort();
  const decisions = [];
  for (const name of names) {
    decisions.push(await json(join(caseDirectory, name)));
  }
  return decisions;
}

async function eventDurations(runDirectory) {
  const path = join(runDirectory, 'events.jsonl');
  if (!await exists(path)) return new Map();
  const events = (await readFile(path, 'utf8')).split(/\r?\n/)
    .filter(Boolean).map(line => JSON.parse(line));
  const starts = new Map();
  const durations = new Map();
  for (const event of events) {
    if (event.type === 'case-start') starts.set(event.name, Date.parse(event.timestamp));
    if (event.type === 'case-finish' && starts.has(event.name)) {
      durations.set(event.name, Date.parse(event.timestamp) - starts.get(event.name));
    }
  }
  return durations;
}

function commandFor(run) {
  // --run-id is intentionally omitted: the runner autogenerates a fresh
  // timestamped ID, so a re-run gets its own output directory and mutation
  // record names instead of corrupting the analyzed artifacts.
  const options = [
    ['--mode', run.mode], ['--strategy', run.strategy],
    ['--task', run.task], ['--model', run.model], ['--effort', run.effort],
    ['--runtime', run.runtime], ['--max-agents', run.maxAgents], ['--limit', run.limit],
    ['--permission-mode', run.permissionMode]
  ];
  return `node scripts/ai-multi-agent-matrix.mjs ${options
    .filter(([, value]) => value !== null && value !== undefined)
    .map(([key, value]) => `${key} ${String(value)}`).join(' ')}`;
}

async function analyzeRun(runDirectory) {
  const run = await json(join(runDirectory, 'run.json'));
  const recordedResults = await exists(join(runDirectory, 'results.json'))
    ? await json(join(runDirectory, 'results.json')) : [];
  const recordedByName = new Map(recordedResults.map(result => [result.name, result]));
  const durations = await eventDurations(runDirectory);
  const cases = [];
  const directories = await caseDirectories(runDirectory);
  if (!Number.isSafeInteger(run.cases) || run.cases < 1 || directories.length !== run.cases) {
    throw new Error(`${run.runId}: expected ${run.cases} case directories, found ${directories.length}`);
  }
  if (recordedResults.length && recordedResults.length !== run.cases) {
    throw new Error(`${run.runId}: expected ${run.cases} recorded results, found ${recordedResults.length}`);
  }
  if (recordedByName.size !== recordedResults.length) {
    throw new Error(`${run.runId}: results.json contains duplicate case names`);
  }
  for (const caseDirectory of directories) {
    const name = basename(caseDirectory);
    const definition = await json(join(caseDirectory, 'case.json'));
    // The runner only saves the ATIF when the trajectory fetch succeeded; a
    // missing file must still re-evaluate (and fail) instead of crashing.
    const trajectoryPath = join(caseDirectory, 'trajectory.atif.json');
    const trajectoryMissing = !await exists(trajectoryPath);
    const trajectory = trajectoryMissing ? undefined : await json(trajectoryPath);
    const responseHistory = await responses(caseDirectory);
    const response = responseHistory.at(-1) || null;
    const recorded = recordedByName.get(name) || {};
    const confirmationRequests = confirmationRequestCount(responseHistory);
    const confirmations = confirmationEvidence(responseHistory);
    const decisions = await approvalDecisions(caseDirectory);
    const legacyApprovalInference = (run.evidenceSchemaVersion || 1) < 2;
    const reevaluated = evaluateMultiAgentCase({
      testCase: definition.testCase,
      task: definition.task,
      trajectory,
      // The runner auto-approves each unique saved confirmation. Reconstruct the
      // count from source responses rather than trusting the derived results.json.
      approvals: confirmationRequests,
      response,
      failure: recorded.failure,
      permissionMode: run.permissionMode,
      maxAgents: run.maxAgents,
      confirmationRequests,
      confirmationEvidence: confirmations,
      approvalDecisions: decisions,
      allowLegacyApprovalInference: legacyApprovalInference,
      sourceResponseConversationIds: responseHistory.map(item => item?.conversationId),
      sourceResponses: responseHistory,
      evidenceSchemaVersion: run.evidenceSchemaVersion || 1
    });
    cases.push({
      runId: run.runId,
      name,
      task: run.task,
      testCase: definition.testCase,
      recordedSuccess: recorded.success ?? null,
      recordedApprovals: recorded.approvals ?? null,
      reevaluatedSuccess: reevaluated.success,
      durationMs: recorded.durationMs ?? durations.get(name) ?? null,
      trajectoryMissing,
      ...reevaluated
    });
  }
  return {
    runId: run.runId,
    source: runDirectory,
    configuration: run,
    reproductionCommand: commandFor(run),
    cases
  };
}

function seconds(milliseconds) {
  return Number.isFinite(milliseconds) ? (milliseconds / 1000).toFixed(1) : 'n/a';
}

function markdownCell(value) {
  return String(value ?? '').replaceAll('|', '\\|').replaceAll('\n', ' ');
}

function pairedComparisons(runs) {
  const comparisons = [];
  for (const run of runs) {
    const singles = run.cases.filter(item => item.testCase.mode === 'single');
    const parallels = run.cases.filter(item => item.testCase.mode === 'parallel');
    for (const single of singles) {
      const combo = single.testCase.combo;
      for (const parallel of parallels.filter(item =>
        item.testCase.combo.modelName === combo.modelName
          && item.testCase.combo.reasoningEffort === combo.reasoningEffort
          && item.testCase.combo.runtime === combo.runtime
          && item.task === single.task)) {
        comparisons.push({
          runId: run.runId,
          modelName: combo.modelName,
          reasoningEffort: combo.reasoningEffort,
          runtime: combo.runtime,
          task: single.task,
          singleCase: single.name,
          parallelCase: parallel.name,
          singleDurationMs: single.durationMs,
          parallelDurationMs: parallel.durationMs,
          durationRatio: Number.isFinite(single.durationMs) && Number.isFinite(parallel.durationMs)
            ? parallel.durationMs / single.durationMs : null,
          singleCompletionTokens: single.metrics.total_completion_tokens ?? null,
          parallelCompletionTokens: parallel.metrics.total_completion_tokens ?? null,
          singleSteps: single.metrics.total_steps ?? null,
          parallelSteps: parallel.metrics.total_steps ?? null
        });
      }
    }
  }
  return comparisons;
}

function summaryMarkdown(analysis) {
  const lines = [
    '# AI multi-agent matrix offline verification',
    '',
    `- Generated: ${analysis.generatedAt}`,
    `- Source: \`${analysis.input}\``,
    `- Strict result: **${analysis.successfulCases}/${analysis.totalCases} passed**`,
    '- Evaluation: saved REST responses and ATIF only; no provider or MCP calls were made.',
    '',
    '## Results',
    '',
    '| Run | Case | Model / effort / runtime | Mode / strategy | Seconds | Graph | Mutation/read-back | Result |',
    '|---|---|---|---|---:|---|---|:---:|'
  ];
  for (const run of analysis.runs) {
    for (const item of run.cases) {
      const combo = item.testCase.combo;
      const evidence = item.task === 'mutation'
        ? `${item.tools.createdId || 'no ID'} / ${item.tools.readBack ? item.tools.readBackTool : 'missing'}`
        : item.tools.readBack ? item.tools.readBackTool : 'missing read';
      lines.push(`| ${markdownCell(run.runId)} | ${markdownCell(item.name)} | `
        + `${markdownCell(`${combo.modelName} / ${combo.reasoningEffort} / ${combo.runtime}`)} | `
        + `${markdownCell(`${item.testCase.mode} / ${item.testCase.strategy}`)} | `
        + `${seconds(item.durationMs)} | ${item.graph.valid ? 'valid' : markdownCell(item.graph.violations.join('; '))} | `
        + `${markdownCell(evidence)} | ${item.reevaluatedSuccess ? 'PASS' : 'FAIL'} |`);
    }
  }
  lines.push('', '## Paired performance observations', '');
  if (analysis.comparisons.length) {
    lines.push('| Run / configuration | Single | Parallel | Duration ratio | Completion tokens | ATIF steps |',
      '|---|---:|---:|---:|---:|---:|');
    for (const comparison of analysis.comparisons) {
      lines.push(`| ${markdownCell(`${comparison.runId}: ${comparison.modelName} / `
        + `${comparison.reasoningEffort} / ${comparison.runtime} / ${comparison.task}`)} | `
        + `${seconds(comparison.singleDurationMs)}s | ${seconds(comparison.parallelDurationMs)}s | `
        + `${comparison.durationRatio?.toFixed(2) ?? 'n/a'}x | `
        + `${comparison.singleCompletionTokens ?? 'n/a'} -> ${comparison.parallelCompletionTokens ?? 'n/a'} | `
        + `${comparison.singleSteps ?? 'n/a'} -> ${comparison.parallelSteps ?? 'n/a'} |`);
    }
  } else {
    lines.push('No same-model/effort/runtime/task single-vs-parallel pair was found.');
  }
  lines.push('', '> These are n=1 descriptive observations, not causal model/runtime benchmarks. '
    + 'Repeat the same task while changing one axis at a time before drawing performance conclusions.', '');
  lines.push('', '## Reproduction commands', '');
  for (const run of analysis.runs) {
    lines.push(`### ${run.runId}`, '', '```bash', run.reproductionCommand, '```', '');
  }
  lines.push('## Strict checks', '',
    '- Nested MCP error payloads are rejected.',
    '- Mutation success requires exactly one executed create result and one exact ID/name/description read-back after it.',
    '- Lead and child lifecycle state machines, deterministic IDs, request/fan-out linkage, depth, and ordinals are validated.',
    '- Specialist calls must carry the recorded read-only guard classification derived from the server readOnlyHint annotations.',
    '');
  const failures = analysis.runs.flatMap(run => run.cases)
    .filter(item => !item.reevaluatedSuccess);
  if (failures.length) {
    lines.push('## Failures', '');
    for (const item of failures) {
      const trajectoryNote = item.trajectoryMissing
        ? 'trajectory.atif.json missing (runner failed before the trajectory fetch)' : '';
      const reason = [trajectoryNote, item.failure].filter(Boolean).join('; ')
        || item.graph.violations.join('; ') || 'tool evidence failed';
      lines.push(`- \`${item.name}\`: ${reason}`);
    }
    lines.push('');
  }
  return `${lines.join('\n')}\n`;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const directories = await runDirectories(args.input);
  if (!directories.length) throw new Error(`No run.json found under ${args.input}`);
  const runs = [];
  for (const directory of directories) runs.push(await analyzeRun(directory));
  const cases = runs.flatMap(run => run.cases);
  const comparisons = pairedComparisons(runs);
  const analysis = {
    schemaVersion: 2,
    generatedAt: new Date().toISOString(),
    input: args.input,
    totalCases: cases.length,
    successfulCases: cases.filter(item => item.reevaluatedSuccess).length,
    allPassed: cases.every(item => item.reevaluatedSuccess),
    comparisons,
    runs
  };
  await mkdir(args.output, {recursive: true});
  await writeFile(join(args.output, 'analysis.json'), `${JSON.stringify(analysis, null, 2)}\n`);
  await writeFile(join(args.output, 'AI_MULTI_AGENT_MATRIX_RESULTS.md'), summaryMarkdown(analysis));
  process.stdout.write(`${analysis.successfulCases}/${analysis.totalCases} strict cases passed.\n`);
  process.stdout.write(`Analysis: ${join(args.output, 'analysis.json')}\n`);
  process.stdout.write(`Summary candidate: ${join(args.output, 'AI_MULTI_AGENT_MATRIX_RESULTS.md')}\n`);
  if (!analysis.allPassed) process.exitCode = 1;
}

main().catch(error => {
  process.stderr.write(`${error.stack || error.message || error}\n`);
  process.exitCode = 1;
});
