import {
  AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY,
  AiChatCommandService,
  api,
  component,
  of,
  setupAiChatPanelSpec,
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

  it('removes the model command from history when its settings are cancelled', () => {
    (component as any).loadAvailableModels();

    (component as any).openModelSettings('/model');
    component.closeModelSettings();

    expect(component.state.modelSettingsOpen).toBe(false);
    expect(component.state.messages.some(message => message.content === '/model')).toBe(false);
  });

  it('changes mutation approval policy through the permissions command', () => {
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
