#!/usr/bin/env node

import {mkdir, writeFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {join, resolve} from 'node:path';

const baseUrl = process.env.SCORE_BASE_URL || 'http://127.0.0.1:9000';
const runId = process.argv[2]
  || `workflow-live-${new Date().toISOString().replace(/[-:]/g, '').replace(/\..+/, 'Z')}`;
const outputDir = resolve('experiments', 'ai-workflow-validation', runId);
const workflows = (process.env.SCORE_WORKFLOWS
  || 'direct,chain,parallel,routing,orchestrator_workers')
  .split(',')
  .map(value => value.trim())
  .filter(Boolean);
let sessionCookie = '';

async function request(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (sessionCookie) headers.set('Cookie', sessionCookie);
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers,
    signal: AbortSignal.timeout(options.timeoutMs || 660_000)
  });
  const text = await response.text();
  let body = text;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    // Preserve a non-JSON error body.
  }
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${text.slice(0, 2000)}`);
  return body;
}

async function login() {
  const response = await fetch(`${baseUrl}/login`, {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: new URLSearchParams({
      username: process.env.SCORE_USERNAME || 'oagis',
      password: process.env.SCORE_PASSWORD || 'oagis'
    }),
    signal: AbortSignal.timeout(30_000)
  });
  if (!response.ok) throw new Error(`Login failed with HTTP ${response.status}`);
  const cookie = response.headers.get('set-cookie');
  if (!cookie) throw new Error('Login returned no session cookie.');
  sessionCookie = cookie.split(';', 1)[0];
}

function allTrajectories(trajectory) {
  return [trajectory, ...(trajectory.subagent_trajectories || [])
    .flatMap(allTrajectories)];
}

function allSteps(trajectory) {
  return allTrajectories(trajectory).flatMap(item => item.steps || []);
}

function completedToolCalls(trajectory) {
  return allSteps(trajectory).filter(step => step.extra?.message_kind === 'tool_call'
      && step.extra?.tool_status === 'completed')
    .map(step => step.extra.tool_name);
}

async function runWorkflow(workflow, ordinal) {
  const started = performance.now();
  try {
    const workflowLabel = workflow === 'orchestrator_workers'
      ? 'orchestrator workers' : workflow;
    const multiAgent = {
      enabled: workflow !== 'direct',
      maxAgents: 3,
      strategy: 'verification'
    };
    // Workflow preference is intentionally server-authoritative. Exercise the same
    // two-turn command path as the UI instead of injecting ChatRequest.activeWorkflow.
    const preference = await request('/ai/chat', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        prompt: `For all following requests, always use the ${workflowLabel} workflow.`,
        requestId: randomUUID(),
        agent: 'connectcenter-assistant',
        conversationId: null,
        pageContext: `Live workflow matrix ${runId}`,
        attachments: [],
        changeConfirmation: null,
        modelName: 'claude-sonnet-5',
        reasoningEffort: 'medium',
        runtime: 'claude',
        runtimeOptions: {},
        permissionMode: 'ask',
        multiAgent
      })
    });
    const chat = await request('/ai/chat', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        prompt: 'Exercise the configured workflow with read-only connectCenter MCP operations. '
          + 'Request the first page with limit 2 for context categories and context schemes, '
          + 'then report their returned total counts and IDs. Use actual tools even if earlier '
          + 'conversation context exists. Do not create, update, or delete anything.',
        requestId: randomUUID(),
        agent: 'connectcenter-assistant',
        conversationId: preference.conversationId,
        pageContext: `Live workflow matrix ${runId}`,
        attachments: [],
        changeConfirmation: null,
        modelName: 'claude-sonnet-5',
        reasoningEffort: 'medium',
        runtime: 'claude',
        runtimeOptions: {},
        permissionMode: 'ask',
        multiAgent
      })
    });
    const trajectory = await request(
      `/ai/chat/conversations/${chat.conversationId}/trajectory`);
    const terminal = trajectory.steps.at(-1) || {};
    const steps = allSteps(trajectory);
    const nestedTrajectories = allTrajectories(trajectory).slice(1);
    const calls = completedToolCalls(trajectory);
    const searchCalls = calls.filter(name => name === 'toolSearchTool').length;
    const dataCalls = calls.filter(name => name !== 'toolSearchTool');
    const selectedWorkflow = terminal.extra?.workflow;
    const status = selectedWorkflow === workflow
        && terminal.extra?.controller_workflow === 'evaluator_optimizer'
        && terminal.extra?.evaluation_status === 'complete'
        && searchCalls > 0
        && dataCalls.some(name => name.startsWith('get_')) ? 'PASS' : 'FAIL';
    const result = {
      workflow,
      status,
      durationMs: Math.round(performance.now() - started),
      conversationId: chat.conversationId,
      selectedWorkflow,
      controllerWorkflow: terminal.extra?.controller_workflow || null,
      evaluationStatus: terminal.extra?.evaluation_status || null,
      workflowIterations: terminal.extra?.workflow_iterations || null,
      plannerCalls: steps.filter(
        step => step.extra?.agent_name === 'workflow-planner').length,
      evaluatorCalls: steps.filter(
        step => step.extra?.agent_name === 'workflow-evaluator').length,
      subagentTrajectories: nestedTrajectories.length,
      subagentModelCalls: nestedTrajectories.flatMap(item => item.steps || [])
        .filter(step => step.extra?.message_kind === 'model_call').length,
      toolSearchCalls: searchCalls,
      dataToolCalls: dataCalls,
      promptTokens: trajectory.final_metrics?.total_prompt_tokens || 0,
      completionTokens: trajectory.final_metrics?.total_completion_tokens || 0,
      steps: trajectory.final_metrics?.total_steps || steps.length,
      answer: String(chat.response || terminal.message || '').trim(),
      error: status === 'PASS' ? null
        : 'Expected workflow/evaluator/tool-search evidence was not all present.'
    };
    process.stdout.write(`[${ordinal}/${workflows.length}] ${status} ${workflow} `
      + `${result.durationMs}ms; tools=${dataCalls.length}; iterations=${result.workflowIterations}\n`);
    return result;
  } catch (error) {
    const result = {
      workflow,
      status: 'FAIL',
      durationMs: Math.round(performance.now() - started),
      error: error instanceof Error ? error.message : String(error)
    };
    process.stdout.write(`[${ordinal}/${workflows.length}] FAIL ${workflow}: ${result.error}\n`);
    return result;
  }
}

function markdown(summary) {
  const rows = summary.results.map(result =>
    `| ${result.workflow} | ${result.status} | ${result.durationMs || ''} | `
      + `${result.workflowIterations || ''} | ${result.plannerCalls || ''} | `
      + `${result.evaluatorCalls || ''} | ${result.subagentModelCalls || 0} | `
      + `${result.toolSearchCalls || 0} | ${(result.dataToolCalls || []).join(', ')} | `
      + `${result.error ? result.error.replaceAll('|', '\\|') : ''} |`
  ).join('\n');
  return `# Live workflow matrix\n\n`
    + `- Run: \`${summary.runId}\`\n`
    + `- Model: \`claude-sonnet-5\`, effort: \`medium\`, runtime: \`claude\`\n`
    + `- Cases: ${summary.total}; passed: ${summary.passed}; failed: ${summary.failed}\n\n`
    + '| Workflow | Result | ms | Iterations | Planner | Evaluator | Subagent calls | Tool search | Data tools | Error |\n'
    + '|---|---:|---:|---:|---:|---:|---:|---:|---|---|\n'
    + `${rows}\n`;
}

await mkdir(outputDir, {recursive: true});
await login();
const results = [];
for (let index = 0; index < workflows.length; index += 1) {
  results.push(await runWorkflow(workflows[index], index + 1));
}
const passed = results.filter(result => result.status === 'PASS').length;
const summary = {
  runId,
  createdAt: new Date().toISOString(),
  total: results.length,
  passed,
  failed: results.length - passed,
  results
};
await writeFile(join(outputDir, 'workflow-live-matrix.json'),
  `${JSON.stringify(summary, null, 2)}\n`, 'utf8');
await writeFile(join(outputDir, 'workflow-live-matrix.md'), markdown(summary), 'utf8');
process.stdout.write(`Results: ${outputDir}\n`);
if (summary.failed > 0) process.exitCode = 1;
