import {
  api,
  component,
  finishChangeConfirmationRequest,
  of,
  Subject,
  sendChangeConfirmationNotice,
  setupAiChatPanelSpec,
  snackBar,
  startChangeConfirmation,
  storageText,
  teardownAiChatPanelSpec,
  transport
} from './ai-chat-panel.component.spec-support';

describe('AiChatPanelComponent elicitation and change interaction', () => {
  beforeEach(setupAiChatPanelSpec);
  afterEach(teardownAiChatPanelSpec);

  it('shows an MCP elicitation inline and sends the structured choice back on the same request', () => {
    startElicitation();

    expect(component.state.elicitation).toMatchObject({
      elicitationId: 'elicitation-1', message: 'Choose an import strategy.'
    });
    expect(component.state.currentStatus).toBe('Waiting for your input');

    component.respondToElicitation({
      action: 'ACCEPT', content: {strategy: 'merge'}
    });

    expect(transport.publish).toHaveBeenLastCalledWith('/app/ai/chat/elicitation', {
      requestId: 'request-1', conversationId: 'conversation-1',
      elicitationId: 'elicitation-1', generation: 7, action: 'ACCEPT',
      content: {strategy: 'merge'}
    });
    expect(component.state.elicitationBusy).toBe(true);

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'elicitation_decision_accepted',
      metadata: {elicitationId: 'elicitation-1'}
    });
    expect(component.state.elicitation).toBeUndefined();
    expect(component.state.pending).toBe(true);
  });

  it('ignores a delayed elicitation from an older generation of a reused request ID', () => {
    startElicitation(7, 8);

    expect(component.state.activeRequest?.generation).toBe(8);
    expect(component.state.elicitation).toBeUndefined();
  });

  it('sends one complete decision for a parallel change batch and resumes the active request', () => {
    startApprovalBatch(true);

    expect(component.state.changeApprovalBatch).toMatchObject({
      batchId: 'batch-1', parallel: true
    });
    expect(component.state.changeApprovalBatch?.items).toHaveLength(2);
    expect(component.state.currentStatus).toBe('2 approvals required');
    expect(component.state.messages.at(-1)).toEqual({
      role: 'guide', content: 'Approval requested for 2 changes.'
    });

    component.decideChangeApprovalBatch('APPROVE');

    expect(transport.publish).toHaveBeenLastCalledWith('/app/ai/chat/change-approval', {
      requestId: 'request-1', conversationId: 'conversation-1', batchId: 'batch-1',
      decisions: [
        {confirmationRequestId: 'confirmation-1', decision: 'APPROVE'},
        {confirmationRequestId: 'confirmation-2', decision: 'APPROVE'}
      ]
    });
    expect(component.state.changeApprovalBatchBusy).toBe(true);

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
      subtype: 'change_approval_decision_accepted',
      content: 'Approved 2 changes and denied 0. Continuing the active request.',
      metadata: {batchId: 'batch-1'}
    });

    expect(component.state.changeApprovalBatch).toBeUndefined();
    expect(component.state.pending).toBe(true);
    expect(component.state.messages.filter(message => message.role === 'user')).toHaveLength(1);
    expect(component.state.messages.at(-1)).toEqual({
      role: 'guide',
      content: 'Approved 2 changes and denied 0. Continuing the active request.'
    });

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1', type: 'assistant_final',
      content: 'The approved parallel work completed.'
    });
    const approvalIndex = component.state.messages.findIndex(message =>
      message.role === 'guide' && message.content.startsWith('Approved 2 changes'));
    const finalIndex = component.state.messages.findIndex(message =>
      message.role === 'assistant' && message.content === 'The approved parallel work completed.');
    expect(approvalIndex).toBeGreaterThanOrEqual(0);
    expect(finalIndex).toBeGreaterThan(approvalIndex);
  });

  it('completes an attachment-backed HTTP chat through a live approval batch', () => {
    const live = new Subject<{body: string}>();
    const response = new Subject<{
      response: string;
      conversationId: string;
      events: any[];
    }>();
    transport.watch.mockReturnValueOnce(live);
    api.sendChat.mockReturnValueOnce(response);
    component.state.conversationId = 'conversation-1';
    component.state.prompt = 'Update the item described by this attachment';
    component.state.attachments = [{
      name: 'change.txt', mediaType: 'text/plain', size: 6, data: 'change'
    }];
    component.send();
    const batchEvent = {
      requestId: 'request-1', conversationId: 'conversation-1', sequence: 1,
      type: 'system', subtype: 'change_approval_batch_required', visibility: 'visible',
      content: 'Approval required.', metadata: {
        batchId: 'batch-http', parallel: false, expiresAt: '2099-07-15T00:00:00Z',
        items: [{
          confirmationRequestId: 'confirmation-http', toolName: 'update_bbie',
          argumentsSummary: '{"id":1}', agentId: 'agent-http', agentLabel: 'HTTP Agent'
        }]
      }
    };
    live.next({body: JSON.stringify(batchEvent)});

    expect(component.state.changeApprovalBatch).toMatchObject({batchId: 'batch-http'});
    component.decideChangeApprovalBatch('APPROVE');
    expect(transport.publish).toHaveBeenLastCalledWith('/app/ai/chat/change-approval', {
      requestId: 'request-1', conversationId: 'conversation-1', batchId: 'batch-http',
      decisions: [{confirmationRequestId: 'confirmation-http', decision: 'APPROVE'}]
    });

    live.next({body: JSON.stringify({
      requestId: 'request-1', conversationId: 'conversation-1', sequence: 2,
      type: 'system', subtype: 'change_approval_decision_rejected',
      content: 'The approval state changed. Please decide again.',
      metadata: {batchId: 'batch-http'}
    })});
    expect(component.state.changeApprovalBatchBusy).toBe(false);
    expect(component.state.changeApprovalBatch).toMatchObject({batchId: 'batch-http'});
    component.decideChangeApprovalBatch('APPROVE');
    expect(transport.publish).toHaveBeenCalledTimes(2);

    const acceptedEvent = {
      requestId: 'request-1', conversationId: 'conversation-1', sequence: 3,
      type: 'system', subtype: 'change_approval_decision_accepted',
      content: 'Approved 1 change and denied 0. Continuing the active request.',
      metadata: {batchId: 'batch-http'}
    };
    live.next({body: JSON.stringify(acceptedEvent)});
    expect(component.state.changeApprovalBatch).toBeUndefined();
    expect(component.state.pending).toBe(true);

    response.next({
      response: 'Attachment-backed update completed.', conversationId: 'conversation-1',
      events: [batchEvent, acceptedEvent]
    });

    expect(component.state.pending).toBe(false);
    expect(component.state.messages.filter(message => message.role === 'user')).toHaveLength(1);
    expect(component.state.messages.filter(message =>
      message.role === 'guide' && message.content.startsWith('Approval requested'))).toHaveLength(1);
    expect(component.state.messages.filter(message =>
      message.role === 'guide' && message.content.startsWith('Approved 1 change'))).toHaveLength(1);
    expect(component.state.messages.at(-1)).toEqual({
      role: 'assistant', content: 'Attachment-backed update completed.'
    });
  });

  it('queues a second nested parallel approval batch until the displayed batch is decided', () => {
    startApprovalBatch(true);
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
      subtype: 'change_approval_batch_required', content: 'Approval required.',
      metadata: {
        batchId: 'batch-2', parallel: true, expiresAt: '2099-07-15T00:00:00Z',
        items: [{
          confirmationRequestId: 'confirmation-3', toolName: 'update_asbie',
          argumentsSummary: '{"id":3}', agentId: 'agent-c', agentLabel: 'Agent C'
        }]
      }
    });

    expect(component.state.changeApprovalBatch?.batchId).toBe('batch-1');
    expect(component.state.changeApprovalBatchQueue).toHaveLength(1);

    component.decideChangeApprovalBatch('APPROVE');
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
      subtype: 'change_approval_decision_accepted', content: 'First batch approved.',
      metadata: {batchId: 'batch-1'}
    });

    expect(component.state.changeApprovalBatch?.batchId).toBe('batch-2');
    expect(component.state.changeApprovalBatchQueue).toHaveLength(0);
    expect(component.state.currentStatus).toBe('Approval required');
    component.decideChangeApprovalBatch('DENY');
    expect(transport.publish).toHaveBeenLastCalledWith('/app/ai/chat/change-approval', {
      requestId: 'request-1', conversationId: 'conversation-1', batchId: 'batch-2',
      decisions: [{confirmationRequestId: 'confirmation-3', decision: 'DENY'}]
    });
  });

  it('reports a queued approval that expires before the preceding batch is decided', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-07-21T12:00:00Z'));
    startApprovalBatch(true);
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
      subtype: 'change_approval_batch_required', content: 'Approval required.',
      metadata: {
        batchId: 'batch-expiring', parallel: false,
        expiresAt: '2026-07-21T12:00:01Z', items: [{
          confirmationRequestId: 'confirmation-expiring', toolName: 'update_asbie',
          argumentsSummary: '{"id":3}'
        }]
      }
    });
    vi.advanceTimersByTime(1_001);

    component.decideChangeApprovalBatch('APPROVE');
    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
      subtype: 'change_approval_decision_accepted', content: 'First batch approved.',
      metadata: {batchId: 'batch-1'}
    });

    expect(component.state.changeApprovalBatch).toBeUndefined();
    expect(component.state.messages).toContainEqual({
      role: 'error',
      content: 'A queued approval request expired before it could be shown.'
    });
  });

  it('expires a displayed approval automatically and explains why it was cleared', () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date('2026-07-21T12:00:00Z'));
      component.state.pending = true;
      component.state.conversationId = 'conversation-1';
      component.state.activeRequest = {requestId: 'request-1'};
      (component as any).handleSocketEvent({
        requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
        subtype: 'change_approval_batch_required', content: 'Approval required.',
        metadata: {
          batchId: 'expiring-batch', parallel: false,
          expiresAt: '2026-07-21T12:00:01Z',
          items: [{
            confirmationRequestId: 'confirmation-expiring', toolName: 'update_bbie',
            argumentsSummary: '{"id":1}'
          }]
        }
      });

      vi.advanceTimersByTime(1_001);

      expect(component.state.changeApprovalBatch).toBeUndefined();
      expect(component.state.changeApprovalBatchBusy).toBe(false);
      expect(component.state.currentStatus).toBe('Approval expired');
      expect(component.state.messages.at(-1)).toEqual({
        role: 'error', content: 'This approval request expired before a decision was sent.'
      });
    } finally {
      vi.useRealTimers();
    }
  });

  it('makes a decision retryable when its acknowledgement is lost', () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date('2026-07-21T12:00:00Z'));
      startApprovalBatch(false, 1);
      (component as any).handleSocketEvent({
        requestId: 'request-1', conversationId: 'conversation-1',
        type: 'system', subtype: 'accepted',
        metadata: {generation: 1, deadline: '2099-07-15T00:00:00Z'}
      });
      component.decideChangeApprovalBatch('APPROVE');

      expect(component.state.changeApprovalBatchBusy).toBe(true);
      vi.advanceTimersByTime(15_001);

      expect(component.state.changeApprovalBatch?.batchId).toBe('batch-1');
      expect(component.state.changeApprovalBatchBusy).toBe(false);
      expect(component.state.currentStatus).toBe('Approval required');
      expect(component.state.messages.at(-1)).toEqual({
        role: 'error',
        content: 'No acknowledgement was received. You can retry the approval decision.'
      });

      component.decideChangeApprovalBatch('APPROVE');
      expect(transport.publish.mock.calls.filter(call =>
        call[0] === '/app/ai/chat/change-approval')).toHaveLength(2);
      (component as any).handleSocketEvent({
        requestId: 'request-1', conversationId: 'conversation-1',
        type: 'system', subtype: 'change_approval_decision_accepted',
        content: 'Approved 1 change and denied 0. Continuing the active request.',
        metadata: {batchId: 'batch-1', replayed: true}
      });
      expect(component.state.changeApprovalBatch).toBeUndefined();
      expect(component.state.changeApprovalBatchBusy).toBe(false);
      expect(component.state.currentStatus).toBe('Working');
    } finally {
      vi.useRealTimers();
    }
  });

  it('disables and fences an approval as soon as Stop is requested', () => {
    startApprovalBatch(false, 1);

    component.cancelActiveRequest();
    component.decideChangeApprovalBatch('APPROVE');

    expect(component.state.cancellation.phase).not.toBe('idle');
    expect(component.changeApprovalControlsBusy).toBe(true);
    expect((component as any).changeApprovalExpiryTimeout).toBeDefined();
    expect(transport.publish).not.toHaveBeenCalledWith(
      '/app/ai/chat/change-approval', expect.anything()
    );
  });

  it.each([
    ['completion', {
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'Done.'
    }],
    ['failure', {requestId: 'request-1', type: 'system', subtype: 'error', content: 'Failed.'}],
    ['cancellation', {
      requestId: 'request-1', type: 'system', subtype: 'cancelled', content: 'Cancelled.'
    }]
  ])('clears active and queued approvals on request %s', (_label, terminalEvent) => {
    startApprovalBatch(true);
    component.state.changeApprovalBatchQueue.push({
      batchId: 'batch-2', requestId: 'request-1', conversationId: 'conversation-1',
      parallel: true, expiresAt: '2099-07-15T00:00:00Z', items: [{
        confirmationRequestId: 'confirmation-3', toolName: 'update_asbie',
        argumentsSummary: '{"id":3}'
      }]
    });
    component.state.changeApprovalBatchBusy = true;

    (component as any).handleSocketEvent(terminalEvent);

    expect(component.state.changeApprovalBatch).toBeUndefined();
    expect(component.state.changeApprovalBatchQueue).toEqual([]);
    expect(component.state.changeApprovalBatchBusy).toBe(false);
  });

  it('clears approval state when a new-chat reset occurs', () => {
    component.state.changeApprovalBatch = {
      batchId: 'batch-1', requestId: 'request-1', conversationId: 'conversation-1',
      parallel: false, expiresAt: '2099-07-15T00:00:00Z', items: [{
        confirmationRequestId: 'confirmation-1', toolName: 'update_bbie',
        argumentsSummary: '{}'
      }]
    };
    component.state.changeApprovalBatchQueue = [component.state.changeApprovalBatch];
    component.state.changeApprovalBatchBusy = true;

    component.state.resetForNewChat();

    expect(component.state.changeApprovalBatch).toBeUndefined();
    expect(component.state.changeApprovalBatchQueue).toEqual([]);
    expect(component.state.changeApprovalBatchBusy).toBe(false);
  });

  it('keeps an individual sub-agent approval separate and retryable after rejection', () => {
    startApprovalBatch(false, 1);
    component.decideChangeApprovalBatch('DENY');

    expect(transport.publish).toHaveBeenLastCalledWith('/app/ai/chat/change-approval', {
      requestId: 'request-1', conversationId: 'conversation-1', batchId: 'batch-1',
      decisions: [{confirmationRequestId: 'confirmation-1', decision: 'DENY'}]
    });

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
      subtype: 'change_approval_decision_rejected',
      content: 'The batch changed. Please decide again.', metadata: {batchId: 'batch-1'}
    });

    expect(component.state.changeApprovalBatch).toMatchObject({
      batchId: 'batch-1', parallel: false
    });
    expect(component.state.changeApprovalBatchBusy).toBe(false);
    expect(component.state.messages.at(-1)).toEqual({
      role: 'error', content: 'The batch changed. Please decide again.'
    });
  });

  it('shows a rejected elicitation response as a retryable chat error', () => {
    startElicitation();
    component.respondToElicitation({
      action: 'ACCEPT', content: {strategy: 'merge'}
    });

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'elicitation_decision_rejected',
      content: '  That import strategy is no longer available.  ',
      metadata: {elicitationId: 'elicitation-1'}
    });

    expect(component.state.elicitation).toMatchObject({elicitationId: 'elicitation-1'});
    expect(component.state.elicitationBusy).toBe(false);
    expect(component.state.currentStatus).toBe('Waiting for your input');
    expect(component.state.messages.at(-1)).toEqual({
      role: 'error', content: 'That import strategy is no longer available.'
    });
    expect(snackBar.open).not.toHaveBeenCalled();

    component.respondToElicitation({
      action: 'ACCEPT', content: {strategy: 'replace'}
    });
    expect(transport.publish).toHaveBeenLastCalledWith('/app/ai/chat/elicitation', {
      requestId: 'request-1', conversationId: 'conversation-1',
      elicitationId: 'elicitation-1', generation: 7, action: 'ACCEPT',
      content: {strategy: 'replace'}
    });
    expect(component.state.elicitationBusy).toBe(true);
  });

  it.each([
    ['no message', undefined],
    ['only whitespace', '   ']
  ])('uses the standard chat error when an elicitation rejection has %s', (_label, content) => {
    startElicitation();

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'elicitation_decision_rejected',
      content,
      metadata: {elicitationId: 'elicitation-1'}
    });

    expect(component.state.messages.at(-1)).toEqual({
      role: 'error',
      content: 'The assistant could not accept that response. Please try again.'
    });
    expect(snackBar.open).not.toHaveBeenCalled();
  });

  it('ignores an elicitation rejection for a different interaction', () => {
    startElicitation();
    component.respondToElicitation({
      action: 'ACCEPT', content: {strategy: 'merge'}
    });
    const messagesBeforeStaleEvent = [...component.state.messages];

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'elicitation_decision_rejected',
      content: 'A stale failure.',
      metadata: {elicitationId: 'different-elicitation'}
    });

    expect(component.state.elicitation).toMatchObject({elicitationId: 'elicitation-1'});
    expect(component.state.elicitationBusy).toBe(true);
    expect(component.state.currentStatus).toBe('Sending your response');
    expect(component.state.messages).toEqual(messagesBeforeStaleEvent);
    expect(snackBar.open).not.toHaveBeenCalled();
  });

  it('shows a synchronous elicitation publish failure as a retryable chat error', () => {
    startElicitation();
    transport.publish.mockImplementationOnce(() => {
      throw new Error('WebSocket publish failed');
    });

    component.respondToElicitation({
      action: 'ACCEPT', content: {strategy: 'merge'}
    });

    expect(component.state.elicitation).toMatchObject({elicitationId: 'elicitation-1'});
    expect(component.state.elicitationBusy).toBe(false);
    expect(component.state.currentStatus).toBe('Waiting for your input');
    expect(component.state.messages.at(-1)).toEqual({
      role: 'error', content: 'Could not send your response.'
    });
    expect(snackBar.open).not.toHaveBeenCalled();

    component.respondToElicitation({
      action: 'ACCEPT', content: {strategy: 'replace'}
    });
    expect(transport.publish).toHaveBeenLastCalledWith('/app/ai/chat/elicitation', {
      requestId: 'request-1', conversationId: 'conversation-1',
      elicitationId: 'elicitation-1', generation: 7, action: 'ACCEPT',
      content: {strategy: 'replace'}
    });
    expect(component.state.elicitationBusy).toBe(true);
  });

  it('waits for the original terminal event, then publishes one confirmed repeat without leaking its grant', () => {
    const canonicalGrant = 'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA';
    const createRequestId = (component as any).createRequestId as ReturnType<typeof vi.fn>;
    createRequestId.mockReturnValueOnce('request-1').mockReturnValueOnce('request-2');
    api.decideChangeConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'APPROVED',
      disposition: 'APPROVED',
      expiresAt: '2099-07-15T00:00:00Z',
      approvedAt: '2099-07-14T00:30:00Z',
      confirmationGrant: canonicalGrant
    }));
    const log = vi.spyOn(console, 'log').mockImplementation(() => undefined);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    startChangeConfirmation();

    expect(component.changeInteraction).toBeUndefined();
    const composerFocus = vi.fn();
    component.composer = {focus: composerFocus, resize: vi.fn()} as any;
    finishChangeConfirmationRequest();
    expect(component.state.pending).toBe(false);
    expect(component.changeInteraction).toEqual({
      mode: 'confirm', busy: false,
      toolName: 'create_business_context', argumentsSummary: '{"name":"Example"}'
    });
    expect(api.decideChangeConfirmation).not.toHaveBeenCalled();
    expect(composerFocus).not.toHaveBeenCalled();

    component.approveChangeInteraction();

    expect(api.decideChangeConfirmation).toHaveBeenCalledOnce();
    expect(api.decideChangeConfirmation).toHaveBeenCalledWith(
      'conversation-1', 'confirmation-1', 'APPROVE'
    );
    expect(api.sendChat).not.toHaveBeenCalled();
    expect(transport.publishWhenConnected).toHaveBeenCalledTimes(2);
    const confirmedPublish = transport.publishWhenConnected.mock.calls[1][0];
    confirmedPublish.publish();
    expect(transport.publish).toHaveBeenCalledWith('/app/ai/chat', expect.objectContaining({
      requestId: 'request-2', prompt: 'Create the same item again',
      conversationId: 'conversation-1', attachments: [],
      changeConfirmation: {
        confirmationRequestId: 'confirmation-1', confirmationGrant: canonicalGrant,
        toolName: 'create_business_context', arguments: '{"name":"Example"}'
      }
    }));
    expect(transport.publish.mock.calls.at(-1)?.[1]).not.toHaveProperty('multiAgent');
    expect(JSON.stringify(component.state)).not.toContain(canonicalGrant);
    expect((component as any).changeRepeatDraft).toEqual({
      requestId: 'request-2', prompt: 'Create the same item again', attachments: []
    });
    expect((component as any).pendingChangeConfirmation).toBeUndefined();
    expect((component as any).requestSubscription.closed).toBe(false);
    const componentFields = component as unknown as Record<string, unknown>;
    expect(Object.keys(componentFields).filter(key => /grant/i.test(key))).toEqual([]);
    expect(Object.values(componentFields)
      .filter(value => typeof value === 'string')).not.toContain(canonicalGrant);
    expect(storageText(localStorage) + storageText(sessionStorage)).not.toContain(canonicalGrant);
    expect(JSON.stringify([...log.mock.calls, ...warn.mock.calls, ...error.mock.calls]))
      .not.toContain(canonicalGrant);
    log.mockRestore();
    warn.mockRestore();
    error.mockRestore();
  });

  it('binds an out-of-order safe notice for the first request to the matching accepted identity', () => {
    component.state.prompt = 'Create the same item again';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();

    expect(component.state.conversationId).toBeUndefined();
    sendChangeConfirmationNotice();
    expect((component as any).pendingChangeConfirmation).toMatchObject({
      conversationId: 'conversation-1'
    });

    (component as any).handleSocketEvent({
      requestId: 'request-1',
      conversationId: 'conversation-1',
      type: 'system',
      subtype: 'accepted',
      content: 'Request received.',
      metadata: {generation: 7, deadline: '2099-07-14T13:05:00Z'}
    });
    finishChangeConfirmationRequest();

    expect(component.state.conversationId).toBe('conversation-1');
    expect(component.state.reconciliationRequired).toBe(false);
    expect(component.changeInteraction?.mode).toBe('confirm');
  });

  it('invalidates an out-of-order notice when the accepted identity disagrees', () => {
    component.state.prompt = 'Create the same item again';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    sendChangeConfirmationNotice();

    (component as any).handleSocketEvent({
      requestId: 'request-1',
      conversationId: 'conversation-2',
      type: 'system',
      subtype: 'accepted',
      content: 'Request received.',
      metadata: {generation: 7, deadline: '2099-07-14T13:05:00Z'}
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1',
      conversationId: 'conversation-2',
      type: 'assistant_final',
      content: 'The admitted conversation completed.'
    });

    expect(component.state.conversationId).toBe('conversation-2');
    expect(component.state.reconciliationRequired).toBe(false);
    expect(component.changeInteraction).toBeUndefined();
    expect(api.decideChangeConfirmation).not.toHaveBeenCalled();
  });

  it('turns close or backdrop into one explicit DENY without a chat retry', () => {
    api.decideChangeConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'DENIED',
      disposition: 'DENIED',
      expiresAt: '2099-07-15T00:00:00Z',
      deniedAt: '2099-07-14T01:00:00Z'
    }));
    startChangeConfirmation();
    finishChangeConfirmationRequest();

    component.denyChangeInteraction();

    expect(api.decideChangeConfirmation).toHaveBeenCalledOnce();
    expect(api.decideChangeConfirmation).toHaveBeenCalledWith(
      'conversation-1', 'confirmation-1', 'DENY'
    );
    expect(api.sendChat).not.toHaveBeenCalled();
    expect(snackBar.open).not.toHaveBeenCalledWith(
      'The change denial response was not valid.',
      expect.anything(), expect.anything()
    );
  });

  it('approves the revised same-tool change and sends it without a second confirmation', () => {
    const canonicalGrant = 'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA';
    const createRequestId = (component as any).createRequestId as ReturnType<typeof vi.fn>;
    createRequestId.mockReturnValueOnce('request-1').mockReturnValueOnce('request-2');
    api.decideChangeConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'APPROVED',
      disposition: 'APPROVED',
      expiresAt: '2099-07-15T00:00:00Z',
      approvedAt: '2099-07-14T01:00:00Z',
      confirmationGrant: canonicalGrant
    }));
    startChangeConfirmation();
    finishChangeConfirmationRequest();
    const composerFocus = vi.fn();
    component.composer = {focus: composerFocus, resize: vi.fn()} as any;

    expect(component.commandInputBlocked).toBe(false);
    expect(component.composerPlaceholder).toBe('Describe how to revise this change');
    component.requestChangeRevision();
    expect(composerFocus).toHaveBeenCalledOnce();

    component.state.prompt = 'Use the name Revised Business Context instead';
    component.send();

    expect(api.decideChangeConfirmation).toHaveBeenCalledOnce();
    expect(api.decideChangeConfirmation).toHaveBeenCalledWith(
      'conversation-1', 'confirmation-1', 'APPROVE',
      'Use the name Revised Business Context instead'
    );
    expect(component.changeInteraction).toBeUndefined();
    expect(component.state.pending).toBe(true);
    expect(component.state.prompt).toBe('');
    expect(component.state.attachments).toEqual([]);
    expect(component.composer.resize).toHaveBeenCalledOnce();
    expect(component.state.messages).toContainEqual({
      role: 'user', content: 'Use the name Revised Business Context instead'
    });
    expect(transport.publishWhenConnected).toHaveBeenCalledTimes(2);
    transport.publishWhenConnected.mock.calls[1][0].publish();
    expect(transport.publish).toHaveBeenLastCalledWith('/app/ai/chat', expect.objectContaining({
      requestId: 'request-2',
      conversationId: 'conversation-1',
      prompt: 'Use the name Revised Business Context instead',
      attachments: [],
      changeConfirmation: {
        confirmationRequestId: 'confirmation-1',
        confirmationGrant: canonicalGrant,
        toolName: 'create_business_context',
        approvalMode: 'REVISED',
        revisionPrompt: 'Use the name Revised Business Context instead'
      }
    }));
  });

  it('keeps the change draft and approval visible when revised approval is not confirmed', () => {
    api.decideChangeConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'REQUESTED',
      disposition: 'EXISTING',
      expiresAt: '2099-07-15T00:00:00Z'
    }));
    startChangeConfirmation();
    finishChangeConfirmationRequest();
    component.state.prompt = 'Use another name';

    component.send();

    expect(component.state.prompt).toBe('Use another name');
    expect(component.state.pending).toBe(false);
    expect(component.state.currentStatus).toBe('Approval required');
    expect(component.changeInteraction?.mode).toBe('confirm');
    expect(transport.publishWhenConnected).toHaveBeenCalledTimes(1);
    expect(snackBar.open).toHaveBeenCalledWith(
      'The approval response for the revised change was not valid.',
      'Dismiss', {duration: 3500}
    );
  });

  it('keeps the first bound notice when an exact duplicate notice repeats', () => {
    startChangeConfirmation();
    sendChangeConfirmationNotice();

    finishChangeConfirmationRequest();

    expect(component.changeInteraction).toEqual({
      mode: 'confirm', busy: false,
      toolName: 'create_business_context', argumentsSummary: '{"name":"Example"}'
    });
    expect(api.decideChangeConfirmation).not.toHaveBeenCalled();
    expect(api.sendChat).not.toHaveBeenCalled();
  });

  it('ignores a second-change notice on the same request and still offers the first approval', () => {
    startChangeConfirmation();
    sendChangeConfirmationNotice({metadata: {
      confirmationRequestId: 'confirmation-2',
      status: 'REQUESTED',
      expiresAt: '2099-07-15T00:00:00Z',
      toolName: 'update_business_context',
      argumentsSummary: '{"id":2}'
    }});

    expect((component as any).pendingChangeConfirmation).toMatchObject({
      conversationId: 'conversation-1',
      notice: {confirmationRequestId: 'confirmation-1'}
    });
    finishChangeConfirmationRequest();

    expect(component.changeInteraction).toEqual({
      mode: 'confirm', busy: false,
      toolName: 'create_business_context', argumentsSummary: '{"name":"Example"}'
    });
    expect(api.decideChangeConfirmation).not.toHaveBeenCalled();
    expect(api.sendChat).not.toHaveBeenCalled();
  });

  it.each([
    ['foreign conversation', {
      conversationId: 'conversation-2'
    }],
    ['expired notice', {
      metadata: {
        confirmationRequestId: 'confirmation-1',
        status: 'REQUESTED',
        expiresAt: '2000-01-01T00:00:00Z'
      }
    }],
    ['grant-smuggling notice', {
      metadata: {
        confirmationRequestId: 'confirmation-1',
        status: 'REQUESTED',
        expiresAt: '2099-07-15T00:00:00Z',
        confirmationGrant: 'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA'
      }
    }]
  ])('fails closed for a %s', (_label, override) => {
    component.state.conversationId = 'conversation-1';
    component.state.prompt = 'Create the same item again';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    sendChangeConfirmationNotice(override);

    finishChangeConfirmationRequest();

    expect(component.changeInteraction).toBeUndefined();
    expect(api.decideChangeConfirmation).not.toHaveBeenCalled();
    expect(api.sendChat).not.toHaveBeenCalled();
  });

  it('offers explicit revocation for an already-approved notice without auto-reapproving or revoking', () => {
    startChangeConfirmation('APPROVED');
    finishChangeConfirmationRequest();

    expect(component.changeInteraction?.mode).toBe('lost_grant');
    expect(api.decideChangeConfirmation).not.toHaveBeenCalled();
    expect(api.decideChangeConfirmation).not.toHaveBeenCalled();
    expect(api.sendChat).not.toHaveBeenCalled();
  });

  it('revokes a lost approval only after the user explicitly chooses it', () => {
    api.decideChangeConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'DENIED',
      disposition: 'DENIED',
      expiresAt: '2099-07-15T00:00:00Z',
      approvedAt: '2099-07-14T00:30:00Z',
      deniedAt: '2099-07-14T01:00:00Z'
    }));
    startChangeConfirmation('APPROVED');
    finishChangeConfirmationRequest();

    component.revokeChangeInteraction();

    expect(api.decideChangeConfirmation).toHaveBeenCalledOnce();
    expect(api.decideChangeConfirmation).toHaveBeenCalledWith(
      'conversation-1', 'confirmation-1', 'DENY'
    );
    expect(snackBar.open).not.toHaveBeenCalledWith(
      'The change denial response was not valid.',
      expect.anything(), expect.anything()
    );
  });

  it('does not reapprove or auto-revoke when approval already consumed the one-time grant', () => {
    api.decideChangeConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'APPROVED',
      disposition: 'ALREADY_APPROVED',
      expiresAt: '2099-07-15T00:00:00Z',
      approvedAt: '2099-07-14T00:30:00Z'
    }));
    startChangeConfirmation();
    finishChangeConfirmationRequest();

    component.approveChangeInteraction();

    expect(component.changeInteraction?.mode).toBe('lost_grant');
    expect(api.decideChangeConfirmation).toHaveBeenCalledTimes(1);
    expect(api.decideChangeConfirmation).toHaveBeenCalledWith(
      'conversation-1', 'confirmation-1', 'APPROVE'
    );
    expect(api.sendChat).not.toHaveBeenCalled();
    expect(api.decideChangeConfirmation).toHaveBeenCalledTimes(1);
  });

});

