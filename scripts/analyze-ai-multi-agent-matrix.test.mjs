import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, mkdir, readFile, rm, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {spawnSync} from 'node:child_process';

test('fails closed when a declared matrix case directory is missing', async t => {
  const root = await mkdtemp(join(tmpdir(), 'score-ai-matrix-missing-'));
  t.after(() => rm(root, {recursive: true, force: true}));
  const input = join(root, 'input');
  const output = join(root, 'output');
  await mkdir(input, {recursive: true});
  await writeFile(join(input, 'run.json'), `${JSON.stringify({
    runId: 'missing-case-proof',
    cases: 1,
    evidenceSchemaVersion: 3
  })}\n`);

  const result = spawnSync(process.execPath, [
    resolve('scripts/analyze-ai-multi-agent-matrix.mjs'),
    '--input', input,
    '--output', output
  ], {cwd: resolve('.'), encoding: 'utf8'});

  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /expected 1 case directories, found 0/);
});

test('reports a case with a missing trajectory as a failure without crashing', async t => {
  const root = await mkdtemp(join(tmpdir(), 'score-ai-matrix-no-trajectory-'));
  t.after(() => rm(root, {recursive: true, force: true}));
  const input = join(root, 'input');
  const output = join(root, 'output');
  const caseName = '01-model-low-default-single-balanced';
  const caseDir = join(input, caseName);
  await mkdir(caseDir, {recursive: true});
  await writeFile(join(input, 'run.json'), `${JSON.stringify({
    runId: 'missing-trajectory-proof',
    mode: 'single',
    strategy: 'balanced',
    task: 'landscape',
    maxAgents: 3,
    limit: 1,
    permissionMode: 'full_access',
    cases: 1,
    evidenceSchemaVersion: 3
  })}\n`);
  await writeFile(join(caseDir, 'case.json'), `${JSON.stringify({
    testCase: {
      mode: 'single',
      strategy: 'balanced',
      combo: {modelName: 'model', reasoningEffort: 'low', runtime: 'default'}
    },
    task: {
      label: 'read-only landscape',
      expectedChange: null,
      expectedReads: ['get_libraries']
    }
  })}\n`);
  await writeFile(join(caseDir, 'result.json'), `${JSON.stringify({
    name: caseName,
    success: false
  })}\n`);

  const result = spawnSync(process.execPath, [
    resolve('scripts/analyze-ai-multi-agent-matrix.mjs'),
    '--input', input,
    '--output', output
  ], {cwd: resolve('.'), encoding: 'utf8'});

  assert.notEqual(result.status, 0);
  assert.doesNotMatch(`${result.stderr}`, /ENOENT/);
  const analysis = JSON.parse(await readFile(join(output, 'analysis.json'), 'utf8'));
  assert.equal(analysis.totalCases, 1);
  assert.equal(analysis.successfulCases, 0);
  const markdown = await readFile(
    join(output, 'AI_MULTI_AGENT_MATRIX_RESULTS.md'), 'utf8');
  assert.match(markdown, /## Failures/);
  assert.match(markdown, new RegExp(`- \`${caseName}\``));
  assert.match(markdown,
    /trajectory\.atif\.json missing \(runner failed before the trajectory fetch\)/);
});
