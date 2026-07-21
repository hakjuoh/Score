import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {
  approvalAuthorizationDigest, evaluateMultiAgentCase, toolResultSucceeded
} from './ai-multi-agent-evaluator.mjs';

const requestId = '00000000-0000-4000-8000-000000000001';
const continuationRequestId = '00000000-0000-4000-8000-000000000002';
const fanoutId = `fanout-${createHash('sha256').update(requestId).digest('hex').slice(0, 20)}`;
const leadNodeId = `${fanoutId}-lead`;
const verificationRoles = [
  ['requirements-auditor', 'requirements audit'],
  ['evidence-checker', 'evidence verification'],
  ['safety-auditor', 'safety verification'],
  ['completeness-auditor', 'completeness verification']
];
const task = {
  label: 'MA Strict 01',
  prompt: "Create exactly one Context Category named 'MA Strict 01' with description 'strict evidence'.",
  expectedMutation: 'create_context_category',
  expectedReads: ['get_context_category', 'get_context_categories'],
  expectedRecord: {
    idKey: 'ctx_category_id',
    name: 'MA Strict 01',
    description: 'strict evidence'
  }
};

function lifecycle(nodeId, depth, status, extra = {}) {
  const role = depth === 0 ? ['lead', 'workflow orchestrator']
    : verificationRoles[(extra.ordinal || 1) - 1];
  return {
    message: status,
    extra: {
      message_kind: 'agent_lifecycle',
      fanout_id: fanoutId,
      request_id: requestId,
      node_id: nodeId,
      depth,
      status,
      agent_name: role[0],
      agent_role: role[1],
      ...(depth === 1 ? {agent_id: 'evidence-researcher'} : {}),
      ...(depth === 0 ? {strategy: 'verification', max_agents: 1} : {}),
      ...extra
    }
  };
}

function tool(name, result, extra = {}) {
  return {
    message: `${name}\nArguments: {}\nResult: ${JSON.stringify(result)}`,
    extra: {
      message_kind: 'tool_call',
      tool_name: name,
      success: true,
      arguments: {},
      // Mirrors the recorded guard classification derived from the MCP server's
      // readOnlyHint tool annotations.
      read_only: name.startsWith('get_') || name === 'who_am_i',
      ...extra
    }
  };
}

function childTool(name = 'get_libraries', overrides = {}) {
  return tool(name, [{text: JSON.stringify({items: []})}], {
    fanout_id: fanoutId,
    request_id: requestId,
    node_id: `${fanoutId}-agent-01`,
    parent_node_id: leadNodeId,
    agent_name: verificationRoles[0][0],
    agent_role: verificationRoles[0][1],
    agent_id: 'evidence-researcher',
    ordinal: 1,
    depth: 1,
    ...overrides
  });
}

function leadNamespace() {
  return {
    fanout_id: fanoutId,
    request_id: requestId,
    node_id: leadNodeId,
    agent_name: 'lead',
    agent_role: 'workflow orchestrator',
    depth: 0,
    strategy: 'verification',
    max_agents: 1
  };
}

function assistant(request, content, extra = {}) {
  return {
    message: content,
    extra: {
      message_kind: 'assistant', visibility: 'visible', request_id: request, ...extra
    }
  };
}

const confirmationEvidence = [{
  confirmationRequestId: 'confirmation-1',
  conversationId: 'strict-session',
  toolName: 'create_context_category',
  argumentsSummary: JSON.stringify({name: 'MA Strict 01', description: 'strict evidence'})
}];
const confirmationGrantDigest = 'a'.repeat(64);
const approvalDecisions = [{
  confirmationRequestId: 'confirmation-1',
  conversationId: 'strict-session',
  status: 'APPROVED',
  disposition: 'APPROVED',
  approvedAt: '2026-07-19T12:00:00.000Z',
  expiresAt: '2026-07-19T12:10:00.000Z',
  confirmationGrantDigest,
  grantEncoding: 'base64url',
  grantBytes: 32,
  continuationRequestId,
  authorizationDigest: approvalAuthorizationDigest({
    confirmationRequestId: 'confirmation-1',
    conversationId: 'strict-session',
    continuationRequestId,
    toolName: 'create_context_category',
    argumentsSummary: confirmationEvidence[0].argumentsSummary,
    confirmationGrantDigest
  })
}];

