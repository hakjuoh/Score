import {Injectable, inject} from '@angular/core';
import {AuthService} from '../../../authentication/auth.service';
import {AiChatPanelState} from './ai-chat-panel-state';
import {AiMutationPermissionMode, AiRuntimeOptions} from './ai-chat-panel.model';

export const AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY = 'score.ai-chat.selection-preference';
export const AI_CHAT_LAST_CONVERSATION_STORAGE_KEY_PREFIX = 'score.ai-chat.last-conversation';
const AI_CHAT_SELECTION_PREFERENCE_VERSION = 1;

interface AiChatSelectionPreference {
  version: number;
  modelName: string;
  reasoningEffort: string;
  runtime: string;
  runtimeOptions: AiRuntimeOptions;
  permissionMode: AiMutationPermissionMode;
}

@Injectable({providedIn: 'root'})
export class AiChatSessionPersistenceService {
  private auth = inject(AuthService);

  restoreSelection(state: AiChatPanelState): void {
    if (state.availableModels.length === 0) return;
    const stored = this.readSelectionPreference();
    if (!stored.present) return;

    const preference = stored.preference;
    const requestedModelName = typeof preference?.modelName === 'string'
      ? preference.modelName : '';
    const model = state.availableModels.find(candidate => candidate.name === requestedModelName)
      || state.defaultModel();
    if (!model) return;

    const requestedReasoningEffort = typeof preference?.reasoningEffort === 'string'
      ? preference.reasoningEffort : '';
    const reasoningEffort = model.reasoningEfforts
      .some(candidate => candidate.name === requestedReasoningEffort)
      ? requestedReasoningEffort : model.defaultReasoningEffort;
    const requestedRuntime = typeof preference?.runtime === 'string' ? preference.runtime : '';
    const runtime = model.runtimes.some(candidate => candidate.name === requestedRuntime)
      ? requestedRuntime : model.defaultRuntime || 'default';
    const requestedRuntimeOptions = this.isRecord(preference?.runtimeOptions)
      ? preference.runtimeOptions : {};
    const requestedPermissionMode = preference?.permissionMode;

    state.selectedModelName = model.name;
    state.selectedReasoningEffort = reasoningEffort;
    state.selectRuntime(
      runtime, state.runtimeOptionsFor(runtime, requestedRuntimeOptions, model)
    );
    state.permissionMode = requestedPermissionMode === 'auto'
      || requestedPermissionMode === 'full_access' ? requestedPermissionMode : 'ask';
    state.permissionDraft = state.permissionMode;
    this.persistSelection(state);
  }

  persistSelection(state: AiChatPanelState): void {
    if (!state.selectedModelName || !state.selectedReasoningEffort || !state.selectedRuntime) {
      return;
    }
    const preference: AiChatSelectionPreference = {
      version: AI_CHAT_SELECTION_PREFERENCE_VERSION,
      modelName: state.selectedModelName,
      reasoningEffort: state.selectedReasoningEffort,
      runtime: state.selectedRuntime,
      runtimeOptions: {...state.selectedRuntimeOptions},
      permissionMode: state.permissionMode
    };
    try {
      localStorage.setItem(AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY, JSON.stringify(preference));
    } catch {
      // Storage can be disabled or full; the in-memory selection remains usable.
    }
  }

  readLastConversation(): string | undefined {
    try {
      const conversationId = localStorage.getItem(this.lastConversationStorageKey())?.trim();
      return conversationId || undefined;
    } catch {
      return undefined;
    }
  }

  rememberLastConversation(conversationId?: string): void {
    const normalized = conversationId?.trim();
    if (!normalized) return;
    try {
      localStorage.setItem(this.lastConversationStorageKey(), normalized);
    } catch {
      // Storage can be disabled or full; the in-memory conversation remains usable.
    }
  }

  clearLastConversation(): void {
    try {
      localStorage.removeItem(this.lastConversationStorageKey());
    } catch {
      // Storage can be disabled; there is no persisted value to clear in that case.
    }
  }

  private readSelectionPreference(): {
    present: boolean;
    preference?: Partial<AiChatSelectionPreference>;
  } {
    try {
      const raw = localStorage.getItem(AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY);
      if (raw === null) return {present: false};
      const parsed: unknown = JSON.parse(raw);
      if (!this.isRecord(parsed)
        || parsed['version'] !== AI_CHAT_SELECTION_PREFERENCE_VERSION) {
        return {present: true};
      }
      return {present: true, preference: parsed};
    } catch {
      return {present: true};
    }
  }

  private lastConversationStorageKey(): string {
    const username = this.auth.getUserToken()?.username?.trim() || 'unknown';
    return `${AI_CHAT_LAST_CONVERSATION_STORAGE_KEY_PREFIX}:${encodeURIComponent(username)}`;
  }

  private isRecord(value: unknown): value is Record<string, unknown> {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
  }
}
