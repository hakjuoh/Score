#!/usr/bin/env node

import {appendFile, mkdir, writeFile} from 'node:fs/promises';
import {join, resolve} from 'node:path';
import {createHash, randomUUID} from 'node:crypto';
import {
  approvalAuthorizationDigest, evaluateMultiAgentCase
} from './ai-multi-agent-evaluator.mjs';

const BASE_URL = process.env.SCORE_BASE_URL || 'http://localhost:4200';
const STRATEGIES = ['balanced', 'creative', 'verification'];

function parseArgs(argv) {
  const args = {
    runId: `multi-agent-${new Date().toISOString().replace(/[-:]/g, '').replace(/\..+/, 'Z')}`,
    mode: 'both',
    strategy: 'balanced',
    task: 'creative',
    model: null,
    effort: null,
    runtime: null,
    maxAgents: 3,
    limit: 4,
    permissionMode: 'full_access'
  };
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    const value = argv[index + 1];
    if (key === '--run-id') args.runId = value, index += 1;
    else if (key === '--mode') args.mode = value, index += 1;
    else if (key === '--strategy') args.strategy = value, index += 1;
    else if (key === '--task') args.task = value, index += 1;
    else if (key === '--model') args.model = value, index += 1;
    else if (key === '--effort') args.effort = value, index += 1;
    else if (key === '--runtime') args.runtime = value, index += 1;
    else if (key === '--max-agents') args.maxAgents = Number(value), index += 1;
    else if (key === '--limit') args.limit = Number(value), index += 1;
    else if (key === '--permission-mode') args.permissionMode = value, index += 1;
    else if (key === '--help') {
      process.stdout.write(
        'Usage: node scripts/ai-multi-agent-matrix.mjs ' +
        '[--mode single|parallel|both] [--strategy balanced|creative|verification|all] ' +
        '[--task landscape|creative|mutation] [--model NAME] [--effort NAME] ' +
        '[--runtime NAME] [--max-agents 2..4] [--limit N] ' +
        '[--permission-mode ask|auto|full_access]\n'
      );
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${key}`);
    }
  }
  if (!['single', 'parallel', 'both'].includes(args.mode)) {
    throw new Error(`Unsupported mode: ${args.mode}`);
  }
  if (![...STRATEGIES, 'all'].includes(args.strategy)) {
    throw new Error(`Unsupported strategy: ${args.strategy}`);
  }
  if (!['landscape', 'creative', 'mutation'].includes(args.task)) {
    throw new Error(`Unsupported task: ${args.task}`);
  }
  if (!['ask', 'auto', 'full_access'].includes(args.permissionMode)) {
    throw new Error(`Unsupported permission mode: ${args.permissionMode}`);
  }
  if (!Number.isInteger(args.maxAgents) || args.maxAgents < 2 || args.maxAgents > 4) {
    throw new Error('--max-agents must be an integer between 2 and 4.');
  }
  if (!Number.isInteger(args.limit) || args.limit < 1) {
    throw new Error('--limit must be a positive integer.');
  }
  return args;
}

const args = parseArgs(process.argv.slice(2));
const outputDir = resolve('experiments', 'ai-multi-agent-matrix', 'output', args.runId);
const eventsPath = join(outputDir, 'events.jsonl');
let sessionCookie = '';

async function record(event) {
  const payload = {timestamp: new Date().toISOString(), ...event};
  await appendFile(eventsPath, `${JSON.stringify(payload)}\n`, 'utf8');
  process.stdout.write(`${JSON.stringify(payload)}\n`);
}

async function request(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (sessionCookie) headers.set('Cookie', sessionCookie);
  const response = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers,
    signal: AbortSignal.timeout(options.timeoutMs || 660_000)
  });
  const text = await response.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }
  if (!response.ok) {
    const error = new Error(`HTTP ${response.status}: ${text.slice(0, 1000)}`);
    error.body = body;
    throw error;
  }
  return body;
}

function resolveCredentials() {
  let url;
  try {
    url = new URL(BASE_URL);
  } catch {
    url = null;
  }
  if (!url || !['http:', 'https:'].includes(url.protocol)) {
    throw new Error(`SCORE_BASE_URL must be an absolute http(s) URL, got: ${BASE_URL}`);
  }
  const local = ['localhost', '127.0.0.1', '::1', '[::1]'].includes(url.hostname);
  const username = process.env.SCORE_USERNAME || (local ? 'oagis' : '');
  const password = process.env.SCORE_PASSWORD || (local ? 'oagis' : '');
  if (!username || !password) {
    throw new Error(
      'SCORE_USERNAME and SCORE_PASSWORD must be set when SCORE_BASE_URL is not '
      + `localhost, 127.0.0.1, or ::1; refusing default credentials against ${BASE_URL}.`
    );
  }
  return {username, password};
}

async function login({username, password}) {
  const response = await fetch(`${BASE_URL}/api/login`, {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: new URLSearchParams({username, password}),
    signal: AbortSignal.timeout(30_000)
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Login failed (${response.status}): ${text}`);
  const setCookie = response.headers.get('set-cookie');
  if (!setCookie) throw new Error('Login succeeded without a session cookie.');
  sessionCookie = setCookie.split(';', 1)[0];
  return JSON.parse(text);
}

