/**
 * Verifies agent-event classification, timeline upserts, terminal guards, and display summaries.
 */

import {
  AiAgentActivity,
  agentActivityElapsedLabel,
  agentActivitySummary,
  agentActivityUpdate,
  agentToolEventContent,
  isExecutionActivityEvent,
  isSpecialistActivityEvent,
  isSpecialistToolEvent,
  specialistActivityAgentId,
  specialistToolAgentId,
  upsertAgentActivity,
  upsertAgentGuideEvent,
  upsertAgentProviderErrorEvent,
  upsertAgentToolEvent
} from './ai-agent-activity';
import {AiChatSocketEvent} from './ai-chat-panel.model';

describe('AI agent activity semantics', () => {
  const event = (subtype: string, metadata: Record<string, unknown> = {}): AiChatSocketEvent => ({
    requestId: 'request-1',
    type: 'system',
    subtype,
    content: 'Agent status changed.',
    metadata
  });

  it('maps stable child identity and lifecycle into one activity contract', () => {
    const started = agentActivityUpdate(event('subagent_started', {
      agentId: 'request-1:agent:1',
      agentName: 'Verifier',
      agentRole: 'Independent evidence check',
      taskLabel: 'Sync Purchase Order'
    }));
    const completed = agentActivityUpdate(event('subagent_completed', {
      agentId: 'request-1:agent:1',
      agentName: 'Verifier',
      agentRole: 'Independent evidence check'
    }));

    expect(started).toEqual(expect.objectContaining({
      agentId: 'request-1:agent:1', agentName: 'Verifier', agentRole: 'Independent evidence check',
      taskLabel: 'Sync Purchase Order',
      status: 'started', inProgress: true, isLead: false
    }));
    expect(completed).toEqual(expect.objectContaining({
      agentId: 'request-1:agent:1', status: 'completed', inProgress: false
    }));
  });

  it('recognizes only the bounded orchestration event family', () => {
    expect(isExecutionActivityEvent(event('subagent_preparing'))).toBe(true);
    expect(isExecutionActivityEvent(event('subagent_retry'))).toBe(true);
    expect(isExecutionActivityEvent(event('multi_agent_synthesizing'))).toBe(false);
    expect(isExecutionActivityEvent(event('parallel_workflow_synthesizing'))).toBe(false);
    expect(isExecutionActivityEvent(event('tool_started'))).toBe(false);
    expect(agentActivityUpdate({...event('subagent_started'), content: ''})).toBeUndefined();
  });

  it('accepts a contentless started event from its type and task metadata', () => {
    expect(agentActivityUpdate({
      ...event('subagent_started', {
        agentId: 'request-1:worker:1', agentName: 'Verifier', taskLabel: 'Verify release'
      }),
      content: ''
    })).toEqual(expect.objectContaining({
      agentId: 'request-1:worker:1', status: 'started', inProgress: true,
      content: '', taskLabel: 'Verify release'
    }));
  });

  it('maps current subagent lifecycle states without creating a synthetic lead', () => {
    expect(agentActivityUpdate(event('subagent_completed', {
      agentId: 'request-1:worker:1'
    }))).toEqual(expect.objectContaining({
      agentId: 'request-1:worker:1', isLead: false,
      status: 'completed', inProgress: false
    }));
    expect(agentActivityUpdate(event('subagent_refused', {
      agentId: 'request-1:worker:1'
    }))).toEqual(expect.objectContaining({
      status: 'failed', inProgress: false
    }));
    expect(agentActivityUpdate(event('subagent_cancelled', {
      agentId: 'request-1:worker:1'
    }))).toEqual(expect.objectContaining({
      status: 'cancelled', inProgress: false
    }));
    expect(agentActivityUpdate(event('subagent_output_retry_handoff', {
      agentId: 'request-1:worker:1'
    }))).toEqual(expect.objectContaining({
      status: 'cancelled', inProgress: false
    }));
    expect(agentActivityUpdate(event('subagent_planned', {
      agentId: 'request-1:worker:1'
    }))).toEqual(expect.objectContaining({
      status: 'planned', inProgress: false, isLead: false
    }));
    expect(agentActivityUpdate(event('subagent_cancelled', {
      agentId: 'request-1:worker:1'
    }))).toEqual(expect.objectContaining({
      status: 'cancelled', inProgress: false, isLead: false
    }));
  });

  it('uses workflow_type to present a subagent as a parallel item', () => {
    expect(agentActivityUpdate(event('subagent_completed', {
      agentId: 'parallel-1', workflow_type: 'parallel', conversation_kind: 'PARALLEL'
    }))).toEqual(expect.objectContaining({
      isLead: false, workflowType: 'parallel', status: 'completed'
    }));
    expect(agentActivityUpdate(event('subagent_completed', {
      agentId: 'agent-1', workflow_type: 'parallel', conversation_kind: 'SUBAGENT'
    }))).toEqual(expect.objectContaining({
      isLead: false, workflowType: 'parallel', status: 'completed'
    }));
  });

  it('upserts by agent identity, accumulates the event timeline, and stamps timestamps', () => {
    const activities: AiAgentActivity[] = [];
    const specialist = {agentId: 'request-1:agent:1', agentName: 'Verifier'};

    expect(upsertAgentActivity(
      activities, agentActivityUpdate(event('subagent_started', specialist))!, 1000
    )).toBe(true);
    expect(upsertAgentActivity(activities, agentActivityUpdate({
      ...event('subagent_completed', specialist), content: 'Verified the evidence.'
    })!, 4000)).toBe(true);

    expect(activities).toHaveLength(1);
    expect(activities[0]).toEqual(expect.objectContaining({
      status: 'completed', inProgress: false, firstSeenAt: 1000, lastUpdateAt: 4000
    }));
    expect(activities[0].events).toEqual([
      {status: 'started', content: 'Agent status changed.'},
      {status: 'completed', content: 'Verified the evidence.'}
    ]);
  });

  it('starts elapsed execution time when a queued worker actually starts', () => {
    const activities: AiAgentActivity[] = [];
    const specialist = {agentId: 'request-1:worker:1', agentName: 'Verifier'};
    upsertAgentActivity(
      activities, agentActivityUpdate(event('subagent_planned', specialist))!, 1000
    );
    upsertAgentActivity(
      activities, agentActivityUpdate(event('subagent_started', specialist))!, 5000
    );

    expect(activities[0]).toMatchObject({
      status: 'started', firstSeenAt: 5000, lastUpdateAt: 5000
    });
  });

  it('uses the root-chat message flow inside each Agent conversation', () => {
    const activities: AiAgentActivity[] = [];
    const specialist = {
      nodeId: 'request-1:worker:1', agentName: 'Verifier',
      executionScope: 'worker', conversationKind: 'SUBAGENT',
      assignment: 'Verify the current release record.'
    };
    upsertAgentActivity(activities, agentActivityUpdate({
      ...event('subagent_planned', specialist), content: 'Queued verification.'
    })!);
    expect(activities[0].messages).toEqual([
      {role: 'user', content: 'Verify the current release record.'}
    ]);

    upsertAgentActivity(activities, agentActivityUpdate({
      ...event('subagent_started', specialist),
      content: 'I’ll verify the current release data.'
    })!);
    expect(activities[0].messages).toEqual([
      {role: 'user', content: 'Verify the current release record.'},
      {role: 'guide', content: 'I’ll verify the current release data.'},
      expect.objectContaining({
        role: 'progress', content: 'Working', inProgress: true,
        eventType: 'agent_status', statusStartedAt: expect.any(Number)
      })
    ]);

    const tool = (subtype: string): AiChatSocketEvent => ({
      requestId: 'request-1', type: 'tool_call', subtype,
      groupId: 'request-1', toolCallId: 'call-1',
      metadata: {...specialist, toolName: 'get_release'}
    });
    upsertAgentToolEvent(activities, tool('started'));
    expect(activities[0].messages.at(-1)).toMatchObject({
      role: 'progress', content: 'Calling get_release.'
    });
    upsertAgentToolEvent(activities, tool('completed'));
    expect(activities[0].messages.slice(-2)).toEqual([
      expect.objectContaining({
        role: 'tool_call', content: 'get_release completed.', toolStatus: 'completed'
      }),
      expect.objectContaining({
        role: 'progress', content: 'Working', inProgress: true,
        eventType: 'agent_status', statusStartedAt: expect.any(Number)
      })
    ]);

    upsertAgentActivity(activities, agentActivityUpdate({
      ...event('subagent_completed', {...specialist, result: 'The release record is verified.'}),
      content: 'Verified.'
    })!);
    expect(activities[0].messages.map(message => message.role))
      .toEqual(['user', 'guide', 'tool_call', 'assistant']);
    expect(activities[0].messages.at(-1)?.content)
      .toBe('The release record is verified.');
  });

  it('never regresses a terminal status to a stale live update', () => {
    const activities: AiAgentActivity[] = [];
    const specialist = {agentId: 'request-1:agent:1', agentName: 'Verifier'};
    upsertAgentActivity(activities, agentActivityUpdate(event('subagent_completed', specialist))!);

    expect(upsertAgentActivity(
      activities, agentActivityUpdate(event('subagent_started', specialist))!
    )).toBe(false);

    expect(activities[0]).toEqual(expect.objectContaining({status: 'completed', inProgress: false}));
    expect(activities[0].events).toHaveLength(1);
  });

  it('ignores late guide, provider, and tool frames after a terminal result', () => {
    const activities: AiAgentActivity[] = [];
    const specialist = {
      agentId: 'request-1:agent:1', agentName: 'Verifier',
      executionScope: 'worker', conversationKind: 'SUBAGENT'
    };
    upsertAgentActivity(activities, agentActivityUpdate(
      event('subagent_completed', {...specialist, result: 'Done.'})
    )!);
    const messages = activities[0].messages?.map(message => ({...message}));
    const late = {
      requestId: 'request-1', type: 'system', content: 'Late update.',
      metadata: specialist
    } as AiChatSocketEvent;

    expect(upsertAgentGuideEvent(activities, {...late, subtype: 'guide'})).toBe(true);
    expect(upsertAgentProviderErrorEvent(
      activities, {...late, subtype: 'provider_error'}
    )).toBe(true);
    expect(upsertAgentToolEvent(activities, {
      ...late, type: 'tool_call', subtype: 'started',
      groupId: 'late', toolCallId: 'call-1'
    })).toBe(true);

    expect(activities[0].messages).toEqual(messages);
    expect(activities[0]).toEqual(expect.objectContaining({
      status: 'completed', inProgress: false
    }));
  });

  it('keeps equal provider tool ids separate across tool groups', () => {
    const activities: AiAgentActivity[] = [];
    const specialist = {
      agentId: 'request-1:agent:1', agentName: 'Verifier',
      executionScope: 'worker', conversationKind: 'SUBAGENT'
    };
    upsertAgentActivity(activities, agentActivityUpdate(
      event('subagent_started', specialist)
    )!);
    for (const groupId of ['first-group', 'second-group']) {
      upsertAgentToolEvent(activities, {
        requestId: 'request-1', type: 'tool_call', subtype: 'completed',
        groupId, toolCallId: 'call-1',
        metadata: {...specialist, toolName: groupId}
      });
    }

    expect(activities[0].messages?.filter(message => message.role === 'tool_call'))
      .toEqual([
        expect.objectContaining({groupId: 'first-group', toolCallId: 'call-1'}),
        expect.objectContaining({groupId: 'second-group', toolCallId: 'call-1'})
      ]);
  });

  it('deduplicates a replayed identical terminal event', () => {
    const activities: AiAgentActivity[] = [];
    const specialist = {agentId: 'request-1:agent:1', agentName: 'Verifier'};
    const completed = agentActivityUpdate(event('subagent_completed', specialist))!;

    upsertAgentActivity(activities, completed);
    upsertAgentActivity(activities, {...completed});

    expect(activities).toHaveLength(1);
    expect(activities[0].events).toHaveLength(1);
  });

  it('summarizes the fan-out for the consolidated group header', () => {
    const activities: AiAgentActivity[] = [];
    upsertAgentActivity(activities, agentActivityUpdate(
      event('subagent_completed', {agentId: 'a1', agentName: 'One'})
    )!);
    upsertAgentActivity(activities, agentActivityUpdate(
      event('subagent_started', {agentId: 'a2', agentName: 'Two'})
    )!);
    expect(agentActivitySummary(activities)).toBe('1 done · 1 running');

    upsertAgentActivity(activities, agentActivityUpdate(
      event('subagent_failed', {agentId: 'a2', agentName: 'Two'})
    )!);
    expect(agentActivitySummary(activities)).toBe('1 done · 1 failed');
  });

  it('identifies specialist tool events and formats compact timeline content', () => {
    const toolEvent: AiChatSocketEvent = {
      requestId: 'request-1', type: 'tool_call', subtype: 'completed',
      groupId: 'fanout-1', toolCallId: 'call-7',
      metadata: {
        toolName: 'get_libraries',
        agentId: 'fanout-1-agent-01', parentNodeId: 'fanout-1-lead'
      }
    };

    expect(isSpecialistToolEvent(toolEvent)).toBe(true);
    expect(specialistToolAgentId(toolEvent)).toBe('fanout-1-agent-01');
    expect(agentToolEventContent(toolEvent)).toBe('get_libraries completed.');

    expect(agentToolEventContent({...toolEvent, subtype: 'started'}))
      .toBe('Calling get_libraries.');
    expect(agentToolEventContent({...toolEvent, subtype: 'denied'}))
      .toBe('get_libraries was denied before execution.');
    expect(agentToolEventContent({
      ...toolEvent, metadata: {...toolEvent.metadata, toolName: 'toolSearchTool'}
    })).toBe('tool_search_tool completed.');

    expect(isSpecialistToolEvent(
      {...toolEvent, metadata: {nodeId: 'fanout-1-agent-02'}}
    )).toBe(true);
    expect(isSpecialistToolEvent(
      {...toolEvent, metadata: {toolName: 'get_libraries'}}
    )).toBe(false);
    expect(isSpecialistToolEvent({...toolEvent, type: 'system'})).toBe(false);
  });

  it('routes composed worker events by explicit execution scope instead of id shape', () => {
    const activities: AiAgentActivity[] = [];
    const workerMetadata = {
      agentId: 'request-1:find-extenders', agentName: 'Evidence researcher',
      executionScope: 'worker', conversationKind: 'SUBAGENT'
    };
    upsertAgentActivity(activities, agentActivityUpdate(
      event('subagent_started', workerMetadata)
    )!);
    const guide: AiChatSocketEvent = {
      requestId: 'request-1', type: 'system', subtype: 'guide',
      content: 'Gathering the relevant associations.', metadata: workerMetadata
    };
    const tool: AiChatSocketEvent = {
      requestId: 'request-1', type: 'tool_call', subtype: 'completed',
      groupId: 'request-1', toolCallId: 'call-1',
      metadata: {...workerMetadata, toolName: 'get_acc'}
    };

    expect(isSpecialistActivityEvent(guide)).toBe(true);
    expect(specialistActivityAgentId(guide)).toBe('request-1:find-extenders');
    expect(upsertAgentGuideEvent(activities, guide)).toBe(true);
    expect(isSpecialistToolEvent(tool)).toBe(true);
    expect(specialistToolAgentId(tool)).toBe('request-1:find-extenders');
    expect(activities[0].events).toContainEqual(expect.objectContaining({
      content: 'Gathering the relevant associations.'
    }));

    expect(isSpecialistActivityEvent({
      ...guide,
      metadata: {node_id: 'request-1:legacy-worker', conversation_kind: 'SUBAGENT'}
    })).toBe(true);
    expect(isSpecialistActivityEvent({
      ...guide,
      metadata: {nodeId: 'request-1:composed:lead', executionScope: 'lead'}
    })).toBe(false);
    expect(specialistActivityAgentId({
      ...guide,
      metadata: {
        nodeId: 'request-1:composed:worker:research', agentId: 'evidence-researcher',
        executionScope: 'worker'
      }
    })).toBe('request-1:composed:worker:research');
  });

  it('formats client-side elapsed time from the recorded timestamps', () => {
    const activities: AiAgentActivity[] = [];
    upsertAgentActivity(activities, agentActivityUpdate(
      event('subagent_started', {agentId: 'a1', agentName: 'One'})
    )!, 10_000);

    expect(agentActivityElapsedLabel(activities[0], 22_000)).toBe('12s');

    upsertAgentActivity(activities, agentActivityUpdate(
      event('subagent_completed', {agentId: 'a1', agentName: 'One'})
    )!, 75_000);
    expect(agentActivityElapsedLabel(activities[0], 999_000)).toBe('1m 05s');
  });
});
