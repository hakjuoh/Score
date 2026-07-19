import {createHash} from 'node:crypto';
import {readdirSync, readFileSync} from 'node:fs';

const agentDirectory = new URL(
  '../score-http/src/main/resources/prompts/', import.meta.url);

function registeredAgentIds() {
  const ids = readdirSync(agentDirectory, {withFileTypes: true})
    .filter(entry => entry.isFile()
      && entry.name.startsWith('agent-') && entry.name.endsWith('.md'))
    .map(entry => {
      const markdown = readFileSync(new URL(entry.name, agentDirectory), 'utf8');
      const frontmatter = markdown.match(/^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/)?.[1];
      const id = frontmatter?.match(/^id:\s*([a-z0-9][a-z0-9-]{1,63})\s*$/m)?.[1];
      if (!id) throw new Error(`Invalid AI agent frontmatter: ${entry.name}`);
      return id;
    });
  if (new Set(ids).size !== ids.length) throw new Error('Duplicate AI agent id');
  return new Set(ids);
}

// Read-only classification is not a fixed contract list: the MCP server declares
// it per tool through the readOnlyHint annotation, the backend guard resolves it
// per session, and every recorded tool step carries the resulting boolean in
// extra.read_only. The evaluator verifies against that recorded classification,
// which is backend-attested rather than independently derived; the independent
// check that no mutating tool ever claims the hint lives in connect-center-api's
// tests/tools/test_tool_annotations.py tripwire.
export function stepIsReadOnly(step) {
  return step?.extra?.read_only === true;
}
function stepIsSafeNonMutation(step) {
  return stepIsReadOnly(step) || step?.extra?.tool_name === 'toolSearchTool';
}
const LEAD_NAME = 'lead';
const LEAD_ROLE = 'workflow orchestrator';
const REGISTERED_AGENT_IDS = registeredAgentIds();
// Keep the evaluator's expected wire values independent from the runtime so a
// coordinated but breaking runtime change is caught instead of self-approved.
const MUTATION_CONFIRMATION_MARKER = 'MUTATION_CONFIRMATION_REQUIRED';
const REQUEST_STOPPING_MARKER = 'REQUEST_STOPPING';
const USAGE_STEP_KIND = 'fanout_usage';
const BASE64URL_32_BYTES = /^[A-Za-z0-9_-]{43}$/;
const SHA256_HEX = /^[a-f0-9]{64}$/;
const TOOL_IDENTIFIER_KEYS = Object.freeze({
  get_libraries: new Set(['library_id']),
  get_library: new Set(['library_id']),
  get_releases: new Set(['release_id', 'library_id']),
  get_release: new Set(['release_id', 'library_id']),
  get_working_release: new Set(['release_id', 'library_id']),
  get_data_types: new Set(['dt_manifest_id', 'dt_id', 'based_dt_manifest_id']),
  get_data_type: new Set(['dt_manifest_id', 'dt_id', 'based_dt_manifest_id']),
  get_core_components: new Set([
    'manifest_id', 'component_id', 'acc_manifest_id', 'asccp_manifest_id',
    'bccp_manifest_id', 'acc_id', 'asccp_id', 'bccp_id'
  ]),
  get_acc: new Set(['manifest_id', 'component_id', 'acc_manifest_id', 'acc_id']),
  get_asccp: new Set(['manifest_id', 'component_id', 'asccp_manifest_id', 'asccp_id']),
  get_bccp: new Set(['manifest_id', 'component_id', 'bccp_manifest_id', 'bccp_id'])
});
const READ_DOMAIN_GROUPS = Object.freeze([
  ['library', new Set(['get_libraries', 'get_library']), new Set(['library_id'])],
  ['release', new Set(['get_releases', 'get_release', 'get_working_release']),
    new Set(['release_id'])],
  ['datatype', new Set(['get_data_types', 'get_data_type']),
    new Set(['dt_manifest_id', 'dt_id', 'based_dt_manifest_id'])],
  ['core-component', new Set(['get_core_components', 'get_acc', 'get_asccp', 'get_bccp']),
    new Set(['manifest_id', 'component_id', 'acc_manifest_id', 'asccp_manifest_id',
      'bccp_manifest_id', 'acc_id', 'asccp_id', 'bccp_id'])]
]);

function jsonValue(raw) {
  try {
    return JSON.parse(raw);
  } catch {
    return undefined;
  }
}

function decodeNestedJson(value, depth = 0) {
  if (depth > 12) return value;
  if (typeof value === 'string') {
    const decoded = jsonValue(value);
    return decoded === undefined ? value : decodeNestedJson(decoded, depth + 1);
  }
  if (Array.isArray(value)) {
    return value.map(item => decodeNestedJson(item, depth + 1));
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value)
      .map(([key, item]) => [key, decodeNestedJson(item, depth + 1)]));
  }
  return value;
}

export function parseToolResult(step) {
  const message = String(step?.message || '');
  const marker = '\nResult:';
  const index = message.indexOf(marker);
  if (index < 0) return {parsed: false, raw: '', value: undefined};
  const raw = message.slice(index + marker.length).trim();
  const value = jsonValue(raw);
  return {
    parsed: value !== undefined,
    raw,
    value: value === undefined ? undefined : decodeNestedJson(value)
  };
}

