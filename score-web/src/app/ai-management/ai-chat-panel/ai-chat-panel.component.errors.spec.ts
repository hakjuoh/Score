/**
 * Verifies the AI chat panel's terminal error and data-change behavior.
 */

import {HttpErrorResponse, HttpHeaders} from '@angular/common/http';
import {
  AiCancellationResponse,
  Subject,
  Subscription,
  throwError,
  api,
  cancellationResponse,
  component,
  navigation,
  setupAiChatPanelSpec,
  teardownAiChatPanelSpec,
  transport
} from './ai-chat-panel.component.spec-support';

describe('AiChatPanelComponent terminal errors and data changes', () => {
  beforeEach(setupAiChatPanelSpec);
  afterEach(teardownAiChatPanelSpec);

  it('fails safe on malformed request errors and unknown event types', () => {
    component.state.prompt = 'Keep waiting for authoritative state';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    const subscription = (component as any).requestSubscription as Subscription;
    const acknowledgementTimeout = (component as any).acknowledgementTimeout;

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'request_error',
      sequence: 13,
      content: 'Missing terminal contract fields.',
      metadata: {terminal: true, status: 'FAILED', generation: 7}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'request_error',
      sequence: 14,
      content: 'A nonterminal status cannot end the request.',
      metadata: {
        terminal: true, recoverable: false, retryable: false,
        status: 'RUNNING', generation: 7
      }
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'future_unknown_event',
      message: 'Do not treat an unknown event as terminal.'
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'system', subtype: 'future_unknown_status',
      content: 'Do not let an unknown status disarm the acknowledgement watchdog.'
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'assistant_final', content: ''
    });

    expect(component.state.pending).toBe(true);
    expect(subscription.closed).toBe(false);
    expect((component as any).acknowledgementTimeout).toBe(acknowledgementTimeout);
    expect(component.state.messages).not.toContainEqual(expect.objectContaining({
      role: 'error'
    }));
  });

  it('treats the legacy tool-scoped system error as recoverable only with an active matching call', () => {
    component.state.prompt = 'Support an older backend';
    component.send();
    const subscription = (component as any).requestSubscription as Subscription;
    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'tool_call', subtype: 'started',
      groupId: 'mcp', toolCallId: 'call-legacy', content: 'GitHub search',
      metadata: {toolName: 'github_search', statusMessage: 'Searching GitHub.'}
    });

    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'system', subtype: 'error',
      content: 'GitHub search failed: upstream returned 500',
      metadata: {toolName: 'github_search'}
    });

    expect(component.state.pending).toBe(true);
    expect(subscription.closed).toBe(false);
    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'tool_call', toolCallId: 'call-legacy', toolStatus: 'failed',
      content: 'Execution failed'
    }));
  });

  it('keeps a legacy tool-discovery failure recoverable with the generic execution row', () => {
    component.state.prompt = 'Discover tools on an older backend';
    component.send();
    const subscription = (component as any).requestSubscription as Subscription;
    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'tool_call', subtype: 'started',
      groupId: 'discovery', toolCallId: 'search-1', content: 'Tool discovery',
      metadata: {
        toolName: 'tool_search_agent', toolDiscovery: true,
        statusMessage: 'Discovering tools.'
      }
    });

    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'system', subtype: 'error',
      content: 'Tool discovery failed: search service returned 500',
      metadata: {toolName: 'tool_search_agent'}
    });

    expect(component.state.pending).toBe(true);
    expect(subscription.closed).toBe(false);
    expect(component.state.currentStatus).toBe('Continuing after tool failure');
    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'tool_call', toolCallId: 'search-1', content: 'Execution failed'
    }));
  });

  it('does not let legacy tool metadata downgrade an unmatched terminal error', () => {
    component.state.prompt = 'Reject a metadata-only bypass';
    component.send();
    const subscription = (component as any).requestSubscription as Subscription;

    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'system', subtype: 'error',
      content: 'GitHub search failed: terminal request failure',
      metadata: {toolName: 'github_search'}
    });

    expect(component.state.pending).toBe(false);
    expect(subscription.closed).toBe(true);
    expect(component.state.messages).toContainEqual({
      role: 'error', content: 'GitHub search failed: terminal request failure'
    });
  });

  it('rejects a stale terminal generation when a request ID is reused', () => {
    component.state.prompt = 'First generation';
    component.send();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'accepted', content: 'Request received.',
      metadata: {generation: 7, deadline: '2026-07-14T18:00:00Z'}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'First answer.'
    });

    component.state.prompt = 'Second generation';
    component.send();
    const subscription = (component as any).requestSubscription as Subscription;
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'accepted', content: 'Request received.',
      metadata: {generation: 8, deadline: '2026-07-14T18:05:00Z'}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'request_error', sequence: 21, content: 'Stale failure.',
      metadata: {
        terminal: true, recoverable: false, retryable: false,
        status: 'FAILED', generation: 7
      }
    });

    expect(component.state.pending).toBe(true);
    expect(subscription.closed).toBe(false);
    expect(component.state.messages).not.toContainEqual({role: 'error', content: 'Stale failure.'});

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'request_error', sequence: 22, content: 'Current failure.',
      metadata: {
        terminal: true, recoverable: false, retryable: false,
        status: 'FAILED', generation: 8
      }
    });
    expect(component.state.pending).toBe(false);
    expect(subscription.closed).toBe(true);
  });

  it('drops prior tool correlation when a request reaches a terminal', () => {
    component.state.prompt = 'Start a tool';
    component.send();
    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'tool_call', subtype: 'started',
      groupId: 'mcp', toolCallId: 'call-stale', content: 'GitHub search',
      metadata: {toolName: 'github_search', statusMessage: 'Searching GitHub.'}
    });
    expect((component as any).messageTracker.activeToolCallCount).toBe(1);

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'Finished without the tool.'
    });

    expect((component as any).messageTracker.activeToolCallCount).toBe(0);
    const messages = [...component.state.messages];
    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'system', subtype: 'error',
      content: 'GitHub search failed: stale delivery',
      metadata: {toolName: 'github_search'}
    });
    expect(component.state.messages).toEqual(messages);
  });

  it('still refreshes a data change that committed before cancellation ACK', () => {
    const cancellation = new Subject<AiCancellationResponse>();
    api.cancelRequest.mockReturnValueOnce(cancellation);
    component.state.prompt = 'Keep committed refresh';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    component.cancelActiveRequest();
    cancellation.next(cancellationResponse());

    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'system', subtype: 'data_changed',
      resource: 'business-context', action: 'update', ids: ['7']
    });

    expect(navigation.handleDataChanged).toHaveBeenCalledOnce();
    expect(component.state.currentStatus).toBe('Cancelling');
    expect(component.state.pending).toBe(true);
  });

  it('ignores a stale final after ACK and accepts the canonical cancelled terminal', () => {
    const cancellation = new Subject<AiCancellationResponse>();
    api.cancelRequest.mockReturnValueOnce(cancellation);
    component.state.prompt = 'Let a final winner through';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    component.cancelActiveRequest();
    cancellation.next(cancellationResponse());

    (component as any).handleSocketEvent({
      requestId: 'request-1',
      conversationId: 'conversation-1',
      type: 'assistant_final',
      content: 'This stale answer must not reopen the request.',
      continuationRequired: false
    });

    expect(component.state.messages).not.toContainEqual(expect.objectContaining({
      role: 'assistant', content: 'This stale answer must not reopen the request.'
    }));
    expect(component.state.pending).toBe(true);
    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'system', subtype: 'cancelled'
    });
    expect(component.state.pending).toBe(false);
    expect(component.state.currentStatus).toBe('Ready');
  });

  it('does not block the composer while the self-service policy is loading (optimistic UI)', () => {
    const policy = new Subject<any>();
    (api as any).getPolicy = vi.fn(() => policy);

    (component as any).loadPolicy();

    expect(component.state.policyLoading).toBe(true);
    expect(component.commandInputBlocked).toBe(false);
    expect(component.composerPlaceholder).toBe('Ask a question');
    expect(component.policyNotice).toBeUndefined();
  });

  it('fails closed when the self-service policy cannot be loaded', () => {
    (api as any).getPolicy = vi.fn(() => throwError(() => new Error('unavailable')));

    (component as any).loadPolicy();

    expect(component.state.policyLoadFailed).toBe(true);
    expect(component.commandInputBlocked).toBe(true);
    expect(component.policyNotice).toContain('could not be loaded');
    expect(component.policyNotice).toContain('contact your administrator');
  });

  it('handles AI_DISABLED_BY_POLICY error with clear admin contact instruction', () => {
    const error = new HttpErrorResponse({
      status: 403,
      headers: new HttpHeaders({
        'X-Error-Code': 'AI_DISABLED_BY_POLICY',
        'X-Error-Message': 'AI Assistant is disabled by policy'
      })
    });
    const message = (component as any).resolveRequestErrorMessage(error);
    expect(message).toContain('disabled by your administrator');
    expect(message).toContain('contact your administrator');
    expect(component.state.policy?.enabled).toBe(false);
  });

  it('handles AI_QUOTA_EXHAUSTED error with quota instructions', () => {
    component.state.policy = {
      enabled: true,
      multiAgentEnabled: true,
      maxAgentsPerRequest: 4,
      maxActiveRequests: 8,
      allowedModels: [],
      defaultModelKey: 'gpt-4o',
      allowedReasoningEfforts: {},
      quota: {limitTokens: 1000, consumedTokens: 1000, reservedTokens: 0, remainingTokens: 50, periodEnd: null}
    };
    const error = new HttpErrorResponse({
      status: 429,
      headers: new HttpHeaders({
        'X-Error-Code': 'AI_QUOTA_EXHAUSTED',
        'X-Error-Message': 'AI token quota exhausted'
      })
    });
    const message = (component as any).resolveRequestErrorMessage(error);
    expect(message).toContain('quota has been exhausted');
    expect(message).toContain('contact your administrator');
    expect(component.state.policy.quota.remainingTokens).toBe(0);
  });

  it('handles all policy error codes with administrator contact guidance', () => {
    const errorCodes = [
      'AI_DISABLED_BY_POLICY',
      'AI_QUOTA_EXHAUSTED',
      'AI_REQUEST_TOKEN_LIMIT_EXHAUSTED',
      'AI_ACTIVE_REQUEST_LIMIT',
      'AI_MODEL_NOT_ALLOWED',
      'AI_REASONING_EFFORT_NOT_ALLOWED',
      'AI_NO_ALLOWED_MODELS',
      'AI_PROVIDER_NOT_CONFIGURED',
      'AI_POLICY_VERSION_CONFLICT'
    ];

    for (const code of errorCodes) {
      const error = new HttpErrorResponse({
        status: 400,
        headers: new HttpHeaders({'X-Error-Code': code})
      });
      const message = (component as any).resolveRequestErrorMessage(error);
      expect(message).toContain('administrator');
    }
  });

});