function startElicitation(noticeGeneration = 7, activeGeneration = 7): void {
  component.state.conversationId = 'conversation-1';
  component.state.prompt = 'Perform the complex operation';
  component.send();
  transport.publishWhenConnected.mock.calls[0][0].publish();
  if (component.state.activeRequest) {
    component.state.activeRequest.generation = activeGeneration;
  }
  (component as any).handleSocketEvent({
    requestId: 'request-1', conversationId: 'conversation-1',
    type: 'system', subtype: 'elicitation_required', visibility: 'visible',
    content: 'The assistant needs your input before it can continue.',
    metadata: {
      elicitationId: 'elicitation-1', generation: noticeGeneration, mode: 'form',
      expiresAt: '2099-07-15T00:00:00Z',
      message: 'Choose an import strategy.',
      requestedSchema: {
        type: 'object', properties: {
          strategy: {type: 'string', enum: ['merge', 'replace']}
        }, required: ['strategy']
      }
    }
  });
}

function startApprovalBatch(parallel: boolean, count = 2): void {
  component.state.conversationId = 'conversation-1';
  component.state.prompt = 'Apply the requested changes';
  component.send();
  transport.publishWhenConnected.mock.calls[0][0].publish();
  (component as any).handleSocketEvent({
    requestId: 'request-1', conversationId: 'conversation-1',
    type: 'system', subtype: 'change_approval_batch_required', visibility: 'visible',
    content: 'Approval required.', metadata: {
      batchId: 'batch-1', parallel, expiresAt: '2099-07-15T00:00:00Z',
      items: [
        {confirmationRequestId: 'confirmation-1', toolName: 'update_bbie',
          argumentsSummary: '{"id":1}', agentId: 'agent-a', agentLabel: 'Agent A'},
        {confirmationRequestId: 'confirmation-2', toolName: 'delete_bbie',
          argumentsSummary: '{"id":2}', agentId: 'agent-b', agentLabel: 'Agent B'}
      ].slice(0, count)
    }
  });
}