function strictTrajectory() {
  const steps = [
    lifecycle(leadNodeId, 0, 'started'),
    lifecycle(`${fanoutId}-agent-01`, 1, 'started', {
      ordinal: 1,
      parent_node_id: leadNodeId
    }),
    childTool(),
    lifecycle(`${fanoutId}-agent-01`, 1, 'completed', {
      ordinal: 1,
      parent_node_id: leadNodeId
    }),
    lifecycle(leadNodeId, 0, 'synthesizing'),
    tool('create_context_category', {
      error: 'MUTATION_CONFIRMATION_REQUIRED',
      message: 'not executed'
    }, {...leadNamespace(), arguments: {
      name: 'MA Strict 01', description: 'strict evidence'
    }}),
    lifecycle(leadNodeId, 0, 'completed'),
    assistant(requestId, 'Approval required.', {...leadNamespace(), status: 'completed'}),
    tool('create_context_category', [{text: JSON.stringify({ctx_category_id: 90})}], {
      request_id: continuationRequestId,
      arguments: {name: 'MA Strict 01', description: 'strict evidence'}
    }),
    tool('get_context_category', [{text: JSON.stringify({
      ctx_category_id: 90,
      name: 'MA Strict 01',
      description: 'strict evidence'
    })}], {request_id: continuationRequestId, arguments: {ctx_category_id: 90}}),
    assistant(continuationRequestId, 'Created ID 90.')
  ].map((step, index) => ({...step, step_id: index + 1}));
  return {
    session_id: 'strict-session',
    final_metrics: {total_steps: steps.length},
    steps
  };
}

function evaluate(trajectory = strictTrajectory(), responseText = 'Created ID 90.', overrides = {}) {
  return evaluateMultiAgentCase({
    testCase: {mode: 'parallel', strategy: 'verification', combo: {}},
    task,
    trajectory,
    approvals: 1,
    response: {conversationId: 'strict-session', response: responseText},
    permissionMode: 'ask',
    maxAgents: 1,
    confirmationRequests: 1,
    confirmationEvidence,
    approvalDecisions,
    sourceResponses: [
      {conversationId: 'strict-session', response: 'Approval required.'},
      {conversationId: 'strict-session', response: responseText}
    ],
    evidenceSchemaVersion: 4,
    ...overrides
  });
}

test('accepts exact mutation, read-back, and deterministic graph evidence', () => {
  const trajectory = strictTrajectory();
  const result = evaluate(trajectory);
  assert.equal(result.success, true);
  assert.equal(result.tools.createdId, '90');
  assert.equal(result.tools.executedMutations, 1);
  assert.equal(result.tools.rejectedMutationCalls, 1);
});

test('rejects a lead tool attached to the wrong graph node', () => {
  const trajectory = strictTrajectory();
  trajectory.steps[5].extra = {
    ...trajectory.steps[5].extra,
    fanout_id: fanoutId,
    request_id: requestId,
    node_id: `${fanoutId}-agent-01`,
    agent_name: 'lead',
    agent_role: 'workflow orchestrator',
    depth: 0
  };
  assert.equal(evaluate(trajectory).success, false);
});

test('rejects an error nested in an MCP text envelope', () => {
  const step = tool('create_context_category', [{text: JSON.stringify({error: 'denied'})}]);
  assert.equal(toolResultSucceeded(step), false);
  const trajectory = strictTrajectory();
  trajectory.steps[8] = {...step, step_id: 9, extra: {
    ...step.extra, request_id: continuationRequestId
  }};
  assert.equal(evaluate(trajectory).success, false);
});

test('does not accept a matching pre-read as mutation read-back', () => {
  const trajectory = strictTrajectory();
  const read = trajectory.steps.splice(9, 1)[0];
  trajectory.steps.splice(8, 0, read);
  const result = evaluate(trajectory);
  assert.equal(result.success, false);
  assert.equal(result.tools.readBack, false);
});

test('requires exact read-back description and final response ID', () => {
  const trajectory = strictTrajectory();
  trajectory.steps[9] = {...tool('get_context_category', [{text: JSON.stringify({
    ctx_category_id: 90,
    name: 'MA Strict 01',
    description: 'wrong'
  })}], {request_id: continuationRequestId}), step_id: 10};
  assert.equal(evaluate(trajectory).success, false);
  assert.equal(evaluate(strictTrajectory(), 'Created the requested record.').success, false);
});

