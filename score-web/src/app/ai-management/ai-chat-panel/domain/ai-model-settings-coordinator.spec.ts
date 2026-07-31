import {TestBed} from '@angular/core/testing';
import {Subject, of, throwError} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {AiChatApiService} from './ai-chat-api.service';
import {AiChatPanelState} from './ai-chat-panel-state';
import {AiChatSessionPersistenceService} from './ai-chat-session-persistence.service';
import {AiModelSettingsCoordinator} from './ai-model-settings-coordinator';
import {AiChatModelInfo} from './ai-chat-panel.model';

describe('AiModelSettingsCoordinator', () => {
  let coordinator: AiModelSettingsCoordinator;
  let state: AiChatPanelState;
  let api: {getAvailableModels: ReturnType<typeof vi.fn>;
    updateConversationModel: ReturnType<typeof vi.fn>};
  let persistence: {restoreSelection: ReturnType<typeof vi.fn>;
    persistSelection: ReturnType<typeof vi.fn>};
  const model: AiChatModelInfo = {
    name: 'model-2', displayName: 'Model Two', description: 'Second model',
    provider: 'test', defaultModel: false, defaultReasoningEffort: 'medium',
    reasoningEfforts: [{name: 'medium', displayName: 'Medium', description: 'Balanced'}]
  };

  beforeEach(() => {
    api = {getAvailableModels: vi.fn(), updateConversationModel: vi.fn()};
    persistence = {restoreSelection: vi.fn(), persistSelection: vi.fn()};
    TestBed.configureTestingModule({providers: [
      AiModelSettingsCoordinator,
      {provide: AiChatApiService, useValue: api},
      {provide: AiChatSessionPersistenceService, useValue: persistence}
    ]});
    coordinator = TestBed.inject(AiModelSettingsCoordinator);
    state = new AiChatPanelState();
  });

  it('loads preferences, fails closed, and stops loading after destruction', () => {
    const destroyed$ = new Subject<void>();
    api.getAvailableModels.mockReturnValueOnce(of([model]));
    coordinator.load(state, destroyed$);
    expect(state.availableModels).toEqual([model]);
    expect(persistence.restoreSelection).toHaveBeenCalledWith(state);

    state.setAvailableModels([model]);
    api.getAvailableModels.mockReturnValueOnce(throwError(() => new Error('offline')));
    coordinator.load(state, destroyed$);
    expect(state.availableModels).toEqual([]);

    const delayed = new Subject<AiChatModelInfo[]>();
    api.getAvailableModels.mockReturnValueOnce(delayed);
    coordinator.load(state, destroyed$);
    destroyed$.next();
    delayed.next([model]);
    expect(state.availableModels).toEqual([]);
  });

  it('applies a remote model with context state and compaction notification', () => {
    const destroyed$ = new Subject<void>();
    state.setAvailableModels([model]);
    state.conversationId = 'conversation-1';
    state.modelSettingsOpen = true;
    state.modelDraftName = model.name;
    state.modelDraftReasoningEffort = 'medium';
    const contextUsage = {
      modelName: model.name, currentInputTokens: 20, contextWindow: 100,
      safeInputLimit: 80, remainingTokens: 60, usedPercent: 25,
      estimated: false, source: 'provider'
    };
    api.updateConversationModel.mockReturnValue(of({
      conversationId: 'conversation-1', modelName: model.name,
      reasoningEffort: 'medium', contextCompacted: true, contextUsage
    }));
    const callbacks = {completed: vi.fn(), compacted: vi.fn(), failed: vi.fn()};

    coordinator.apply(state, destroyed$, callbacks);

    expect(state.contextUsage).toEqual(contextUsage);
    expect(state.modelChangePending).toBe(false);
    expect(callbacks.completed).toHaveBeenCalledOnce();
    expect(callbacks.compacted).toHaveBeenCalledOnce();
    expect(callbacks.failed).not.toHaveBeenCalled();
    expect(persistence.persistSelection).toHaveBeenCalledWith(state);
  });

  it('rolls back a failed remote change and clears pending state', () => {
    state.setAvailableModels([model]);
    state.selectedModelName = 'model-1';
    state.selectedReasoningEffort = 'high';
    state.conversationId = 'conversation-1';
    state.modelSettingsOpen = true;
    state.modelDraftName = model.name;
    state.modelDraftReasoningEffort = 'medium';
    api.updateConversationModel.mockReturnValue(throwError(() => new Error('failed')));
    const callbacks = {completed: vi.fn(), compacted: vi.fn(), failed: vi.fn()};

    coordinator.apply(state, new Subject<void>(), callbacks);

    expect(state.selectedModelName).toBe('model-1');
    expect(state.selectedReasoningEffort).toBe('high');
    expect(state.modelChangePending).toBe(false);
    expect(callbacks.failed).toHaveBeenCalledOnce();
  });

  it('ignores a remote change response after destruction', () => {
    const destroyed$ = new Subject<void>();
    const response$ = new Subject<{
      conversationId: string;
      modelName: string;
      reasoningEffort: string;
    }>();
    state.setAvailableModels([model]);
    state.selectedModelName = 'model-1';
    state.selectedReasoningEffort = 'high';
    state.conversationId = 'conversation-1';
    state.modelSettingsOpen = true;
    state.modelDraftName = model.name;
    state.modelDraftReasoningEffort = 'medium';
    api.updateConversationModel.mockReturnValue(response$);
    const callbacks = {completed: vi.fn(), compacted: vi.fn(), failed: vi.fn()};

    coordinator.apply(state, destroyed$, callbacks);
    destroyed$.next();
    response$.next({
      conversationId: 'conversation-1', modelName: model.name, reasoningEffort: 'medium'
    });

    expect(state.selectedModelName).toBe('model-1');
    expect(state.selectedReasoningEffort).toBe('high');
    expect(persistence.persistSelection).not.toHaveBeenCalled();
    expect(callbacks.completed).not.toHaveBeenCalled();
    expect(callbacks.compacted).not.toHaveBeenCalled();
    expect(callbacks.failed).not.toHaveBeenCalled();
  });

  it('guards invalid duplicate apply and refuses close while pending', () => {
    state.setAvailableModels([model]);
    state.modelSettingsOpen = true;
    state.modelDraftName = model.name;
    state.modelDraftReasoningEffort = 'invalid';
    coordinator.apply(state, new Subject<void>(), {
      completed: vi.fn(), compacted: vi.fn(), failed: vi.fn()
    });
    expect(api.updateConversationModel).not.toHaveBeenCalled();
    state.modelChangePending = true;
    state.conversationId = 'conversation-1';
    state.modelDraftReasoningEffort = 'medium';
    coordinator.apply(state, new Subject<void>(), {
      completed: vi.fn(), compacted: vi.fn(), failed: vi.fn()
    });
    expect(api.updateConversationModel).not.toHaveBeenCalled();
    expect(coordinator.close(state)).toBe(false);
  });
});
