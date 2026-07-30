import {
  AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY,
  AiChatCommandService,
  api,
  component,
  of,
  setupAiChatPanelSpec,
  Subject,
  teardownAiChatPanelSpec,
  transport
} from './ai-chat-panel.component.spec-support';

describe('AiChatPanelComponent settings and active recovery', () => {
  beforeEach(setupAiChatPanelSpec);
  afterEach(teardownAiChatPanelSpec);

  it('restores a versioned model preference after model discovery', () => {
    localStorage.setItem(AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY, JSON.stringify({
      version: 1,
      modelName: 'claude-fable-5',
      reasoningEffort: 'medium',
      permissionMode: 'auto'
    }));

    (component as any).loadAvailableModels();

    expect(component.state.selectedModelName).toBe('claude-fable-5');
    expect(component.state.selectedReasoningEffort).toBe('medium');
    expect(JSON.parse(localStorage.getItem(AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY)!)).toEqual({
      version: 1,
      modelName: 'claude-fable-5',
      reasoningEffort: 'medium',
      permissionMode: 'auto'
    });
  });

  it('restores the legacy none preference as disabled reasoning', () => {
    localStorage.setItem(AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY, JSON.stringify({
      version: 1, modelName: 'claude-sonnet-5', reasoningEffort: 'none', permissionMode: 'ask'
    }));
    api.getAvailableModels.mockReturnValueOnce(of([{
      name: 'claude-sonnet-5', displayName: 'Claude Sonnet 5', description: 'Claude model.',
      provider: 'azure-foundry', defaultModel: true, defaultReasoningEffort: 'medium',
      reasoningEfforts: [
        {name: 'disabled', displayName: 'Disabled', description: 'Disable reasoning.'},
        {name: 'medium', displayName: 'Medium', description: 'Balanced reasoning.'}
      ]
    }]));

    (component as any).loadAvailableModels();

    expect(component.state.selectedReasoningEffort).toBe('disabled');
  });

  it('replaces stale preference capabilities and versions with current defaults', () => {
    localStorage.setItem(AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY, JSON.stringify({
      version: 1,
      modelName: 'gpt-5_6-sol',
      reasoningEffort: 'ultra'
    }));

    (component as any).loadAvailableModels();

    expect(JSON.parse(localStorage.getItem(AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY)!)).toEqual({
      version: 1,
      modelName: 'gpt-5_6-sol',
      reasoningEffort: 'medium',
      permissionMode: 'ask'
    });

    localStorage.setItem(AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY, JSON.stringify({
      version: 0,
      modelName: 'gpt-5_6-sol',
      reasoningEffort: 'medium'
    }));
    (component as any).loadAvailableModels();

    expect(JSON.parse(localStorage.getItem(AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY)!)).toEqual({
      version: 1,
      modelName: 'claude-fable-5',
      reasoningEffort: 'high',
      permissionMode: 'ask'
    });
  });

  it('changes the session model and reasoning effort through model command settings', () => {
    (component as any).loadAvailableModels();
    component.state.conversationId = 'conversation-1';
    api.updateConversationModel.mockReturnValue(of({
      conversationId: 'conversation-1', modelName: 'gpt-5_6-sol',
      reasoningEffort: 'high'
    }));

    (component as any).openModelSettings('/model');
    component.changeModelDraft('gpt-5_6-sol');
    component.state.modelDraftReasoningEffort = 'high';
    component.applyModelSettings();

    expect(api.getAvailableModels).toHaveBeenCalledOnce();
    expect(api.updateConversationModel)
      .toHaveBeenCalledWith('conversation-1', 'gpt-5_6-sol', 'high');
    expect(component.state.selectedModelName).toBe('gpt-5_6-sol');
    expect(component.state.selectedReasoningEffort).toBe('high');
    expect(component.state.modelSettingsOpen).toBe(false);
    expect(component.state.messages.some(message => message.content === '/model')).toBe(true);
    expect(JSON.parse(localStorage.getItem(AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY)!)).toEqual({
      version: 1, modelName: 'gpt-5_6-sol', reasoningEffort: 'high',
      permissionMode: 'ask'
    });
  });

  it('describes a model change without reasoning when disabled', () => {
    (component as any).loadAvailableModels();
    const model = component.state.availableModels[0];
    model.reasoningEfforts = [
      ...model.reasoningEfforts,
      {name: 'disabled', displayName: 'Disabled', description: 'Disable reasoning.'}
    ];
    component.state.modelSettingsOpen = true;
    component.state.modelDraftName = model.name;
    component.state.modelDraftReasoningEffort = 'disabled';

    component.applyModelSettings();

    expect(component.state.messages.at(-1)?.content)
      .toBe('Model changed to Claude Fable 5 without reasoning.');
  });

  it('removes the model command from history when its settings are cancelled', () => {
    (component as any).loadAvailableModels();

    (component as any).openModelSettings('/model');
    component.closeModelSettings();

    expect(component.state.modelSettingsOpen).toBe(false);
    expect(component.state.messages.some(message => message.content === '/model')).toBe(false);
  });

  it('updates the change approval policy through the permissions command', () => {
    (component as any).loadAvailableModels();

    (component as any).openPermissionSettings('/permissions');
    component.state.permissionDraft = 'full_access';
    component.applyPermissionSettings();

    expect(component.state.permissionMode).toBe('full_access');
    expect(component.state.permissionSettingsOpen).toBe(false);
    expect(component.state.messages.some(message => message.content === '/permissions')).toBe(true);
    expect(JSON.parse(localStorage.getItem(AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY)!))
      .toEqual(expect.objectContaining({permissionMode: 'full_access'}));

    component.state.prompt = 'Create the records';
    component.send();
    transport.publishWhenConnected.mock.calls[0][0].publish();
    expect(transport.publish).toHaveBeenCalledWith('/app/ai/chat',
      expect.objectContaining({permissionMode: 'full_access'}));
  });

  it('restores and follows a running request after a full page refresh', () => {
    vi.useFakeTimers();
    const running = {
      requestId: 'request-running', conversationId: 'conversation-1', generation: 1,
      status: 'RUNNING', deadline: '2026-07-17T05:00:00Z'
    };
    const completed = {...running, status: 'COMPLETED'};
    api.getActiveRequest.mockReturnValue(of(running));
    api.getRequestStatus
      .mockReturnValueOnce(of(running))
      .mockReturnValueOnce(of(completed));
    api.getConversation
      .mockReturnValueOnce(of({
        conversationId: 'conversation-1', title: 'Long task', modelName: 'claude-fable-5',
        reasoningEffort: 'high', messages: [
          {index: 0, role: 'user', content: 'Do a long task', requestId: 'request-running'}
        ]
      }))
      .mockReturnValueOnce(of({
        conversationId: 'conversation-1', title: 'Long task', modelName: 'claude-fable-5',
        reasoningEffort: 'high', messages: [
          {index: 0, role: 'user', content: 'Do a long task', requestId: 'request-running'}
        ]
      }))
      .mockReturnValueOnce(of({
        conversationId: 'conversation-1', title: 'Long task', modelName: 'claude-fable-5',
        reasoningEffort: 'high', messages: [
          {index: 0, role: 'user', content: 'Do a long task', requestId: 'request-running'},
          {index: 1, role: 'assistant', content: 'Task complete', requestId: 'request-running'}
        ]
      }));

    component.open();

    expect(component.state.pending).toBe(true);
    expect(component.state.activeRequest?.requestId).toBe('request-running');
    expect(component.state.selectedModelName).toBe('claude-fable-5');
    expect(component.state.messages.some(message => message.inProgress)).toBe(true);

    vi.advanceTimersByTime(1000);

    expect(component.state.pending).toBe(false);
    expect(component.state.activeRequest).toBeUndefined();
    expect(component.state.messages.some(message => message.content === 'Task complete')).toBe(true);
  });

  it('removes the recovery notice when live request activity resumes', () => {
    vi.useFakeTimers();
    const running = {
      requestId: 'request-running', conversationId: 'conversation-1', generation: 1,
      status: 'RUNNING', deadline: '2026-07-17T05:00:00Z'
    };
    api.getActiveRequest.mockReturnValue(of(running));
    api.getRequestStatus.mockReturnValue(of(running));
    api.getConversation.mockReturnValue(of({
      conversationId: 'conversation-1', title: 'Long task', messages: [
        {index: 0, role: 'user', content: 'Do a long task', requestId: 'request-running'}
      ]
    }));

    component.open();
    expect(component.state.messages).toContainEqual(expect.objectContaining({
      content: 'The request is still running. Progress is restored automatically.',
      inProgress: true
    }));

    (component as any).handleSocketEvent({
      requestId: 'request-stale', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'completed', turnId: 'request-stale',
      groupId: 'request-stale', toolCallId: 'stale-call',
      metadata: {toolName: 'get_acc', toolCallSeq: 0}
    });
    expect(component.state.messages).toContainEqual(expect.objectContaining({
      content: 'The request is still running. Progress is restored automatically.'
    }));

    (component as any).handleSocketEvent({
      requestId: 'request-running', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'completed', turnId: 'request-running',
      groupId: 'request-running', toolCallId: 'call-1',
      content: 'get_acc completed.',
      metadata: {toolName: 'get_acc', toolCallSeq: 0}
    });

    expect(component.state.messages.some(message =>
      message.content === 'The request is still running. Progress is restored automatically.'
    )).toBe(false);
    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'tool_call', content: 'get_acc completed.'
    }));
    vi.advanceTimersByTime(1000);
    expect(api.getConversation).toHaveBeenCalledOnce();
  });

  it('does not let a delayed recovery snapshot overwrite earlier live activity', () => {
    vi.useFakeTimers();
    const running = {
      requestId: 'request-running', conversationId: 'conversation-1', generation: 1,
      status: 'RUNNING', deadline: '2026-07-17T05:00:00Z'
    };
    const delayedConversation = new Subject<any>();
    api.getActiveRequest.mockReturnValue(of(running));
    api.getRequestStatus.mockReturnValue(of(running));
    api.getConversation.mockReturnValue(delayedConversation);

    component.open();
    (component as any).handleSocketEvent({
      requestId: 'request-running', conversationId: 'conversation-1',
      type: 'tool_call', subtype: 'completed', turnId: 'request-running',
      groupId: 'request-running', toolCallId: 'call-live',
      metadata: {toolName: 'get_acc', toolCallSeq: 0}
    });
    delayedConversation.next({
      conversationId: 'conversation-1', title: 'Stale snapshot', messages: [
        {index: 0, role: 'user', content: 'Do a long task', requestId: 'request-running'}
      ]
    });
    delayedConversation.complete();

    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'tool_call', content: 'get_acc completed.'
    }));
    expect(component.state.messages.some(message =>
      message.content === 'The request is still running. Progress is restored automatically.'
    )).toBe(false);
    vi.advanceTimersByTime(1000);
    expect(api.getConversation).toHaveBeenCalledOnce();
  });

  it('keeps the recovery notice for an invalid exact-request interaction event', () => {
    vi.useFakeTimers();
    const running = {
      requestId: 'request-running', conversationId: 'conversation-1', generation: 1,
      status: 'RUNNING', deadline: '2026-07-17T05:00:00Z'
    };
    api.getActiveRequest.mockReturnValue(of(running));
    api.getRequestStatus.mockReturnValue(of(running));
    api.getConversation.mockReturnValue(of({
      conversationId: 'conversation-1', title: 'Long task', messages: []
    }));
    component.open();

    (component as any).handleSocketEvent({
      requestId: 'request-running', conversationId: 'conversation-other',
      type: 'system', subtype: 'elicitation_required', visibility: 'visible',
      metadata: {
        elicitationId: 'elicitation-invalid', mode: 'form',
        expiresAt: '2099-07-15T00:00:00Z', message: 'Choose a strategy.',
        requestedSchema: {type: 'object', properties: {strategy: {type: 'string'}}}
      }
    });

    expect(component.state.messages).toContainEqual(expect.objectContaining({
      content: 'The request is still running. Progress is restored automatically.'
    }));
    vi.advanceTimersByTime(1000);
    expect(api.getConversation).toHaveBeenCalledTimes(2);
  });

  it('preserves cancellation acknowledgement while retiring recovery polling', () => {
    vi.useFakeTimers();
    const running = {
      requestId: 'request-running', conversationId: 'conversation-1', generation: 1,
      status: 'RUNNING', deadline: '2026-07-17T05:00:00Z'
    };
    api.getActiveRequest.mockReturnValue(of(running));
    api.getRequestStatus.mockReturnValue(of(running));
    api.getConversation.mockReturnValue(of({
      conversationId: 'conversation-1', title: 'Long task', messages: []
    }));
    component.open();
    component.cancelActiveRequest();

    (component as any).handleSocketEvent({
      requestId: 'request-running', conversationId: 'conversation-1',
      type: 'system', subtype: 'cancellation_acknowledged', sequence: 1,
      metadata: {
        cancellationRequestId: 'cancel-1', effectiveCancellationRequestId: 'cancel-1',
        generation: 1, lifecycleEventSequence: 4, status: 'CANCELLING',
        acknowledged: true, terminal: false
      }
    });

    expect(component.state.cancellation.phase).toBe('acknowledged');
    expect(component.state.messages).toContainEqual(expect.objectContaining({
      role: 'progress', content: 'Cancellation acknowledged.', inProgress: true
    }));
    vi.advanceTimersByTime(1000);
    expect(api.getConversation).toHaveBeenCalledOnce();
  });

  it('shows model normally and only cancel while a request is active', () => {
    (component as any).commandService = new AiChatCommandService();
    component.state.prompt = '/';

    expect(component.commandSuggestions.map(command => command.name)).toContain('/model');
    expect(component.commandSuggestions.map(command => command.name)).not.toContain('/runtime');
    expect(component.commandSuggestions.map(command => command.name)).not.toContain('/cancel');

    component.state.pending = true;
    component.state.activeRequest = {requestId: 'request-1'};

    expect(component.commandSuggestions.map(command => command.name)).toEqual(['/cancel']);
    expect(component.showCommandSuggestions).toBe(true);
  });

  it('accepts the cancel command while a request is active', () => {
    (component as any).commandService = new AiChatCommandService();
    component.state.prompt = '/cancel';
    component.state.pending = true;
    component.state.activeRequest = {requestId: 'request-1'};
    (component as any).activeRequestPublished = false;

    component.send();

    expect(component.state.prompt).toBe('');
    expect(component.state.pending).toBe(false);
  });

  it('toggles debug progress with the single debug command', () => {
    (component as any).commandService = new AiChatCommandService();
    component.state.prompt = '/debug';

    component.send();

    expect(component.state.debugEnabled).toBe(true);
    expect(component.state.messages.at(-1)?.content).toContain('enabled');

    component.state.prompt = '/debug';
    component.send();

    expect(component.state.debugEnabled).toBe(false);
    expect(component.state.messages.at(-1)?.content).toContain('disabled');
  });

});