test('rejects mixed fan-out IDs, nested depth, and child mutation tools', () => {
  const mixed = strictTrajectory();
  mixed.steps[3].extra.fanout_id = 'fanout-other';
  assert.equal(evaluate(mixed).graph.valid, false);

  const nested = strictTrajectory();
  nested.steps[2].extra.depth = 2;
  assert.equal(evaluate(nested).graph.valid, false);

  const mutation = strictTrajectory();
  mutation.steps[2] = childTool('create_context_category');
  const result = evaluate(mutation);
  assert.equal(result.success, false);
  assert.equal(result.tools.specialistMutationAttempts, 1);
});

test('rejects a specialist tool the guard did not classify as read-only', () => {
  const trajectory = strictTrajectory();
  trajectory.steps[2] = {...childTool('get_libraries', {read_only: false}), step_id: 3};
  const result = evaluate(trajectory);
  assert.equal(result.success, false);
  assert.deepEqual(result.graph.unsafeSpecialistTools, ['get_libraries']);
});

test('requires the boolean guard classification on every tool step at schema v4', () => {
  const trajectory = strictTrajectory();
  delete trajectory.steps[9].extra.read_only;
  const result = evaluate(trajectory);
  assert.equal(result.success, false);
  assert.ok(result.graph.violations.includes(
    'every tool step must carry the boolean guard read-only classification'));
  assert.equal(evaluate(trajectory, 'Created ID 90.', {evidenceSchemaVersion: 3}).graph.violations
    .includes('every tool step must carry the boolean guard read-only classification'), false);
});

test('rejects out-of-lifecycle tools, unexpected lead mutations, and conversation mismatch', () => {
  const lateChildTool = strictTrajectory();
  const moved = lateChildTool.steps.splice(2, 1)[0];
  lateChildTool.steps.push(moved);
  assert.equal(evaluate(lateChildTool).success, false);

  const unexpectedMutation = strictTrajectory();
  unexpectedMutation.steps.splice(10, 0, {
    ...tool('delete_context_category', {deleted: true}, {request_id: continuationRequestId}),
    step_id: 11
  });
  unexpectedMutation.steps[11].step_id = 12;
  assert.equal(evaluate(unexpectedMutation).success, false);

  const result = evaluateMultiAgentCase({
    testCase: {mode: 'parallel', strategy: 'verification', combo: {}},
    task,
    trajectory: strictTrajectory(),
    approvals: 1,
    confirmationRequests: 1,
    confirmationEvidence,
    approvalDecisions,
    sourceResponseConversationIds: ['different-session'],
    response: {conversationId: 'different-session', response: 'Created ID 90.'},
    permissionMode: 'ask',
    maxAgents: 1
  });
  assert.equal(result.success, false);
  assert.equal(result.conversationMatches, false);
});

test('requires saved confirmation evidence when ask mode approves a mutation', () => {
  const result = evaluateMultiAgentCase({
    testCase: {mode: 'parallel', strategy: 'verification', combo: {}},
    task,
    trajectory: strictTrajectory(),
    approvals: 1,
    confirmationRequests: 0,
    confirmationEvidence: [],
    approvalDecisions: [],
    sourceResponseConversationIds: ['strict-session'],
    response: {conversationId: 'strict-session', response: 'Created ID 90.'},
    permissionMode: 'ask',
    maxAgents: 1
  });
  assert.equal(result.success, false);
  assert.equal(result.tools.approvalValid, false);
});

test('requires complete execution namespaces, consistent metadata, and one fan-out final', () => {
  const stripped = strictTrajectory();
  for (const key of ['fanout_id', 'node_id', 'parent_node_id', 'agent_name',
    'agent_role', 'ordinal', 'depth']) delete stripped.steps[2].extra[key];
  assert.equal(evaluate(stripped).success, false);

  const inconsistent = strictTrajectory();
  inconsistent.steps[2].extra.agent_name = 'forged-agent';
  assert.equal(evaluate(inconsistent).success, false);

  const missingFinal = strictTrajectory();
  missingFinal.steps.splice(7, 1);
  assert.equal(evaluate(missingFinal).success, false);

  const duplicateFinal = strictTrajectory();
  duplicateFinal.steps.push({
    ...assistant(requestId, 'Duplicate.', {...leadNamespace(), status: 'completed'}),
    step_id: 12
  });
  assert.equal(evaluate(duplicateFinal).success, false);
});