function combinations(models) {
  return models.flatMap(model => model.reasoningEfforts.flatMap(effort =>
    model.runtimes.map(runtime => ({
      modelName: model.name,
      reasoningEffort: effort.name,
      runtime: runtime.name,
      runtimeOptions: null
    }))
  )).filter(combo => !args.model || combo.modelName === args.model)
    .filter(combo => !args.effort || combo.reasoningEffort === args.effort)
    .filter(combo => !args.runtime || combo.runtime === args.runtime)
    .slice(0, args.limit);
}

function casesFor(combo) {
  const modes = args.mode === 'both' ? ['single', 'parallel'] : [args.mode];
  const strategies = args.strategy === 'all' ? STRATEGIES : [args.strategy];
  return modes.flatMap(mode => (mode === 'single' ? ['balanced'] : strategies)
    .map(strategy => ({combo, mode, strategy})));
}

function taskFor(runId, ordinal) {
  const tag = runId.replace(/[^A-Za-z0-9]/g, '').slice(-8);
  const categoryName = `MA ${tag} ${String(ordinal).padStart(2, '0')} Creative Assurance`;
  if (args.task === 'landscape') {
    return {
      label: 'read-only landscape',
      prompt: [
        'Use connectCenter read-only MCP tools to inspect the current libraries, working releases,',
        'and representative core components. Explain the most important relationships with exact',
        'IDs from tool evidence. Do not change data. Separate observed facts from inference.'
      ].join(' '),
      expectedMutation: null,
      expectedReads: ['get_libraries', 'get_library', 'get_releases', 'get_working_release',
        'get_core_components', 'get_acc']
    };
  }
  if (args.task === 'mutation') {
    const description = `multi-agent matrix ${tag}`;
    return {
      label: categoryName,
      prompt: [
        `Create exactly one Context Category named '${categoryName}' with description`,
        `'${description}'. Then use get_context_category or get_context_categories`,
        'to read the stored record back. Report its exact ID. Do not claim success without read-back.'
      ].join(' '),
      expectedMutation: 'create_context_category',
      expectedReads: ['get_context_category', 'get_context_categories'],
      expectedRecord: {
        idKey: 'ctx_category_id',
        name: categoryName,
        description
      }
    };
  }
  return {
    label: 'creative read-only review',
    responseRequirements: {kind: 'creative-proposals', proposals: 3,
      evidencePerProposal: true, counterargumentPerProposal: true},
    prompt: [
      'Explore connectCenter with read-only MCP tools and propose three non-obvious but practical',
      'ways its current library, release, datatype, and core-component relationships could support',
      'interoperability quality work. Ground every proposal in records or IDs you actually read.',
      'You must inspect and cite at least one library_id, release_id, dt_id or dt_manifest_id, and',
      'component_id or manifest_id, using those exact ID labels in the answer.',
      'Include one skeptical counterargument per proposal. Do not change data.'
    ].join(' '),
    expectedMutation: null,
    expectedReads: ['get_libraries', 'get_library', 'get_releases', 'get_working_release',
      'get_data_types', 'get_data_type', 'get_core_components', 'get_acc']
  };
}

