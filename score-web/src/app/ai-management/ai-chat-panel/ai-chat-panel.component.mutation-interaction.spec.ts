import {
  api,
  component,
  finishMutationConfirmationRequest,
  of,
  sendMutationConfirmationNotice,
  setupAiChatPanelSpec,
  snackBar,
  startMutationConfirmation,
  storageText,
  teardownAiChatPanelSpec,
  transport
} from './ai-chat-panel.component.spec-support';

describe('AiChatPanelComponent elicitation and mutation interaction', () => {
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
      elicitationId: 'elicitation-1', action: 'ACCEPT',
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
      elicitationId: 'elicitation-1', action: 'ACCEPT',
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
      elicitationId: 'elicitation-1', action: 'ACCEPT',
      content: {strategy: 'replace'}
    });
    expect(component.state.elicitationBusy).toBe(true);
  });

  it('waits for the original terminal event, then publishes one confirmed repeat without leaking its grant', () => {
    const canonicalGrant = 'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA';
    const createRequestId = (component as any).createRequestId as ReturnType<typeof vi.fn>;
    createRequestId.mockReturnValueOnce('request-1').mockReturnValueOnce('request-2');
    api.decideMutationConfirmation.mockReturnValueOnce(of({
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

    startMutationConfirmation();

    expect(component.mutationInteraction).toBeUndefined();
    const composerFocus = vi.fn();
    component.composer = {focus: composerFocus, resize: vi.fn()} as any;
    finishMutationConfirmationRequest();
    expect(component.state.pending).toBe(false);
    expect(component.mutationInteraction).toEqual({
      mode: 'confirm', busy: false,
      toolName: 'create_business_context', argumentsSummary: '{"name":"Example"}'
    });
    expect(api.decideMutationConfirmation).not.toHaveBeenCalled();
    expect(composerFocus).not.toHaveBeenCalled();

    component.approveMutationInteraction();

    expect(api.decideMutationConfirmation).toHaveBeenCalledOnce();
    expect(api.decideMutationConfirmation).toHaveBeenCalledWith(
      'conversation-1', 'confirmation-1', 'APPROVE'
    );
    expect(api.sendChat).not.toHaveBeenCalled();
    expect(transport.publishWhenConnected).toHaveBeenCalledTimes(2);
    const confirmedPublish = transport.publishWhenConnected.mock.calls[1][0];
    confirmedPublish.publish();
    expect(transport.publish).toHaveBeenCalledWith('/app/ai/chat', expect.objectContaining({
      requestId: 'request-2', prompt: 'Create the same item again',
      conversationId: 'conversation-1', attachments: [],
      mutationConfirmation: {
        confirmationRequestId: 'confirmation-1', confirmationGrant: canonicalGrant,
        toolName: 'create_business_context', arguments: '{"name":"Example"}'
      }
    }));
    expect(transport.publish.mock.calls.at(-1)?.[1]).not.toHaveProperty('multiAgent');
    expect(JSON.stringify(component.state)).not.toContain(canonicalGrant);
    expect((component as any).mutationRepeatDraft).toEqual({
      requestId: 'request-2', prompt: 'Create the same item again', attachments: []
    });
    expect((component as any).pendingMutationConfirmation).toBeUndefined();
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
    sendMutationConfirmationNotice();
    expect((component as any).pendingMutationConfirmation).toMatchObject({
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
    finishMutationConfirmationRequest();

    expect(component.state.conversationId).toBe('conversation-1');
    expect(component.state.reconciliationRequired).toBe(false);
    expect(component.mutationInteraction?.mode).toBe('confirm');
  });

  it('invalidates an out-of-order notice when the accepted identity disagrees', () => {
    component.state.prompt = 'Create the same item again';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    sendMutationConfirmationNotice();

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
    expect(component.mutationInteraction).toBeUndefined();
    expect(api.decideMutationConfirmation).not.toHaveBeenCalled();
  });

  it('turns close or backdrop into one explicit DENY without a chat retry', () => {
    api.decideMutationConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'DENIED',
      disposition: 'DENIED',
      expiresAt: '2099-07-15T00:00:00Z',
      deniedAt: '2099-07-14T01:00:00Z'
    }));
    startMutationConfirmation();
    finishMutationConfirmationRequest();

    component.denyMutationInteraction();

    expect(api.decideMutationConfirmation).toHaveBeenCalledOnce();
    expect(api.decideMutationConfirmation).toHaveBeenCalledWith(
      'conversation-1', 'confirmation-1', 'DENY'
    );
    expect(api.sendChat).not.toHaveBeenCalled();
    expect(snackBar.open).not.toHaveBeenCalledWith(
      'The action denial response was not valid.',
      expect.anything(), expect.anything()
    );
  });

  it('approves the revised same-tool mutation and sends it without a second confirmation', () => {
    const canonicalGrant = 'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA';
    const createRequestId = (component as any).createRequestId as ReturnType<typeof vi.fn>;
    createRequestId.mockReturnValueOnce('request-1').mockReturnValueOnce('request-2');
    api.decideMutationConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'APPROVED',
      disposition: 'APPROVED',
      expiresAt: '2099-07-15T00:00:00Z',
      approvedAt: '2099-07-14T01:00:00Z',
      confirmationGrant: canonicalGrant
    }));
    startMutationConfirmation();
    finishMutationConfirmationRequest();
    const composerFocus = vi.fn();
    component.composer = {focus: composerFocus, resize: vi.fn()} as any;

    expect(component.commandInputBlocked).toBe(false);
    expect(component.composerPlaceholder).toBe('Describe changes to this action');
    component.requestMutationChange();
    expect(composerFocus).toHaveBeenCalledOnce();

    component.state.prompt = 'Use the name Revised Business Context instead';
    component.send();

    expect(api.decideMutationConfirmation).toHaveBeenCalledOnce();
    expect(api.decideMutationConfirmation).toHaveBeenCalledWith(
      'conversation-1', 'confirmation-1', 'APPROVE',
      'Use the name Revised Business Context instead'
    );
    expect(component.mutationInteraction).toBeUndefined();
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
      mutationConfirmation: {
        confirmationRequestId: 'confirmation-1',
        confirmationGrant: canonicalGrant,
        toolName: 'create_business_context',
        approvalMode: 'REVISED',
        revisionPrompt: 'Use the name Revised Business Context instead'
      }
    }));
  });

  it('keeps the change draft and approval visible when revised approval is not confirmed', () => {
    api.decideMutationConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'REQUESTED',
      disposition: 'EXISTING',
      expiresAt: '2099-07-15T00:00:00Z'
    }));
    startMutationConfirmation();
    finishMutationConfirmationRequest();
    component.state.prompt = 'Use another name';

    component.send();

    expect(component.state.prompt).toBe('Use another name');
    expect(component.state.pending).toBe(false);
    expect(component.state.currentStatus).toBe('Approval required');
    expect(component.mutationInteraction?.mode).toBe('confirm');
    expect(transport.publishWhenConnected).toHaveBeenCalledTimes(1);
    expect(snackBar.open).toHaveBeenCalledWith(
      'The revised action approval response was not valid.',
      'Dismiss', {duration: 3500}
    );
  });

  it('keeps the first bound notice when an exact duplicate notice repeats', () => {
    startMutationConfirmation();
    sendMutationConfirmationNotice();

    finishMutationConfirmationRequest();

    expect(component.mutationInteraction).toEqual({
      mode: 'confirm', busy: false,
      toolName: 'create_business_context', argumentsSummary: '{"name":"Example"}'
    });
    expect(api.decideMutationConfirmation).not.toHaveBeenCalled();
    expect(api.sendChat).not.toHaveBeenCalled();
  });

  it('ignores a second-mutation notice on the same request and still offers the first approval', () => {
    startMutationConfirmation();
    sendMutationConfirmationNotice({metadata: {
      confirmationRequestId: 'confirmation-2',
      status: 'REQUESTED',
      expiresAt: '2099-07-15T00:00:00Z',
      toolName: 'update_business_context',
      argumentsSummary: '{"id":2}'
    }});

    expect((component as any).pendingMutationConfirmation).toMatchObject({
      conversationId: 'conversation-1',
      notice: {confirmationRequestId: 'confirmation-1'}
    });
    finishMutationConfirmationRequest();

    expect(component.mutationInteraction).toEqual({
      mode: 'confirm', busy: false,
      toolName: 'create_business_context', argumentsSummary: '{"name":"Example"}'
    });
    expect(api.decideMutationConfirmation).not.toHaveBeenCalled();
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
    sendMutationConfirmationNotice(override);

    finishMutationConfirmationRequest();

    expect(component.mutationInteraction).toBeUndefined();
    expect(api.decideMutationConfirmation).not.toHaveBeenCalled();
    expect(api.sendChat).not.toHaveBeenCalled();
  });

  it('offers explicit revocation for an already-approved notice without auto-reapproving or revoking', () => {
    startMutationConfirmation('APPROVED');
    finishMutationConfirmationRequest();

    expect(component.mutationInteraction?.mode).toBe('lost_grant');
    expect(api.decideMutationConfirmation).not.toHaveBeenCalled();
    expect(api.decideMutationConfirmation).not.toHaveBeenCalled();
    expect(api.sendChat).not.toHaveBeenCalled();
  });

  it('revokes a lost approval only after the user explicitly chooses it', () => {
    api.decideMutationConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'DENIED',
      disposition: 'DENIED',
      expiresAt: '2099-07-15T00:00:00Z',
      approvedAt: '2099-07-14T00:30:00Z',
      deniedAt: '2099-07-14T01:00:00Z'
    }));
    startMutationConfirmation('APPROVED');
    finishMutationConfirmationRequest();

    component.revokeMutationInteraction();

    expect(api.decideMutationConfirmation).toHaveBeenCalledOnce();
    expect(api.decideMutationConfirmation).toHaveBeenCalledWith(
      'conversation-1', 'confirmation-1', 'DENY'
    );
    expect(snackBar.open).not.toHaveBeenCalledWith(
      'The action denial response was not valid.',
      expect.anything(), expect.anything()
    );
  });

  it('does not reapprove or auto-revoke when approval already consumed the one-time grant', () => {
    api.decideMutationConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'APPROVED',
      disposition: 'ALREADY_APPROVED',
      expiresAt: '2099-07-15T00:00:00Z',
      approvedAt: '2099-07-14T00:30:00Z'
    }));
    startMutationConfirmation();
    finishMutationConfirmationRequest();

    component.approveMutationInteraction();

    expect(component.mutationInteraction?.mode).toBe('lost_grant');
    expect(api.decideMutationConfirmation).toHaveBeenCalledTimes(1);
    expect(api.decideMutationConfirmation).toHaveBeenCalledWith(
      'conversation-1', 'confirmation-1', 'APPROVE'
    );
    expect(api.sendChat).not.toHaveBeenCalled();
    expect(api.decideMutationConfirmation).toHaveBeenCalledTimes(1);
  });

});

function startElicitation(): void {
  component.state.conversationId = 'conversation-1';
  component.state.prompt = 'Perform the complex operation';
  component.send();
  transport.publishWhenConnected.mock.calls[0][0].publish();
  (component as any).handleSocketEvent({
    requestId: 'request-1', conversationId: 'conversation-1',
    type: 'system', subtype: 'elicitation_required', visibility: 'visible',
    content: 'The assistant needs your input before it can continue.',
    metadata: {
      elicitationId: 'elicitation-1', mode: 'form',
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