test('requires overlapping fan-out admission, monotonic steps, and one continuation request', () => {
  const sequential = strictTrajectory();
  sequential.steps.splice(4, 0,
    lifecycle(`${fanoutId}-agent-02`, 1, 'started', {
      ordinal: 2, parent_node_id: leadNodeId
    }),
    lifecycle(`${fanoutId}-agent-02`, 1, 'completed', {
      ordinal: 2, parent_node_id: leadNodeId
    }));
  sequential.steps.forEach((step, index) => step.step_id = index + 1);
  const sequentialResult = evaluateMultiAgentCase({
    testCase: {mode: 'parallel', strategy: 'verification', combo: {}},
    task, trajectory: sequential, approvals: 1, confirmationRequests: 1,
    confirmationEvidence, approvalDecisions,
    sourceResponseConversationIds: ['strict-session', 'strict-session'],
    response: {conversationId: 'strict-session', response: 'Created ID 90.'},
    permissionMode: 'ask', maxAgents: 2
  });
  assert.equal(sequentialResult.success, false);

  const nonMonotonic = strictTrajectory();
  nonMonotonic.steps[3].step_id = nonMonotonic.steps[2].step_id;
  assert.equal(evaluate(nonMonotonic).success, false);

  const splitContinuation = strictTrajectory();
  splitContinuation.steps[9].extra.request_id = 'different-continuation';
  assert.equal(evaluate(splitContinuation).success, false);
});

test('binds confirmation tool, exact arguments, conversation, and server decision evidence', () => {
  for (const forged of [
    {...confirmationEvidence[0], toolName: 'delete_context_category'},
    {...confirmationEvidence[0], argumentsSummary: '{}'},
    {...confirmationEvidence[0], conversationId: 'other-conversation'}
  ]) {
    const result = evaluateMultiAgentCase({
      testCase: {mode: 'parallel', strategy: 'verification', combo: {}},
      task, trajectory: strictTrajectory(), approvals: 1, confirmationRequests: 1,
      confirmationEvidence: [forged], approvalDecisions,
      sourceResponseConversationIds: ['strict-session', 'strict-session'],
      response: {conversationId: 'strict-session', response: 'Created ID 90.'},
      permissionMode: 'ask', maxAgents: 1
    });
    assert.equal(result.success, false);
  }
});

test('rejects forged decision state, short grant evidence, and extra mutation arguments', () => {
  for (const decision of [
    {...approvalDecisions[0], confirmationGrantDigest: 'x'},
    {...approvalDecisions[0], status: 'DENIED', disposition: 'DENIED'},
    {...approvalDecisions[0], conversationId: 'other-conversation'}
  ]) {
    const result = evaluate(strictTrajectory(), 'Created ID 90.', {approvalDecisions: [decision]});
    assert.equal(result.success, false);
    assert.equal(result.tools.decisionValid, false);
  }
  const extra = {
    ...confirmationEvidence[0],
    argumentsSummary: JSON.stringify({
      name: 'MA Strict 01', description: 'strict evidence', unexpected: true
    })
  };
  const result = evaluate(strictTrajectory(), 'Created ID 90.', {confirmationEvidence: [extra]});
  assert.equal(result.success, false);
  assert.equal(result.tools.approvalValid, false);
});

test('binds approved arguments to the executed mutation and created-ID read request', () => {
  const forgedMutation = strictTrajectory();
  forgedMutation.steps[8].extra.arguments = {name: 'forged', description: 'forged'};
  const mutationResult = evaluate(forgedMutation);
  assert.equal(mutationResult.success, false);
  assert.equal(mutationResult.tools.mutationArgumentsValid, false);

  const forgedRead = strictTrajectory();
  forgedRead.steps[9].extra.arguments = {ctx_category_id: 999};
  const readResult = evaluate(forgedRead);
  assert.equal(readResult.success, false);
  assert.equal(readResult.tools.readBack, false);
});

test('requires exactly one pre-approval mutation rejection in ask mode', () => {
  const missingRejection = strictTrajectory();
  missingRejection.steps.splice(5, 1);
  missingRejection.steps.forEach((step, index) => step.step_id = index + 1);
  missingRejection.final_metrics.total_steps = missingRejection.steps.length;
  const result = evaluate(missingRejection);
  assert.equal(result.success, false);
  assert.equal(result.tools.approvalTraceValid, false);
  assert.equal(result.tools.rejectedMutationCalls, 0);
});