function chatPayload(testCase, task, conversationId, mutationConfirmation, requestId = randomUUID()) {
  const parallel = testCase.mode === 'parallel' && !mutationConfirmation;
  return {
    prompt: mutationConfirmation
      ? `Execute the exactly approved ${mutationConfirmation.toolName} call, then read the record back and finish the original request.`
      : task.prompt,
    requestId,
    agent: 'connectcenter-assistant',
    conversationId: conversationId || null,
    pageContext: `Multi-agent matrix; task=${args.task}; case=${task.label}`,
    attachments: [],
    mutationConfirmation: mutationConfirmation || null,
    modelName: testCase.combo.modelName,
    reasoningEffort: testCase.combo.reasoningEffort,
    runtime: testCase.combo.runtime,
    runtimeOptions: testCase.combo.runtimeOptions,
    permissionMode: args.permissionMode,
    multiAgent: {
      enabled: parallel,
      maxAgents: args.maxAgents,
      strategy: testCase.strategy
    }
  };
}

function redactedDecisionEvidence(decision, confirmationEvent, continuationRequestId) {
  const grant = String(decision?.confirmationGrant || '');
  if (!/^[A-Za-z0-9_-]{43}$/.test(grant)) {
    throw new Error('Approval decision did not return a 32-byte base64url grant.');
  }
  const confirmationGrantDigest = createHash('sha256').update(grant).digest('hex');
  const evidence = {
    confirmationRequestId: decision.confirmationRequestId,
    conversationId: decision.conversationId,
    status: decision.status,
    disposition: decision.disposition,
    expiresAt: decision.expiresAt,
    approvedAt: decision.approvedAt,
    confirmationGrantDigest,
    grantEncoding: 'base64url',
    grantBytes: 32,
    continuationRequestId
  };
  return {
    ...evidence,
    authorizationDigest: approvalAuthorizationDigest({
      confirmationRequestId: evidence.confirmationRequestId,
      conversationId: evidence.conversationId,
      continuationRequestId,
      toolName: confirmationEvent.metadata.toolName,
      argumentsSummary: confirmationEvent.metadata.argumentsSummary,
      confirmationGrantDigest
    })
  };
}

async function postChat(payload) {
  return request('/api/ai/chat', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(payload)
  });
}

function confirmation(response) {
  return (response?.events || []).find(event =>
    event?.subtype === 'mutation_confirmation_required'
      && event?.metadata?.confirmationRequestId
  );
}

