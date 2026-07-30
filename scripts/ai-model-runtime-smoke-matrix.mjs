#!/usr/bin/env node

import {mkdir, writeFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {join, resolve} from 'node:path';

const baseUrl = process.env.SCORE_BASE_URL || 'http://127.0.0.1:9000';
const runId = process.argv[2]
  || `model-runtime-${new Date().toISOString().replace(/[-:]/g, '').replace(/\..+/, 'Z')}`;
const outputDir = resolve('experiments', 'ai-workflow-validation', runId);
const username = process.env.SCORE_USERNAME || 'oagis';
const password = process.env.SCORE_PASSWORD || 'oagis';
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
    // Preserve non-JSON error bodies for diagnostics.
  }
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${String(text).slice(0, 2000)}`);
  }
  return body;
}

async function login() {
  const response = await fetch(`${baseUrl}/login`, {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: new URLSearchParams({username, password}),
    signal: AbortSignal.timeout(30_000)
  });
  if (!response.ok) throw new Error(`Login failed with HTTP ${response.status}`);
  const cookie = response.headers.get('set-cookie');
  if (!cookie) throw new Error('Login returned no session cookie.');
  sessionCookie = cookie.split(';', 1)[0];
}

function combinations(models) {
  return models.flatMap(model => model.reasoningEfforts.flatMap(effort =>
    model.runtimes.map(runtime => ({
      modelName: model.name,
      reasoningEffort: effort.name,
      runtime: runtime.name,
      runtimeOptions: {}
    }))
  ));
}

function terminalMetrics(trajectory) {
  const metrics = trajectory?.final_metrics || {};
  return {
    promptTokens: metrics.total_prompt_tokens || 0,
    completionTokens: metrics.total_completion_tokens || 0,
    cachedTokens: metrics.total_cached_tokens || 0,
    steps: metrics.total_steps || 0
  };
}

async function runCase(combo, ordinal, total) {
  const requestId = randomUUID();
  const started = performance.now();
  let conversationId = null;
  try {
    const response = await request('/ai/chat', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        prompt: 'This is a runtime smoke test. Reply with exactly READY and nothing else. Do not call tools.',
        requestId,
        agent: 'connectcenter-assistant',
        conversationId: null,
        pageContext: `Model/runtime smoke matrix ${runId}`,
        attachments: [],
        changeConfirmation: null,
        modelName: combo.modelName,
        reasoningEffort: combo.reasoningEffort,
        runtime: combo.runtime,
        runtimeOptions: combo.runtimeOptions,
        permissionMode: 'ask',
        multiAgent: {enabled: false, maxAgents: 3, strategy: 'balanced'}
      })
    });
    conversationId = response.conversationId;
    const trajectory = await request(`/ai/chat/conversations/${conversationId}/trajectory`);
    const answer = String(response.response || '').trim();
    const result = {
      ...combo,
      status: answer === 'READY' ? 'PASS' : 'FAIL',
      answer,
      durationMs: Math.round(performance.now() - started),
      conversationId,
      ...terminalMetrics(trajectory),
      error: answer === 'READY' ? null : 'Response was not exactly READY.'
    };
    process.stdout.write(`[${ordinal}/${total}] ${result.status} ${combo.modelName} `
      + `${combo.reasoningEffort}/${combo.runtime} ${result.durationMs}ms\n`);
    return result;
  } catch (error) {
    const result = {
      ...combo,
      status: 'FAIL',
      answer: null,
      durationMs: Math.round(performance.now() - started),
      conversationId,
      promptTokens: 0,
      completionTokens: 0,
      cachedTokens: 0,
      steps: 0,
      error: error instanceof Error ? error.message : String(error)
    };
    process.stdout.write(`[${ordinal}/${total}] FAIL ${combo.modelName} `
      + `${combo.reasoningEffort}/${combo.runtime}: ${result.error}\n`);
    return result;
  } finally {
    if (conversationId) {
      try {
        await request(`/ai/chat/conversations/${conversationId}`, {method: 'DELETE'});
      } catch {
        // Cleanup failure remains visible through the conversation ID in the result.
      }
    }
  }
}

function markdown(summary) {
  const rows = summary.results.map(result =>
    `| ${result.modelName} | ${result.reasoningEffort} | ${result.runtime} | ${result.status} | `
      + `${result.durationMs} | ${result.promptTokens} | ${result.completionTokens} | `
      + `${result.error ? result.error.replaceAll('|', '\\|') : ''} |`
  ).join('\n');
  return `# AI model / reasoning / runtime smoke matrix\n\n`
    + `- Run: \`${summary.runId}\`\n`
    + `- Base URL: \`${summary.baseUrl}\`\n`
    + `- Cases: ${summary.total}; passed: ${summary.passed}; failed: ${summary.failed}\n`
    + `- Pass rate: ${summary.passRate.toFixed(1)}%\n\n`
    + `| Model | Reasoning | Runtime | Result | ms | Prompt tokens | Completion tokens | Error |\n`
    + `|---|---|---|---:|---:|---:|---:|---|\n${rows}\n`;
}

await mkdir(outputDir, {recursive: true});
await login();
const models = await request('/ai/chat/models');
const combos = combinations(models);
const results = [];
for (let index = 0; index < combos.length; index += 1) {
  results.push(await runCase(combos[index], index + 1, combos.length));
}
const passed = results.filter(result => result.status === 'PASS').length;
const summary = {
  runId,
  createdAt: new Date().toISOString(),
  baseUrl,
  total: results.length,
  passed,
  failed: results.length - passed,
  passRate: results.length ? passed * 100 / results.length : 0,
  results
};
await writeFile(join(outputDir, 'model-runtime-matrix.json'),
  `${JSON.stringify(summary, null, 2)}\n`, 'utf8');
await writeFile(join(outputDir, 'model-runtime-matrix.md'), markdown(summary), 'utf8');
process.stdout.write(`Results: ${outputDir}\n`);
if (summary.failed > 0) process.exitCode = 1;