test('rejects namespace downgrade, forged agents, and all-child failure', () => {
  const downgraded = strictTrajectory();
  for (const key of ['fanout_id', 'node_id', 'parent_node_id', 'agent_name',
    'agent_role', 'ordinal', 'depth']) delete downgraded.steps[2].extra[key];
  downgraded.steps[2].extra.request_id = 'other-request';
  assert.equal(evaluate(downgraded).graph.valid, false);

  const wrongLead = strictTrajectory();
  wrongLead.steps[5].extra.agent_role = 'forged lead';
  assert.equal(evaluate(wrongLead).graph.valid, false);

  const wrongChild = strictTrajectory();
  wrongChild.steps[1].extra.agent_name = 'arbitrary-agent';
  wrongChild.steps[1].extra.agent_role = 'arbitrary-role';
  wrongChild.steps[1].extra.agent_id = 'unregistered-agent';
  wrongChild.steps[2].extra.agent_name = 'arbitrary-agent';
  wrongChild.steps[2].extra.agent_role = 'arbitrary-role';
  wrongChild.steps[2].extra.agent_id = 'unregistered-agent';
  wrongChild.steps[3].extra.agent_name = 'arbitrary-agent';
  wrongChild.steps[3].extra.agent_role = 'arbitrary-role';
  wrongChild.steps[3].extra.agent_id = 'unregistered-agent';
  assert.equal(evaluate(wrongChild).graph.valid, false);

  const failedChildren = strictTrajectory();
  failedChildren.steps[3].extra.status = 'failed';
  assert.equal(evaluate(failedChildren).graph.valid, false);
});

test('binds unique ATIF finals exactly to ordered REST responses', () => {
  const missingId = strictTrajectory();
  missingId.steps[10].message = 'Created the requested record.';
  const missingIdResult = evaluate(missingId, 'Created the requested record.');
  assert.equal(missingIdResult.success, false);
  assert.equal(missingIdResult.tools.continuationFinal, false);

  const divergent = strictTrajectory();
  const divergentResult = evaluate(divergent, 'REST says something else.');
  assert.equal(divergentResult.success, false);
  assert.equal(divergentResult.finals.valid, false);

  const duplicate = strictTrajectory();
  duplicate.steps.push({...duplicate.steps.at(-1), step_id: 12});
  duplicate.final_metrics.total_steps = duplicate.steps.length;
  const duplicateResult = evaluate(duplicate, 'Created ID 90.', {
    sourceResponses: [
      {conversationId: 'strict-session', response: 'Approval required.'},
      {conversationId: 'strict-session', response: 'Created ID 90.'},
      {conversationId: 'strict-session', response: 'Created ID 90.'}
    ]
  });
  assert.equal(duplicateResult.success, false);
  assert.equal(duplicateResult.finals.valid, false);
});

test('requires one grounded single-mode final and non-empty identifier evidence', () => {
  const readTask = {
    expectedMutation: null,
    expectedReads: ['get_libraries']
  };
  const single = result => ({
    session_id: 'single-session',
    steps: [
      {...tool('get_libraries', result, {request_id: requestId}), step_id: 1},
      {...assistant(requestId, 'Observed library ID 3.'), step_id: 2}
    ],
    final_metrics: {total_steps: 2}
  });
  const evaluateSingle = (trajectory, source = 'Observed library ID 3.') =>
    evaluateMultiAgentCase({
      testCase: {mode: 'single', strategy: 'balanced', combo: {}},
      task: readTask,
      trajectory,
      response: {conversationId: 'single-session', response: source},
      sourceResponses: [{conversationId: 'single-session', response: source}],
      evidenceSchemaVersion: 4
    });

  assert.equal(evaluateSingle(single([{library_id: 3}])).success, true);
  const empty = evaluateSingle(single([]));
  assert.equal(empty.success, false);
  assert.equal(empty.tools.grounded, false);

  const auditOnly = evaluateSingle(single([{created: {who: {user_id: 1}}}]));
  assert.equal(auditOnly.success, false);
  assert.equal(auditOnly.tools.grounded, false);

  const missingFinal = single([{library_id: 3}]);
  missingFinal.steps.pop();
  missingFinal.final_metrics.total_steps = 1;
  assert.equal(evaluateSingle(missingFinal).success, false);

  const duplicateFinal = single([{library_id: 3}]);
  duplicateFinal.steps.push({...duplicateFinal.steps[1], step_id: 3});
  duplicateFinal.final_metrics.total_steps = 3;
  assert.equal(evaluateSingle(duplicateFinal).success, false);
});

