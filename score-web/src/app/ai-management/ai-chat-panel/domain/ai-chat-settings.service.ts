import {Injectable, inject} from '@angular/core';
import {AiChatPanelState} from './ai-chat-panel-state';
import {AiChatSessionPersistenceService} from './ai-chat-session-persistence.service';

@Injectable()
export class AiChatSettingsService {
  private persistence = inject(AiChatSessionPersistenceService);
  private modelCommandMessageIndex?: number;
  private permissionCommandMessageIndex?: number;

  reset(): void {
    this.modelCommandMessageIndex = undefined;
    this.permissionCommandMessageIndex = undefined;
  }

  changeModelDraft(state: AiChatPanelState, modelName: string): void {
    const model = state.availableModels.find(candidate => candidate.name === modelName);
    if (!model || state.modelChangePending) return;
    state.modelDraftName = modelName;
    if (!model.reasoningEfforts.some(effort => effort.name === state.modelDraftReasoningEffort)) {
      state.modelDraftReasoningEffort = model.defaultReasoningEffort;
    }
  }

  closeModel(state: AiChatPanelState): boolean {
    if (state.modelChangePending) return false;
    this.discardCommandMessage(state, this.modelCommandMessageIndex, '/model');
    this.modelCommandMessageIndex = undefined;
    state.modelSettingsOpen = false;
    return true;
  }

  openModel(state: AiChatPanelState, commandText: string): void {
    const model = state.selectedModel() || state.defaultModel();
    state.prompt = '';
    state.messages.push({role: 'user', content: commandText});
    this.modelCommandMessageIndex = state.messages.length - 1;
    if (!model) {
      this.modelCommandMessageIndex = undefined;
      state.messages.push({role: 'error', content: 'No assistant models are available.'});
      return;
    }
    state.modelDraftName = model.name;
    state.modelDraftReasoningEffort = model.reasoningEfforts
      .some(effort => effort.name === state.selectedReasoningEffort)
      ? state.selectedReasoningEffort : model.defaultReasoningEffort;
    state.modelSettingsOpen = true;
  }

  finishModel(state: AiChatPanelState, displayName: string, reasoningEffort: string): void {
    this.modelCommandMessageIndex = undefined;
    state.modelSettingsOpen = false;
    state.modelDraftName = '';
    state.modelDraftReasoningEffort = '';
    this.persistence.persistSelection(state);
    state.messages.push({
      role: 'debug',
      content: `Model changed to ${displayName} with ${reasoningEffort} reasoning effort.`
    });
  }

  openPermission(state: AiChatPanelState, commandText: string): void {
    state.prompt = '';
    state.messages.push({role: 'user', content: commandText});
    this.permissionCommandMessageIndex = state.messages.length - 1;
    state.permissionDraft = state.permissionMode;
    state.permissionSettingsOpen = true;
  }

  applyPermission(state: AiChatPanelState): boolean {
    if (!state.permissionSettingsOpen) return false;
    state.permissionMode = state.permissionDraft;
    this.permissionCommandMessageIndex = undefined;
    state.permissionSettingsOpen = false;
    this.persistence.persistSelection(state);
    const label = state.permissionMode === 'ask' ? 'Ask for approval'
      : state.permissionMode === 'auto' ? 'Ask only for risky actions' : 'Full access';
    state.messages.push({
      role: 'debug', content: `Mutation permissions changed to ${label}.`
    });
    return true;
  }

  closePermission(state: AiChatPanelState): void {
    this.discardCommandMessage(
      state, this.permissionCommandMessageIndex, '/permissions'
    );
    this.permissionCommandMessageIndex = undefined;
    state.permissionSettingsOpen = false;
    state.permissionDraft = state.permissionMode;
  }

  private discardCommandMessage(state: AiChatPanelState, index: number | undefined,
                                command: string): void {
    if (index === undefined) return;
    const message = state.messages[index];
    if (message?.role === 'user' && message.content.trim().toLowerCase() === command) {
      state.messages.splice(index, 1);
    }
  }
}
