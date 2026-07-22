import {
  AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY,
  AiContextBudgetDialogComponent,
  AiConversationRestoreService,
  HttpErrorResponse,
  api,
  attachmentService,
  component,
  dialog,
  lastConversationStorageKey,
  of,
  panelDockStorageKey,
  panelVisibilityStorageKey,
  setCurrentUsername,
  setupAiChatPanelSpec,
  snackBar,
  Subscription,
  Subject,
  teardownAiChatPanelSpec,
  throwError,
  transport,
  windowCoordinator,
  windowModeStorageKey,
  workspaceStorageKey
} from './ai-chat-panel.component.spec-support';

describe('AiChatPanelComponent conversation restore and attachments', () => {
  beforeEach(setupAiChatPanelSpec);
  afterEach(teardownAiChatPanelSpec);

  it('restores an open assistant panel when the page initializes again', () => {
    localStorage.setItem(panelVisibilityStorageKey(), 'open');

    component.ngOnInit();

    expect(component.state.isOpen).toBe(true);
    expect(api.getConversationHistory).toHaveBeenCalledOnce();
    expect(api.getActiveRequest).toHaveBeenCalledOnce();
  });

  it('persists both open and closed assistant panel states', () => {
    component.open();

    expect(localStorage.getItem(panelVisibilityStorageKey())).toBe('open');

    component.close();

    expect(component.state.isOpen).toBe(false);
    expect(localStorage.getItem(panelVisibilityStorageKey())).toBe('closed');
  });

  it('restores the saved assistant dock when the page initializes again', () => {
    localStorage.setItem(panelDockStorageKey(), 'left');
    localStorage.setItem(panelVisibilityStorageKey(), 'open');

    component.ngOnInit();

    expect(component.state.dock).toBe('left');
    expect(component.state.isOpen).toBe(true);
  });

  it('persists a changed assistant dock', () => {
    component.setDock('bottom');

    expect(component.state.dock).toBe('bottom');
    expect(localStorage.getItem(panelDockStorageKey())).toBe('bottom');
  });

  it('ignores an invalid saved assistant dock', () => {
    localStorage.setItem(panelDockStorageKey(), 'center');

    component.ngOnInit();

    expect(component.state.dock).toBe('right');
  });

  it('restores the detailed workspace without losing its draft or selected tab', () => {
    localStorage.setItem(workspaceStorageKey(), JSON.stringify({
      version: 1,
      activePanelTab: 'history',
      sideSize: 610,
      horizontalSize: 455,
      prompt: 'Keep this unfinished request',
      chatScrollTop: 380,
      historyScrollTop: 125
    }));
    localStorage.setItem(panelVisibilityStorageKey(), 'open');
    localStorage.setItem(lastConversationStorageKey(), 'conversation-last');
    api.getConversation.mockReturnValueOnce(of({
      conversationId: 'conversation-last', title: 'Last conversation',
      modelName: 'claude-fable-5', reasoningEffort: 'high',
      messages: [{index: 0, role: 'assistant', content: 'Previous response'}]
    }));

    component.ngOnInit();

    expect(component.state.activePanelTab).toBe('history');
    expect(component.state.sideSize).toBe(610);
    expect(component.state.horizontalSize).toBe(455);
    expect(component.state.prompt).toBe('Keep this unfinished request');
    expect(component.state.chatScrollTop).toBe(380);
    expect(component.state.historyScrollTop).toBe(125);
    expect(component.state.messages).toEqual([{role: 'assistant', content: 'Previous response'}]);
  });

  it('persists detailed workspace changes detected during interaction', () => {
    component.ngOnInit();
    component.state.activePanelTab = 'history';
    component.state.sideSize = 540;
    component.state.horizontalSize = 410;
    component.state.prompt = 'Draft after initialization';
    component.state.chatScrollTop = 222;
    component.state.historyScrollTop = 91;

    component.ngDoCheck();

    expect(JSON.parse(localStorage.getItem(workspaceStorageKey())!)).toEqual({
      version: 1,
      activePanelTab: 'history',
      sideSize: 540,
      horizontalSize: 410,
      prompt: 'Draft after initialization',
      chatScrollTop: 222,
      historyScrollTop: 91
    });
  });

  it('opens the Assistant in a separate window and remembers that mode', () => {
    component.open();

    component.openPopout();

    expect(windowCoordinator.openPopout).toHaveBeenCalledOnce();
    expect(component.state.popoutActive).toBe(true);
    expect(localStorage.getItem(windowModeStorageKey())).toBe('popout');
  });

  it('restores detached mode without also reconnecting the docked panel', () => {
    localStorage.setItem(panelVisibilityStorageKey(), 'open');
    localStorage.setItem(windowModeStorageKey(), 'popout');

    component.ngOnInit();

    expect(component.state.popoutActive).toBe(true);
    expect(component.state.isOpen).toBe(true);
    expect(api.getActiveRequest).not.toHaveBeenCalled();
  });

  it('blocks pop-out while a request is active', () => {
    component.state.pending = true;

    component.openPopout();

    expect(windowCoordinator.openPopout).not.toHaveBeenCalled();
    expect(snackBar.open).toHaveBeenCalledWith(
      'Finish the active assistant interaction before opening a separate window.',
      'Dismiss', {duration: 3500}
    );
  });

  it('keeps the assistant closed by default when no open state was saved', () => {
    component.ngOnInit();

    expect(component.state.isOpen).toBe(false);
    expect(api.getActiveRequest).not.toHaveBeenCalled();
  });

  it('ignores delayed history frames after the active restore has finished', () => {
    const restoreService = (component as any).conversationRestoreService;
    restoreService.isRestoreEvent = vi.fn(() => true);
    restoreService.handleEvent = vi.fn();
    (component as any).activeRestoreRequestId = undefined;

    (component as any).handleSocketEvent({
      requestId: 'old-restore', type: 'HISTORY_MESSAGE',
      message: 'assistant', content: 'stale history', index: 0
    });

    expect(restoreService.handleEvent).not.toHaveBeenCalled();
    expect(component.state.messages).toEqual([]);
  });

  it('keeps the active restore open when a retired token finishes late', async () => {
    vi.useFakeTimers();
    const restoreService = new AiConversationRestoreService();
    (component as any).conversationRestoreService = restoreService;
    (component as any).activeRestoreRequestId = 'same-restore';
    component.state.restoringConversation = true;
    const oldToken = '00000000-0000-4000-8000-000000000001';
    const activeToken = '00000000-0000-4000-8000-000000000002';

    restoreService.expectAttempt(oldToken, 1);
    restoreService.expectAttempt(activeToken, 2);
    (component as any).handleSocketEvent({
      requestId: 'same-restore', type: 'HISTORY_START', conversationId: 'conversation-1',
      metadata: {restoreToken: activeToken, restoreSequence: 2}
    });
    (component as any).handleSocketEvent({
      requestId: 'same-restore', type: 'HISTORY_MESSAGE', conversationId: 'conversation-1',
      message: 'user', content: 'new replay', index: 0,
      metadata: {restoreToken: activeToken, restoreSequence: 2}
    });
    (component as any).handleSocketEvent({
      requestId: 'same-restore', type: 'HISTORY_START', conversationId: 'conversation-1',
      metadata: {restoreToken: oldToken, restoreSequence: 1}
    });
    (component as any).handleSocketEvent({
      requestId: 'same-restore', type: 'HISTORY_FINAL', conversationId: 'conversation-1',
      metadata: {restoreToken: oldToken, restoreSequence: 1}
    });

    expect((component as any).activeRestoreRequestId).toBe('same-restore');
    expect(component.state.messages.map(message => message.content))
      .toEqual(['new replay']);

    (component as any).handleSocketEvent({
      requestId: 'same-restore', type: 'HISTORY_FINAL', conversationId: 'conversation-1',
      metadata: {restoreToken: activeToken, restoreSequence: 2}
    });
    await vi.runAllTimersAsync();

    expect((component as any).activeRestoreRequestId).toBeUndefined();
    expect(component.state.messages.map(message => message.content))
      .toEqual(['new replay']);
  });

  it('subscribes chat responses through the authenticated user queue', () => {
    component.state.prompt = 'Who am I?';

    component.send();

    expect(transport.watch).toHaveBeenCalledWith('/user/queue/ai/chat/request-1');
  });

  it('preserves the unsent draft and attachments when compacting from the context meter', () => {
    component.state.conversationId = 'conversation-1';
    component.state.prompt = 'Keep this draft';
    component.state.attachments = [{
      name: 'draft.txt', mediaType: 'text/plain', size: 5, data: 'ZHJhZnQ='
    }];

    component.requestManualCompact();

    expect(transport.watch).toHaveBeenCalledWith('/user/queue/ai/chat/request-1');
    expect(component.state.messages.some(message =>
      message.role === 'user' && message.content === '/compact')).toBe(true);
    expect(component.state.prompt).toBe('Keep this draft');
    expect(component.state.attachments).toEqual([{
      name: 'draft.txt', mediaType: 'text/plain', size: 5, data: 'ZHJhZnQ='
    }]);
  });

  it('opens context usage in a small pie-chart dialog with configured reserves', () => {
    (component as any).loadAvailableModels();
    Object.assign(component.state.selectedModel()!, {
      contextWindow: 200000,
      outputReserveTokens: 16000,
      emergencyHeadroomTokens: 8192
    });
    component.state.contextUsage = {
      modelName: 'claude-fable-5', currentInputTokens: 12380,
      contextWindow: 200000, safeInputLimit: 175808, remainingTokens: 163428,
      usedPercent: 7, estimated: true, source: 'preflight_estimate'
    };

    component.openContextBudgetDialog();

    expect(dialog.open).toHaveBeenCalledWith(AiContextBudgetDialogComponent, expect.objectContaining({
      width: '408px',
      data: expect.objectContaining({
        outputReserveTokens: 16000,
        emergencyHeadroomTokens: 8192
      })
    }));
  });

  it('opens and closes the pie-chart hover card with pointer-friendly delays', async () => {
    vi.useFakeTimers();
    component.state.contextUsage = {
      modelName: 'claude-fable-5', currentInputTokens: 12380,
      contextWindow: 200000, safeInputLimit: 175808, remainingTokens: 163428,
      usedPercent: 7, estimated: true, source: 'preflight_estimate'
    };

    component.showContextBudgetHover();
    await vi.advanceTimersByTimeAsync(180);
    expect(component.contextBudgetHoverOpen).toBe(true);

    component.closeContextBudgetHover();
    await vi.advanceTimersByTimeAsync(119);
    expect(component.contextBudgetHoverOpen).toBe(true);
    await vi.advanceTimersByTimeAsync(1);
    expect(component.contextBudgetHoverOpen).toBe(false);
  });

  it('publishes model settings without a runtime selector', () => {
    (component as any).loadAvailableModels();
    component.state.prompt = 'Run with these settings';

    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();

    const socketPayload = transport.publish.mock.calls[0][1];
    expect(socketPayload).toEqual(expect.objectContaining({modelName: 'claude-fable-5'}));
    expect(socketPayload).not.toHaveProperty('runtime');
    expect(socketPayload).not.toHaveProperty('runtimeOptions');

    component.state.resetForNewChat();
    component.state.prompt = 'Inspect this file';
    component.state.attachments = [{
      name: 'sample.txt', mediaType: 'text/plain', size: 4, data: 'test'
    }];
    component.send();

    const restPayload = api.sendChat.mock.calls[0][0];
    expect(restPayload).toEqual(expect.objectContaining({modelName: 'claude-fable-5'}));
    expect(restPayload).not.toHaveProperty('runtime');
    expect(restPayload).not.toHaveProperty('runtimeOptions');
  });

  it('blocks send while a file is loading and discards a late read after a new chat', async () => {
    let resolveRead!: (value: any) => void;
    attachmentService.readAttachment.mockReturnValue(new Promise(resolve => resolveRead = resolve));
    (component as any).addFile(new File(['payload'], 'payload.txt', {type: 'text/plain'}));
    component.state.prompt = 'inspect';

    component.send();

    expect(transport.publishWhenConnected).not.toHaveBeenCalled();
    expect(snackBar.open).toHaveBeenCalledWith(
      'Wait for attachments to finish loading.', 'Dismiss', {duration: 3000}
    );
    component.startNewChat();
    resolveRead({name: 'payload.txt', mediaType: 'text/plain', size: 7, data: 'cGF5bG9hZA=='});
    await Promise.resolve();
    await Promise.resolve();
    expect(component.state.attachments).toEqual([]);
  });

  it('preserves a file read that finishes while an active server request is recovered', async () => {
    let resolveRead!: (value: any) => void;
    attachmentService.readAttachment.mockReturnValue(new Promise(resolve => resolveRead = resolve));
    (component as any).addFile(new File(['payload'], 'payload.txt', {type: 'text/plain'}));
    api.getActiveRequest.mockReturnValue(of({
      requestId: 'request-running', conversationId: 'conversation-1', generation: 7,
      status: 'RUNNING', deadline: '2026-07-17T23:00:00Z'
    }));

    component.open();
    resolveRead({name: 'payload.txt', mediaType: 'text/plain', size: 7, data: 'cGF5bG9hZA=='});
    await Promise.resolve();
    await Promise.resolve();

    expect(component.state.pending).toBe(true);
    expect(component.state.attachments).toEqual([{
      name: 'payload.txt', mediaType: 'text/plain', size: 7, data: 'cGF5bG9hZA=='
    }]);
  });

  it('reconstructs a pending approval and its live decision channel when recovering a request', () => {
    const live = new Subject<{body: string}>();
    const status = {
      requestId: 'request-running', conversationId: 'conversation-1', generation: 7,
      status: 'RUNNING' as const, deadline: '2099-07-17T23:00:00Z'
    };
    api.getActiveRequest.mockReturnValue(of(status));
    api.getRequestStatus.mockReturnValue(of(status));
    api.getConversation.mockReturnValue(of({
      conversationId: 'conversation-1', title: 'Running request', messages: [{
        index: 1, role: 'guide', content: 'Approval requested for one data-changing action.',
        requestId: 'request-running', subtype: 'mutation_approval_batch_requested',
        metadata: {
          batchId: 'batch-recovered', parallel: false,
          expiresAt: '2099-07-15T00:00:00Z', items: [{
            confirmationRequestId: 'confirmation-recovered', toolName: 'update_bbie',
            argumentsSummary: '{"id":1}', agentId: 'agent-a', agentLabel: 'Agent A'
          }]
        }
      }]
    }));
    transport.watch.mockReturnValueOnce(live);

    component.open();

    expect(transport.watch).toHaveBeenCalledWith('/user/queue/ai/chat/request-running');
    expect(component.state.mutationApprovalBatch).toMatchObject({
      batchId: 'batch-recovered', requestId: 'request-running'
    });
    expect(component.state.currentStatus).toBe('Approval required');
    const subscription = (component as any).requestSubscription as Subscription;
    expect(subscription.closed).toBe(false);
    component.decideMutationApprovalBatch('APPROVE');
    expect(transport.publish).toHaveBeenLastCalledWith('/app/ai/chat/mutation-approval', {
      requestId: 'request-running', conversationId: 'conversation-1',
      batchId: 'batch-recovered', decisions: [{
        confirmationRequestId: 'confirmation-recovered', decision: 'APPROVE'
      }]
    });

    live.next({body: JSON.stringify({
      requestId: 'request-running', conversationId: 'conversation-1', type: 'system',
      subtype: 'mutation_approval_decision_rejected', content: 'Please decide again.',
      metadata: {batchId: 'batch-recovered'}
    })});
    expect(component.state.mutationApprovalBatchBusy).toBe(false);
    component.decideMutationApprovalBatch('DENY');
    expect(transport.publish).toHaveBeenCalledTimes(2);

    (component as any).finishRecoveredRequest({...status, status: 'COMPLETED'});
    expect(subscription.closed).toBe(true);
    expect((component as any).requestSubscription).toBeUndefined();
    expect(component.state.mutationApprovalBatch).toBeUndefined();
    expect(component.state.pending).toBe(false);
  });

  it('restores the last completed conversation after the assistant is reopened following refresh', () => {
    const storageKey = lastConversationStorageKey();
    localStorage.setItem(storageKey, 'conversation-last');
    api.getConversation.mockReturnValueOnce(of({
      conversationId: 'conversation-last', title: 'Last conversation',
      modelName: 'claude-fable-5', reasoningEffort: 'high',
      messages: [
        {index: 0, role: 'user', content: 'Remember this request'},
        {index: 1, role: 'assistant', content: 'Remembered response'}
      ]
    }));

    component.open();

    expect(api.getConversation).toHaveBeenCalledWith('conversation-last');
    expect(component.state.activePanelTab).toBe('chat');
    expect(component.state.conversationId).toBe('conversation-last');
    expect(component.state.messages).toEqual([
      {role: 'user', content: 'Remember this request'},
      {role: 'assistant', content: 'Remembered response'}
    ]);
    expect(component.state.restoringConversation).toBe(false);
  });

  it('clears an unavailable last conversation instead of retrying it after every refresh', () => {
    const storageKey = lastConversationStorageKey();
    localStorage.setItem(storageKey, 'conversation-deleted');
    api.getConversation.mockReturnValueOnce(throwError(() => new HttpErrorResponse({status: 404})));

    component.open();

    expect(localStorage.getItem(storageKey)).toBeNull();
    expect(component.state.conversationId).toBeUndefined();
    expect(component.state.messages).toEqual([]);
  });

  it('clears the remembered conversation when the user explicitly starts a new chat', () => {
    const storageKey = lastConversationStorageKey();
    localStorage.setItem(storageKey, 'conversation-last');
    component.state.conversationId = 'conversation-last';

    component.startNewChat();

    expect(localStorage.getItem(storageKey)).toBeNull();
    expect(component.state.conversationId).toBeUndefined();
  });

  it('stores the last conversation under the current user instead of a fixed username', () => {
    const defaultUserKey = lastConversationStorageKey();
    setCurrentUsername('another user');
    const currentUserKey = lastConversationStorageKey();

    component.loadConversation('conversation-another-user');

    expect(localStorage.getItem(currentUserKey)).toBe('conversation-another-user');
    expect(localStorage.getItem(defaultUserKey)).toBeNull();
  });

  it('subscribes conversation restoration through the authenticated user queue', () => {
    component.loadConversation('conversation-1');

    expect(transport.watch).toHaveBeenCalledWith('/user/queue/ai/chat/request-1');
    expect(localStorage.getItem(lastConversationStorageKey())).toBe('conversation-1');
  });

  it('restores conversation settings without replacing the next-chat preference', () => {
    (component as any).conversationRestoreService = new AiConversationRestoreService();
    (component as any).loadAvailableModels();
    localStorage.setItem(AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY, JSON.stringify({
      version: 1,
      modelName: 'gpt-5_6-sol',
      reasoningEffort: 'high',
      permissionMode: 'full_access'
    }));
    component.state.selectedModelName = 'gpt-5_6-sol';
    component.state.selectedReasoningEffort = 'high';
    component.state.permissionMode = 'full_access';

    const restoreToken = '00000000-0000-4000-8000-000000000002';
    vi.spyOn(component as any, 'createRestoreToken').mockReturnValue(restoreToken);
    component.loadConversation('conversation-original');
    transport.publishWhenConnected.mock.calls[0][0].publish();
    const metadata = {
      restoreToken,
      restoreSequence: 1,
      modelName: 'claude-fable-5',
      reasoningEffort: 'medium',
      permissionMode: 'auto',
      activeWorkflow: 'parallel'
    };

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-original',
      type: 'HISTORY_START', metadata
    });

    expect(component.state.selectedModelName).toBe('claude-fable-5');
    expect(component.state.selectedReasoningEffort).toBe('medium');
    expect(component.state.permissionMode).toBe('auto');
    expect(component.state.activeWorkflow).toBe('parallel');

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-original',
      type: 'HISTORY_FINAL', metadata
    });
    component.startNewChat();

    expect(component.state.selectedModelName).toBe('gpt-5_6-sol');
    expect(component.state.selectedReasoningEffort).toBe('high');
    expect(component.state.permissionMode).toBe('full_access');
    expect(component.state.activeWorkflow).toBe('');
  });

  it('clears the draft prompt and attachments when switching conversations', () => {
    component.state.conversationId = 'conversation-a';
    component.state.prompt = 'Draft intended for A';
    component.state.attachments = [{
      name: 'a-only.txt', mediaType: 'text/plain', size: 6, data: 'secret'
    }];

    component.loadConversation('conversation-b');

    expect(component.state.prompt).toBe('');
    expect(component.state.attachments).toEqual([]);
    expect(component.state.conversationId).toBe('conversation-b');
  });

  it('does not let stale reconnect callbacks tear down a newer restore', () => {
    const createRequestId = (component as any).createRequestId as ReturnType<typeof vi.fn>;
    createRequestId.mockReturnValueOnce('restore-a').mockReturnValueOnce('restore-b');
    component.loadConversation('conversation-a');
    const staleRestore = transport.publishWhenConnected.mock.calls[0][0];
    component.loadConversation('conversation-b');
    const activeRestore = transport.publishWhenConnected.mock.calls[1][0];
    component.state.currentStatus = 'Restore B pending';

    staleRestore.onReconnectStatus(3, 3);
    staleRestore.onConnected();
    staleRestore.onReconnectFailure();
    staleRestore.onPublishError();

    expect(staleRestore.active()).toBe(false);
    expect(activeRestore.active()).toBe(true);
    expect((component as any).activeRestoreRequestId).toBe('restore-b');
    expect(component.state.restoringConversation).toBe(true);
    expect(component.state.currentStatus).toBe('Restore B pending');
    expect(component.state.messages).toEqual([]);
  });

  it('registers a fresh restore identity before every publish attempt', () => {
    const restoreService = (component as any).conversationRestoreService;
    const createRestoreToken = vi.spyOn(component as any, 'createRestoreToken')
      .mockReturnValueOnce('00000000-0000-4000-8000-000000000001')
      .mockReturnValueOnce('00000000-0000-4000-8000-000000000002');
    component.loadConversation('conversation-1');
    const pendingPublish = transport.publishWhenConnected.mock.calls[0][0];

    pendingPublish.publish();
    pendingPublish.publish();

    expect(createRestoreToken).toHaveBeenCalledTimes(2);
    expect(restoreService.expectAttempt.mock.calls).toEqual([
      ['00000000-0000-4000-8000-000000000001', 1],
      ['00000000-0000-4000-8000-000000000002', 2]
    ]);
    expect(transport.publish.mock.calls).toEqual([
      ['/app/ai/chat/conversation', {
        requestId: 'request-1', conversationId: 'conversation-1',
        restoreToken: '00000000-0000-4000-8000-000000000001', restoreSequence: 1
      }],
      ['/app/ai/chat/conversation', {
        requestId: 'request-1', conversationId: 'conversation-1',
        restoreToken: '00000000-0000-4000-8000-000000000002', restoreSequence: 2
      }]
    ]);
    expect(restoreService.expectAttempt.mock.invocationCallOrder[0])
      .toBeLessThan(transport.publish.mock.invocationCallOrder[0]);
  });

  it('uses secure random bytes when randomUUID is unavailable', () => {
    const originalCrypto = Object.getOwnPropertyDescriptor(window, 'crypto');
    const getRandomValues = vi.fn((bytes: Uint8Array) => {
      bytes.set(Array.from({length: 16}, (_, index) => index));
      return bytes;
    });
    Object.defineProperty(window, 'crypto', {
      configurable: true,
      value: {getRandomValues}
    });

    try {
      expect((component as any).createRestoreToken())
        .toBe('00010203-0405-4607-8809-0a0b0c0d0e0f');
      expect(getRandomValues).toHaveBeenCalledOnce();
    } finally {
      if (originalCrypto) {
        Object.defineProperty(window, 'crypto', originalCrypto);
      }
    }
  });

  it('accepts a synchronous start emitted from inside the publish call', () => {
    const restoreService = new AiConversationRestoreService();
    (component as any).conversationRestoreService = restoreService;
    vi.spyOn(component as any, 'createRestoreToken')
      .mockReturnValue('00000000-0000-4000-8000-000000000002');
    transport.publish.mockImplementation((_destination: string, body: any) => {
      (component as any).handleSocketEvent({
        requestId: body.requestId,
        conversationId: body.conversationId,
        type: 'HISTORY_START',
        metadata: {
          restoreToken: body.restoreToken,
          restoreSequence: body.restoreSequence
        }
      });
    });
    component.loadConversation('conversation-1');

    transport.publishWhenConnected.mock.calls[0][0].publish();

    expect(component.state.currentStatus).toBe('Restoring');
    expect(component.state.conversationId).toBe('conversation-1');
  });

  it('ignores an untagged error while an identified restore is active', () => {
    const restoreService = new AiConversationRestoreService();
    (component as any).conversationRestoreService = restoreService;
    vi.spyOn(component as any, 'createRestoreToken')
      .mockReturnValue('00000000-0000-4000-8000-000000000002');
    component.loadConversation('conversation-1');
    transport.publishWhenConnected.mock.calls[0][0].publish();

    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'system', subtype: 'error',
      content: 'Stale legacy failure.'
    });

    expect((component as any).activeRestoreRequestId).toBe('request-1');
    expect(component.state.restoringConversation).toBe(true);
    expect(component.state.messages).toEqual([]);
  });

  it('cleans up one latest restore error and ignores its duplicate', () => {
    const restoreService = new AiConversationRestoreService();
    (component as any).conversationRestoreService = restoreService;
    (component as any).activeRestoreRequestId = 'same-restore';
    component.state.restoringConversation = true;
    const token = '00000000-0000-4000-8000-000000000002';
    restoreService.expectAttempt(token, 2);
    (component as any).handleSocketEvent({
      requestId: 'same-restore', type: 'HISTORY_START', conversationId: 'conversation-1',
      metadata: {restoreToken: token, restoreSequence: 2}
    });
    const error = {
      requestId: 'same-restore', type: 'system', subtype: 'error',
      content: 'Restore B failed.',
      metadata: {restoreToken: token, restoreSequence: 2}
    };

    (component as any).handleSocketEvent(error);
    (component as any).handleSocketEvent(error);

    expect((component as any).activeRestoreRequestId).toBeUndefined();
    expect(component.state.restoringConversation).toBe(false);
    expect(component.state.currentStatus).toBe('Error');
    expect(component.state.messages).toEqual([{role: 'error', content: 'Restore B failed.'}]);
  });

});
