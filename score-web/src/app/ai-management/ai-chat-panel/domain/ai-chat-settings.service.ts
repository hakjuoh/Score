import {Injectable, inject} from '@angular/core';
import {AiChatPanelState} from './ai-chat-panel-state';
import {AiChatSessionPersistenceService} from './ai-chat-session-persistence.service';
import {AiChatRuntimeInfo} from './ai-chat-panel.model';

@Injectable()
export class AiChatSettingsService {
  private persistence = inject(AiChatSessionPersistenceService);
  private modelCommandMessageIndex?: number;
  private runtimeCommandMessageIndex?: number;
  private permissionCommandMessageIndex?: number;

  reset(): void {
    this.modelCommandMessageIndex = undefined;
    this.runtimeCommandMessageIndex = undefined;
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

  changeRuntimeDraft(state: AiChatPanelState, runtime: string): void {
    const model = state.selectedModel() || state.defaultModel();
    if (state.modelChangePending
      || !model || !this.modelRuntimes(model).some(candidate => candidate.name === runtime)) {
      return;
    }
    state.draftRuntime(runtime);
  }

  closeRuntime(state: AiChatPanelState): boolean {
    if (state.modelChangePending) return false;
    this.discardCommandMessage(state, this.runtimeCommandMessageIndex, '/runtime');
    this.runtimeCommandMessageIndex = undefined;
    state.runtimeSettingsOpen = false;
    state.runtimeDraft = '';
    state.runtimeDraftOptions = {};
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

  openRuntime(state: AiChatPanelState, commandText: string): void {
    const model = state.selectedModel() || state.defaultModel();
    state.prompt = '';
    state.messages.push({role: 'user', content: commandText});
    this.runtimeCommandMessageIndex = state.messages.length - 1;
    if (!model) {
      this.runtimeCommandMessageIndex = undefined;
      state.messages.push({role: 'error', content: 'No assistant runtimes are available.'});
      return;
    }
    const runtime = this.modelRuntimes(model)
      .some(candidate => candidate.name === state.selectedRuntime)
      ? state.selectedRuntime : model.defaultRuntime || 'default';
    state.draftRuntime(runtime);
    state.runtimeSettingsOpen = true;
  }

  finishRuntime(state: AiChatPanelState, runtimeDisplayName: string): void {
    this.runtimeCommandMessageIndex = undefined;
    state.runtimeSettingsOpen = false;
    state.runtimeDraft = '';
    state.runtimeDraftOptions = {};
    this.persistence.persistSelection(state);
    state.messages.push({
      role: 'debug', content: `Runtime changed to ${runtimeDisplayName}.`
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
      : state.permissionMode === 'auto' ? 'Approve for me' : 'Full access';
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

  modelRuntimes(model: {runtimes?: AiChatRuntimeInfo[]}): AiChatRuntimeInfo[] {
    return model.runtimes?.length ? model.runtimes : [{
      name: 'default', displayName: 'Default',
      description: 'Uses the Default runtime.', settings: []
    }];
  }

  availableRuntime(state: AiChatPanelState, requested: string): string {
    const model = state.selectedModel();
    if (!model && state.availableModels.length === 0) {
      return requested?.trim() || 'default';
    }
    const runtimes = model ? this.modelRuntimes(model) : [];
    return runtimes.some(runtime => runtime.name === requested)
      ? requested : model?.defaultRuntime || 'default';
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