async function runCase(testCase, task, caseDir) {
  let response;
  let conversationId;
  let approvals = 0;
  let confirmationRequests = 0;
  const confirmationEvidence = [];
  const approvalDecisions = [];
  const sourceResponseConversationIds = [];
  const sourceResponses = [];
  let failure;
  try {
    response = await postChat(chatPayload(testCase, task));
    conversationId = response.conversationId;
    sourceResponseConversationIds.push(response.conversationId);
    sourceResponses.push(response);
    await writeFile(join(caseDir, 'response-00.json'), `${JSON.stringify(response, null, 2)}\n`);
    for (let turn = 1; turn <= 12; turn += 1) {
      const pending = confirmation(response);
      if (!pending) break;
      confirmationRequests += 1;
      confirmationEvidence.push({
        confirmationRequestId: pending.metadata.confirmationRequestId,
        conversationId: response.conversationId,
        toolName: pending.metadata.toolName,
        argumentsSummary: pending.metadata.argumentsSummary
      });
      const decision = await request(
        `/api/ai/chat/conversations/${conversationId}/mutation-confirmations/` +
        `${pending.metadata.confirmationRequestId}/decision`,
        {
          method: 'POST',
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({decision: 'APPROVE'}),
          timeoutMs: 30_000
        }
      );
      approvals += 1;
      const authorization = {
        confirmationRequestId: pending.metadata.confirmationRequestId,
        confirmationGrant: decision.confirmationGrant,
        toolName: pending.metadata.toolName,
        arguments: pending.metadata.argumentsSummary
      };
      const continuationRequestId = randomUUID();
      response = await postChat(chatPayload(
        testCase, task, conversationId, authorization, continuationRequestId));
      sourceResponseConversationIds.push(response.conversationId);
      sourceResponses.push(response);
      await writeFile(join(caseDir, `response-${String(turn).padStart(2, '0')}.json`),
        `${JSON.stringify(response, null, 2)}\n`);
      const decisionEvidence = redactedDecisionEvidence(
        decision, pending, continuationRequestId);
      approvalDecisions.push(decisionEvidence);
      await writeFile(join(caseDir, `decision-${String(turn).padStart(2, '0')}.json`),
        `${JSON.stringify(decisionEvidence, null, 2)}\n`);
    }
  } catch (error) {
    failure = error;
  }
  let trajectory;
  if (conversationId) {
    try {
      trajectory = await request(`/api/ai/chat/conversations/${conversationId}/trajectory`, {
        timeoutMs: 60_000
      });
      await writeFile(join(caseDir, 'trajectory.atif.json'),
        `${JSON.stringify(trajectory, null, 2)}\n`);
    } catch (error) {
      failure ||= error;
    }
  }
  return evaluateMultiAgentCase({
    testCase,
    task,
    trajectory,
    approvals,
    response,
    failure,
    permissionMode: args.permissionMode,
    maxAgents: args.maxAgents,
    confirmationRequests,
    confirmationEvidence,
    approvalDecisions,
    sourceResponseConversationIds,
    sourceResponses,
    evidenceSchemaVersion: 4
  });
}

async function main() {
  const credentials = resolveCredentials();
  await mkdir(outputDir, {recursive: true});
  const identity = await login(credentials);
  const models = await request('/api/ai/chat/models', {timeoutMs: 30_000});
  const combos = combinations(models);
  const testCases = combos.flatMap(casesFor);
  if (testCases.length === 0) {
    const message = 'No matrix cases matched the requested model/effort/runtime filters.';
    await record({type: 'fatal', message});
    console.error(message);
    process.exitCode = 1;
    return;
  }
  await writeFile(join(outputDir, 'run.json'), `${JSON.stringify({
    ...args, evidenceSchemaVersion: 4,
    baseUrl: BASE_URL, identity, combinations: combos, cases: testCases.length
  }, null, 2)}\n`);
  const results = [];
  await record({type: 'run-start', runId: args.runId, cases: testCases.length});
  for (let index = 0; index < testCases.length; index += 1) {
    const testCase = testCases[index];
    const task = taskFor(args.runId, index + 1);
    const name = `${String(index + 1).padStart(2, '0')}-${testCase.combo.modelName}-` +
      `${testCase.combo.reasoningEffort}-${testCase.combo.runtime}-${testCase.mode}-${testCase.strategy}`;
    const caseDir = join(outputDir, name);
    await mkdir(caseDir, {recursive: true});
    await writeFile(join(caseDir, 'case.json'), `${JSON.stringify({testCase, task}, null, 2)}\n`);
    await record({type: 'case-start', name, testCase, task: task.label});
    const startedAt = Date.now();
    const result = {
      name, testCase, task: task.label,
      ...await runCase(testCase, task, caseDir),
      durationMs: Date.now() - startedAt
    };
    results.push(result);
    await writeFile(join(caseDir, 'result.json'), `${JSON.stringify(result, null, 2)}\n`);
    await writeFile(join(outputDir, 'results.json'), `${JSON.stringify(results, null, 2)}\n`);
    await record({type: 'case-finish', name, success: result.success});
  }
  await record({
    type: 'run-finish',
    total: results.length,
    successful: results.filter(result => result.success).length
  });
  if (results.some(result => !result.success)) process.exitCode = 1;
}

main().catch(async error => {
  await mkdir(outputDir, {recursive: true});
  await record({type: 'fatal', message: String(error.stack || error.message || error)});
  process.exitCode = 1;
});
