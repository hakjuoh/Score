import {
  AiCancellationResponse,
  AiChatRestResponse,
  HttpErrorResponse,
  MUTATION_CONFIRMATION_DECISION_TIMEOUT_MS,
  Subject,
  Subscription,
  api,
  component,
  consumedDecisionResponse,
  destroyComponent,
  finishMutationConfirmationRequest,
  mutationConfirmationEvent,
  of,
  setupAiChatPanelSpec,
  snackBar,
  startMutationConfirmation,
  teardownAiChatPanelSpec,
  throwError,
  transport
} from './ai-chat-panel.component.spec-support';

describe('AiChatPanelComponent mutation lifecycle', () => {
  beforeEach(setupAiChatPanelSpec);
  afterEach(teardownAiChatPanelSpec);

  it('publishes an approved repeat once through the chat WebSocket', () => {
    const canonicalGrant = 'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA';
    ((component as any).createRequestId as ReturnType<typeof vi.fn>)
      .mockReturnValueOnce('request-1').mockReturnValueOnce('request-2');
    api.decideMutationConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'APPROVED', disposition: 'APPROVED',
      expiresAt: '2099-07-15T00:00:00Z',
      approvedAt: '2099-07-14T00:30:00Z',
      confirmationGrant: canonicalGrant
    }));
    startMutationConfirmation();
    finishMutationConfirmationRequest();
    component.approveMutationInteraction();

    expect(transport.publishWhenConnected).toHaveBeenCalledTimes(2);
    const confirmedConnection = transport.publishWhenConnected.mock.calls[1][0];
    confirmedConnection.publish();

    expect(api.sendChat).not.toHaveBeenCalled();
    expect(transport.publish).toHaveBeenCalledTimes(2);
    expect(transport.publish).toHaveBeenLastCalledWith('/app/ai/chat', expect.objectContaining({
      requestId: 'request-2',
      mutationConfirmation: expect.objectContaining({confirmationGrant: canonicalGrant})
    }));
    expect(component.state.pending).toBe(true);
    expect(JSON.stringify(component.state)).not.toContain(canonicalGrant);
  });

  it('accepts the exact final frame for an approved WebSocket repeat', () => {
    const canonicalGrant = 'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA';
    ((component as any).createRequestId as ReturnType<typeof vi.fn>)
      .mockReturnValueOnce('request-1').mockReturnValueOnce('request-2');
    api.decideMutationConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1', conversationId: 'conversation-1',
      status: 'APPROVED', disposition: 'APPROVED', expiresAt: '2099-07-15T00:00:00Z',
      approvedAt: '2099-07-14T00:30:00Z', confirmationGrant: canonicalGrant
    }));
    startMutationConfirmation();
    finishMutationConfirmationRequest();
    component.approveMutationInteraction();
    transport.publishWhenConnected.mock.calls[1][0].publish();

    (component as any).handleSocketEvent({
      requestId: 'request-2', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'The confirmed repeat completed.'
    });

    expect(component.state.pending).toBe(false);
    expect(component.state.reconciliationRequired).toBe(false);
    expect(component.state.messages).toContainEqual({
      role: 'assistant', content: 'The confirmed repeat completed.'
    });
  });

  it('requires reconciliation instead of continuing after a foreign confirmation final', () => {
    startMutationConfirmation();

    (component as any).handleSocketEvent({
      requestId: 'request-1',
      conversationId: 'conversation-2',
      type: 'assistant_final',
      content: 'A foreign terminal frame.'
    });

    expect(component.state.pending).toBe(false);
    expect(component.state.conversationId).toBe('conversation-1');
    expect(component.state.reconciliationRequired).toBe(true);
    expect(component.state.currentStatus).toBe('Review needed');
    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'error', content: expect.stringContaining('requires reconciliation')
    }));
    expect(component.state.messages).not.toContainEqual(expect.objectContaining({
      content: 'A foreign terminal frame.'
    }));
    expect(component.mutationInteraction).toBeUndefined();
    expect(api.decideMutationConfirmation).not.toHaveBeenCalled();
    expect(api.sendChat).not.toHaveBeenCalled();

    component.state.prompt = 'Must not continue on an ambiguous conversation';
    component.send();
    expect(transport.publishWhenConnected).toHaveBeenCalledTimes(1);
  });

  it('times out an unanswered approval without retrying or permanently blocking interaction', () => {
    vi.useFakeTimers();
    startMutationConfirmation();
    finishMutationConfirmationRequest();
    component.approveMutationInteraction();

    expect(component.interactionBlocked).toBe(true);
    vi.advanceTimersByTime(MUTATION_CONFIRMATION_DECISION_TIMEOUT_MS);

    expect(api.decideMutationConfirmation).toHaveBeenCalledTimes(1);
    expect(api.sendChat).not.toHaveBeenCalled();
    expect(component.mutationInteraction?.mode).toBe('lost_grant');
    component.dismissMutationInteraction();
    expect(component.interactionBlocked).toBe(false);
    expect(api.decideMutationConfirmation).toHaveBeenCalledTimes(1);

    component.state.prompt = 'Continue safely';
    component.send();
    expect(transport.publishWhenConnected).toHaveBeenCalledTimes(2);
  });

  it('times out an unanswered DENY and releases interaction without retrying', () => {
    vi.useFakeTimers();
    startMutationConfirmation();
    finishMutationConfirmationRequest();
    component.denyMutationInteraction();

    expect(component.interactionBlocked).toBe(true);
    vi.advanceTimersByTime(MUTATION_CONFIRMATION_DECISION_TIMEOUT_MS);

    expect(component.interactionBlocked).toBe(false);
    expect(api.decideMutationConfirmation).toHaveBeenCalledTimes(1);
    expect(api.sendChat).not.toHaveBeenCalled();
    expect(snackBar.open).toHaveBeenCalledWith(
      'The denial outcome is unknown; the action was not sent.',
      'Dismiss', {duration: 3500}
    );
  });

  it('times out an unanswered explicit revoke and releases interaction without retrying', () => {
    vi.useFakeTimers();
    startMutationConfirmation('APPROVED');
    finishMutationConfirmationRequest();
    component.revokeMutationInteraction();

    expect(component.interactionBlocked).toBe(true);
    vi.advanceTimersByTime(MUTATION_CONFIRMATION_DECISION_TIMEOUT_MS);

    expect(component.interactionBlocked).toBe(false);
    expect(api.decideMutationConfirmation).toHaveBeenCalledTimes(1);
    expect(api.decideMutationConfirmation).toHaveBeenCalledWith(
      'conversation-1', 'confirmation-1', 'DENY'
    );
    expect(api.sendChat).not.toHaveBeenCalled();
  });

  it.each([
    ['foreign', {
      confirmationRequestId: 'confirmation-1', conversationId: 'conversation-2',
      status: 'DENIED', disposition: 'DENIED',
      expiresAt: '2099-07-15T00:00:00Z', deniedAt: '2099-07-14T01:00:00Z'
    }],
    ['malformed', {
      confirmationRequestId: 'confirmation-1', conversationId: 'conversation-1',
      status: 'DENIED', disposition: 'DENIED',
      expiresAt: '2099-07-15T00:00:00Z'
    }],
    ['conflict', {
      confirmationRequestId: 'confirmation-1', conversationId: 'conversation-1',
      status: 'APPROVED', disposition: 'CONFLICT',
      expiresAt: '2099-07-15T00:00:00Z'
    }],
    ['consumed', {
      confirmationRequestId: 'confirmation-1', conversationId: 'conversation-1',
      status: 'CONSUMED', disposition: 'CONSUMED',
      expiresAt: '2099-07-15T00:00:00Z', consumedAt: '2099-07-14T01:00:00Z'
    }]
  ])('does not treat a %s DENY response as success', (_label, response) => {
    api.decideMutationConfirmation.mockReturnValueOnce(of(response));
    startMutationConfirmation();
    finishMutationConfirmationRequest();
    component.denyMutationInteraction();

    expect(component.interactionBlocked).toBe(false);
    expect(api.sendChat).not.toHaveBeenCalled();
    expect(snackBar.open).toHaveBeenCalledWith(
      'The action denial response was not valid.',
      'Dismiss', {duration: 3500}
    );
  });

  it('tears down an unanswered decision when the component is destroyed', () => {
    startMutationConfirmation();
    finishMutationConfirmationRequest();
    component.denyMutationInteraction();
    expect(component.interactionBlocked).toBe(true);

    destroyComponent();

    expect(component.interactionBlocked).toBe(false);
    expect(api.decideMutationConfirmation).toHaveBeenCalledTimes(1);
  });

  it('tears down the confirmed WebSocket subscription on destroy', () => {
    const canonicalGrant = 'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA';
    ((component as any).createRequestId as ReturnType<typeof vi.fn>)
      .mockReturnValueOnce('request-1').mockReturnValueOnce('request-2');
    api.decideMutationConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1', conversationId: 'conversation-1',
      status: 'APPROVED', disposition: 'APPROVED',
      expiresAt: '2099-07-15T00:00:00Z',
      approvedAt: '2099-07-14T00:30:00Z', confirmationGrant: canonicalGrant
    }));
    startMutationConfirmation();
    finishMutationConfirmationRequest();
    component.approveMutationInteraction();
    const subscription = (component as any).requestSubscription as Subscription;
    expect(subscription.closed).toBe(false);
    const messages = [...component.state.messages];

    destroyComponent();

    expect(subscription.closed).toBe(true);
    expect(component.state.messages).toEqual(messages);
    expect(JSON.stringify(component.state)).not.toContain(canonicalGrant);
  });

  it('delivers an attachment-originated REST notice and carries the exact draft into its approved repeat', () => {
    const attachmentRequest = new Subject<AiChatRestResponse>();
    const canonicalGrant = 'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA';
    ((component as any).createRequestId as ReturnType<typeof vi.fn>)
      .mockReturnValueOnce('request-1').mockReturnValueOnce('request-2');
    api.sendChat.mockReturnValueOnce(attachmentRequest);
    api.decideMutationConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1', conversationId: 'conversation-1',
      status: 'APPROVED', disposition: 'APPROVED',
      expiresAt: '2099-07-15T00:00:00Z',
      approvedAt: '2099-07-14T00:30:00Z', confirmationGrant: canonicalGrant
    }));
    component.state.conversationId = 'conversation-1';
    component.state.prompt = 'Repeat with an attachment';
    component.state.attachments = [{
      name: 'secret.txt', mediaType: 'text/plain', size: 6, data: 'secret'
    }];
    component.send();

    attachmentRequest.next({
      conversationId: 'conversation-1', response: 'Approval is required.',
      events: [mutationConfirmationEvent('request-1')]
    });
    expect(component.mutationInteraction?.mode).toBe('confirm');
    component.approveMutationInteraction();

    expect(api.decideMutationConfirmation).toHaveBeenCalledTimes(1);
    expect(api.sendChat).toHaveBeenCalledTimes(1);
    transport.publishWhenConnected.mock.calls[0][0].publish();
    expect(transport.publish).toHaveBeenLastCalledWith('/app/ai/chat', expect.objectContaining({
      requestId: 'request-2',
      prompt: 'Repeat with an attachment',
      attachments: [{name: 'secret.txt', mediaType: 'text/plain', size: 6, data: 'secret'}],
      mutationConfirmation: {
        confirmationRequestId: 'confirmation-1', confirmationGrant: canonicalGrant,
        toolName: 'create_business_context', arguments: '{"name":"Example"}'
      }
    }));
    (component as any).handleSocketEvent({
      requestId: 'request-2', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'Approved attachment action completed.'
    });
    expect(component.state.pending).toBe(false);
  });

  it('surfaces a second mutation notice returned by an approved WebSocket follow-up', () => {
    const canonicalGrant = 'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA';
    ((component as any).createRequestId as ReturnType<typeof vi.fn>)
      .mockReturnValueOnce('request-1').mockReturnValueOnce('request-2');
    api.decideMutationConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1', conversationId: 'conversation-1',
      status: 'APPROVED', disposition: 'APPROVED',
      expiresAt: '2099-07-15T00:00:00Z',
      approvedAt: '2099-07-14T00:30:00Z', confirmationGrant: canonicalGrant
    }));
    startMutationConfirmation();
    finishMutationConfirmationRequest();
    component.approveMutationInteraction();
    transport.publishWhenConnected.mock.calls[1][0].publish();

    (component as any).handleSocketEvent({
      ...mutationConfirmationEvent('request-2'),
      metadata: {
        confirmationRequestId: 'confirmation-2', status: 'REQUESTED',
        expiresAt: '2099-07-15T00:00:00Z',
        toolName: 'update_business_context', argumentsSummary: '{"id":2}'
      }
    });
    (component as any).handleSocketEvent({
      requestId: 'request-2', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'A second action also requires approval.'
    });

    expect(component.mutationInteraction).toMatchObject({
      mode: 'confirm', argumentsSummary: '{"id":2}'
    });
  });

  it('cancels a confirmed request without retrying and ignores its late response', () => {
    const cancellation = new Subject<AiCancellationResponse>();
    const canonicalGrant = 'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA';
    ((component as any).createRequestId as ReturnType<typeof vi.fn>)
      .mockReturnValueOnce('request-1').mockReturnValueOnce('request-2');
    api.decideMutationConfirmation.mockReturnValueOnce(of({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'APPROVED', disposition: 'APPROVED',
      expiresAt: '2099-07-15T00:00:00Z',
      approvedAt: '2099-07-14T00:30:00Z', confirmationGrant: canonicalGrant
    }));
    api.cancelRequest.mockReturnValueOnce(cancellation);
    startMutationConfirmation();
    finishMutationConfirmationRequest();
    component.approveMutationInteraction();
    transport.publishWhenConnected.mock.calls[1][0].publish();

    const subscription = (component as any).requestSubscription as Subscription;
    expect(subscription.closed).toBe(false);
    component.cancelActiveRequest();
    cancellation.next({
      requestId: 'request-2', conversationId: 'conversation-1', generation: 1,
      cancellationRequestId: 'cancel-1', effectiveCancellationRequestId: 'cancel-1',
      disposition: 'CANCELLED', status: 'CANCELLED', acknowledged: true,
      terminal: true, lifecycleEventSequence: 2
    });
    expect(subscription.closed).toBe(true);

    expect(api.sendChat).not.toHaveBeenCalled();
    expect(component.state.pending).toBe(false);
    expect(component.state.messages).not.toContainEqual(expect.objectContaining({
      content: 'A late confirmed response.'
    }));
  });

  it('moves an exact consumed approval conflict to reconciliation without sending again', () => {
    api.decideMutationConfirmation.mockReturnValueOnce(throwError(() =>
      new HttpErrorResponse({status: 409, error: consumedDecisionResponse()})
    ));
    startMutationConfirmation();
    finishMutationConfirmationRequest();

    component.approveMutationInteraction();

    expect(component.state.reconciliationRequired).toBe(true);
    expect(component.state.currentStatus).toBe('Review needed');
    expect(api.sendChat).not.toHaveBeenCalled();
    expect(component.mutationInteraction).toBeUndefined();
  });

  it.each([
    ['conflict', {
      confirmationRequestId: 'confirmation-1', conversationId: 'conversation-1',
      status: 'APPROVED', disposition: 'CONFLICT',
      expiresAt: '2099-07-15T00:00:00Z'
    }],
    ['foreign consumed', {
      ...consumedDecisionResponse(), conversationId: 'conversation-2'
    }],
    ['malformed consumed', {
      ...consumedDecisionResponse(), consumedAt: undefined
    }]
  ])('rejects a 409 %s approval body without reconciliation or lost-grant replay',
    (_label, errorBody) => {
      api.decideMutationConfirmation.mockReturnValueOnce(throwError(() =>
        new HttpErrorResponse({status: 409, error: errorBody})
      ));
      startMutationConfirmation();
      finishMutationConfirmationRequest();

      component.approveMutationInteraction();

      expect(component.state.reconciliationRequired).toBe(false);
      expect(api.sendChat).not.toHaveBeenCalled();
      expect(component.mutationInteraction?.mode).toBe('confirm');
      expect(snackBar.open).toHaveBeenCalledWith(
        'The action could not be approved.',
        'Dismiss', {duration: 3500}
      );
    }
  );

  it.each([
    ['consumed', consumedDecisionResponse(), true],
    ['conflict', {
      confirmationRequestId: 'confirmation-1', conversationId: 'conversation-1',
      status: 'APPROVED', disposition: 'CONFLICT',
      expiresAt: '2099-07-15T00:00:00Z'
    }, false],
    ['foreign consumed', {
      ...consumedDecisionResponse(), conversationId: 'conversation-2'
    }, false],
    ['malformed consumed', {
      ...consumedDecisionResponse(), consumedAt: undefined
    }, false]
  ])('handles a 409 %s DENY body without treating it as success',
    (_label, errorBody, reconciliationRequired) => {
      api.decideMutationConfirmation.mockReturnValueOnce(throwError(() =>
        new HttpErrorResponse({status: 409, error: errorBody})
      ));
      startMutationConfirmation();
      finishMutationConfirmationRequest();

      component.denyMutationInteraction();

      expect(component.state.reconciliationRequired).toBe(reconciliationRequired);
      expect(api.sendChat).not.toHaveBeenCalled();
      if (reconciliationRequired) {
        expect(component.state.currentStatus).toBe('Review needed');
      } else {
        expect(snackBar.open).toHaveBeenCalledWith(
          'The denial outcome is unknown; the action was not sent.',
          'Dismiss', {duration: 3500}
        );
      }
    }
  );

});