function containsError(value, seen = new Set()) {
  if (typeof value === 'string') {
    return value.includes(MUTATION_CONFIRMATION_MARKER)
      || value.includes(REQUEST_STOPPING_MARKER);
  }
  if (!value || typeof value !== 'object' || seen.has(value)) return false;
  seen.add(value);
  if (!Array.isArray(value) && Object.entries(value)
    .some(([key, item]) => key.toLowerCase() === 'error' && item !== null && item !== false && item !== '')) {
    return true;
  }
  return Object.values(value).some(item => containsError(item, seen));
}

export function toolResultSucceeded(step) {
  if (step?.extra?.success === false) return false;
  const result = parseToolResult(step);
  return result.parsed && !containsError(result.value);
}

function allObjects(value, output = [], seen = new Set()) {
  if (!value || typeof value !== 'object' || seen.has(value)) return output;
  seen.add(value);
  if (!Array.isArray(value)) output.push(value);
  Object.values(value).forEach(item => allObjects(item, output, seen));
  return output;
}

function canonicalId(value) {
  return value === null || value === undefined ? null : String(value);
}

function identifierValues(step, groupKeys = null) {
  const result = parseToolResult(step);
  const allowedKeys = TOOL_IDENTIFIER_KEYS[step?.extra?.tool_name] || new Set();
  const values = [];
  for (const object of allObjects(result.value)) {
    for (const [key, value] of Object.entries(object)) {
      if (!allowedKeys.has(key) || groupKeys && !groupKeys.has(key)) continue;
      if (!['string', 'number'].includes(typeof value)) continue;
      const normalized = canonicalId(value)?.trim();
      if (normalized) values.push({key, value: normalized});
    }
  }
  return [...new Map(values.map(item => [`${item.key}\0${item.value}`, item])).values()];
}