test('requires grounded coverage for every requested read domain', () => {
  const multiDomainTask = {
    expectedMutation: null,
    expectedReads: [
      'get_libraries', 'get_releases', 'get_data_types', 'get_core_components'
    ]
  };
  const trajectory = {
    session_id: 'coverage-session',
    steps: [
      {...tool('get_libraries', [{library_id: 3}], {request_id: requestId}), step_id: 1},
      {...assistant(requestId, 'Observed library_id 3.'), step_id: 2}
    ],
    final_metrics: {total_steps: 2}
  };
  const result = evaluateMultiAgentCase({
    testCase: {mode: 'single', strategy: 'balanced', combo: {}},
    task: multiDomainTask,
    trajectory,
    response: {conversationId: 'coverage-session', response: 'Observed library_id 3.'},
    sourceResponses: [{conversationId: 'coverage-session', response: 'Observed library_id 3.'}],
    evidenceSchemaVersion: 4
  });
  assert.equal(result.success, false);
  assert.deepEqual(result.tools.readGroups.map(group => group.grounded),
    [true, false, false, false]);

  const crossDomain = {
    session_id: 'coverage-session',
    steps: [
      {...tool('get_libraries', [{library_id: 3}], {request_id: requestId}), step_id: 1},
      {...tool('get_releases', [{library_id: 3}], {request_id: requestId}), step_id: 2},
      {...tool('get_data_types', [{dt_id: 7}], {request_id: requestId}), step_id: 3},
      {...tool('get_core_components', [{component_id: 9}], {request_id: requestId}), step_id: 4},
      {...assistant(requestId,
        'Observed library_id 3, dt_id 7, and component_id 9.'), step_id: 5}
    ],
    final_metrics: {total_steps: 5}
  };
  const crossDomainResult = evaluateMultiAgentCase({
    testCase: {mode: 'single', strategy: 'balanced', combo: {}},
    task: multiDomainTask,
    trajectory: crossDomain,
    response: {
      conversationId: 'coverage-session',
      response: 'Observed library_id 3, dt_id 7, and component_id 9.'
    },
    sourceResponses: [{
      conversationId: 'coverage-session',
      response: 'Observed library_id 3, dt_id 7, and component_id 9.'
    }],
    evidenceSchemaVersion: 4
  });
  assert.equal(crossDomainResult.success, false);
  assert.equal(crossDomainResult.tools.readGroups[1].grounded, false);

  const shallowText = 'library_id 3; release_id 74; dt_id 7; component_id 9.';
  const shallow = {
    session_id: 'coverage-session',
    steps: [
      {...tool('get_libraries', [{library_id: 3}], {request_id: requestId}), step_id: 1},
      {...tool('get_releases', [{release_id: 74}], {request_id: requestId}), step_id: 2},
      {...tool('get_data_types', [{dt_id: 7}], {request_id: requestId}), step_id: 3},
      {...tool('get_core_components', [{component_id: 9}], {request_id: requestId}), step_id: 4},
      {...assistant(requestId, shallowText), step_id: 5}
    ],
    final_metrics: {total_steps: 5}
  };
  const shallowResult = evaluateMultiAgentCase({
    testCase: {mode: 'single', strategy: 'balanced', combo: {}},
    task: {...multiDomainTask, label: 'creative read-only review'},
    trajectory: shallow,
    response: {conversationId: 'coverage-session', response: shallowText},
    sourceResponses: [{conversationId: 'coverage-session', response: shallowText}],
    evidenceSchemaVersion: 4
  });
  assert.equal(shallowResult.success, false);
  assert.equal(shallowResult.tools.responseStructure.valid, false);
});

