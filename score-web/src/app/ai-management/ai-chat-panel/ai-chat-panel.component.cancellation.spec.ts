/**
 * Verifies the AI chat panel's request cancellation behavior.
 */

import {
  AiCancellationResponse,
  AiConversationRestoreService,
  AiPublicExecutionRequestStatus,
  CANCELLATION_ACK_TIMEOUT_MS,
  CANCELLATION_ADMISSION_RETRY_MS,
  CANCELLATION_TERMINAL_TIMEOUT_MS,
  HttpErrorResponse,
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

describe('AiChatPanelComponent request cancellation', () => {
  beforeEach(setupAiChatPanelSpec);
  afterEach(teardownAiChatPanelSpec);

  it('cancels a chat waiting for its initial connection without queueing a backend cancel', () => {
    component.state.prompt = 'Wait for a connection';
    component.send();
    const pendingPublish = transport.publishWhenConnected.mock.calls[0][0];

    component.cancelActiveRequest();

    expect(transport.cancelReconnect).toHaveBeenCalledOnce();
    expect(transport.publish).not.toHaveBeenCalled();
    expect(api.cancelRequest).not.toHaveBeenCalled();
    expect(pendingPublish.active()).toBe(false);
    expect(component.state.pending).toBe(false);
    expect(component.state.currentStatus).toBe('Ready');

    // Even a callback already queued by the transport must fail closed.
    pendingPublish.publish();
    expect(transport.publish).not.toHaveBeenCalled();
  });

  it('cancels a scheduled publish during reconnect without starting backend work later', () => {
    component.state.prompt = 'Reconnect before sending';
    component.send();
    const pendingPublish = transport.publishWhenConnected.mock.calls[0][0];
    pendingPublish.onReconnectStatus(2, 3);

    component.cancelActiveRequest();

    expect(transport.cancelReconnect).toHaveBeenCalledOnce();
    expect(pendingPublish.active()).toBe(false);
    pendingPublish.onConnected();
    pendingPublish.publish();
    expect(transport.publish).not.toHaveBeenCalled();
    expect(api.cancelRequest).not.toHaveBeenCalled();
  });

  it('publishes a later request normally after cancelling before the first publish', () => {
    component.state.prompt = 'Cancel this request';
    component.send();
    component.cancelActiveRequest();

    component.state.prompt = 'Send the next request';
    component.send();
    const nextPublish = transport.publishWhenConnected.mock.calls[1][0];
    nextPublish.publish();

    expect(transport.publish).toHaveBeenCalledOnce();
    expect(transport.publish).toHaveBeenCalledWith('/app/ai/chat', expect.objectContaining({
      requestId: 'request-1', prompt: 'Send the next request'
    }));
  });

  it('rejects a stale publish callback while a different request is pending', () => {
    const createRequestId = (component as any).createRequestId as ReturnType<typeof vi.fn>;
    createRequestId.mockReturnValueOnce('request-a').mockReturnValueOnce('request-b');
    component.state.prompt = 'First request';
    component.send();
    const stalePublish = transport.publishWhenConnected.mock.calls[0][0];
    component.cancelActiveRequest();

    component.state.prompt = 'Second request';
    component.send();
    const currentPublish = transport.publishWhenConnected.mock.calls[1][0];
    stalePublish.publish();
    currentPublish.publish();

    expect(transport.publish).toHaveBeenCalledOnce();
    expect(transport.publish).toHaveBeenCalledWith('/app/ai/chat', expect.objectContaining({
      requestId: 'request-b', prompt: 'Second request'
    }));
  });

  it('starts published text cancellation through authoritative HTTP only', () => {
    component.state.prompt = 'Send this request';
    component.send();
    const pendingPublish = transport.publishWhenConnected.mock.calls[0][0];
    pendingPublish.publish();

    component.cancelActiveRequest();

    expect(transport.publish).toHaveBeenCalledOnce();
    expect(transport.publish).toHaveBeenCalledWith('/app/ai/chat', expect.any(Object));
    expect(api.cancelRequest).toHaveBeenCalledWith('request-1', {
      cancellationRequestId: 'cancel-1'
    });
    expect(component.state.messages).toContainEqual({
      role: 'progress', content: 'Cancelling request.', inProgress: true
    });
  });

  it('preserves backend cancellation for an attachment request already started over HTTP', () => {
    component.state.prompt = 'Inspect this file';
    component.state.attachments = [{
      name: 'sample.txt', mediaType: 'text/plain', size: 4, data: 'test'
    }];
    component.send();

    component.cancelActiveRequest();

    expect(api.sendChat).toHaveBeenCalledOnce();
    expect(transport.publish).not.toHaveBeenCalled();
    expect(api.cancelRequest).toHaveBeenCalledWith('request-1', {
      cancellationRequestId: 'cancel-1'
    });
  });

  it('does not emit a speculative STOMP cancel before the HTTP result is known', () => {
    component.state.prompt = 'Send this request';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    component.cancelActiveRequest();

    expect(api.cancelRequest).toHaveBeenCalledWith('request-1', {
      cancellationRequestId: 'cancel-1'
    });
    expect(transport.publish).toHaveBeenCalledOnce();
    expect(component.state.pending).toBe(true);
  });

  it('retries the same cancellation ID after a transient pre-admission 404', () => {
    vi.useFakeTimers();
    const first = new Subject<AiCancellationResponse>();
    const retry = new Subject<AiCancellationResponse>();
    api.cancelRequest.mockReturnValueOnce(first).mockReturnValueOnce(retry);
    component.state.prompt = 'Stop at admission boundary';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    component.cancelActiveRequest();

    first.error(new HttpErrorResponse({
      status: 404,
      error: {}
    }));
    expect(transport.publish).toHaveBeenNthCalledWith(2, '/app/ai/chat/cancel', {
      requestId: 'request-1', cancellationRequestId: 'cancel-1'
    });

    vi.advanceTimersByTime(CANCELLATION_ADMISSION_RETRY_MS);
    expect(api.cancelRequest).toHaveBeenNthCalledWith(2, 'request-1', {
      cancellationRequestId: 'cancel-1'
    });
    retry.next(cancellationResponse());
    expect(component.state.cancellation.phase).toBe('acknowledged');
  });

  it('keeps retrying attachment cancellation over HTTP while the live interaction channel is open', () => {
    vi.useFakeTimers();
    const cancellations = [
      new Subject<AiCancellationResponse>(),
      new Subject<AiCancellationResponse>(),
      new Subject<AiCancellationResponse>()
    ];
    api.cancelRequest
      .mockReturnValueOnce(cancellations[0])
      .mockReturnValueOnce(cancellations[1])
      .mockReturnValueOnce(cancellations[2]);
    component.state.prompt = 'Stop attachment admission safely';
    component.state.attachments = [{
      name: 'sample.txt', mediaType: 'text/plain', size: 4, data: 'test'
    }];
    component.send();
    component.cancelActiveRequest();

    expect(transport.watch).toHaveBeenCalledWith('/user/queue/ai/chat/request-1');
    for (let responseIndex = 0; responseIndex < 2; responseIndex++) {
      cancellations[responseIndex].error(new HttpErrorResponse({
        status: 404,
        error: {}
      }));
      vi.advanceTimersByTime(CANCELLATION_ADMISSION_RETRY_MS);
    }

    expect(api.cancelRequest).toHaveBeenCalledTimes(3);
    for (const [, command] of api.cancelRequest.mock.calls) {
      expect(command).toEqual({cancellationRequestId: 'cancel-1'});
    }
    cancellations[2].next(cancellationResponse());
    expect(component.state.cancellation).toMatchObject({
      phase: 'acknowledged', acknowledged: true
    });
  });

  it('stores the accepted conversation, generation, and deadline for exact cancellation', () => {
    (component as any).conversationRestoreService = new AiConversationRestoreService();
    component.state.prompt = 'Track this request';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();

    (component as any).handleSocketEvent({
      requestId: 'request-1',
      conversationId: 'conversation-1',
      type: 'system',
      subtype: 'accepted',
      content: 'Request received.',
      metadata: {generation: 7, deadline: '2026-07-14T13:05:00Z'}
    });
    component.cancelActiveRequest();

    expect(component.state.activeRequest).toEqual({
      requestId: 'request-1',
      conversationId: 'conversation-1',
      generation: 7,
      deadline: '2026-07-14T13:05:00Z'
    });
    expect(api.cancelRequest).toHaveBeenCalledWith('request-1', {
      cancellationRequestId: 'cancel-1',
      conversationId: 'conversation-1',
      expectedGeneration: 7
    });
  });

  it('does not let a conflicting accepted frame replace the stored generation fence', () => {
    component.state.prompt = 'Track one generation';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
      subtype: 'accepted', content: 'Accepted.',
      metadata: {generation: 7, deadline: '2026-07-14T13:05:00Z'}
    });

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
      subtype: 'accepted', content: 'Stale accepted.',
      metadata: {generation: 8, deadline: '2026-07-14T13:06:00Z'}
    });

    expect(component.state.activeRequest?.generation).toBe(7);
    expect(component.state.activeRequest?.deadline).toBe('2026-07-14T13:05:00Z');
  });

  it('ignores an accepted identity with a non-positive generation', () => {
    component.state.prompt = 'Reject malformed identity';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();

    for (const generation of [0, -1]) {
      (component as any).handleSocketEvent({
        requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
        subtype: 'accepted', content: 'Malformed accepted.',
        metadata: {generation, deadline: '2026-07-14T13:05:00Z'}
      });
    }
    component.cancelActiveRequest();

    expect(component.state.activeRequest).toEqual({requestId: 'request-1'});
    expect(api.cancelRequest).toHaveBeenCalledWith('request-1', {
      cancellationRequestId: 'cancel-1'
    });
  });

  it('shows delayed at two seconds and falls back over STOMP with the same ID', () => {
    vi.useFakeTimers();
    component.state.prompt = 'Cancel with fallback';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    component.cancelActiveRequest();

    vi.advanceTimersByTime(CANCELLATION_ACK_TIMEOUT_MS - 1);
    expect(component.cancellationDelayed).toBe(false);
    expect(transport.publish).toHaveBeenCalledOnce();

    vi.advanceTimersByTime(1);
    expect(component.cancellationDelayed).toBe(true);
    expect(component.state.currentStatus).toBe('Cancellation delayed');
    expect(transport.publish).toHaveBeenNthCalledWith(2, '/app/ai/chat/cancel', {
      requestId: 'request-1', cancellationRequestId: 'cancel-1'
    });

    vi.advanceTimersByTime(13_000);
    expect(component.state.pending).toBe(true);
    expect(component.state.currentStatus).toBe('Cancellation delayed');
    expect(component.state.messages).not.toContainEqual(expect.objectContaining({
      content: expect.stringContaining('backend did not acknowledge')
    }));
  });

  it('accepts HTTP ACK without a duplicate STOMP command and reads terminal status at five seconds', () => {
    vi.useFakeTimers();
    const cancellation = new Subject<AiCancellationResponse>();
    const status = new Subject<AiPublicExecutionRequestStatus>();
    api.cancelRequest.mockReturnValueOnce(cancellation);
    api.getRequestStatus.mockReturnValueOnce(status);
    component.state.prompt = 'Cancel and read back';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    component.cancelActiveRequest();

    cancellation.next(cancellationResponse());
    expect(component.state.cancellation.phase).toBe('acknowledged');
    expect(transport.publish).toHaveBeenCalledOnce();

    vi.advanceTimersByTime(CANCELLATION_TERMINAL_TIMEOUT_MS);
    expect(api.getRequestStatus).toHaveBeenCalledWith('request-1', 'conversation-1', 7);
    status.next(publicStatus('CANCELLED'));

    expect(component.state.pending).toBe(false);
    expect(component.state.currentStatus).toBe('Ready');
    expect(component.state.cancellation.phase).toBe('idle');
  });

  it('preserves the active handshake when new chat is requested during cancellation', () => {
    component.state.prompt = 'Do not forget this request';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();

    component.startNewChat();

    expect(component.state.pending).toBe(true);
    expect(component.state.activeRequest?.requestId).toBe('request-1');
    expect(component.state.messages.some(message => message.role === 'user')).toBe(true);
    expect(api.cancelRequest).toHaveBeenCalledOnce();

    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'system', subtype: 'cancelled'
    });
    expect(component.state.pending).toBe(false);
    expect(component.state.activeRequest).toBeUndefined();
    expect(component.state.messages).toEqual([]);
  });

  it('does not execute a deferred new chat after reconciliation-required terminal', () => {
    const cancellation = new Subject<AiCancellationResponse>();
    api.cancelRequest.mockReturnValueOnce(cancellation);
    component.state.prompt = 'Keep the reconciliation warning';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    component.startNewChat();

    cancellation.next({
      ...cancellationResponse(),
      disposition: 'ALREADY_TERMINAL',
      status: 'UNKNOWN_RECONCILIATION_REQUIRED',
      acknowledged: false,
      terminal: true,
      lifecycleEventSequence: 4
    });

    expect(component.state.reconciliationRequired).toBe(true);
    expect(component.state.currentStatus).toBe('Review needed');
    expect((component as any).pendingChangeConfirmation).toBeUndefined();
    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'error',
      content: expect.stringContaining('requires reconciliation')
    }));
    const messages = [...component.state.messages];
    component.startNewChat();
    component.state.prompt = 'This must remain blocked';
    component.send();
    component.loadConversation('conversation-2');
    component.deleteConversation('conversation-1');
    expect(component.state.messages).toEqual(messages);
    expect(transport.publishWhenConnected).toHaveBeenCalledOnce();
    expect(transport.watch).toHaveBeenCalledOnce();
    expect(api.deleteConversation).not.toHaveBeenCalled();
  });

  it('ignores late progress after durable cancellation acknowledgement', () => {
    const cancellation = new Subject<AiCancellationResponse>();
    api.cancelRequest.mockReturnValueOnce(cancellation);
    component.state.prompt = 'Fence late progress';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    component.cancelActiveRequest();
    cancellation.next(cancellationResponse());

    (component as any).handleSocketEvent({
      requestId: 'request-1',
      type: 'assistant_update',
      content: 'This arrived too late.'
    });

    expect(component.state.messages).not.toContainEqual(expect.objectContaining({
      role: 'assistant', content: 'This arrived too late.'
    }));
    expect(component.state.currentStatus).toBe('Cancelling');
  });

  it('fences a generic error after ACK and accepts only explicit cancellation status', () => {
    const cancellation = new Subject<AiCancellationResponse>();
    api.cancelRequest.mockReturnValueOnce(cancellation);
    component.state.prompt = 'Ignore stale generic error';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    component.cancelActiveRequest();
    cancellation.next(cancellationResponse());

    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'system', subtype: 'error',
      content: 'A stale request error.'
    });
    expect(component.state.pending).toBe(true);
    expect(component.state.messages).not.toContainEqual(expect.objectContaining({
      role: 'error', content: 'A stale request error.'
    }));

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'request_error',
      sequence: 9,
      content: 'A stale terminal request error.',
      metadata: {
        terminal: true, recoverable: false, retryable: false,
        status: 'FAILED', generation: 7
      }
    });
    expect(component.state.pending).toBe(true);
    expect(component.state.messages).not.toContainEqual(expect.objectContaining({
      role: 'error', content: 'A stale terminal request error.'
    }));

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'cancellation_current_status',
      metadata: {
        cancellationRequestId: 'cancel-1', generation: 7,
        lifecycleEventSequence: 4, status: 'TIMED_OUT', terminal: true
      }
    });
    expect(component.state.pending).toBe(false);
    expect(component.state.currentStatus).toBe('Timed out');
  });

});
