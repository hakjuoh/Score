/**
 * Loads model choices and coordinates draft model and reasoning-effort selection.
 */

import {Injectable, inject} from '@angular/core';
import {Observable} from 'rxjs';
import {take, takeUntil} from 'rxjs/operators';
import {AiChatApiService} from './ai-chat-api.service';
import {contextUsageValue} from './ai-chat-event-semantics';
import {aiModelSessionLabel} from './ai-chat-panel.model';
import {AiChatPanelState} from './ai-chat-panel-state';
import {AiChatSessionPersistenceService} from './ai-chat-session-persistence.service';

export interface AiModelSettingsCallbacks {
  completed(): void;
  compacted(): void;
  failed(): void;
}

@Injectable()
export class AiModelSettingsCoordinator {
  private api = inject(AiChatApiService);
  private persistence = inject(AiChatSessionPersistenceService);
  private commandMessageIndex?: number;

  reset(): void {
    this.commandMessageIndex = undefined;
  }

  load(state: AiChatPanelState, destroyed$: Observable<void>): void {
    this.api.getAvailableModels().pipe(take(1), takeUntil(destroyed$)).subscribe({
      next: models => {
        state.setAvailableModels(models);
        if (!state.conversationId) this.persistence.restoreSelection(state);
      },
      error: () => state.setAvailableModels([])
    });
  }

  open(state: AiChatPanelState, commandText: string): void {
    const model = state.selectedModel() || state.defaultModel();
    state.prompt = '';
    state.messages.push({role: 'user', content: commandText});
    this.commandMessageIndex = state.messages.length - 1;
    if (!model) {
      this.commandMessageIndex = undefined;
      state.messages.push({role: 'error', content: 'No assistant models are available.'});
      return;
    }
    state.modelDraftName = model.name;
    state.modelDraftReasoningEffort = model.reasoningEfforts
      .some(effort => effort.name === state.selectedReasoningEffort)
      ? state.selectedReasoningEffort : model.defaultReasoningEffort;
    state.modelSettingsOpen = true;
  }

  changeDraft(state: AiChatPanelState, modelName: string): void {
    const model = state.availableModels.find(candidate => candidate.name === modelName);
    if (!model || state.modelChangePending) return;
    state.modelDraftName = modelName;
    if (!model.reasoningEfforts.some(effort => effort.name === state.modelDraftReasoningEffort)) {
      state.modelDraftReasoningEffort = model.defaultReasoningEffort;
    }
  }

  apply(state: AiChatPanelState, destroyed$: Observable<void>,
        callbacks: AiModelSettingsCallbacks): void {
    const model = state.availableModels.find(candidate => candidate.name === state.modelDraftName);
    const effort = state.modelDraftReasoningEffort;
    if (!state.modelSettingsOpen || !model
      || !model.reasoningEfforts.some(candidate => candidate.name === effort)
      || state.modelChangePending) return;
    const previous = {
      modelName: state.selectedModelName,
      reasoningEffort: state.selectedReasoningEffort
    };
    if (!state.conversationId) {
      state.selectedModelName = model.name;
      state.selectedReasoningEffort = effort;
      state.resetContextUsageForSelectedModel();
      this.finish(state, model.displayName, effort);
      callbacks.completed();
      return;
    }
    state.modelChangePending = true;
    this.api.updateConversationModel(state.conversationId, model.name, effort).pipe(
      take(1), takeUntil(destroyed$)
    ).subscribe({
      next: response => {
        state.selectedModelName = response.modelName;
        state.selectedReasoningEffort = response.reasoningEffort;
        state.resetContextUsageForSelectedModel();
        state.setContextUsage(contextUsageValue(response.contextUsage, response.modelName));
        state.modelChangePending = false;
        this.finish(state, model.displayName, response.reasoningEffort);
        callbacks.completed();
        if (response.contextCompacted) callbacks.compacted();
      },
      error: () => {
        state.selectedModelName = previous.modelName;
        state.selectedReasoningEffort = previous.reasoningEffort;
        state.modelChangePending = false;
        callbacks.failed();
      }
    });
  }

  close(state: AiChatPanelState): boolean {
    if (state.modelChangePending) return false;
    const message = this.commandMessageIndex === undefined
      ? undefined : state.messages[this.commandMessageIndex];
    if (message?.role === 'user' && message.content.trim().toLowerCase() === '/model') {
      state.messages.splice(this.commandMessageIndex!, 1);
    }
    this.commandMessageIndex = undefined;
    state.modelSettingsOpen = false;
    return true;
  }

  private finish(state: AiChatPanelState, displayName: string, effort: string): void {
    this.commandMessageIndex = undefined;
    state.modelSettingsOpen = false;
    state.modelDraftName = '';
    state.modelDraftReasoningEffort = '';
    this.persistence.persistSelection(state);
    state.messages.push({
      role: 'debug', content: `Model changed to ${aiModelSessionLabel(displayName, effort)}.`
    });
  }
}