test('accepts bold and indented numbering while ignoring nested sub-lists', () => {
  const creativeTask = {
    label: 'creative read-only review',
    expectedMutation: null,
    expectedReads: [
      'get_libraries', 'get_releases', 'get_data_types', 'get_core_components'
    ]
  };
  const creativeText = [
    '**1.** Pair library_id 3 stewardship with release readiness reviews.',
    '   1. Start with the working release owner sign-off.',
    'Counterargument: library ownership metadata may lag the actual state.',
    '**2.** Audit release_id 74 for datatype drift on every cycle.',
    'Counterargument: drift counts alone can overstate interoperability risk.',
    '  3. Cross-check dt_id 7 usage against component_id 9 contracts.',
    'Counterargument: a single component sample may not generalize.'
  ].join('\n');
  const trajectory = {
    session_id: 'creative-session',
    steps: [
      {...tool('get_libraries', [{library_id: 3}], {request_id: requestId}), step_id: 1},
      {...tool('get_releases', [{release_id: 74}], {request_id: requestId}), step_id: 2},
      {...tool('get_data_types', [{dt_id: 7}], {request_id: requestId}), step_id: 3},
      {...tool('get_core_components', [{component_id: 9}], {request_id: requestId}), step_id: 4},
      {...assistant(requestId, creativeText), step_id: 5}
    ],
    final_metrics: {total_steps: 5}
  };
  const result = evaluateMultiAgentCase({
    testCase: {mode: 'single', strategy: 'balanced', combo: {}},
    task: creativeTask,
    trajectory,
    response: {conversationId: 'creative-session', response: creativeText},
    sourceResponses: [{conversationId: 'creative-session', response: creativeText}],
    evidenceSchemaVersion: 4
  });
  assert.equal(result.tools.responseStructure.valid, true);
  assert.equal(result.tools.responseStructure.proposals, 3);
  assert.equal(result.success, true);
});

test('ignores the fan-out usage accounting step in graph and final checks', () => {
  const trajectory = strictTrajectory();
  trajectory.steps.splice(7, 0, {
    source: 'system',
    message: 'fan-out usage',
    extra: {
      message_kind: 'fanout_usage',
      visibility: 'debug',
      request_id: requestId,
      fanout_id: fanoutId,
      agents: [{
        node_id: `${fanoutId}-agent-01`,
        agent_name: verificationRoles[0][0],
        prompt_tokens: 100,
        completion_tokens: 50,
        model_calls: 2
      }]
    },
    metrics: {
      fanout_prompt_tokens: 100,
      fanout_completion_tokens: 50,
      context_input_tokens: 900,
      context_estimated: false
    }
  });
  trajectory.steps.forEach((step, index) => step.step_id = index + 1);
  trajectory.final_metrics.total_steps = trajectory.steps.length;
  const result = evaluate(trajectory);
  assert.equal(result.graph.valid, true);
  assert.equal(result.finals.valid, true);
  assert.equal(result.success, true);
});

// Request-scoped orchestration wire shapes recorded by the backend's
// required-tool recovery path and the internal workflow planner/evaluator.
function orchestrationModelCall(agent, nodeId, role, extra = {}) {
  return {
    message: `${agent} model call`,
    extra: {
      message_kind: 'model_call',
      request_id: requestId,
      node_id: nodeId,
      agent_name: agent,
      agent_role: role,
      depth: 0,
      ...extra
    }
  };
}

function plannerModelCall() {
  return orchestrationModelCall(
    'workflow-planner', `${requestId}:workflow-planner`, 'workflow selection');
}

function evaluatorModelCall() {
  return orchestrationModelCall('workflow-evaluator',
    `${requestId}:workflow-evaluator:1`, 'completion evaluation', {iteration: 1});
}

function evaluationFallback(extra = {}) {
  return {
    message: 'The workflow evaluator failed; accepting the current bounded result.',
    extra: {
      message_kind: 'agent_lifecycle',
      lifecycle_subtype: 'workflow_evaluation_fallback',
      node_id: `${requestId}:workflow-evaluator:1`,
      agent_name: 'workflow-evaluator',
      agent_role: 'completion evaluation',
      depth: 0,
      iteration: 1,
      status: 'fallback',
      reason: 'IllegalArgumentException',
      ...extra
    }
  };
}

function singleModeTrajectory(orchestrationSteps) {
  const steps = [
    plannerModelCall(),
    {...tool('get_libraries', [{library_id: 3}], {request_id: requestId})},
    ...orchestrationSteps,
    {...assistant(requestId, 'Observed library ID 3.')}
  ].map((step, index) => ({...step, step_id: index + 1}));
  return {
    session_id: 'single-session',
    final_metrics: {total_steps: steps.length},
    steps
  };
}

