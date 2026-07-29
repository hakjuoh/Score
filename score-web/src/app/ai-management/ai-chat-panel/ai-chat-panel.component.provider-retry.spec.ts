import {
  AiCancellationResponse,
  AiChatRestResponse,
  REQUEST_STATUS_WATCHDOG_MS,
  Subject,
  api,
  cancellationResponse,
  component,
  of,
  publicStatus,
  setupAiChatPanelSpec,
  teardownAiChatPanelSpec,
  transport
} from './ai-chat-panel.component.spec-support';

const RATE_LIMIT_REASON = 'This request would exceed your rate limit tier of '
  + '50,000,000 input tokens per minute (org: example, model: claude-fable-5). '
  + 'Reduce the prompt length or the maximum tokens requested, or try again later.';

function providerRetryEvent(metadata: Record<string, unknown> = {}): any {
  const retryMetadata = {
    attempt: 7,
    max_attempts: 10,
    delay_millis: 15000,
    reason: RATE_LIMIT_REASON,
    failure_class: 'RateLimitException',
    status_code: 429,
    ...metadata
  };
  return {
    requestId: 'request-1',
    conversationId: 'conversation-1',
    type: 'system',
    subtype: 'provider_retry',
    visibility: 'debug',
    sequence: 5,
    content: `The model provider request failed; retrying (attempt ${retryMetadata.attempt} of ${retryMetadata.max_attempts}).`,
    metadata: retryMetadata
  };
}

function providerErrorEvent(metadata: Record<string, unknown> = {}): any {
  const retry = providerRetryEvent(metadata);
  return {
    ...retry,
    subtype: 'provider_error',
    visibility: 'visible',
    sequence: retry.sequence - 1,
    content: RATE_LIMIT_REASON
  };
}

function alertStatusRow() {
  return component.state.messages.find(message =>
    message.role === 'progress' && !!message.alertSuffix);
}

function trackedStatusRow() {
  const rows = component.state.messages.filter(message => message.role === 'progress');
  return rows[rows.length - 1];
}

