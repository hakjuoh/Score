import {
  AiChatRestResponse,
  Subject,
  api,
  component,
  setupAiChatPanelSpec,
  teardownAiChatPanelSpec,
  transport
} from './ai-chat-panel.component.spec-support';

describe('AiChatPanelComponent multi-agent lifecycle', () => {
  beforeEach(setupAiChatPanelSpec);
  afterEach(teardownAiChatPanelSpec);

  const activity = (subtype: string, agentId: string, agentName: string) => ({
    requestId: 'request-1',
    conversationId: 'conversation-1',
    type: 'system',
    subtype,
    content: `${agentName} ${subtype}.`,
    metadata: {agentId, agentName, agentRole: 'Independent review'}
  });

  function startPublishedRequest(): void {
    component.state.prompt = 'Verify this request independently';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
  }

  it('renders admission as Working and then opens a guided workflow', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'accepted', content: '',
      metadata: {generation: 7, deadline: '2099-07-14T13:05:00Z'}
    });

    expect(component.state.messages.at(-1)).toMatchObject({
      role: 'progress', content: 'Working...', inProgress: true
    });
    expect(component.state.messages.some(message => message.content === 'Request received.'))
      .toBe(false);

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'workflow_started',
      content: 'I’ll check the three component counts independently.',
      metadata: {
        nodeId: 'main:1:counts', parentNodeId: 'main', depth: 1,
        member_count: 3
      }
    });

    expect(component.state.messages.slice(-3).map(message =>
      [message.role, message.content])).toEqual([
      ['guide', 'I’ll check the three component counts independently.'],
      ['workflow_group', 'Workflow'],
      ['progress', 'Working...']
    ]);
  });

  it('nests a delegated workflow inside its owning Agent conversation', () => {
    startPublishedRequest();
    const root = 'main:1:counts';
    const parentAgent = `${root}:agent:count-accs`;
    const nested = `${parentAgent}:count-accs-delegated`;
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'workflow_started', content: 'I’ll verify the counts.',
      metadata: {nodeId: root, parentNodeId: 'main', depth: 1, member_count: 1}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'subagent_started',
      content: 'I’ll count the ACCs in this release.',
      metadata: {
        nodeId: parentAgent, parentNodeId: root, depth: 2,
        agentName: 'Evidence researcher', executionKind: 'parallel',
        assignment: 'Spawn exactly 2 sub-agents to cross-check the ACC count.'
      }
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'workflow_started',
      content: 'I’ll cross-check the result with two independent readers.',
      metadata: {nodeId: nested, parentNodeId: parentAgent, depth: 3, member_count: 2}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'subagent_planned', content: 'Queued first check.',
      metadata: {
        nodeId: `${nested}:agent:first`, parentNodeId: nested, depth: 4,
        agentName: 'First reader', executionKind: 'parallel'
      }
    });

    const parent = component.state.agentActivities[0];
    expect(parent.messages.map(message => message.role))
      .toEqual(['user', 'guide', 'guide', 'workflow_group', 'progress']);
    expect(parent.messages[0].content)
      .toBe('Spawn exactly 2 sub-agents to cross-check the ACC count.');
    const nestedWorkflow = parent.messages.at(-2);
    expect(nestedWorkflow?.workflowParentNodeId).toBe(parentAgent);
    expect(nestedWorkflow?.activities?.map(activity => activity.agentName))
      .toEqual(['First reader']);
    component.focusAgentActivity(parentAgent);
    component.focusAgentActivity(`${nested}:agent:first`);
    expect(component.focusedAgentActivity?.agentName).toBe('First reader');
    expect(component.agentFocusBackLabel).toBe('Back to Evidence researcher agent activity');
    component.closeAgentFocus();
    expect(component.focusedAgentActivity?.agentId).toBe(parentAgent);
    expect(component.agentFocusBackLabel).toBe('Back to conversation');
    component.closeAgentFocus();
    expect(component.focusedAgentActivity).toBeUndefined();
  });

  it.each([
    ['workflow_completed', 'completed'],
    ['workflow_cancelled', 'cancelled'],
    ['workflow_output_retry_handoff', 'cancelled'],
    ['workflow_failed', 'failed'],
    ['workflow_stalled', 'failed'],
    ['workflow_refused', 'failed']
  ] as const)('settles nested Working state for %s', (subtype, expectedStatus) => {
    startPublishedRequest();
    const root = 'main:1:root';
    const parentAgent = `${root}:agent:parent`;
    const nested = `${parentAgent}:delegated`;
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'workflow_started', content: 'I’m checking the request.',
      metadata: {nodeId: root, parentNodeId: 'main', depth: 1}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'subagent_started', content: 'I’m checking one part.',
      metadata: {nodeId: parentAgent, parentNodeId: root, depth: 2, agentName: 'Parent'}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'workflow_started', content: 'I’m cross-checking it.',
      metadata: {nodeId: nested, parentNodeId: parentAgent, depth: 3}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype, content: 'Nested workflow stopped.',
      metadata: {nodeId: nested, parentNodeId: parentAgent, depth: 3}
    });

    const parent = component.state.agentActivities[0];
    const nestedWorkflow = parent.messages?.find(message => message.workflowNodeId === nested);
    expect(nestedWorkflow?.workflowStatus).toBe(expectedStatus);
    expect(parent.messages?.some(message => message.eventType === 'composite_status')).toBe(false);
    expect(parent.inProgress).toBe(false);
    if (expectedStatus === 'completed') {
      expect(parent.messages?.some(message => message.role === 'error')).toBe(false);
    } else {
      expect(parent.messages?.at(-1)).toMatchObject({
        role: 'error', content: 'Nested workflow stopped.', eventType: subtype
      });
    }
  });

  it('recursively settles every descendant when the request terminates', () => {
    startPublishedRequest();
    const root = 'main:1:root';
    const parentAgent = `${root}:agent:parent`;
    const nested = `${parentAgent}:delegated`;
    const childAgent = `${nested}:agent:child`;
    for (const event of [
      {subtype: 'workflow_started', content: 'I’m checking the request.',
        metadata: {nodeId: root, parentNodeId: 'main', depth: 1}},
      {subtype: 'subagent_started', content: 'I’m checking one part.',
        metadata: {nodeId: parentAgent, parentNodeId: root, depth: 2, agentName: 'Parent'}},
      {subtype: 'workflow_started', content: 'I’m cross-checking it.',
        metadata: {nodeId: nested, parentNodeId: parentAgent, depth: 3}},
      {subtype: 'subagent_started', content: 'I’m reading the evidence.',
        metadata: {nodeId: childAgent, parentNodeId: nested, depth: 4, agentName: 'Child'}}
    ]) {
      (component as any).handleSocketEvent({
        requestId: 'request-1', conversationId: 'conversation-1', type: 'system', ...event
      });
    }

    (component as any).settleAgentActivity('cancelled');

    const parent = component.state.agentActivities[0];
    const nestedWorkflow = parent.messages?.find(message => message.workflowNodeId === nested);
    const child = nestedWorkflow?.activities?.[0];
    expect(parent).toMatchObject({status: 'cancelled', inProgress: false});
    expect(child).toMatchObject({status: 'cancelled', inProgress: false});
    expect(nestedWorkflow?.workflowStatus).toBe('cancelled');
    expect(parent.messages?.some(message => message.role === 'progress')).toBe(false);
    expect(child?.messages?.some(message => message.role === 'progress')).toBe(false);
  });

  it('consolidates lifecycle events into activities behind one group anchor', () => {
    startPublishedRequest();

    (component as any).handleSocketEvent(activity(
      'multi_agent_started', 'fanout-1-lead', 'Lead agent'
    ));
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-01', 'Evidence checker'
    ));
    (component as any).handleSocketEvent(activity(
      'multi_agent_synthesizing', 'fanout-1-lead', 'Lead agent'
    ));
    // A streamed answer lands between lifecycle events. The consolidated block
    // must keep tracking agents by stable identity, not by array position.
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_update', content: 'Drafting the final answer.'
    });
    (component as any).handleSocketEvent(activity(
      'multi_agent_completed', 'fanout-1-lead', 'Lead agent'
    ));

    expect(component.state.messages.filter(message => message.role === 'agent_group'))
      .toHaveLength(1);
    expect(component.state.agentActivities).toHaveLength(2);
    expect(component.state.agentActivities.find(agent => agent.agentId === 'fanout-1-lead'))
      .toMatchObject({
        agentName: 'Lead agent', isLead: true, status: 'completed', inProgress: false
      });
    expect(component.state.agentActivities.find(agent => agent.agentId === 'fanout-1-agent-01'))
      .toMatchObject({
        agentName: 'Evidence checker', agentRole: 'Independent review',
        status: 'started', inProgress: true
      });

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'Verified final answer.'
    });
    expect(component.state.agentActivities
      .every(agent => agent.inProgress === false)).toBe(true);
    expect(component.state.messages.filter(message => message.role === 'agent_group'))
      .toHaveLength(1);
  });

  it('renders parallel workflow lifecycle in a distinct workflow group', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent({
      ...activity('parallel_workflow_started', 'fanout-1-lead', 'Workflow lead'),
      metadata: {
        agentId: 'fanout-1-lead', agentName: 'Workflow lead', workflow: 'parallel',
        executionKind: 'parallel', agent_count: 2
      }
    });
    (component as any).handleSocketEvent({
      ...activity('parallel_task_started', 'fanout-1-agent-01', 'Record reader'),
      metadata: {
        agentId: 'fanout-1-agent-01', agentName: 'Record reader', workflow: 'parallel',
        executionKind: 'parallel', conversationKind: 'PARALLEL'
      }
    });

    expect(component.state.messages.filter(message => message.role === 'workflow_group'))
      .toHaveLength(1);
    expect(component.state.messages.filter(message => message.role === 'agent_group'))
      .toHaveLength(0);
    expect(component.agentStripLabel).toBe('Tasks 0/2 · working');
    expect(component.state.currentStatus).toBe('Parallel tasks working');
  });

  it('keeps the aggregate status working while other specialists still run', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-01', 'Evidence checker'
    ));
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-02', 'Edge case hunter'
    ));

    (component as any).handleSocketEvent(activity(
      'subagent_completed', 'fanout-1-agent-01', 'Evidence checker'
    ));
    expect(component.state.currentStatus).toBe('Agents working');

    (component as any).handleSocketEvent(activity(
      'subagent_completed', 'fanout-1-agent-02', 'Edge case hunter'
    ));
    expect(component.state.currentStatus).toBe('Agents finished');
  });

  it('settles agent activity in place without recreating the transcript array', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-01', 'Evidence checker'
    ));
    const messagesReference = component.state.messages;
    const activitiesReference = component.state.agentActivities;

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'Verified final answer.'
    });

    expect(component.state.messages).toBe(messagesReference);
    expect(component.state.agentActivities).toBe(activitiesReference);
    expect(component.state.agentActivities[0]).toMatchObject({
      status: 'completed', inProgress: false, content: 'Evidence checker finished.'
    });
  });

  it('settles active agent activity when a request is cancelled', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-01', 'Evidence checker'
    ));

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'cancelled', content: 'Request cancelled.'
    });

    expect(component.state.agentActivities.find(agent => agent.agentId === 'fanout-1-agent-01'))
      .toMatchObject({
        status: 'cancelled', inProgress: false,
        content: 'Evidence checker stopped when the request was cancelled.'
      });
  });

  it('merges live and REST attachment lifecycle without duplicate entries', () => {
    const live = new Subject<{body: string}>();
    const response = new Subject<AiChatRestResponse>();
    transport.watch.mockReturnValueOnce(live);
    api.sendChat.mockReturnValueOnce(response);
    component.state.prompt = 'Inspect this attachment creatively';
    component.state.attachments = [{
      name: 'sample.txt', mediaType: 'text/plain', size: 4, data: 'test'
    }];
    component.send();

    const started = activity('subagent_started', 'fanout-1-agent-01', 'Domain explorer');
    const toolCall = {
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'completed',
      groupId: 'fanout-1', toolCallId: 'call-7', content: 'get_libraries completed',
      metadata: {
        toolName: 'get_libraries',
        agentId: 'fanout-1-agent-01', parentNodeId: 'fanout-1-lead'
      }
    };
    const completed = activity('subagent_completed', 'fanout-1-agent-01', 'Domain explorer');
    live.next({body: JSON.stringify(started)});
    live.next({body: JSON.stringify(toolCall)});
    live.next({body: JSON.stringify(completed)});
    response.next({
      response: 'Attachment analysis complete.',
      conversationId: 'conversation-1',
      events: [started, toolCall, completed]
    });

    expect(component.state.agentActivities).toHaveLength(1);
    expect(component.state.agentActivities[0]).toMatchObject({
      agentId: 'fanout-1-agent-01', status: 'completed', inProgress: false
    });
    expect(component.state.agentActivities[0].events
      .filter(entry => entry.status === 'tool')).toEqual([
      expect.objectContaining({status: 'tool', content: 'get_libraries completed.'})
    ]);
    expect(component.state.messages.some(message =>
      message.role === 'tool_call' || message.role === 'tool_group')).toBe(false);
    expect(component.state.messages.filter(message => message.role === 'agent_group'))
      .toHaveLength(1);
    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'assistant', content: 'Attachment analysis complete.'
    }));
  });

  it('keeps a settled fan-out block intact when the next plain request starts', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-01', 'Evidence checker'
    ));
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'Verified final answer.'
    });
    const anchor = component.state.messages.find(message => message.role === 'agent_group');
    const snapshot = anchor?.activities;
    expect(snapshot?.[0]).toMatchObject({agentId: 'fanout-1-agent-01', status: 'completed'});

    component.state.prompt = 'Plain follow-up question';
    component.send();

    expect(component.state.agentActivities).toEqual([]);
    expect(anchor?.activities).toBe(snapshot);
    expect(snapshot).toHaveLength(1);
    expect(snapshot?.[0]).toMatchObject({
      agentId: 'fanout-1-agent-01', status: 'completed',
      content: 'Evidence checker finished.'
    });
    // Historical agents stay reachable for the focused view.
    component.focusAgentActivity('fanout-1-agent-01');
    expect(component.focusedAgentActivity?.agentId).toBe('fanout-1-agent-01');
  });

  it('renders sequential fan-outs from their own activity snapshots', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-01', 'Evidence checker'
    ));
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'First answer.'
    });

    component.state.prompt = 'Verify this again independently';
    component.send();
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-2-agent-01', 'Second checker'
    ));

    const anchors = component.state.messages.filter(message => message.role === 'agent_group');
    expect(anchors).toHaveLength(2);
    expect(anchors[0].activities).not.toBe(anchors[1].activities);
    expect(anchors[0].activities?.map(agent => agent.agentId)).toEqual(['fanout-1-agent-01']);
    expect(anchors[0].activities?.[0]).toMatchObject({status: 'completed', inProgress: false});
    expect(anchors[1].activities?.map(agent => agent.agentId)).toEqual(['fanout-2-agent-01']);
    expect(anchors[1].activities?.[0]).toMatchObject({status: 'started', inProgress: true});
    expect(anchors[1].activities).toBe(component.state.agentActivities);
  });

  it('diverts specialist tool events into the agent timeline instead of the chat', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-01', 'Evidence checker'
    ));

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'completed',
      groupId: 'fanout-1', toolCallId: 'call-7', content: 'get_libraries completed',
      metadata: {
        toolName: 'get_libraries',
        agentId: 'fanout-1-agent-01', parentNodeId: 'fanout-1-lead'
      }
    });
    // Specialist detection also works from the child agent id pattern alone.
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'started',
      groupId: 'fanout-1', toolCallId: 'call-8', content: 'get_contexts',
      metadata: {toolName: 'get_contexts', nodeId: 'fanout-1-agent-01'}
    });

    expect(component.state.messages.some(message =>
      message.role === 'tool_call' || message.role === 'tool_group')).toBe(false);
    const agent = component.state.agentActivities
      .find(candidate => candidate.agentId === 'fanout-1-agent-01');
    expect(agent?.events.filter(entry => entry.status === 'tool')).toEqual([
      expect.objectContaining({status: 'tool', content: 'get_libraries completed.'}),
      expect.objectContaining({status: 'tool', content: 'Calling get_contexts.'})
    ]);
  });

  it('keeps a composed worker chain planned and isolated in one specialist timeline', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'multi_agent_started',
      content: 'Checking every extender and reviewing the conflicts.',
      metadata: {
        agentId: 'request-1:composed:lead', agentName: 'Lead agent',
        activeVerb: 'Checking', completedVerb: 'Checked', agent_count: 2,
        executionScope: 'lead', workflow: 'chain', executionKind: 'multi_agent'
      }
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'subagent_planned', content: 'Find extenders is queued.',
      metadata: {
        agentId: 'request-1:composed:worker:find-extenders',
        agentName: 'Evidence researcher', taskLabel: 'Find extenders',
        activeVerb: 'Searching', completedVerb: 'Searched',
        executionScope: 'worker', conversationKind: 'SUBAGENT'
      }
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'subagent_planned', content: 'Review conflicts is queued.',
      metadata: {
        agentId: 'request-1:composed:worker:conflict-review',
        agentName: 'Critical reviewer', taskLabel: 'Review conflicts',
        activeVerb: 'Reviewing', completedVerb: 'Reviewed',
        executionScope: 'worker', conversationKind: 'SUBAGENT'
      }
    });

    const group = component.state.messages.find(message => message.role === 'agent_group');
    expect(group?.activities?.find(candidate => candidate.isLead))
      .toMatchObject({plannedAgentCount: 2, activeVerb: 'Checking'});
    expect(group?.activities?.filter(candidate => !candidate.isLead))
      .toEqual(expect.arrayContaining([
        expect.objectContaining({taskLabel: 'Find extenders', status: 'planned'}),
        expect.objectContaining({taskLabel: 'Review conflicts', status: 'planned'})
      ]));
    expect(group?.activities).toHaveLength(3);

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'subagent_started',
      content: 'Listing every extending ACC.',
      metadata: {
        agentId: 'request-1:composed:worker:find-extenders', agentName: 'Evidence researcher',
        activeVerb: 'Searching', completedVerb: 'Searched',
        executionScope: 'worker', conversationKind: 'SUBAGENT'
      }
    });

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'guide', content: 'Inspecting the ACC associations.',
      metadata: {
        agentId: 'request-1:composed:worker:find-extenders', executionScope: 'worker',
        conversationKind: 'SUBAGENT'
      }
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'completed',
      groupId: 'request-1', toolCallId: 'call-1', content: 'get_acc completed.',
      metadata: {
        agentId: 'request-1:composed:worker:find-extenders', executionScope: 'worker',
        conversationKind: 'SUBAGENT', toolName: 'get_acc'
      }
    });

    expect(component.state.messages.some(message =>
      message.content === 'Inspecting the ACC associations.')).toBe(false);
    expect(component.state.messages.some(message => message.role === 'tool_call')).toBe(false);
    expect(component.state.agentActivities
      .find(candidate => candidate.agentId === 'request-1:composed:worker:find-extenders')?.events)
      .toEqual(expect.arrayContaining([
        expect.objectContaining({content: 'Inspecting the ACC associations.'}),
        expect.objectContaining({status: 'tool', content: 'get_acc completed.'})
      ]));

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'subagent_completed', content: 'Searched.',
      metadata: {
        agentId: 'request-1:composed:worker:find-extenders', agentName: 'Evidence researcher',
        activeVerb: 'Searching', completedVerb: 'Searched',
        executionScope: 'worker', conversationKind: 'SUBAGENT'
      }
    });

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'subagent_started',
      content: 'Reviewing the conflict findings.',
      metadata: {
        agentId: 'request-1:composed:worker:conflict-review', agentName: 'Critical reviewer',
        activeVerb: 'Reviewing', completedVerb: 'Reviewed',
        executionScope: 'worker', conversationKind: 'SUBAGENT'
      }
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'subagent_completed', content: 'Reviewed.',
      metadata: {
        agentId: 'request-1:composed:worker:conflict-review', agentName: 'Critical reviewer',
        activeVerb: 'Reviewing', completedVerb: 'Reviewed',
        executionScope: 'worker', conversationKind: 'SUBAGENT'
      }
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'multi_agent_completed', content: 'Checked.',
      metadata: {
        agentId: 'request-1:composed:lead', agentName: 'Lead agent',
        activeVerb: 'Checking', completedVerb: 'Checked', agent_count: 2,
        executionScope: 'lead', workflow: 'chain', executionKind: 'multi_agent'
      }
    });

    expect(component.state.messages.filter(message => message.role === 'agent_group'))
      .toHaveLength(1);
    expect(component.state.agentActivities.filter(candidate => !candidate.isLead))
      .toHaveLength(2);
    expect(component.state.agentActivities.find(candidate => candidate.isLead))
      .toMatchObject({status: 'completed', completedVerb: 'Checked'});
  });

  it('starts a new group for each evaluator workflow iteration', () => {
    startPublishedRequest();
    const lifecycle = (subtype: string, agentId: string, content: string) => ({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype, content,
      metadata: {
        agentId,
        agentName: subtype.startsWith('multi_agent') ? 'Lead agent' : 'Evidence researcher',
        executionScope: subtype.startsWith('multi_agent') ? 'lead' : 'worker',
        conversationKind: subtype.startsWith('multi_agent') ? 'ROOT' : 'SUBAGENT',
        activeVerb: 'Checking', completedVerb: 'Checked', agent_count: 1,
        workflow: 'chain', executionKind: 'multi_agent'
      }
    });

    const leadOne = 'request-1:composed:iteration-1:lead';
    const workerOne = 'request-1:composed:iteration-1:worker:research';
    (component as any).handleSocketEvent(lifecycle('multi_agent_started', leadOne, 'First plan.'));
    (component as any).handleSocketEvent(lifecycle('subagent_planned', workerOne, 'Queued.'));
    (component as any).handleSocketEvent(lifecycle('subagent_started', workerOne, 'Checking.'));
    (component as any).handleSocketEvent(lifecycle('subagent_completed', workerOne, 'Checked.'));
    (component as any).handleSocketEvent(lifecycle('multi_agent_completed', leadOne, 'Checked.'));

    const firstGroup = component.state.messages.find(message => message.role === 'agent_group');
    const firstActivities = firstGroup?.activities;
    const leadTwo = 'request-1:composed:iteration-2:lead';
    (component as any).handleSocketEvent(lifecycle('multi_agent_started', leadTwo, 'Revised plan.'));

    const groups = component.state.messages.filter(message => message.role === 'agent_group');
    expect(groups).toHaveLength(2);
    expect(groups[0].activities).toBe(firstActivities);
    expect(groups[0].activities?.every(candidate => !candidate.inProgress)).toBe(true);
    expect(groups[1].activities).toBe(component.state.agentActivities);
    expect(component.state.agentActivities).toEqual([
      expect.objectContaining({agentId: leadTwo, status: 'started', inProgress: true})
    ]);
  });

  it('separates worker-only Planner iterations and keeps only the latest synthesis', () => {
    startPublishedRequest();
    const worker = (iteration: number, index: number, subtype: string) => ({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype,
      content: subtype === 'subagent_completed' ? `Count ${index} completed.` : `Counting ${index}.`,
      metadata: {
        nodeId: `main:${iteration}:release-count:worker-${index}`,
        parentNodeId: `main:${iteration}:release-count`,
        agentId: `main:${iteration}:release-count:worker-${index}`,
        agentName: `Counter ${index}`,
        executionScope: 'worker', conversationKind: 'PARALLEL',
        executionKind: 'parallel'
      }
    });
    const completeIteration = (iteration: number, result: string) => {
      for (let index = 1; index <= 3; index++) {
        (component as any).handleSocketEvent(worker(iteration, index, 'subagent_started'));
        (component as any).handleSocketEvent(worker(iteration, index, 'subagent_completed'));
      }
      (component as any).handleSocketEvent({
        requestId: 'request-1', conversationId: 'conversation-1',
        type: 'system', subtype: 'workflow_result', content: result,
        metadata: {nodeId: `main:${iteration}:release-count`, depth: 1}
      });
    };

    completeIteration(1, 'First synthesis.');
    completeIteration(2, 'Revised synthesis.');

    const groups = component.state.messages.filter(message => message.role === 'workflow_group');
    expect(groups).toHaveLength(2);
    expect(groups.map(group => group.activities?.filter(activity => !activity.isLead).length))
      .toEqual([3, 3]);
    expect(component.state.agentActivities).toBe(groups[1].activities);
    expect(component.state.messages.filter(message => message.eventType === 'workflow_result'))
      .toEqual([
        expect.objectContaining({role: 'assistant', content: 'Revised synthesis.'})
      ]);

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_update', content: 'Canonical '
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_update', content: 'answer.'
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'Canonical answer.'
    });
    expect(component.state.messages.filter(message =>
      message.role === 'assistant')).toEqual([
      expect.objectContaining({content: 'Canonical answer.'})
    ]);
    expect(component.state.messages.some(message =>
      message.eventType === 'workflow_result')).toBe(false);
  });

  it('coalesces a REST workflow result preview with the canonical response', () => {
    const response = new Subject<AiChatRestResponse>();
    transport.watch.mockReturnValueOnce(new Subject<{body: string}>());
    api.sendChat.mockReturnValueOnce(response);
    component.state.prompt = 'Synthesize with an attachment';
    component.state.attachments = [{
      name: 'sample.txt', mediaType: 'text/plain', size: 4, data: 'test'
    }];
    component.send();

    response.next({
      response: 'Canonical synthesis.', conversationId: 'conversation-1',
      events: [{
        requestId: 'request-1', conversationId: 'conversation-1',
        type: 'system', subtype: 'workflow_result', content: 'Canonical synthesis.',
        metadata: {depth: 1}
      }]
    });

    expect(component.state.messages.filter(message =>
      message.role === 'assistant' && message.content === 'Canonical synthesis.')).toHaveLength(1);
  });

  it('reattaches live updates to a restored workflow group', () => {
    startPublishedRequest();
    const nodeId = 'main:1:release-count:worker-1';
    const activities = [{
      agentId: nodeId, agentName: 'Counter 1', status: 'started',
      content: 'Counting.', inProgress: true, isLead: false,
      firstSeenAt: 1, lastUpdateAt: 1, events: [{status: 'started', content: 'Counting.'}]
    }] as any;
    component.state.messages.push({
      role: 'workflow_group', content: 'Parallel workflow',
      groupId: 'request-1\u0000main:1:release-count', activities
    });

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'subagent_completed', content: 'Counted.',
      metadata: {
        nodeId, parentNodeId: 'main:1:release-count',
        agentId: nodeId, agentName: 'Counter 1',
        executionScope: 'worker', conversationKind: 'PARALLEL', executionKind: 'parallel'
      }
    });

    expect(component.state.messages.filter(message => message.role === 'workflow_group'))
      .toHaveLength(1);
    expect(activities[0]).toMatchObject({status: 'completed', content: 'Counted.', inProgress: false});
  });

  it('shows a specialist guard-blocked tool as awaiting approval in the agent timeline', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-01', 'Evidence checker'
    ));

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'blocked',
      groupId: 'fanout-1', toolCallId: 'call-10',
      content: 'create_business_context is awaiting approval.',
      metadata: {
        toolName: 'create_business_context',
        agentId: 'fanout-1-agent-01', parentNodeId: 'fanout-1-lead'
      }
    });

    expect(component.state.messages.some(message =>
      message.role === 'tool_call' || message.role === 'tool_group')).toBe(false);
    const agent = component.state.agentActivities
      .find(candidate => candidate.agentId === 'fanout-1-agent-01');
    expect(agent?.events.filter(entry => entry.status === 'tool')).toEqual([
      expect.objectContaining({
        content: 'create_business_context is awaiting approval.',
        toolStatus: 'blocked'
      })
    ]);
  });

  it('settles a specialist denied retry as a non-executed terminal tool event', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-01', 'Evidence checker'
    ));

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'denied',
      groupId: 'fanout-1', toolCallId: 'call-11',
      metadata: {
        toolName: 'delete_business_context',
        agentId: 'fanout-1-agent-01', parentNodeId: 'fanout-1-lead'
      }
    });

    const agent = component.state.agentActivities
      .find(candidate => candidate.agentId === 'fanout-1-agent-01');
    expect(agent?.events.filter(entry => entry.status === 'tool')).toEqual([
      expect.objectContaining({
        content: 'delete_business_context was denied before execution.',
        toolStatus: 'denied'
      })
    ]);
  });

  it('keeps lead tool events in the main chat flow', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent(activity(
      'multi_agent_started', 'fanout-1-lead', 'Lead agent'
    ));

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'completed',
      groupId: 'mcp', toolCallId: 'call-1', content: 'GitHub search',
      metadata: {toolName: 'github_search', statusMessage: 'Searched GitHub.'}
    });

    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'tool_call', toolName: 'github_search', toolStatus: 'completed'
    }));
    expect(component.state.agentActivities
      .find(candidate => candidate.agentId === 'fanout-1-lead')?.events
      .some(entry => entry.status === 'tool')).toBe(false);
  });

  it('splits lead narration at tool boundaries and renders the answer last', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_update', content: 'Checking the key records first.'
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'completed',
      groupId: 'mcp', toolCallId: 'call-1', content: 'get_core_components completed.',
      metadata: {toolName: 'get_core_components'}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_update', content: 'Verified the comparison.'
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'Verified the comparison. Details follow.'
    });

    const messages = component.state.messages;
    const narrationIndex = messages.findIndex(message =>
      message.role === 'progress' && message.content === 'Checking the key records first.');
    const toolIndex = messages.findIndex(message => message.role === 'tool_call');
    const finalIndex = messages.findIndex(message => message.role === 'assistant');
    expect(narrationIndex).toBeGreaterThanOrEqual(0);
    expect(messages[narrationIndex].inProgress).toBe(false);
    expect(toolIndex).toBeGreaterThan(narrationIndex);
    expect(finalIndex).toBeGreaterThan(toolIndex);
    expect(finalIndex).toBe(messages.length - 1);
    expect(messages[finalIndex].content).toBe('Verified the comparison. Details follow.');
  });

  it('moves the final answer below tool rows recorded after the streamed bubble', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_update', content: 'Comparing both specialists directly.'
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'completed',
      groupId: 'mcp', toolCallId: 'call-2', content: 'get_core_components completed.',
      metadata: {toolName: 'get_core_components'}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'Comparing both specialists directly.'
    });

    const messages = component.state.messages;
    const finalIndex = messages.findIndex(message => message.role === 'assistant');
    expect(finalIndex).toBe(messages.length - 1);
    expect(messages.findIndex(message => message.role === 'tool_call'))
      .toBeLessThan(finalIndex);
    // The streamed bubble was consumed by the final: no duplicate narration row.
    expect(messages.filter(message =>
      message.content === 'Comparing both specialists directly.')).toHaveLength(1);
  });

  it('keeps one copy of the answer when a late tool row is spliced in before the bubble', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_update', content: 'Checking the key records first.'
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'completed',
      groupId: 'mcp', toolCallId: 'call-b', content: 'get_releases completed.',
      metadata: {toolName: 'get_releases', toolCallSeq: 2}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_update', content: 'Verified the comparison.'
    });
    // A reordered terminal with a lower sequence is spliced in BEFORE the
    // later tool row, shifting the streamed bubble's position.
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'completed',
      groupId: 'mcp', toolCallId: 'call-a', content: 'get_libraries completed.',
      metadata: {toolName: 'get_libraries', toolCallSeq: 1}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'Verified the comparison.'
    });

    const messages = component.state.messages;
    const finalIndex = messages.findIndex(message => message.role === 'assistant');
    expect(finalIndex).toBe(messages.length - 1);
    expect(messages.filter(message => message.content === 'Verified the comparison.'))
      .toHaveLength(1);
    expect(messages.filter(message => message.role === 'tool_call')
      .map(message => message.toolName)).toEqual(['get_libraries', 'get_releases']);
  });

  it('replaces a specialist tool started row with its completed disclosure detail', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-01', 'Evidence checker'
    ));
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'started',
      groupId: 'fanout-1', toolCallId: 'call-9', content: 'get_libraries',
      metadata: {
        toolName: 'get_libraries',
        agentId: 'fanout-1-agent-01', parentNodeId: 'fanout-1-lead'
      }
    });
    const agent = component.state.agentActivities
      .find(candidate => candidate.agentId === 'fanout-1-agent-01');
    expect(agent?.events.filter(entry => entry.status === 'tool')).toEqual([
      expect.objectContaining({content: 'Calling get_libraries.', toolStatus: 'started'})
    ]);

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'completed',
      groupId: 'fanout-1', toolCallId: 'call-9', content: 'get_libraries completed',
      metadata: {
        toolName: 'get_libraries',
        agentId: 'fanout-1-agent-01', parentNodeId: 'fanout-1-lead',
        toolDetail: 'get_libraries\nArguments: {}\nResult: {"total_items":3}'
      }
    });
    const toolEvents = agent?.events.filter(entry => entry.status === 'tool');
    expect(toolEvents).toHaveLength(1);
    expect(toolEvents?.[0]).toMatchObject({
      content: 'get_libraries completed.',
      toolStatus: 'completed',
      toolKey: 'fanout-1:call-9',
      detail: 'get_libraries\nArguments: {}\nResult: {"total_items":3}'
    });
  });

  it('labels a cancelled fan-out as stopped on the strip', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-01', 'Evidence checker'
    ));

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'cancelled', content: 'Request cancelled.'
    });

    expect(component.agentStripLabel).toBe('Agents 1/1 · stopped');
  });

  it('shows the strip only while agents run and hides it once the fan-out settles', () => {
    expect(component.agentStripVisible).toBe(false);

    startPublishedRequest();
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-01', 'Evidence checker'
    ));
    expect(component.agentStripVisible).toBe(true);

    (component as any).handleSocketEvent(activity(
      'subagent_completed', 'fanout-1-agent-01', 'Evidence checker'
    ));
    expect(component.agentStripVisible).toBe(false);
    // The settled run stays inspectable through the inline agent group block.
    expect(component.state.messages.filter(message => message.role === 'agent_group'))
      .toHaveLength(1);
  });

  it('toggles the agent roster from the strip while a fan-out is live', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent(activity(
      'subagent_completed', 'fanout-1-agent-01', 'Evidence checker'
    ));
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-02', 'Edge case hunter'
    ));

    expect(component.agentStripLabel).toBe('Agents 1/2 · working');

    component.onAgentStripClick();
    expect(component.state.agentListOpen).toBe(true);

    component.onAgentStripClick();
    expect(component.state.agentListOpen).toBe(false);
  });

  it('uses the server-planned specialist count for prompt-triggered fan-out status', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent({
      ...activity('multi_agent_started', 'fanout-1-lead', 'Lead agent'),
      metadata: {
        agentId: 'fanout-1-lead', agentName: 'Lead agent',
        agentRole: 'manager and final synthesizer', agent_count: 2
      }
    });
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-01', 'Sync reader'
    ));
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-02', 'Get reader'
    ));

    expect(component.agentStripLabel).toBe('Agents 0/2 · working');
  });

  it('focuses an agent from the roster and returns to the conversation', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-01', 'Evidence checker'
    ));
    component.state.agentListOpen = true;

    component.focusAgentActivity('fanout-1-agent-01');

    expect(component.state.agentFocusId).toBe('fanout-1-agent-01');
    expect(component.state.agentListOpen).toBe(false);
    expect(component.focusedAgentActivity?.agentName).toBe('Evidence checker');

    component.closeAgentFocus();
    expect(component.state.agentFocusId).toBeUndefined();
    expect(component.focusedAgentActivity).toBeUndefined();
  });

  it('clears agent focus and activities when a new chat or request starts', () => {
    startPublishedRequest();
    (component as any).handleSocketEvent(activity(
      'subagent_started', 'fanout-1-agent-01', 'Evidence checker'
    ));
    component.focusAgentActivity('fanout-1-agent-01');
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'Verified final answer.'
    });

    component.state.prompt = 'Follow up question';
    component.send();

    expect(component.state.agentActivities).toEqual([]);
    expect(component.state.agentFocusId).toBeUndefined();
    expect(component.state.agentListOpen).toBe(false);

    component.startNewChat();
    expect(component.state.agentActivities).toEqual([]);
    expect(component.state.agentFocusId).toBeUndefined();
  });

  it('exposes the active conversation ATIF trajectory in a new tab', () => {
    expect(component.trajectoryUrl).toBeUndefined();
    component.state.conversationId = 'conversation/with spaces';

    expect(component.trajectoryUrl).toBe(
      '/api/ai/chat/conversations/conversation%2Fwith%20spaces/trajectory'
    );
  });
});