function evaluateSingleMode(trajectory) {
  return evaluateMultiAgentCase({
    testCase: {mode: 'single', strategy: 'balanced', combo: {}},
    task: {expectedMutation: null, expectedReads: ['get_libraries']},
    trajectory,
    response: {conversationId: 'single-session', response: 'Observed library ID 3.'},
    sourceResponses: [{conversationId: 'single-session', response: 'Observed library ID 3.'}],
    evidenceSchemaVersion: 4
  });
}

test('exempts request-scoped orchestration steps from single-mode graph checks', () => {
  const result = evaluateSingleMode(singleModeTrajectory([
    {
      message: 'The assistant answered without a connectCenter domain tool call.',
      extra: {
        message_kind: 'agent_lifecycle',
        lifecycle_subtype: 'required_tool_unfulfilled',
        status: 'degraded',
        recovery_attempts: 1
      }
    },
    evaluatorModelCall()
  ]));
  assert.deepEqual(result.graph.violations, []);
  assert.equal(result.graph.lifecycleSteps, 0);
  assert.equal(result.graph.orchestrationSteps, 1);
  assert.equal(result.success, true);
});

test('still rejects a non-exempt lifecycle step in single mode', () => {
  const result = evaluateSingleMode(singleModeTrajectory([
    lifecycle(leadNodeId, 0, 'started')
  ]));
  assert.equal(result.success, false);
  assert.ok(result.graph.violations.includes('single mode emitted agent lifecycle steps'));
});

test('exempts orchestration lifecycle and planner/evaluator execution from fan-out checks', () => {
  const trajectory = strictTrajectory();
  trajectory.steps.unshift(plannerModelCall());
  // The evaluator judges the workflow result after the lead terminates and
  // before the final answer is projected.
  trajectory.steps.splice(8, 0, evaluationFallback(), evaluatorModelCall());
  trajectory.steps.forEach((step, index) => step.step_id = index + 1);
  trajectory.final_metrics.total_steps = trajectory.steps.length;
  const result = evaluate(trajectory);
  assert.deepEqual(result.graph.violations, []);
  assert.equal(result.graph.orchestrationSteps, 1);
  assert.equal(result.success, true);
});

test('flags an exempt lifecycle step that leaks into a fan-out namespace', () => {
  const leaked = strictTrajectory();
  leaked.steps.splice(7, 0, evaluationFallback({fanout_id: fanoutId}));
  leaked.steps.forEach((step, index) => step.step_id = index + 1);
  leaked.final_metrics.total_steps = leaked.steps.length;
  const leakedResult = evaluate(leaked);
  assert.equal(leakedResult.success, false);
  assert.ok(leakedResult.graph.violations.includes(
    'orchestration lifecycle leaked into a fan-out namespace'));

  const missingStatus = strictTrajectory();
  const fallback = evaluationFallback();
  delete fallback.extra.status;
  missingStatus.steps.splice(7, 0, fallback);
  missingStatus.steps.forEach((step, index) => step.step_id = index + 1);
  missingStatus.final_metrics.total_steps = missingStatus.steps.length;
  const missingStatusResult = evaluate(missingStatus);
  assert.equal(missingStatusResult.success, false);
  assert.ok(missingStatusResult.graph.violations.includes(
    'every orchestration lifecycle step must carry status'));
});

test('does not count a trailing version number as a cited release ID', () => {
  const releaseTask = {expectedMutation: null, expectedReads: ['get_releases']};
  const run = text => evaluateMultiAgentCase({
    testCase: {mode: 'single', strategy: 'balanced', combo: {}},
    task: releaseTask,
    trajectory: {
      session_id: 'release-session',
      steps: [
        {...tool('get_releases', [{release_id: 10}], {request_id: requestId}), step_id: 1},
        {...assistant(requestId, text), step_id: 2}
      ],
      final_metrics: {total_steps: 2}
    },
    response: {conversationId: 'release-session', response: text},
    sourceResponses: [{conversationId: 'release-session', response: text}],
    evidenceSchemaVersion: 4
  });

  const versionOnly = run('The library tracks the OAGIS release 10.6 model.');
  assert.equal(versionOnly.success, false);
  assert.equal(versionOnly.tools.grounded, false);

  const grounded = run('The working release_id 10 anchors the library.');
  assert.equal(grounded.success, true);
  assert.equal(grounded.tools.grounded, true);
});