function textReportsAnyId(text, identifiers) {
  return identifiers.some(identifier => {
    const escaped = identifier.value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const keyParts = identifier.key.split('_');
    if (keyParts.at(-1) === 'id') keyParts.pop();
    const label = keyParts
      .map(part => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
      .join('[_\\s-]*') + '(?:[_\\s-]*id)?';
    return new RegExp(`\\b${label}\\b[^A-Za-z0-9]{0,12}${escaped}(?![A-Za-z0-9]|\\.\\d)`, 'i')
      .test(String(text || ''));
  });
}

function requiredReadGroups(task, evidenceSchemaVersion) {
  const expected = new Set(task.expectedReads || []);
  if (evidenceSchemaVersion < 3) {
    return expected.size ? [['legacy-expected-read', expected]] : [];
  }
  const groups = READ_DOMAIN_GROUPS
    .map(([name, tools, keys]) => [
      name, new Set([...tools].filter(tool => expected.has(tool))), keys
    ])
    .filter(([, tools]) => tools.size > 0);
  if (groups.length) return groups;
  return expected.size ? [['expected-read', expected]] : [];
}

function inspectResponseStructure(task, evidenceSchemaVersion, text, identifiers) {
  const required = evidenceSchemaVersion >= 3
    && (task?.responseRequirements?.kind === 'creative-proposals'
      || task?.label === 'creative read-only review');
  if (!required) return {required: false, valid: true, proposals: 0, violations: []};
  // Indents of 0-2 spaces stay top-level; 3+ spaces would also match content
  // nested under a single-digit ordered item ('1. ' is three columns wide).
  const matches = [...String(text || '').matchAll(
    /^\s{0,2}(?:#{1,6}\s+)?\*{0,2}([1-9]\d*)[.)]\*{0,2}\s+/gm)];
  const violations = [];
  if (matches.length !== 3
    || !exactSequence(matches.map(match => Number(match[1])), [1, 2, 3])) {
    violations.push('creative response must contain exactly three numbered proposals');
  }
  const segments = matches.map((match, index) => String(text || '').slice(
    match.index, matches[index + 1]?.index ?? String(text || '').length));
  if (segments.some(segment => !/counterargument/i.test(segment))) {
    violations.push('every creative proposal must contain an explicit counterargument');
  }
  if (segments.some(segment => !textReportsAnyId(segment, identifiers))) {
    violations.push('every creative proposal must cite grounded domain ID evidence');
  }
  return {required: true, valid: violations.length === 0,
    proposals: matches.length, violations};
}

function expectedRecord(task) {
  const descriptionMatch = String(task?.prompt || '').match(/description\s+['`]([^'`]+)['`]/i);
  return {
    idKey: task?.expectedRecord?.idKey || 'ctx_category_id',
    name: task?.expectedRecord?.name || task?.label || '',
    description: task?.expectedRecord?.description || descriptionMatch?.[1] || ''
  };
}

function createdIds(step, idKey) {
  const result = parseToolResult(step);
  return [...new Set(allObjects(result.value)
    .map(object => canonicalId(object[idKey]))
    .filter(Boolean))];
}

function exactRecord(step, record, createdId) {
  const result = parseToolResult(step);
  return allObjects(result.value).some(object =>
    canonicalId(object[record.idKey]) === createdId
      && object.name === record.name
      && object.description === record.description);
}

function exactObjectKeys(value, expected) {
  if (!value || Array.isArray(value) || typeof value !== 'object') return false;
  const actualKeys = Object.keys(value).sort();
  const expectedKeys = Object.keys(expected).sort();
  return exactSequence(actualKeys, expectedKeys)
    && expectedKeys.every(key => value[key] === expected[key]);
}

function responseReportsId(response, createdId) {
  if (!createdId) return false;
  const escaped = createdId.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(^|\\D)${escaped}(\\D|$)`).test(String(response?.response || ''));
}

function toolSteps(trajectory) {
  return (trajectory?.steps || []).filter(step => step?.extra?.message_kind === 'tool_call');
}

function lifecycleSteps(trajectory) {
  return (trajectory?.steps || []).filter(step => step?.extra?.message_kind === 'agent_lifecycle');
}

function expectedFanoutId(requestId) {
  return `fanout-${createHash('sha256').update(String(requestId)).digest('hex').slice(0, 20)}`;
}

function exactSequence(actual, expected) {
  return actual.length === expected.length && actual.every((value, index) => value === expected[index]);
}

function inspectGraph(testCase, maxAgents, lifecycle, tools, allSteps, evidenceSchemaVersion) {
  const violations = [];
  const stepIds = allSteps.map(step => Number(step?.step_id));
  if (stepIds.some(stepId => !Number.isSafeInteger(stepId))
    || new Set(stepIds).size !== stepIds.length
    || stepIds.some((stepId, index) => index > 0 && stepId <= stepIds[index - 1])) {
    violations.push('step_id values must be unique and strictly increasing');
  }
  // Fan-out usage steps are per-fan-out accounting records, not execution
  // steps; they carry a fanout_id but deliberately no depth or node_id.
  const namespacedExecutionSteps = allSteps.filter(step =>
    step?.extra?.message_kind !== 'agent_lifecycle'
      && step?.extra?.message_kind !== USAGE_STEP_KIND
      && (step?.extra?.fanout_id || step?.extra?.node_id
        || step?.extra?.parent_node_id || step?.extra?.agent_name));
  const namespacedTools = tools.filter(step => step?.extra?.fanout_id
    || step?.extra?.node_id || step?.extra?.parent_node_id || step?.extra?.agent_name);
  const specialistTools = namespacedTools.filter(step => Number(step?.extra?.depth) === 1);
  const invalidDepthNamespacedTools = namespacedTools.filter(step =>
    ![0, 1].includes(Number(step?.extra?.depth)));
  const overDepthTools = tools.filter(step => Number(step?.extra?.depth) > 1);
  const invalidDepthExecutionSteps = namespacedExecutionSteps.filter(step =>
    ![0, 1].includes(Number(step?.extra?.depth)));
  const unsafeSpecialistTools = specialistTools.filter(step => !stepIsReadOnly(step));
  if (evidenceSchemaVersion >= 4) {
    const unclassifiedTools = tools.filter(step =>
      typeof step?.extra?.read_only !== 'boolean');
    if (unclassifiedTools.length !== 0) {
      violations.push('every tool step must carry the boolean guard read-only classification');
    }
  }

  if (testCase.mode === 'single') {
    if (lifecycle.length !== 0) violations.push('single mode emitted agent lifecycle steps');
    if (namespacedExecutionSteps.length !== 0) {
      violations.push('single mode emitted agent-namespaced execution steps');
    }
    if (overDepthTools.length !== 0) violations.push('single mode emitted nested tool steps');
    return {
      valid: violations.length === 0,
      violations,
      lifecycleSteps: lifecycle.length,
      childNodes: 0,
      terminalChildren: 0,
      fanoutId: null,
      requestId: null,
      specialistTools: specialistTools.length,
      unsafeSpecialistTools: unsafeSpecialistTools.map(step => step.extra.tool_name)
    };
  }

  const fanoutIds = [...new Set(lifecycle.map(step => step?.extra?.fanout_id).filter(Boolean))];
  const requestIds = [...new Set(lifecycle.map(step => step?.extra?.request_id).filter(Boolean))];
  if (fanoutIds.length !== 1) violations.push('lifecycle must have exactly one fanout_id');
  if (requestIds.length !== 1) violations.push('lifecycle must have exactly one request_id');
  if (lifecycle.some(step => !step?.extra?.fanout_id || !step?.extra?.request_id)) {
    violations.push('every lifecycle step must carry fanout_id and request_id');
  }
  if (lifecycle.some(step => !step?.extra?.node_id || !step?.extra?.status)) {
    violations.push('every lifecycle step must carry node_id and status');
  }
  const fanoutId = fanoutIds[0] || null;
  const requestId = requestIds[0] || null;
  if (fanoutId && requestId && fanoutId !== expectedFanoutId(requestId)) {
    violations.push('fanout_id is not the deterministic SHA-256 derivation of request_id');
  }
  if (lifecycle.some(step => ![0, 1].includes(Number(step?.extra?.depth)))) {
    violations.push('lifecycle contains a depth other than 0 or 1');
  }
  if (overDepthTools.length !== 0) violations.push('tool trace contains depth greater than 1');
  if (invalidDepthExecutionSteps.length !== 0) {
    violations.push('agent-namespaced execution contains a depth other than 0 or 1');
  }
  if (invalidDepthNamespacedTools.length !== 0) {
    violations.push('every namespaced tool must have depth 0 or 1');
  }

  const lead = lifecycle.filter(step => Number(step?.extra?.depth) === 0);
  const leadNodeIds = [...new Set(lead.map(step => step?.extra?.node_id).filter(Boolean))];
  const leadNodeId = leadNodeIds[0] || null;
  if (leadNodeIds.length !== 1 || leadNodeId !== `${fanoutId}-lead`) {
    violations.push('lead must use the deterministic fanout lead node ID');
  }
  if (!exactSequence(lead.map(step => step?.extra?.status), ['started', 'synthesizing', 'completed'])) {
    violations.push('lead lifecycle must be started -> synthesizing -> completed');
  }
  if (lead.some(step => step?.extra?.agent_name !== LEAD_NAME
    || step?.extra?.agent_role !== LEAD_ROLE || Number(step?.extra?.depth) !== 0
    || step?.extra?.strategy !== testCase.strategy
    || Number(step?.extra?.max_agents) !== maxAgents)) {
    violations.push('lead lifecycle metadata is incomplete or inconsistent');
  }
  const leadSynthesisIndex = allSteps.findIndex(step => step?.extra?.message_kind === 'agent_lifecycle'
    && step?.extra?.node_id === leadNodeId && step?.extra?.status === 'synthesizing');
  const leadStartedIndex = allSteps.findIndex(step => step?.extra?.message_kind === 'agent_lifecycle'
    && step?.extra?.node_id === leadNodeId && step?.extra?.status === 'started');
  const leadTerminalIndex = allSteps.findIndex(step => step?.extra?.message_kind === 'agent_lifecycle'
    && step?.extra?.node_id === leadNodeId && ['completed', 'failed'].includes(step?.extra?.status));
  for (const step of namespacedExecutionSteps.filter(candidate =>
    Number(candidate?.extra?.depth) === 0)) {
    const index = allSteps.indexOf(step);
    const finalProjection = step?.extra?.message_kind === 'assistant'
      && step?.extra?.visibility === 'visible';
    const ordered = finalProjection
      ? index > leadTerminalIndex
      : index > leadSynthesisIndex && index < leadTerminalIndex;
    if (step?.extra?.fanout_id !== fanoutId
      || step?.extra?.request_id !== requestId
      || step?.extra?.node_id !== leadNodeId
      || step?.extra?.agent_name !== LEAD_NAME
      || step?.extra?.agent_role !== LEAD_ROLE
      || Number(step?.extra?.depth) !== 0
      || step?.extra?.strategy !== testCase.strategy
      || Number(step?.extra?.max_agents) !== maxAgents
      || step?.extra?.parent_node_id
      || !ordered) {
      violations.push(`lead ${step?.extra?.message_kind || 'execution'} step has invalid graph linkage`);
    }
  }

  const childSteps = lifecycle.filter(step => Number(step?.extra?.depth) === 1);
  const childNodeIds = [...new Set(childSteps.map(step => step?.extra?.node_id).filter(Boolean))];
  const childGroups = new Map(childNodeIds.map(nodeId =>
    [nodeId, childSteps.filter(step => step?.extra?.node_id === nodeId)]));
  if (childNodeIds.length !== maxAgents) {
    violations.push(`expected ${maxAgents} unique child nodes, found ${childNodeIds.length}`);
  }
  const ordinals = [];
  let terminalChildren = 0;
  let completedChildren = 0;
  for (const [nodeId, steps] of childGroups) {
    const ordinalValues = [...new Set(steps.map(step => Number(step?.extra?.ordinal)))];
    const ordinal = ordinalValues[0];
    if (ordinalValues.length !== 1 || !Number.isInteger(ordinal)) {
      violations.push(`${nodeId} has inconsistent ordinal metadata`);
      continue;
    }
    ordinals.push(ordinal);
    if (nodeId !== `${fanoutId}-agent-${String(ordinal).padStart(2, '0')}`) {
      violations.push(`${nodeId} is not the deterministic node ID for ordinal ${ordinal}`);
    }
    const statuses = steps.map(step => step?.extra?.status);
    if (!(statuses.length === 2 && statuses[0] === 'started'
      && ['completed', 'failed'].includes(statuses[1]))) {
      violations.push(`${nodeId} lifecycle must be started -> completed|failed`);
    } else {
      terminalChildren += 1;
      if (statuses[1] === 'completed') completedChildren += 1;
    }
    if (steps.some(step => step?.extra?.parent_node_id !== leadNodeId
      || step?.extra?.fanout_id !== fanoutId || step?.extra?.request_id !== requestId)) {
      violations.push(`${nodeId} parent, fanout, or request linkage is inconsistent`);
    }
    const nodeNames = [...new Set(steps.map(step => step?.extra?.agent_name).filter(Boolean))];
    const nodeRoles = [...new Set(steps.map(step => step?.extra?.agent_role).filter(Boolean))];
    if (nodeNames.length !== 1 || nodeRoles.length !== 1) {
      violations.push(`${nodeId} agent name or role is missing/inconsistent`);
    } else {
      const agentIds = [...new Set(steps.map(step => step?.extra?.agent_id).filter(Boolean))];
      if (agentIds.length !== 1 || !REGISTERED_AGENT_IDS.has(agentIds[0])) {
        violations.push(`${nodeId} does not identify one registered agent`);
      }
    }
  }
  const expectedOrdinals = Array.from({length: maxAgents}, (_, index) => index + 1);
  if (!exactSequence([...ordinals].sort((a, b) => a - b), expectedOrdinals)) {
    violations.push('child ordinals must be unique and contiguous from 1 to maxAgents');
  }
  if (completedChildren === 0) {
    violations.push('lead completion requires at least one completed child agent');
  }

  const childStartedIndexes = childNodeIds.map(nodeId => allSteps.findIndex(step =>
    step?.extra?.message_kind === 'agent_lifecycle' && step?.extra?.node_id === nodeId
      && step?.extra?.status === 'started'));
  const childTerminalIndexes = childNodeIds.map(nodeId => allSteps.findIndex(step =>
    step?.extra?.message_kind === 'agent_lifecycle' && step?.extra?.node_id === nodeId
      && ['completed', 'failed'].includes(step?.extra?.status)));
  if (childStartedIndexes.some(index => index < 0) || childTerminalIndexes.some(index => index < 0)
    || Math.max(...childStartedIndexes) >= Math.min(...childTerminalIndexes)) {
    violations.push('all child agents must start before any child reaches a terminal state');
  }

  const synthesisIndex = lifecycle.findIndex(step => step?.extra?.status === 'synthesizing');
  const childTerminalLifecycleIndexes = childNodeIds.map(nodeId =>
    lifecycle.findLastIndex(step => step?.extra?.node_id === nodeId));
  if (synthesisIndex < 0
    || childTerminalLifecycleIndexes.some(index => index < 0 || index >= synthesisIndex)) {
    violations.push('all child nodes must terminate before synthesis starts');
  }

  const visibleFinals = allSteps.filter(step => step?.extra?.message_kind === 'assistant'
    && step?.extra?.visibility === 'visible' && step?.extra?.request_id === requestId);
  if (visibleFinals.length !== 1 || allSteps.indexOf(visibleFinals[0]) <= leadTerminalIndex) {
    violations.push('fan-out request must have exactly one visible final projection after lead terminal');
  }
  const fanoutExecutionSteps = allSteps.filter((step, index) =>
    index > leadStartedIndex && index < leadTerminalIndex
      && ['model_call', 'tool_call'].includes(step?.extra?.message_kind));
  const projectedFinal = visibleFinals.length === 1 ? visibleFinals[0] : null;
  const postHardeningTrace = evidenceSchemaVersion >= 2 || !!projectedFinal?.extra?.fanout_id;
  if (postHardeningTrace) {
    const unnamespacedExecutions = fanoutExecutionSteps.filter(step =>
      step?.extra?.request_id !== requestId
        || !step?.extra?.fanout_id || !step?.extra?.node_id
          || !step?.extra?.agent_name || !step?.extra?.agent_role
          || ![0, 1].includes(Number(step?.extra?.depth)));
    if (unnamespacedExecutions.length !== 0) {
      violations.push('post-hardening fan-out model/tool steps must carry a complete agent namespace');
    }
  }
  if (evidenceSchemaVersion >= 2 && projectedFinal
    && (projectedFinal.extra?.fanout_id !== fanoutId
      || projectedFinal.extra?.node_id !== leadNodeId
      || projectedFinal.extra?.agent_name !== LEAD_NAME
      || projectedFinal.extra?.agent_role !== LEAD_ROLE
      || Number(projectedFinal.extra?.depth) !== 0
      || projectedFinal.extra?.strategy !== testCase.strategy
      || Number(projectedFinal.extra?.max_agents) !== maxAgents)) {
    violations.push('current-schema fan-out final must carry the complete lead namespace');
  }

  for (const step of specialistTools) {
    const matchingNode = childGroups.get(step?.extra?.node_id);
    const ordinal = Number(step?.extra?.ordinal);
    const toolIndex = allSteps.indexOf(step);
    const startedIndex = allSteps.findIndex(candidate => candidate?.extra?.message_kind === 'agent_lifecycle'
      && candidate?.extra?.node_id === step?.extra?.node_id && candidate?.extra?.status === 'started');
    const terminalIndex = allSteps.findIndex(candidate => candidate?.extra?.message_kind === 'agent_lifecycle'
      && candidate?.extra?.node_id === step?.extra?.node_id
      && ['completed', 'failed'].includes(candidate?.extra?.status));
    if (!matchingNode || step?.extra?.fanout_id !== fanoutId
      || step?.extra?.request_id !== requestId
      || step?.extra?.parent_node_id !== leadNodeId
      || !matchingNode.every(lifecycleStep => Number(lifecycleStep.extra.ordinal) === ordinal)
      || toolIndex <= startedIndex || toolIndex >= terminalIndex) {
      violations.push(`specialist tool ${step?.extra?.tool_name || '<missing>'} has invalid graph linkage`);
    }
  }
  for (const step of namespacedExecutionSteps.filter(candidate =>
    Number(candidate?.extra?.depth) === 1)) {
    const matchingNode = childGroups.get(step?.extra?.node_id);
    const stepIndex = allSteps.indexOf(step);
    const startedIndex = allSteps.findIndex(candidate => candidate?.extra?.message_kind === 'agent_lifecycle'
      && candidate?.extra?.node_id === step?.extra?.node_id && candidate?.extra?.status === 'started');
    const terminalIndex = allSteps.findIndex(candidate => candidate?.extra?.message_kind === 'agent_lifecycle'
      && candidate?.extra?.node_id === step?.extra?.node_id
      && ['completed', 'failed'].includes(candidate?.extra?.status));
    const expected = matchingNode?.[0]?.extra;
    if (!matchingNode || step?.extra?.fanout_id !== fanoutId
      || step?.extra?.request_id !== requestId
      || step?.extra?.parent_node_id !== leadNodeId
      || step?.extra?.agent_name !== expected?.agent_name
      || step?.extra?.agent_role !== expected?.agent_role
      || Number(step?.extra?.ordinal) !== Number(expected?.ordinal)
      || stepIndex <= startedIndex || stepIndex >= terminalIndex) {
      violations.push(`specialist ${step?.extra?.message_kind || 'execution'} step has invalid graph linkage`);
    }
  }
  if (unsafeSpecialistTools.length !== 0) {
    violations.push('specialist invoked a tool the guard did not classify read-only'
      + ' (missing server readOnlyHint)');
  }

  return {
    valid: violations.length === 0,
    violations,
    lifecycleSteps: lifecycle.length,
    childNodes: childNodeIds.length,
    terminalChildren,
    fanoutId,
    requestId,
    specialistTools: specialistTools.length,
    unsafeSpecialistTools: unsafeSpecialistTools.map(step => step.extra.tool_name)
  };
}

function confirmationMatchesTask(evidence, task, conversationId) {
  if (!evidence || evidence.conversationId !== conversationId
    || evidence.toolName !== task.expectedMutation || !evidence.confirmationRequestId) return false;
  const argumentsValue = jsonValue(String(evidence.argumentsSummary || ''));
  const record = expectedRecord(task);
  return exactObjectKeys(argumentsValue, {name: record.name, description: record.description});
}

export function approvalAuthorizationDigest({
  confirmationRequestId, conversationId, continuationRequestId, toolName,
  argumentsSummary, confirmationGrantDigest
}) {
  return createHash('sha256').update(JSON.stringify({
    confirmationRequestId, conversationId, continuationRequestId, toolName,
    argumentsSummary, confirmationGrantDigest
  })).digest('hex');
}

function validDate(value) {
  return typeof value === 'string' && Number.isFinite(Date.parse(value));
}

function decisionMatches(decision, confirmation, conversationId, continuationRequestId,
                         evidenceSchemaVersion) {
  if (!decision || !confirmation
    || decision.confirmationRequestId !== confirmation.confirmationRequestId
    || decision.conversationId !== conversationId
    || decision.status !== 'APPROVED' || decision.disposition !== 'APPROVED'
    || !validDate(decision.approvedAt) || !validDate(decision.expiresAt)
    || Date.parse(decision.expiresAt) <= Date.parse(decision.approvedAt)) return false;
  if (evidenceSchemaVersion >= 3) {
    if (decision.confirmationGrant !== undefined
      || !SHA256_HEX.test(String(decision.confirmationGrantDigest || ''))
      || decision.grantEncoding !== 'base64url' || Number(decision.grantBytes) !== 32
      || decision.continuationRequestId !== continuationRequestId) return false;
    const digest = approvalAuthorizationDigest({
      confirmationRequestId: confirmation.confirmationRequestId,
      conversationId,
      continuationRequestId,
      toolName: confirmation.toolName,
      argumentsSummary: confirmation.argumentsSummary,
      confirmationGrantDigest: decision.confirmationGrantDigest
    });
    return decision.authorizationDigest === digest;
  }
  return evidenceSchemaVersion === 2
    && BASE64URL_32_BYTES.test(String(decision.confirmationGrant || ''));
}

function inspectTools(task, tools, approvals, confirmationRequests, response, permissionMode,
                      allSteps, confirmationEvidence, approvalDecisions,
                      allowLegacyApprovalInference, conversationId, evidenceSchemaVersion) {
  const names = tools.map(step => step?.extra?.tool_name);
  const specialistMutationAttempts = tools.filter(step => Number(step?.extra?.depth) === 1
    && !stepIsReadOnly(step));
  const unexpectedMutationCalls = tools.filter(step =>
    !stepIsSafeNonMutation(step)
      && (!task.expectedMutation || step?.extra?.tool_name !== task.expectedMutation));
  const confirmation = confirmationEvidence.length === 1 ? confirmationEvidence[0] : null;
  const decision = approvalDecisions.length === 1 ? approvalDecisions[0] : null;
  const confirmationValid = confirmationMatchesTask(confirmation, task, conversationId);
  if (!task.expectedMutation) {
    const readGroups = requiredReadGroups(task, evidenceSchemaVersion)
      .map(([name, expectedTools, identifierKeys]) => {
        const steps = tools.filter(step => expectedTools.has(step?.extra?.tool_name)
          && toolResultSucceeded(step));
        const identifiers = steps.flatMap(step => identifierValues(step, identifierKeys));
        return {
          name,
          tools: [...expectedTools],
          calls: steps.length,
          identifiers: identifiers.length,
          grounded: identifiers.length > 0 && textReportsAnyId(response?.response, identifiers)
        };
      });
    const readBackSteps = tools.filter(step => task.expectedReads.includes(step?.extra?.tool_name)
      && toolResultSucceeded(step));
    const identifiers = readBackSteps.flatMap(step => identifierValues(step));
    const readBack = readGroups.length > 0 && readGroups.every(group => group.calls > 0);
    const grounded = readGroups.length > 0 && readGroups.every(group => group.grounded);
    const responseStructure = inspectResponseStructure(
      task, evidenceSchemaVersion, response?.response, identifiers);
    const valid = readBack && grounded && responseStructure.valid
      && specialistMutationAttempts.length === 0
      && unexpectedMutationCalls.length === 0;
    return {
      valid,
      names,
      mutation: true,
      executedMutations: 0,
      rejectedMutationCalls: 0,
      specialistMutationAttempts: specialistMutationAttempts.length,
      unexpectedMutationCalls: unexpectedMutationCalls.map(step => step.extra.tool_name),
      approvalValid: true,
      readBack,
      grounded,
      readGroups,
      responseStructure,
      evidenceIdentifiers: identifiers.length,
      readBackTool: readBackSteps[0]?.extra?.tool_name || null,
      createdId: null,
      responseReportsCreatedId: true
    };
  }

  const mutationSteps = tools.filter(step => step?.extra?.tool_name === task.expectedMutation);
  const executedMutations = mutationSteps.filter(toolResultSucceeded);
  const rejectedMutationSteps = mutationSteps.filter(step => !toolResultSucceeded(step));
  const confirmationRequiredSteps = rejectedMutationSteps.filter(step =>
    parseToolResult(step).raw.includes(MUTATION_CONFIRMATION_MARKER));
  const record = expectedRecord(task);
  const expectedArguments = {name: record.name, description: record.description};
  const mutationArgumentsValid = mutationSteps.length > 0
    && mutationSteps.every(step => exactObjectKeys(step?.extra?.arguments, expectedArguments));
  const ids = executedMutations.length === 1 ? createdIds(executedMutations[0], record.idKey) : [];
  const createdId = ids.length === 1 ? ids[0] : null;
  const mutationIndex = createdId ? tools.indexOf(executedMutations[0]) : -1;
  const mutationRequestId = mutationIndex >= 0
    ? executedMutations[0]?.extra?.request_id : null;
  const confirmationStep = confirmationRequiredSteps.length === 1
    ? confirmationRequiredSteps[0] : null;
  const confirmationStepIndex = confirmationStep ? tools.indexOf(confirmationStep) : -1;
  const approvalTraceValid = permissionMode !== 'ask'
    || mutationSteps.length === 2 && executedMutations.length === 1
      && rejectedMutationSteps.length === 1 && confirmationRequiredSteps.length === 1
      && confirmationStepIndex >= 0 && confirmationStepIndex < mutationIndex
      && confirmationStep?.extra?.request_id !== mutationRequestId
      && (evidenceSchemaVersion < 2
        || Number(confirmationStep?.extra?.depth) === 0
          && confirmationStep?.extra?.agent_name === LEAD_NAME);
  const decisionValid = confirmationValid && decisionMatches(
    decision, confirmation, conversationId, mutationRequestId, evidenceSchemaVersion);
  const sourceApprovalValid = confirmationValid && decisionValid;
  const approvalValid = permissionMode !== 'ask'
    || approvals === 1 && confirmationRequests === 1
      && confirmationValid && (decisionValid || allowLegacyApprovalInference);
  const readBackSteps = tools.slice(mutationIndex + 1).filter(step =>
    task.expectedReads.includes(step?.extra?.tool_name)
      && toolResultSucceeded(step)
      && step?.extra?.request_id === mutationRequestId
      && canonicalId(step?.extra?.arguments?.[record.idKey]) === createdId
      && exactRecord(step, record, createdId));
  const reportsId = responseReportsId(response, createdId);
  const mutation = executedMutations.length === 1 && ids.length === 1;
  const readBack = mutationIndex >= 0 && readBackSteps.length > 0;
  const readBackIndex = readBack ? allSteps.indexOf(readBackSteps[0]) : -1;
  const continuationFinals = readBack ? allSteps.filter((step, index) =>
    index > readBackIndex && step?.extra?.message_kind === 'assistant'
      && step?.extra?.visibility === 'visible'
      && step?.extra?.request_id === mutationRequestId) : [];
  const continuationFinal = continuationFinals.length === 1
    && responseReportsId({response: continuationFinals[0].message}, createdId);
  return {
    valid: mutation && mutationArgumentsValid && approvalTraceValid && readBack
      && continuationFinal && approvalValid && reportsId
      && specialistMutationAttempts.length === 0 && unexpectedMutationCalls.length === 0,
    names,
    mutation,
    mutationArgumentsValid,
    approvalTraceValid,
    executedMutations: executedMutations.length,
    rejectedMutationCalls: rejectedMutationSteps.length,
    specialistMutationAttempts: specialistMutationAttempts.length,
    unexpectedMutationCalls: unexpectedMutationCalls.map(step => step.extra.tool_name),
    approvalValid,
    approvalEvidence: sourceApprovalValid ? 'decision_artifact'
      : allowLegacyApprovalInference ? 'legacy_inferred' : 'missing',
    decisionValid,
    readBack,
    continuationFinal,
    readBackTool: readBackSteps[0]?.extra?.tool_name || null,
    createdId,
    responseReportsCreatedId: reportsId
  };
}

function inspectFinalEvidence(testCase, trajectory, graph, response, sourceResponses) {
  const violations = [];
  const steps = trajectory?.steps || [];
  const responses = sourceResponses.length ? sourceResponses : response ? [response] : [];
  const finals = steps.filter(step => step?.extra?.message_kind === 'assistant'
    && step?.extra?.visibility === 'visible');
  if (responses.length === 0 || finals.length !== responses.length) {
    violations.push('REST responses and visible ATIF finals must have the same non-zero count');
  } else if (finals.some((step, index) => step?.message !== responses[index]?.response)) {
    violations.push('every REST response must exactly match its ordered visible ATIF final');
  }
  const requestIds = finals.map(step => step?.extra?.request_id);
  if (requestIds.some(requestId => typeof requestId !== 'string' || !requestId)
    || new Set(requestIds).size !== requestIds.length) {
    violations.push('visible ATIF finals must have unique non-empty request IDs');
  }
  if (testCase.mode === 'single' && finals.length !== 1) {
    violations.push('single mode must have exactly one visible final');
  }
  if (testCase.mode === 'parallel') {
    const fanoutFinals = finals.filter(step => step?.extra?.request_id === graph.requestId);
    if (fanoutFinals.length !== 1) {
      violations.push('parallel mode must have exactly one fan-out request final');
    }
  }
  if (responses.length && response?.response !== responses.at(-1)?.response) {
    violations.push('the reported final response must be the last saved REST response');
  }
  const totalSteps = Number(trajectory?.final_metrics?.total_steps);
  if (!Number.isSafeInteger(totalSteps) || totalSteps !== steps.length) {
    violations.push('ATIF final_metrics.total_steps must equal the saved step count');
  }
  return {valid: violations.length === 0, violations, finals: finals.length, responses: responses.length};
}

export function evaluateMultiAgentCase({
  testCase, task, trajectory, approvals = 0, response, failure,
  permissionMode = 'full_access', maxAgents = 3, confirmationRequests = 0,
  confirmationEvidence = [], approvalDecisions = [], allowLegacyApprovalInference = false,
  sourceResponseConversationIds = [], sourceResponses = [], evidenceSchemaVersion = 1
}) {
  const steps = trajectory?.steps || [];
  const tools = toolSteps(trajectory);
  const lifecycle = lifecycleSteps(trajectory);
  const graph = inspectGraph(
    testCase, maxAgents, lifecycle, tools, steps, evidenceSchemaVersion);
  const conversationId = trajectory?.session_id || null;
  const toolEvidence = inspectTools(
    task, tools, approvals, confirmationRequests, response, permissionMode, steps,
    confirmationEvidence, approvalDecisions, allowLegacyApprovalInference, conversationId,
    evidenceSchemaVersion
  );
  const finalEvidence = inspectFinalEvidence(testCase, trajectory, graph, response, sourceResponses);
  const responseConversationIds = sourceResponseConversationIds.length
    ? sourceResponseConversationIds
    : sourceResponses.length ? sourceResponses.map(item => item?.conversationId)
      : [response?.conversationId];
  const conversationMatches = !!response?.conversationId && !!conversationId
    && responseConversationIds.length > 0
    && responseConversationIds.every(candidate => candidate === conversationId);
  return {
    success: !failure && graph.valid && toolEvidence.valid
      && finalEvidence.valid && conversationMatches,
    failure: failure ? String(failure.stack || failure.message || failure) : null,
    conversationId: response?.conversationId || trajectory?.session_id || null,
    finalResponse: response?.response || '',
    approvals,
    confirmationRequests,
    conversationMatches,
    evidenceSchemaVersion,
    evidenceTier: evidenceSchemaVersion >= 3 ? 'redacted_linked'
      : evidenceSchemaVersion === 2 ? 'raw_decision' : 'legacy_inferred',
    graph,
    tools: toolEvidence,
    finals: finalEvidence,
    metrics: trajectory?.final_metrics || {}
  };
}