describe('AiChatPanelComponent provider retry countdown', () => {
  beforeEach(setupAiChatPanelSpec);
  afterEach(teardownAiChatPanelSpec);

  it('renders the provider error before a separate retry countdown', () => {
    vi.useFakeTimers();
    component.state.prompt = 'Ride out a rate limit';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();

    (component as any).handleSocketEvent(providerErrorEvent());
    (component as any).handleSocketEvent(providerRetryEvent());

    expect(alertStatusRow()).toMatchObject({
      role: 'progress',
      inProgress: true,
      content: 'The model provider request failed; retrying (attempt 7 of 10).',
      alertSuffix: ' · Retrying in 15s'
    });
    const providerErrorIndex = component.state.messages.findIndex(message =>
      message.role === 'error' && message.content === RATE_LIMIT_REASON);
    const retryIndex = component.state.messages.indexOf(alertStatusRow()!);
    expect(providerErrorIndex).toBeGreaterThanOrEqual(0);
    expect(retryIndex).toBeGreaterThan(providerErrorIndex);
    expect(component.state.currentStatus).toBe('Retrying');
    expect(component.state.pending).toBe(true);

    vi.advanceTimersByTime(1000);
    expect(alertStatusRow()?.alertSuffix).toBe(' · Retrying in 14s');
    vi.advanceTimersByTime(2000);
    expect(alertStatusRow()?.alertSuffix).toBe(' · Retrying in 12s');
    expect(component.state.messages.filter(message =>
      message.role === 'progress' && !!message.alertSuffix)).toHaveLength(1);
  });

  it('shows Reconnecting when the countdown reaches zero', () => {
    vi.useFakeTimers();
    component.state.prompt = 'Wait out a short retry';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();

    (component as any).handleSocketEvent(providerRetryEvent({delay_millis: 2000}));

    expect(alertStatusRow()?.alertSuffix).toBe(' · Retrying in 2s');
    vi.advanceTimersByTime(2000);
    expect(alertStatusRow()?.alertSuffix).toBe(' · Reconnecting…');
    expect((component as any).providerRetryInterval).toBeUndefined();

    vi.advanceTimersByTime(5000);
    expect(alertStatusRow()?.alertSuffix).toBe(' · Reconnecting…');
    expect(component.state.messages.filter(message =>
      message.role === 'progress' && !!message.alertSuffix)).toHaveLength(1);
  });

  it('replaces the line when a later retry attempt arrives', () => {
    vi.useFakeTimers();
    component.state.prompt = 'Keep retrying';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent(providerRetryEvent());
    vi.advanceTimersByTime(3000);

    (component as any).handleSocketEvent(providerRetryEvent({
      attempt: 8, delay_millis: 5000, status_code: undefined
    }));

    expect(alertStatusRow()).toMatchObject({
      content: 'The model provider request failed; retrying (attempt 8 of 10).',
      alertSuffix: ' · Retrying in 5s'
    });
    vi.advanceTimersByTime(1000);
    expect(alertStatusRow()?.alertSuffix).toBe(' · Retrying in 4s');
    expect(component.state.messages.filter(message =>
      message.role === 'progress' && !!message.alertSuffix)).toHaveLength(1);
  });

  it('clears the retry alert and countdown when the assistant answers', () => {
    vi.useFakeTimers();
    component.state.prompt = 'Recover after a retry';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent(providerRetryEvent());
    vi.advanceTimersByTime(2000);

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'Recovered after the retry.'
    });

    expect(alertStatusRow()).toBeUndefined();
    expect((component as any).providerRetryInterval).toBeUndefined();
    expect(component.state.pending).toBe(false);
    expect(component.state.currentStatus).toBe('Ready');
    vi.advanceTimersByTime(20000);
    expect(alertStatusRow()).toBeUndefined();
    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'assistant', content: 'Recovered after the retry.'
    }));
  });

  it('clears a recovered Planner retry when the workflow starts', () => {
    vi.useFakeTimers();
    component.state.prompt = 'Plan a parallel workflow';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent(providerErrorEvent());
    (component as any).handleSocketEvent(providerRetryEvent());

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'parallel_workflow_started',
      content: 'Three specialists started.',
      metadata: {
        fanoutId: 'fanout-1', nodeId: 'fanout-1-lead',
        agentId: 'fanout-1-lead', agentName: 'Lead agent',
        agentCount: 3, executionKind: 'parallel', status: 'started'
      }
    });

    expect(alertStatusRow()).toBeUndefined();
    expect((component as any).providerRetryInterval).toBeUndefined();
    expect(component.state.currentStatus).toBe('Parallel tasks working');
  });

  it('renders the provider message in the red error row after the final failure', () => {
    vi.useFakeTimers();
    const providerMessage = `${RATE_LIMIT_REASON} (failed after 10 attempts)`;
    component.state.prompt = 'Exhaust every retry';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent(providerRetryEvent({attempt: 10}));

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'request_error', sequence: 9,
      content: providerMessage,
      metadata: {
        terminal: true, recoverable: false, retryable: false,
        status: 'FAILED', generation: 7
      }
    });

    expect(component.state.messages).toContainEqual({
      role: 'error', content: providerMessage
    });
    expect(component.state.pending).toBe(false);
    expect(component.state.currentStatus).toBe('Error');
    expect((component as any).providerRetryInterval).toBeUndefined();
    vi.advanceTimersByTime(20000);
    expect(alertStatusRow()).toBeUndefined();
  });

  it('restarts the streamed answer segment after a provider retry', () => {
    vi.useFakeTimers();
    component.state.prompt = 'Stream through a retry';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();

    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'assistant_update', content: 'Hello'
    });
    (component as any).handleSocketEvent(providerRetryEvent());
    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'assistant_update', content: 'Hello again'
    });

    const streamed = component.state.messages.filter(message =>
      message.role === 'progress' && message.eventType === 'assistant_update');
    expect(streamed).toHaveLength(1);
    expect(streamed[0].content).toBe('Hello again');
    expect(component.state.messages.some(message =>
      message.content.includes('HelloHello'))).toBe(false);
    expect(component.state.messages.filter(message =>
      message.content === 'Hello')).toHaveLength(0);
    expect(alertStatusRow()).toBeUndefined();
    expect((component as any).providerRetryInterval).toBeUndefined();
  });

  it('diverts a specialist worker retry to the agent timeline', () => {
    vi.useFakeTimers();
    component.state.prompt = 'Fan out specialists';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_update', content: 'Coordinating the specialists.'
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'multi_agent_started',
      content: 'Lead agent started.',
      metadata: {agentId: 'fanout-1-lead', agentName: 'Lead agent'}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'subagent_started',
      content: 'Evidence checker started.',
      metadata: {agentId: 'fanout-1-agent-01', agentName: 'Evidence checker'}
    });
    expect(component.state.currentStatus).toBe('Agents working');

    const workerMetadata = {
      fanoutId: 'fanout-1',
      nodeId: 'fanout-1-agent-01',
      parentNodeId: 'fanout-1-lead',
      agentId: 'fanout-1-agent-01'
    };
    (component as any).handleSocketEvent(providerErrorEvent(workerMetadata));
    (component as any).handleSocketEvent(providerRetryEvent(workerMetadata));

    expect(alertStatusRow()).toBeUndefined();
    expect((component as any).providerRetryInterval).toBeUndefined();
    expect(component.state.currentStatus).toBe('Agents working');
    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'progress', eventType: 'assistant_update',
      content: 'Coordinating the specialists.'
    }));
    const worker = component.state.agentActivities
      .find(activity => activity.agentId === 'fanout-1-agent-01');
    expect(worker?.events.slice(-2)).toEqual([
      expect.objectContaining({status: 'provider_error', content: RATE_LIMIT_REASON}),
      expect.objectContaining({
        status: 'provider_retry',
        content: 'The model provider request failed; retrying (attempt 7 of 10).'
      })
    ]);
    vi.advanceTimersByTime(3000);
    expect(alertStatusRow()).toBeUndefined();

    (component as any).handleSocketEvent(providerRetryEvent());
    expect(alertStatusRow()).toMatchObject({
      alertSuffix: ' · Retrying in 15s'
    });
  });

  it('removes streamed narration replayed by a retry even when tool rows followed it', () => {
    vi.useFakeTimers();
    component.state.prompt = 'Search with a retry mid-call';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'assistant_update', content: 'Let me search.'
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'tool_call', subtype: 'started',
      groupId: 'mcp', toolCallId: 'call-1', content: 'Component search',
      metadata: {toolName: 'search_components'}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'tool_call', subtype: 'completed',
      groupId: 'mcp', toolCallId: 'call-1', content: 'search_components completed.',
      metadata: {toolName: 'search_components'}
    });

    (component as any).handleSocketEvent(providerRetryEvent());
    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'assistant_update', content: 'Searching again.'
    });

    const streamed = component.state.messages.filter(message =>
      message.role === 'progress' && message.eventType === 'assistant_update');
    expect(streamed).toHaveLength(1);
    expect(streamed[0].content).toBe('Searching again.');
    expect(component.state.messages.filter(message =>
      message.content === 'Let me search.')).toHaveLength(0);
    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'tool_call', toolCallId: 'call-1', toolStatus: 'completed'
    }));
  });

  it('ignores provider retries while a cancellation is in progress', () => {
    vi.useFakeTimers();
    const cancellation = new Subject<AiCancellationResponse>();
    api.cancelRequest.mockReturnValueOnce(cancellation);
    component.state.prompt = 'Stop during a retry wait';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent(providerRetryEvent());
    expect((component as any).providerRetryInterval).toBeDefined();

    component.cancelActiveRequest();

    expect((component as any).providerRetryInterval).toBeUndefined();
    expect(trackedStatusRow()?.content).toBe('Cancelling request.');

    (component as any).handleSocketEvent(providerRetryEvent({attempt: 8}));
    expect(alertStatusRow()).toBeUndefined();
    expect(trackedStatusRow()?.content).toBe('Cancelling request.');

    vi.advanceTimersByTime(1500);
    expect(trackedStatusRow()?.content).toBe('Cancelling request.');

    cancellation.next(cancellationResponse());
    expect(trackedStatusRow()?.content).toBe('Cancellation acknowledged.');
    vi.advanceTimersByTime(2000);
    expect(trackedStatusRow()?.content).toBe('Cancellation acknowledged.');
    expect(alertStatusRow()).toBeUndefined();
  });

  it('clears the countdown when watchdog recovery applies a terminal conversation', () => {
    vi.useFakeTimers();
    component.state.prompt = 'Recover a terminal request';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'accepted', content: 'Request received.',
      metadata: {generation: 7, deadline: '2026-07-14T18:00:00Z'}
    });
    (component as any).handleSocketEvent(providerRetryEvent({delay_millis: 60000}));
    expect((component as any).providerRetryInterval).toBeDefined();
    api.getRequestStatus.mockReturnValueOnce(of(publicStatus('FAILED')));
    api.getConversation.mockReturnValueOnce(of({
      conversationId: 'conversation-1',
      title: 'Recovered conversation',
      messages: [
        {index: 0, role: 'user', content: 'Original prompt'},
        {index: 1, role: 'error', content: 'The request failed upstream.'}
      ]
    }));

    vi.advanceTimersByTime(REQUEST_STATUS_WATCHDOG_MS);

    expect((component as any).providerRetryInterval).toBeUndefined();
    expect(component.state.pending).toBe(false);
    expect(component.state.currentStatus).toBe('FAILED');
    expect(component.state.messages).toContainEqual({role: 'user', content: 'Original prompt'});
    expect(component.state.messages).toContainEqual({role: 'error', content: 'The request failed upstream.'});
    expect(alertStatusRow()).toBeUndefined();
    const snapshot = JSON.parse(JSON.stringify(component.state.messages));
    vi.advanceTimersByTime(10000);
    expect(component.state.messages).toEqual(snapshot);
  });

  it('streams REST provider error before retry and ignores their replay duplicates', () => {
    vi.useFakeTimers();
    const live = new Subject<{body: string}>();
    const response = new Subject<AiChatRestResponse>();
    transport.watch.mockReturnValueOnce(live);
    api.sendChat.mockReturnValueOnce(response);
    component.state.prompt = 'Retry with an attachment';
    component.state.attachments = [{
      name: 'sample.txt', mediaType: 'text/plain', size: 4, data: 'test'
    }];
    component.send();

    live.next({body: JSON.stringify(providerErrorEvent())});
    live.next({body: JSON.stringify(providerRetryEvent())});
    expect(alertStatusRow()).toMatchObject({
      alertSuffix: ' · Retrying in 15s'
    });
    const liveErrorIndex = component.state.messages.findIndex(message =>
      message.role === 'error' && message.content === RATE_LIMIT_REASON);
    const liveRetryIndex = component.state.messages.indexOf(alertStatusRow()!);
    expect(liveErrorIndex).toBeGreaterThanOrEqual(0);
    expect(liveRetryIndex).toBeGreaterThan(liveErrorIndex);
    vi.advanceTimersByTime(1000);
    expect(alertStatusRow()?.alertSuffix).toBe(' · Retrying in 14s');

    response.next({
      response: 'Recovered answer.',
      conversationId: 'conversation-1',
      events: [providerErrorEvent(), providerRetryEvent()]
    });

    expect(component.state.pending).toBe(false);
    expect(alertStatusRow()).toBeUndefined();
    expect((component as any).providerRetryInterval).toBeUndefined();
    vi.advanceTimersByTime(20000);
    expect(alertStatusRow()).toBeUndefined();
    expect(component.state.messages).toContainEqual({
      role: 'assistant', content: 'Recovered answer.'
    });
    expect(component.state.messages.filter(message =>
      message.role === 'error' && message.content === RATE_LIMIT_REASON)).toHaveLength(1);
  });

  it('replays missed REST provider error and retry in order as settled history', () => {
    const response = new Subject<AiChatRestResponse>();
    transport.watch.mockReturnValueOnce(new Subject<{body: string}>());
    api.sendChat.mockReturnValueOnce(response);
    component.state.prompt = 'Recover a missed attachment event';
    component.state.attachments = [{
      name: 'sample.txt', mediaType: 'text/plain', size: 4, data: 'test'
    }];
    component.send();

    response.next({
      response: 'Recovered answer.', conversationId: 'conversation-1',
      events: [providerErrorEvent(), providerRetryEvent()]
    });

    const errorIndex = component.state.messages.findIndex(message =>
      message.role === 'error' && message.content === RATE_LIMIT_REASON);
    const retryIndex = component.state.messages.findIndex(message =>
      message.role === 'progress'
      && message.content === 'The model provider request failed; retrying (attempt 7 of 10).');
    const answerIndex = component.state.messages.findIndex(message =>
      message.role === 'assistant' && message.content === 'Recovered answer.');
    expect(errorIndex).toBeGreaterThanOrEqual(0);
    expect(retryIndex).toBeGreaterThan(errorIndex);
    expect(answerIndex).toBeGreaterThan(retryIndex);
    expect(component.state.messages[retryIndex].inProgress).toBe(false);
  });

});
