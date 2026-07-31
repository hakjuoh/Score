/**
 * Verifies the AI chat panel's request completion and recovery behavior.
 */

import {
  AiCancellationResponse,
  AiChatConversationDetails,
  AiChatRestResponse,
  AiPublicExecutionRequestStatus,
  ACTIVE_REQUEST_RECOVERY_RETRY_DELAY_MS,
  CANCELLATION_TERMINAL_TIMEOUT_MS,
  COMPLETED_PAYLOAD_WAIT_MS,
  HttpErrorResponse,
  HttpHeaders,
  REQUEST_STATUS_WATCHDOG_MS,
  Subject,
  api,
  cancellationResponse,
  cancellationService,
  completedCancellationResponse,
  component,
  destroyComponent,
  changeConfirmationEvent,
  of,
  publicStatus,
  setupAiChatPanelSpec,
  teardownAiChatPanelSpec,
  throwError,
  transport
} from './ai-chat-panel.component.spec-support';

describe('AiChatPanelComponent request completion and recovery', () => {
  beforeEach(setupAiChatPanelSpec);
  afterEach(teardownAiChatPanelSpec);

  it('does not append a duplicate cancelled message after HTTP terminal completion', () => {
    const cancellation = new Subject<AiCancellationResponse>();
    api.cancelRequest.mockReturnValueOnce(cancellation);
    component.state.prompt = 'Complete cancellation once';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    component.cancelActiveRequest();
    cancellation.next({
      ...cancellationResponse(),
      disposition: 'CANCELLED',
      status: 'CANCELLED',
      terminal: true,
      lifecycleEventSequence: 4
    });

    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'system', subtype: 'cancelled',
      content: 'Request cancelled.'
    });

    expect(component.state.messages.filter(message =>
      message.role === 'debug' && message.content === 'Request cancelled.'
    )).toHaveLength(1);
  });

  it('ignores an attachment success that arrives after durable cancellation ACK', () => {
    vi.useFakeTimers();
    const chat = new Subject<AiChatRestResponse>();
    const cancellation = new Subject<AiCancellationResponse>();
    const status = new Subject<AiPublicExecutionRequestStatus>();
    api.sendChat.mockReturnValueOnce(chat);
    api.cancelRequest.mockReturnValueOnce(cancellation);
    api.getRequestStatus.mockReturnValueOnce(status);
    component.state.prompt = 'Fence stale attachment success';
    component.state.attachments = [{
      name: 'sample.txt', mediaType: 'text/plain', size: 4, data: 'test'
    }];
    component.send();
    component.cancelActiveRequest();
    cancellation.next(cancellationResponse());

    chat.next({
      response: 'A stale attachment answer.', conversationId: 'conversation-1'
    });
    expect(component.state.pending).toBe(true);
    expect(component.state.messages).not.toContainEqual(expect.objectContaining({
      role: 'assistant', content: 'A stale attachment answer.'
    }));

    vi.advanceTimersByTime(CANCELLATION_TERMINAL_TIMEOUT_MS);
    status.next(publicStatus('CANCELLED'));
    expect(component.state.pending).toBe(false);
  });

  it('preserves a text final arriving after current-status COMPLETED', () => {
    vi.useFakeTimers();
    const cancellation = new Subject<AiCancellationResponse>();
    api.cancelRequest.mockReturnValueOnce(cancellation);
    component.state.prompt = 'Preserve the completed text answer';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    component.cancelActiveRequest();
    cancellation.next(completedCancellationResponse());

    expect(component.state.pending).toBe(true);
    expect(component.cancellationInProgress).toBe(true);
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'The canonical completed answer.',
      continuationRequired: false,
      files: [{
        fileId: 'file-ws', format: 'csv', filename: 'websocket.csv',
        mediaType: 'text/csv', size: 24, sha256: 'ws-sha256',
        downloadUrl: '/api/ai/chat/conversations/conversation-1/files/file-ws'
      }]
    });

    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'assistant', content: 'The canonical completed answer.',
      files: [expect.objectContaining({fileId: 'file-ws', filename: 'websocket.csv'})]
    }));
    expect(component.state.pending).toBe(false);
    vi.advanceTimersByTime(COMPLETED_PAYLOAD_WAIT_MS);
    expect(api.getConversation).not.toHaveBeenCalled();
  });

  it('preserves an attachment response arriving after current-status COMPLETED', () => {
    vi.useFakeTimers();
    const chat = new Subject<AiChatRestResponse>();
    const cancellation = new Subject<AiCancellationResponse>();
    api.sendChat.mockReturnValueOnce(chat);
    api.cancelRequest.mockReturnValueOnce(cancellation);
    component.state.prompt = 'Preserve the completed attachment answer';
    component.state.attachments = [{
      name: 'sample.txt', mediaType: 'text/plain', size: 4, data: 'test'
    }];
    component.send();
    component.cancelActiveRequest();
    cancellation.next(completedCancellationResponse());

    chat.next({
      response: 'The canonical attachment answer.',
      conversationId: 'conversation-1',
      files: [{
        fileId: 'file-rest', format: 'xlsx', filename: 'response.xlsx',
        mediaType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        size: 48, sha256: 'rest-sha256',
        downloadUrl: '/api/ai/chat/conversations/conversation-1/files/file-rest'
      }]
    });

    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'assistant', content: 'The canonical attachment answer.',
      files: [expect.objectContaining({fileId: 'file-rest', filename: 'response.xlsx'})]
    }));
    expect(component.state.pending).toBe(false);
    vi.advanceTimersByTime(COMPLETED_PAYLOAD_WAIT_MS);
    expect(api.getConversation).not.toHaveBeenCalled();
  });

  it('recovers a completed answer from exact conversation details after bounded wait', () => {
    vi.useFakeTimers();
    const cancellation = new Subject<AiCancellationResponse>();
    const conversation = new Subject<AiChatConversationDetails>();
    api.cancelRequest.mockReturnValueOnce(cancellation);
    api.getConversation.mockReturnValueOnce(conversation);
    component.state.prompt = 'Recover a persisted answer';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    component.state.changeApprovalBatch = {
      batchId: 'batch-completed', requestId: 'request-1', conversationId: 'conversation-1',
      parallel: false, expiresAt: '2099-07-15T00:00:00Z', items: [{
        confirmationRequestId: 'confirmation-completed', toolName: 'update_bbie',
        argumentsSummary: '{"id":1}'
      }]
    };
    component.state.changeApprovalBatchBusy = true;
    component.cancelActiveRequest();
    cancellation.next(completedCancellationResponse());

    vi.advanceTimersByTime(COMPLETED_PAYLOAD_WAIT_MS);
    expect(api.getConversation).toHaveBeenCalledWith('conversation-1');
    conversation.next({
      conversationId: 'conversation-1', title: 'Recovered',
      messages: [
        {index: 0, role: 'user', content: 'Recover a persisted answer'},
        {index: 1, role: 'assistant', content: 'Recovered persisted answer.'}
      ]
    });

    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'assistant', content: 'Recovered persisted answer.'
    }));
    expect(component.state.pending).toBe(false);
    expect(component.state.changeApprovalBatch).toBeUndefined();
    expect(component.state.changeApprovalBatchQueue).toEqual([]);
    expect(component.state.changeApprovalBatchBusy).toBe(false);
    expect(component.commandInputBlocked).toBe(false);
  });

  it('does not turn an attachment cancellation into a generic upload error', () => {
    vi.useFakeTimers();
    const chat = new Subject<any>();
    const cancellation = new Subject<AiCancellationResponse>();
    const status = new Subject<AiPublicExecutionRequestStatus>();
    api.sendChat.mockReturnValueOnce(chat);
    api.cancelRequest.mockReturnValueOnce(cancellation);
    api.getRequestStatus.mockReturnValueOnce(status);
    component.state.prompt = 'Cancel this attachment';
    component.state.attachments = [{
      name: 'sample.txt', mediaType: 'text/plain', size: 4, data: 'test'
    }];
    component.send();
    component.cancelActiveRequest();
    cancellation.next(cancellationResponse());

    chat.error(new Error('cancelled'));
    expect(component.state.pending).toBe(true);
    expect(component.state.messages).not.toContainEqual(expect.objectContaining({
      content: expect.stringContaining('attachment request could not be completed')
    }));

    vi.advanceTimersByTime(CANCELLATION_TERMINAL_TIMEOUT_MS);
    status.next(publicStatus('CANCELLED'));
    expect(component.state.pending).toBe(false);
  });

  it('shows a bounded server attachment validation message', () => {
    const chat = new Subject<AiChatRestResponse>();
    api.sendChat.mockReturnValueOnce(chat);
    component.state.prompt = 'Read this file';
    component.state.attachments = [{
      name: 'sample.bin', mediaType: 'application/octet-stream', size: 4, data: 'test'
    }];
    component.send();

    chat.error(new HttpErrorResponse({
      status: 400,
      headers: new HttpHeaders({
        'X-Error-Message': 'Unsupported AI attachment type: application/octet-stream'
      })
    }));

    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'error', content: 'Unsupported AI attachment type: application/octet-stream'
    }));
  });

  it('recovers a strict change notice from a timed-out REST error', () => {
    const chat = new Subject<AiChatRestResponse>();
    api.sendChat.mockReturnValueOnce(chat);
    component.state.conversationId = 'conversation-1';
    component.state.prompt = 'Change the attached record';
    component.state.attachments = [{
      name: 'sample.txt', mediaType: 'text/plain', size: 4, data: 'test'
    }];
    component.send();

    chat.error(new HttpErrorResponse({
      status: 408,
      error: {events: [changeConfirmationEvent('request-1')]}
    }));

    expect(component.changeInteraction).toMatchObject({
      mode: 'confirm', toolName: 'create_business_context'
    });
    expect(component.state.pending).toBe(false);
    expect((component as any).changeRepeatDraft).toBeUndefined();
  });

  it('does not expose an arbitrary server error through the attachment message header', () => {
    const message = (component as any).attachmentFailureMessage(new HttpErrorResponse({
      status: 500,
      headers: new HttpHeaders({
        'X-Error-Message': 'Attachment database password: internal-secret'
      })
    }));

    expect(message).toBe(
      'The attachment request could not be completed. Check the backend log for details.'
    );
  });

  it.each([
    'Encoded attachment exceeds the 8 MB per-file limit: attachment',
    'Attachment is not valid Base64: attachment',
    'Attachment exceeds the 8 MB per-file limit: attachment',
    'Attachments exceed the 20 MB request limit.',
    'Unsupported AI attachment type: application/zip',
    'Could not encode attachment data.',
    'A prompt or attachment is required.',
    'A maximum of 10 attachments is allowed per request.'
  ])('accepts the exact safe server attachment error contract: %s', message => {
    const result = (component as any).attachmentFailureMessage(new HttpErrorResponse({
      status: 400,
      headers: new HttpHeaders({'X-Error-Message': message})
    }));

    expect(result).toBe(message);
  });

  it('completes twenty published Stop handshakes without a stuck request', () => {
    vi.useFakeTimers();
    let requestNumber = 0;
    let cancellationNumber = 0;
    ((component as any).createRequestId as ReturnType<typeof vi.fn>)
      .mockImplementation(() => `request-${++requestNumber}`);
    ((cancellationService as any).createCancellationRequestId as ReturnType<typeof vi.fn>)
      .mockImplementation(() => `cancel-${++cancellationNumber}`);

    for (let iteration = 1; iteration <= 20; iteration++) {
      component.state.prompt = `Request ${iteration}`;
      component.send();
      transport.publishWhenConnected.mock.calls[iteration - 1][0].publish();
      component.cancelActiveRequest();
      const command = api.cancelRequest.mock.calls[iteration - 1][1];
      expect(command.cancellationRequestId).toBe(`cancel-${iteration}`);
      (component as any).handleSocketEvent({
        requestId: `request-${iteration}`,
        type: 'system',
        subtype: 'cancelled'
      });
      expect(component.state.pending).toBe(false);
    }

    expect(api.cancelRequest).toHaveBeenCalledTimes(20);
    expect(component.state.cancellation.phase).toBe('idle');
  });

  it('recovers a terminal backend failure when its final WebSocket event is lost', () => {
    vi.useFakeTimers();
    api.getRequestStatus.mockReturnValueOnce(of({
      ...publicStatus('FAILED'), cancellationRequestId: undefined,
      cancellationAcknowledgedAt: undefined
    }));
    api.getConversation.mockReturnValueOnce(of({
      conversationId: 'conversation-1', title: 'Failed request',
      messages: [
        {index: 0, role: 'user', content: 'Run a tool'},
        {index: 1, role: 'error', content: 'The assistant request failed.'}
      ]
    }));
    component.state.prompt = 'Run a tool';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'accepted', content: 'Request received.',
      metadata: {generation: 7, deadline: '2099-07-14T13:05:00Z'}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'completed', groupId: 'request-1', toolCallId: 'call-1',
      content: 'create_business_context completed.',
      metadata: {toolName: 'create_business_context', toolCallSeq: 0,
        statusMessage: 'create_business_context completed.'}
    });

    vi.advanceTimersByTime(REQUEST_STATUS_WATCHDOG_MS);

    expect(api.getRequestStatus).toHaveBeenCalledWith('request-1', 'conversation-1', 7);
    expect(component.state.pending).toBe(false);
    expect(component.state.activeRequest).toBeUndefined();
    expect(component.state.currentStatus).toBe('FAILED');
    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'error', content: 'The assistant request failed.'
    }));
  });

  it('shows bounded reconnect progress and ends in the assistant after three failures', async () => {
    vi.useFakeTimers();
    api.getRequestStatus.mockReturnValueOnce(
      throwError(() => new HttpErrorResponse({status: 500}))
    );
    transport.reconnectOnce.mockReturnValue(
      throwError(() => new Error('backend unavailable'))
    );
    component.state.prompt = 'Keep this request visible';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'accepted', content: 'Request received.',
      metadata: {generation: 7, deadline: '2099-07-14T13:05:00Z'}
    });

    vi.advanceTimersByTime(REQUEST_STATUS_WATCHDOG_MS);
    expect(component.state.messages.at(-1)).toEqual(expect.objectContaining({
      role: 'progress', content: 'Reconnecting... (1/3)', inProgress: true
    }));
    await vi.advanceTimersByTimeAsync(ACTIVE_REQUEST_RECOVERY_RETRY_DELAY_MS);
    expect(component.state.messages.at(-1)).toEqual(expect.objectContaining({
      role: 'progress', content: 'Reconnecting... (2/3)', inProgress: true
    }));
    await vi.advanceTimersByTimeAsync(ACTIVE_REQUEST_RECOVERY_RETRY_DELAY_MS);

    expect(transport.reconnectOnce).toHaveBeenCalledTimes(3);
    expect(transport.publish).toHaveBeenCalledTimes(1);
    expect(component.state.pending).toBe(false);
    expect(component.state.reconciliationRequired).toBe(true);
    expect(component.state.currentStatus).toBe('Connection lost');
    expect(component.state.messages.at(-1)).toEqual(expect.objectContaining({
      role: 'error', content: expect.stringContaining('after 3 attempts')
    }));
    expect(component.state.messages.some(message =>
      message.role === 'progress' && message.inProgress
    )).toBe(false);
  });

  it('stops waiting for a request the backend keeps reporting past its deadline', () => {
    vi.useFakeTimers();
    api.getRequestStatus.mockReturnValueOnce(of(publicStatus('RUNNING')));
    component.state.prompt = 'Compare the two addresses';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'accepted', content: 'Request received.',
      metadata: {generation: 7, deadline: '2026-07-14T13:05:00Z'}
    });

    vi.advanceTimersByTime(REQUEST_STATUS_WATCHDOG_MS);

    expect(api.getRequestStatus).toHaveBeenCalledTimes(1);
    expect(component.state.pending).toBe(false);
    expect(component.state.reconciliationRequired).toBe(true);
    expect(component.state.currentStatus).toBe('Outcome unknown');
    expect(component.state.messages.at(-1)).toEqual(expect.objectContaining({
      role: 'error', content: expect.stringContaining('after its deadline')
    }));
    expect(component.state.messages.some(message =>
      message.role === 'progress' && message.inProgress
    )).toBe(false);
  });

  it('keeps polling a request that is still inside its deadline', () => {
    vi.useFakeTimers();
    api.getRequestStatus.mockReturnValue(of({
      ...publicStatus('RUNNING'), deadline: '2099-07-14T13:05:00Z'
    }));
    component.state.prompt = 'Compare the two addresses';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'accepted', content: 'Request received.',
      metadata: {generation: 7, deadline: '2099-07-14T13:05:00Z'}
    });

    vi.advanceTimersByTime(REQUEST_STATUS_WATCHDOG_MS * 2);

    expect(api.getRequestStatus).toHaveBeenCalledTimes(2);
    expect(component.state.pending).toBe(true);
  });

  it('resumes the accepted request without publishing it again after reconnect', async () => {
    vi.useFakeTimers();
    api.getRequestStatus
      .mockReturnValueOnce(throwError(() => new HttpErrorResponse({status: 500})))
      .mockReturnValueOnce(of(publicStatus('RUNNING')));
    transport.reconnectOnce
      .mockReturnValueOnce(throwError(() => new Error('backend unavailable')))
      .mockReturnValueOnce(of(undefined));
    component.state.prompt = 'Do not duplicate this request';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'accepted', content: 'Request received.',
      metadata: {generation: 7, deadline: '2099-07-14T13:05:00Z'}
    });

    vi.advanceTimersByTime(REQUEST_STATUS_WATCHDOG_MS);
    await vi.advanceTimersByTimeAsync(ACTIVE_REQUEST_RECOVERY_RETRY_DELAY_MS);

    expect(api.getRequestStatus).toHaveBeenCalledTimes(2);
    expect(transport.publish).toHaveBeenCalledTimes(1);
    expect(component.state.pending).toBe(true);
    expect(component.state.currentStatus).toBe('Request in progress');
    expect(component.state.messages.at(-1)).toEqual(expect.objectContaining({
      role: 'progress', content: 'Reconnected. Waiting for the assistant response.'
    }));
  });

  it('renders a backend restart terminal status as an assistant error', () => {
    vi.useFakeTimers();
    api.getRequestStatus
      .mockReturnValueOnce(throwError(() => new HttpErrorResponse({status: 500})))
      .mockReturnValueOnce(of({
        ...publicStatus('FAILED'), statusReason: 'WORKER_INSTANCE_SHUTDOWN'
      }));
    transport.reconnectOnce.mockReturnValueOnce(of(undefined));
    api.getConversation.mockReturnValueOnce(of({
      conversationId: 'conversation-1', title: 'Interrupted request',
      messages: [{index: 0, role: 'user', content: 'Long-running request'}]
    }));
    component.state.prompt = 'Long-running request';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'accepted', content: 'Request received.',
      metadata: {generation: 7, deadline: '2099-07-14T13:05:00Z'}
    });

    vi.advanceTimersByTime(REQUEST_STATUS_WATCHDOG_MS);

    expect(component.state.pending).toBe(false);
    expect(component.state.currentStatus).toBe('FAILED');
    expect(component.state.messages.at(-1)).toEqual({
      role: 'error', content: 'The assistant request stopped because the backend restarted.'
    });
  });

  it('shares one three-attempt budget with terminal conversation restoration', async () => {
    vi.useFakeTimers();
    const terminalStatus = {
      ...publicStatus('FAILED'), statusReason: 'WORKER_INSTANCE_SHUTDOWN'
    };
    api.getRequestStatus
      .mockReturnValueOnce(throwError(() => new HttpErrorResponse({status: 500})))
      .mockReturnValue(of(terminalStatus));
    api.getConversation.mockReturnValue(
      throwError(() => new HttpErrorResponse({status: 503}))
    );
    transport.reconnectOnce.mockReturnValue(of(undefined));
    component.state.prompt = 'Restore the terminal result once';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'accepted', content: 'Request received.',
      metadata: {generation: 7, deadline: '2099-07-14T13:05:00Z'}
    });

    vi.advanceTimersByTime(REQUEST_STATUS_WATCHDOG_MS);
    await vi.advanceTimersByTimeAsync(ACTIVE_REQUEST_RECOVERY_RETRY_DELAY_MS * 2);

    expect(transport.reconnectOnce).toHaveBeenCalledTimes(3);
    expect(api.getConversation).toHaveBeenCalledTimes(3);
    expect(transport.publish).toHaveBeenCalledTimes(1);
    expect(component.state.pending).toBe(false);
    expect(component.state.messages.at(-1)).toEqual({
      role: 'error', content: 'The assistant request stopped because the backend restarted.'
    });
    expect(component.state.messages.some(message =>
      message.role === 'progress' && message.inProgress
    )).toBe(false);
  });

  it('cancels an in-flight reconnect when the panel is destroyed', () => {
    vi.useFakeTimers();
    const reconnect = new Subject<void>();
    api.getRequestStatus.mockReturnValueOnce(
      throwError(() => new HttpErrorResponse({status: 500}))
    );
    transport.reconnectOnce.mockReturnValueOnce(reconnect);
    component.state.prompt = 'Do not update a destroyed panel';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'accepted', content: 'Request received.',
      metadata: {generation: 7, deadline: '2099-07-14T13:05:00Z'}
    });

    vi.advanceTimersByTime(REQUEST_STATUS_WATCHDOG_MS);
    destroyComponent();
    reconnect.next();
    reconnect.complete();

    expect(api.getRequestStatus).toHaveBeenCalledTimes(1);
    expect(component.state.messages).not.toContainEqual(expect.objectContaining({
      content: 'Reconnected. Waiting for the assistant response.'
    }));
  });

});
