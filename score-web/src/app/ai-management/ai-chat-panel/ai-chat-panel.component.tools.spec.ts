import {
  AiChatRestResponse,
  AiConversationRestoreService,
  HttpErrorResponse,
  Subject,
  Subscription,
  api,
  component,
  setupAiChatPanelSpec,
  teardownAiChatPanelSpec,
  transport
} from './ai-chat-panel.component.spec-support';

describe('AiChatPanelComponent tool events', () => {
  beforeEach(setupAiChatPanelSpec);
  afterEach(teardownAiChatPanelSpec);

  it('keeps the request active after a recoverable tool failure and later accepts the final answer', () => {
    component.state.prompt = 'Recover from a tool failure';
    component.send();
    const subscription = (component as any).requestSubscription as Subscription;

    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'tool_call', subtype: 'started',
      groupId: 'mcp', toolCallId: 'call-1', content: 'GitHub search',
      metadata: {toolName: 'github_search', statusMessage: 'Searching GitHub.'}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'tool_call', subtype: 'failed',
      groupId: 'mcp', toolCallId: 'call-1', content: 'GitHub search',
      metadata: {
        toolName: 'github_search', statusMessage: 'GitHub search failed.',
        terminal: false, recoverable: true, retryable: true, changeSafe: true
      }
    });

    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'tool_call', content: 'github_search failed.',
      groupId: 'mcp', toolCallId: 'call-1', toolName: 'github_search',
      toolStatus: 'failed', recoverable: true, retryable: true, changeSafe: true
    }));
    expect(component.state.pending).toBe(true);
    expect(component.interactionBlocked).toBe(true);
    expect(component.state.currentStatus).toBe('Continuing after tool failure');
    expect(subscription.closed).toBe(false);

    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'assistant_final',
      conversationId: 'conversation-1', content: 'Used a safe alternative.'
    });

    expect(component.state.pending).toBe(false);
    expect(component.state.currentStatus).toBe('Ready');
    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'assistant', content: 'Used a safe alternative.'
    }));
  });

  it('shows REST attachment tool failure, retry guide, and retry without replay duplicates', () => {
    const live = new Subject<{body: string}>();
    const response = new Subject<AiChatRestResponse>();
    transport.watch.mockReturnValueOnce(live);
    api.sendChat.mockReturnValueOnce(response);
    component.state.prompt = 'Retry an attachment-backed tool call';
    component.state.attachments = [{
      name: 'sample.txt', mediaType: 'text/plain', size: 4, data: 'test'
    }];
    component.send();
    const events = [
      {
        requestId: 'request-1', conversationId: 'conversation-1', sequence: 1,
        type: 'tool_call', subtype: 'failed', groupId: 'request-1', turnId: 'request-1',
        toolCallId: 'call-1', content: 'create_item failed.',
        metadata: {toolName: 'create_item', toolCallSeq: 1, terminal: false,
          recoverable: true, retryable: false, changeSafe: true,
          toolDetail: 'create_item\nError: value must be an integer.'}
      },
      {
        requestId: 'request-1', conversationId: 'conversation-1', sequence: 2,
        type: 'system', subtype: 'guide',
        content: 'I corrected the tool arguments and am retrying it.',
        metadata: {tool_retry: true}
      },
      {
        requestId: 'request-1', conversationId: 'conversation-1', sequence: 3,
        type: 'tool_call', subtype: 'started', groupId: 'request-1', turnId: 'request-1',
        toolCallId: 'call-2', content: 'Calling create_item.',
        metadata: {toolName: 'create_item', toolCallSeq: 2}
      },
      {
        requestId: 'request-1', conversationId: 'conversation-1', sequence: 4,
        type: 'tool_call', subtype: 'completed', groupId: 'request-1', turnId: 'request-1',
        toolCallId: 'call-2', content: 'create_item completed.',
        metadata: {toolName: 'create_item', toolCallSeq: 2}
      }
    ];

    events.forEach(event => live.next({body: JSON.stringify(event)}));

    expect(component.state.messages.filter(message =>
      message.role === 'tool_call' || message.role === 'guide')
      .map(message => [message.role, message.content])).toEqual([
      ['tool_call', 'create_item failed.'],
      ['guide', 'I corrected the tool arguments and am retrying it.'],
      ['tool_call', 'create_item completed.']
    ]);

    response.next({
      response: 'Done.', conversationId: 'conversation-1', events
    });

    expect(component.state.messages.filter(message =>
      message.role === 'tool_call' || message.role === 'guide')
      .map(message => [message.role, message.content])).toEqual([
      ['tool_call', 'create_item failed.'],
      ['guide', 'I corrected the tool arguments and am retrying it.'],
      ['tool_call', 'create_item completed.']
    ]);
  });

  it('replays missed REST tool and guide events from a terminal HTTP error body', () => {
    const live = new Subject<{body: string}>();
    const response = new Subject<AiChatRestResponse>();
    transport.watch.mockReturnValueOnce(live);
    api.sendChat.mockReturnValueOnce(response);
    component.state.prompt = 'Retry an attachment-backed tool call';
    component.state.attachments = [{
      name: 'sample.txt', mediaType: 'text/plain', size: 4, data: 'test'
    }];
    component.send();
    const events = [
      {
        requestId: 'request-1', conversationId: 'conversation-1', sequence: 1,
        type: 'tool_call', subtype: 'failed', groupId: 'request-1', turnId: 'request-1',
        toolCallId: 'call-1', content: 'create_item failed.',
        metadata: {toolName: 'create_item', toolCallSeq: 1, terminal: false,
          recoverable: true, retryable: false, changeSafe: true,
          toolDetail: 'create_item\nError: value must be an integer.'}
      },
      {
        requestId: 'request-1', conversationId: 'conversation-1', sequence: 2,
        type: 'system', subtype: 'guide',
        content: 'I corrected the tool arguments and am retrying it.',
        metadata: {tool_retry: true}
      },
      {
        requestId: 'request-1', conversationId: 'conversation-1', sequence: 3,
        type: 'tool_call', subtype: 'failed', groupId: 'request-1', turnId: 'request-1',
        toolCallId: 'call-2', content: 'create_item failed.',
        metadata: {toolName: 'create_item', toolCallSeq: 2, terminal: false,
          recoverable: true, retryable: false, changeSafe: true,
          toolDetail: 'create_item\nError: value must be an integer.'}
      }
    ];

    response.error(new HttpErrorResponse({status: 500, error: {events}}));

    expect(component.state.messages.filter(message =>
      message.role === 'tool_call' || message.role === 'guide')
      .map(message => [message.role, message.content])).toEqual([
      ['tool_call', 'create_item failed.'],
      ['guide', 'I corrected the tool arguments and am retrying it.'],
      ['tool_call', 'create_item failed.']
    ]);
    expect(component.state.messages.at(-1)).toEqual(expect.objectContaining({role: 'error'}));
  });

  it('renders a live awaiting-approval row for a guard-blocked change without ending the request', () => {
    component.state.prompt = 'Create a business context';
    component.send();

    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'tool_call', subtype: 'started',
      groupId: 'mcp', toolCallId: 'call-1', content: 'Creating business context',
      metadata: {toolName: 'create_business_context'}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'tool_call', subtype: 'blocked',
      groupId: 'mcp', toolCallId: 'call-1',
      content: 'create_business_context is awaiting approval.',
      metadata: {toolName: 'create_business_context'}
    });

    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'tool_call', content: 'create_business_context is awaiting approval.',
      groupId: 'mcp', toolCallId: 'call-1', toolName: 'create_business_context',
      toolStatus: 'blocked', inProgress: false
    }));
    expect(component.state.pending).toBe(true);
    expect(component.state.currentStatus).toBe('Working');
  });

  it('renders a live stopped row for a tool intercepted by a user stop', () => {
    component.state.prompt = 'Create a business context';
    component.send();

    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'tool_call', subtype: 'cancelled',
      groupId: 'mcp', toolCallId: 'call-1',
      content: 'create_business_context was stopped before execution.',
      metadata: {toolName: 'create_business_context'}
    });

    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'tool_call', content: 'create_business_context was stopped before execution.',
      groupId: 'mcp', toolCallId: 'call-1', toolName: 'create_business_context',
      toolStatus: 'cancelled', inProgress: false
    }));

    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'system', subtype: 'cancelled',
      content: 'Request cancelled.'
    });

    expect(component.state.pending).toBe(false);
    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'tool_call', toolStatus: 'cancelled'
    }));
  });

  it('starts a fresh visible tool lifecycle for every follow-up request', () => {
    vi.mocked((component as any).createRequestId).mockReset()
      .mockReturnValueOnce('request-1')
      .mockReturnValueOnce('request-2')
      .mockReturnValueOnce('request-3');

    const runTurn = (requestId: string, prompt: string, toolName: string) => {
      component.state.prompt = prompt;
      component.send();
      (component as any).handleSocketEvent({
        requestId, turnId: requestId, type: 'tool_call', subtype: 'started',
        groupId: requestId, toolCallId: `${requestId}-call`,
        content: `Calling ${toolName}`,
        metadata: {toolName, toolCallSeq: 0}
      });
      expect(component.state.messages.at(-1)).toEqual(expect.objectContaining({
        role: 'progress', content: `Calling ${toolName}.`, inProgress: true
      }));

      (component as any).handleSocketEvent({
        requestId, turnId: requestId, type: 'tool_call', subtype: 'completed',
        groupId: requestId, toolCallId: `${requestId}-call`,
        content: `${toolName} completed`,
        metadata: {toolName, toolCallSeq: 0}
      });
      expect(component.state.messages).toContainEqual(expect.objectContaining({
        role: 'tool_call', turnId: requestId, toolName,
        toolStatus: 'completed', inProgress: false
      }));
      expect(component.state.pending).toBe(true);

      (component as any).handleSocketEvent({
        requestId, conversationId: 'conversation-1',
        type: 'assistant_final', content: `${toolName} result verified.`
      });
      expect(component.state.pending).toBe(false);
    };

    runTurn('request-1', 'Create a sample context', 'create_business_context');
    runTurn('request-2', 'Show its values', 'get_business_context');
    runTurn('request-3', 'Check additional schemes', 'get_context_schemes');

    expect(component.state.messages.filter(message => message.role === 'tool_call')
      .map(message => [message.turnId, message.toolName, message.toolStatus]))
      .toEqual([
        ['request-1', 'create_business_context', 'completed'],
        ['request-2', 'get_business_context', 'completed'],
        ['request-3', 'get_context_schemes', 'completed']
      ]);
  });

  it('replaces the delayed wait placeholder with a follow-up guide before later tool rows', () => {
    vi.useFakeTimers();
    vi.mocked((component as any).createRequestId).mockReset()
      .mockReturnValueOnce('request-1')
      .mockReturnValueOnce('request-2');

    component.state.prompt = 'Create a sample context';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'Created context 73.'
    });

    component.state.prompt = 'Add values';
    component.send();
    transport.publishWhenConnected.mock.calls[1][0].publish();
    vi.advanceTimersByTime(5000);
    expect(component.state.messages.at(-1)).toEqual(expect.objectContaining({
      role: 'progress', content: 'Request sent. Waiting for the assistant response.'
    }));

    (component as any).handleSocketEvent({
      requestId: 'request-2', type: 'system', subtype: 'guide',
      content: 'I will check context 73 before adding values.'
    });
    expect(component.state.messages.map(message => [message.role, message.content]))
      .toEqual([
        ['user', 'Create a sample context'],
        ['assistant', 'Created context 73.'],
        ['user', 'Add values'],
        ['guide', 'I will check context 73 before adding values.']
      ]);

    // The cleared five-second callback must not reinsert a stale wait row.
    vi.advanceTimersByTime(5000);
    expect(component.state.messages.some(message =>
      message.content === 'Request sent. Waiting for the assistant response.')).toBe(false);

    (component as any).handleSocketEvent({
      requestId: 'request-2', turnId: 'request-2',
      type: 'tool_call', subtype: 'started', groupId: 'request-2',
      toolCallId: 'call-2', content: 'Calling get_business_context',
      metadata: {toolName: 'get_business_context', toolCallSeq: 0}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-2', turnId: 'request-2',
      type: 'tool_call', subtype: 'completed', groupId: 'request-2',
      toolCallId: 'call-2', content: 'get_business_context completed',
      metadata: {toolName: 'get_business_context', toolCallSeq: 0}
    });

    const guideIndex = component.state.messages.findIndex(message => message.role === 'guide');
    const toolIndex = component.state.messages.findIndex(message =>
      message.role === 'tool_call' && message.turnId === 'request-2');
    const statusIndex = component.state.messages.findIndex(message =>
      message.role === 'progress' && message.inProgress === true);
    expect(toolIndex).toBeGreaterThan(guideIndex);
    expect(statusIndex).toBeGreaterThan(toolIndex);
  });

  it.each([
    ['the final answer', {
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'Finished.'
    }],
    ['a terminal error', {
      requestId: 'request-1', type: 'system', subtype: 'error',
      content: 'The request failed.'
    }],
    ['cancellation', {
      requestId: 'request-1', type: 'system', subtype: 'cancelled',
      content: 'Request cancelled.'
    }]
  ])('keeps the request spinner after toolSearchTool completes until %s',
    (_terminalName, terminalEvent) => {
      component.state.prompt = 'Find and use the right tool';
      component.send();

      (component as any).handleSocketEvent({
        requestId: 'request-1', type: 'tool_call', subtype: 'started',
        groupId: 'request-1', toolCallId: 'call-1',
        content: 'Searching for tools',
        metadata: {
          toolName: 'toolSearchTool', statusMessage: 'Searching for tools.'
        }
      });
      (component as any).handleSocketEvent({
        requestId: 'request-1', type: 'tool_call', subtype: 'completed',
        groupId: 'request-1', toolCallId: 'call-1',
        content: 'toolSearchTool completed',
        metadata: {
          toolName: 'toolSearchTool', statusMessage: 'Tool search completed.'
        }
      });

      expect(component.state.messages).toContainEqual(expect.objectContaining({
        role: 'tool_call', toolName: 'toolSearchTool', toolStatus: 'completed',
        inProgress: false
      }));
      expect(component.state.messages).toContainEqual(expect.objectContaining({
        role: 'progress', content: 'Working', inProgress: true
      }));
      expect(component.state.messages.at(-1)).toEqual(expect.objectContaining({
        role: 'progress', content: 'Working', inProgress: true
      }));
      expect(component.state.pending).toBe(true);

      (component as any).handleSocketEvent(terminalEvent);

      expect(component.state.messages.some(message => message.inProgress)).toBe(false);
      expect(component.state.pending).toBe(false);
    }
  );

  it('keeps parallel live tool rows in request order and matches canonical restore order', async () => {
    vi.useFakeTimers();
    component.state.prompt = 'Run an allowed slow call and a denied fast call';
    component.send();
    const groupId = 'tool-group-v1-parallel-batch';
    const turnId = 'request-1';

    (component as any).handleSocketEvent({
      requestId: 'request-1', turnId, type: 'tool_call', subtype: 'started',
      groupId, toolCallId: 'call-1', content: 'Allowed lookup',
      metadata: {
        toolName: 'allowed_lookup', statusMessage: 'Running allowed lookup.',
        toolCallSeq: 1
      }
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', turnId, type: 'tool_call', subtype: 'started',
      groupId, toolCallId: 'call-2', content: 'Denied change',
      metadata: {
        toolName: 'denied_change', statusMessage: 'Checking change policy.',
        toolCallSeq: 2
      }
    });

    // The policy-denied second call terminates before the allowed first call.
    (component as any).handleSocketEvent({
      requestId: 'request-1', turnId, type: 'tool_call', subtype: 'failed',
      groupId, toolCallId: 'call-2', content: 'Denied change',
      metadata: {
        toolName: 'denied_change', statusMessage: 'Change denied by policy.',
        toolCallSeq: 2, terminal: false, recoverable: true,
        retryable: false, changeSafe: true
      }
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', turnId, type: 'tool_call', subtype: 'completed',
      groupId, toolCallId: 'call-1', content: 'Allowed lookup',
      metadata: {
        toolName: 'allowed_lookup', statusMessage: 'Allowed lookup completed.',
        toolCallSeq: 1
      }
    });

    const visibleToolRows = () => component.state.messages
      .filter(message => message.role === 'tool_call')
      .map(message => ({
        toolCallId: message.toolCallId,
        content: message.content,
        toolStatus: message.toolStatus
      }));
    const liveRows = visibleToolRows();
    expect(liveRows).toEqual([
      {
        toolCallId: 'call-1', content: 'allowed_lookup completed.',
        toolStatus: 'completed'
      },
      {
        toolCallId: 'call-2', content: 'denied_change failed.',
        toolStatus: 'failed'
      }
    ]);

    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'assistant_final',
      conversationId: 'conversation-1', content: 'Finished.'
    });

    const restoreService = new AiConversationRestoreService();
    (component as any).conversationRestoreService = restoreService;
    const restoreToken = '00000000-0000-4000-8000-000000000099';
    const restoreMetadata = (metadata: {[key: string]: unknown} = {}) => ({
      restoreToken, restoreSequence: 1, ...metadata
    });
    restoreService.expectAttempt(restoreToken, 1);
    (component as any).activeRestoreRequestId = 'restore-order';
    component.state.restoringConversation = true;
    (component as any).handleSocketEvent({
      requestId: 'restore-order', conversationId: 'conversation-1',
      type: 'HISTORY_START', metadata: restoreMetadata()
    });
    (component as any).handleSocketEvent({
      requestId: 'restore-order', conversationId: 'conversation-1',
      type: 'HISTORY_MESSAGE', message: 'tool_call', subtype: 'completed',
      content: 'Allowed lookup completed.', groupId, toolCallId: 'call-1', index: 0,
      metadata: restoreMetadata({toolName: 'allowed_lookup'})
    });
    (component as any).handleSocketEvent({
      requestId: 'restore-order', conversationId: 'conversation-1',
      type: 'HISTORY_MESSAGE', message: 'tool_call', subtype: 'failed',
      content: 'Change denied by policy.', groupId, toolCallId: 'call-2', index: 1,
      metadata: restoreMetadata({
        toolName: 'denied_change', recoverable: true,
        retryable: false, changeSafe: true
      })
    });
    (component as any).handleSocketEvent({
      requestId: 'restore-order', conversationId: 'conversation-1',
      type: 'HISTORY_FINAL', metadata: restoreMetadata()
    });
    await vi.runAllTimersAsync();

    expect(visibleToolRows()).toEqual(liveRows);
  });

  it('terminates pending state and the request subscription only for an explicit request error', () => {
    component.state.prompt = 'Fail the whole request';
    component.send();
    const subscription = (component as any).requestSubscription as Subscription;

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'request_error',
      sequence: 10,
      content: 'The request failed after durable termination.',
      metadata: {
        terminal: true, recoverable: false, retryable: false,
        status: 'FAILED', generation: 7
      }
    });

    expect(component.state.pending).toBe(false);
    expect(component.interactionBlocked).toBe(false);
    expect(component.state.currentStatus).toBe('Error');
    expect(component.state.conversationId).toBe('conversation-1');
    expect(subscription.closed).toBe(true);
    expect(component.state.messages).toContainEqual({
      role: 'error', content: 'The request failed after durable termination.'
    });
  });

  it('renders a terminal request timeout truthfully and closes its subscription', () => {
    component.state.prompt = 'Time out the whole request';
    component.send();
    const subscription = (component as any).requestSubscription as Subscription;

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'request_error',
      sequence: 11,
      content: 'Request deadline exceeded.',
      metadata: {
        terminal: true, recoverable: false, retryable: false,
        status: 'TIMED_OUT', generation: 7
      }
    });

    expect(component.state.pending).toBe(false);
    expect(component.state.currentStatus).toBe('Timed out');
    expect(subscription.closed).toBe(true);
    expect(component.state.messages).toContainEqual({
      role: 'error', content: 'Request deadline exceeded.'
    });
  });

  it('blocks new work on a non-cancellation reconciliation terminal', () => {
    component.state.prompt = 'Require durable reconciliation';
    component.send();
    const subscription = (component as any).requestSubscription as Subscription;

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'reconciliation_required',
      sequence: 12,
      content: 'Request outcome requires reconciliation before retry.',
      metadata: {
        terminal: true, recoverable: false, retryable: false,
        status: 'UNKNOWN_RECONCILIATION_REQUIRED', generation: 7
      }
    });

    expect(component.state.pending).toBe(false);
    expect(component.state.reconciliationRequired).toBe(true);
    expect(component.interactionBlocked).toBe(true);
    expect(component.state.currentStatus).toBe('Review needed');
    expect(subscription.closed).toBe(true);
    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'error', content: expect.stringContaining('requires reconciliation')
    }));
  });

});
