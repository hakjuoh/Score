import {
  AiAgentActivity,
  agentActivityElapsedLabel,
  agentActivitySummary,
  agentActivityUpdate,
  agentToolEventContent,
  isExecutionActivityEvent,
  isSpecialistToolEvent,
  specialistToolAgentId,
  upsertAgentActivity
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
    expect(isExecutionActivityEvent(event('multi_agent_synthesizing'))).toBe(true);
    expect(isExecutionActivityEvent(event('parallel_workflow_synthesizing'))).toBe(true);
    expect(isExecutionActivityEvent(event('tool_started'))).toBe(false);
    expect(agentActivityUpdate({...event('subagent_started'), content: ''})).toBeUndefined();
  });

  it('marks the lead lifecycle and terminal synthesis states', () => {
    expect(agentActivityUpdate(event('multi_agent_completed'))).toEqual(expect.objectContaining({
      agentId: 'request-1:lead', agentName: 'Lead agent', isLead: true,
      status: 'completed', inProgress: false
    }));
    expect(agentActivityUpdate(event('multi_agent_failed'))).toEqual(expect.objectContaining({
      status: 'failed', inProgress: false
    }));
  });

  it('keeps parallel workflow tasks distinct from multi-agent lifecycle', () => {
    expect(agentActivityUpdate(event('parallel_workflow_started', {
      workflow: 'parallel', execution_kind: 'parallel'
    }))).toEqual(expect.objectContaining({
      isLead: true, executionKind: 'parallel', status: 'started'
    }));
    expect(agentActivityUpdate(event('parallel_task_completed', {
      agentId: 'parallel-1', workflow: 'parallel', conversation_kind: 'PARALLEL'
    }))).toEqual(expect.objectContaining({
      isLead: false, executionKind: 'parallel', status: 'completed'
    }));
    expect(agentActivityUpdate(event('subagent_completed', {
      agentId: 'agent-1', workflow: 'parallel', conversation_kind: 'SUBAGENT'
    }))).toEqual(expect.objectContaining({
      isLead: false, executionKind: 'multi_agent', status: 'completed'
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
      event('multi_agent_synthesizing', {agentName: 'Lead agent'})
    )!);
    expect(agentActivitySummary(activities)).toBe('1 done · synthesizing');

    upsertAgentActivity(activities, agentActivityUpdate(
      event('subagent_failed', {agentId: 'a2', agentName: 'Two'})
    )!);
    upsertAgentActivity(activities, agentActivityUpdate(
      event('multi_agent_completed', {agentName: 'Lead agent'})
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

    expect(isSpecialistToolEvent(
      {...toolEvent, metadata: {nodeId: 'fanout-1-agent-02'}}
    )).toBe(true);
    expect(isSpecialistToolEvent(
      {...toolEvent, metadata: {toolName: 'get_libraries'}}
    )).toBe(false);
    expect(isSpecialistToolEvent({...toolEvent, type: 'system'})).toBe(false);
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
