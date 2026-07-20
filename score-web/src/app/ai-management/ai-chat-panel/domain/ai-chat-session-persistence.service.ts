import {Injectable, inject} from '@angular/core';
import {AuthService} from '../../../authentication/auth.service';
import {AiChatPanelState} from './ai-chat-panel-state';
import {MAX_ATTACHMENTS, MAX_TOTAL_ATTACHMENT_BYTES} from './ai-chat-panel.constants';
import {
  AiChatAttachment,
  AiChatDock,
  AiChatPanelTab,
  AiMutationPermissionMode,
  AiRuntimeOptions
} from './ai-chat-panel.model';

export const AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY = 'score.ai-chat.selection-preference';
export const AI_CHAT_LAST_CONVERSATION_STORAGE_KEY_PREFIX = 'score.ai-chat.last-conversation';
export const AI_CHAT_PANEL_DOCK_STORAGE_KEY_PREFIX = 'score.ai-chat.panel-dock';
export const AI_CHAT_PANEL_VISIBILITY_STORAGE_KEY_PREFIX = 'score.ai-chat.panel-visibility';
export const AI_CHAT_WINDOW_MODE_STORAGE_KEY_PREFIX = 'score.ai-chat.window-mode';
export const AI_CHAT_WORKSPACE_STORAGE_KEY_PREFIX = 'score.ai-chat.workspace';
const AI_CHAT_SELECTION_PREFERENCE_VERSION = 1;
const AI_CHAT_WORKSPACE_VERSION = 1;
const AI_CHAT_DRAFT_DATABASE = 'score-ai-chat-drafts';
const AI_CHAT_DRAFT_ATTACHMENT_STORE = 'attachments';

interface AiChatWorkspaceSnapshot {
  version: number;
  activePanelTab: AiChatPanelTab;
  sideSize: number;
  horizontalSize: number;
  prompt: string;
  chatScrollTop: number;
  historyScrollTop: number;
}

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
  private draftDatabasePromise?: Promise<IDBDatabase | undefined>;

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

  restorePanelVisibility(): boolean {
    try {
      return localStorage.getItem(this.panelVisibilityStorageKey()) === 'open';
    } catch {
      return false;
    }
  }

  persistPanelVisibility(isOpen: boolean): void {
    try {
      localStorage.setItem(this.panelVisibilityStorageKey(), isOpen ? 'open' : 'closed');
    } catch {
      // Storage can be disabled or full; the in-memory visibility remains usable.
    }
  }

  restorePanelDock(): AiChatDock | undefined {
    try {
      const dock = localStorage.getItem(this.panelDockStorageKey());
      return dock === 'right' || dock === 'left' || dock === 'bottom' || dock === 'top'
        ? dock : undefined;
    } catch {
      return undefined;
    }
  }

  persistPanelDock(dock: AiChatDock): void {
    try {
      localStorage.setItem(this.panelDockStorageKey(), dock);
    } catch {
      // Storage can be disabled or full; the in-memory dock remains usable.
    }
  }

  restorePopoutActive(): boolean {
    try {
      return localStorage.getItem(this.windowModeStorageKey()) === 'popout';
    } catch {
      return false;
    }
  }

  persistPopoutActive(active: boolean): void {
    try {
      localStorage.setItem(this.windowModeStorageKey(), active ? 'popout' : 'docked');
    } catch {
      // Storage can be disabled or full; the current window mode remains usable.
    }
  }

  restoreWorkspace(state: AiChatPanelState): boolean {
    try {
      const parsed: unknown = JSON.parse(
        localStorage.getItem(this.workspaceStorageKey()) || 'null'
      );
      if (!this.isRecord(parsed) || parsed['version'] !== AI_CHAT_WORKSPACE_VERSION) {
        return false;
      }
      const tab = parsed['activePanelTab'];
      if (tab === 'chat' || tab === 'history') state.activePanelTab = tab;
      state.sideSize = this.boundedNumber(parsed['sideSize'], 320, 5000, state.sideSize);
      state.horizontalSize = this.boundedNumber(
        parsed['horizontalSize'], 240, 5000, state.horizontalSize
      );
      if (typeof parsed['prompt'] === 'string') {
        state.prompt = parsed['prompt'].slice(0, 200_000);
      }
      state.chatScrollTop = this.boundedNumber(parsed['chatScrollTop'], 0, 10_000_000, 0);
      state.historyScrollTop = this.boundedNumber(parsed['historyScrollTop'], 0, 10_000_000, 0);
      return true;
    } catch {
      return false;
    }
  }

  persistWorkspace(state: AiChatPanelState): void {
    const snapshot: AiChatWorkspaceSnapshot = {
      version: AI_CHAT_WORKSPACE_VERSION,
      activePanelTab: state.activePanelTab,
      sideSize: state.sideSize,
      horizontalSize: state.horizontalSize,
      prompt: state.prompt,
      chatScrollTop: state.chatScrollTop,
      historyScrollTop: state.historyScrollTop
    };
    try {
      localStorage.setItem(this.workspaceStorageKey(), JSON.stringify(snapshot));
    } catch {
      // Storage can be disabled or full; the in-memory workspace remains usable.
    }
  }

  async restoreDraftAttachments(): Promise<AiChatAttachment[]> {
    const database = await this.draftDatabase();
    if (!database) return [];
    return new Promise(resolve => {
      try {
        const request = database.transaction(AI_CHAT_DRAFT_ATTACHMENT_STORE, 'readonly')
          .objectStore(AI_CHAT_DRAFT_ATTACHMENT_STORE).get(this.currentUsername());
        request.onsuccess = () => resolve(this.validAttachments(request.result));
        request.onerror = () => resolve([]);
      } catch {
        resolve([]);
      }
    });
  }

  async persistDraftAttachments(attachments: AiChatAttachment[]): Promise<void> {
    const database = await this.draftDatabase();
    if (!database) return;
    await new Promise<void>(resolve => {
      try {
        const transaction = database.transaction(AI_CHAT_DRAFT_ATTACHMENT_STORE, 'readwrite');
        const store = transaction.objectStore(AI_CHAT_DRAFT_ATTACHMENT_STORE);
        if (attachments.length === 0) {
          store.delete(this.currentUsername());
        } else {
          store.put(attachments.map(attachment => ({...attachment})), this.currentUsername());
        }
        transaction.oncomplete = () => resolve();
        transaction.onerror = () => resolve();
        transaction.onabort = () => resolve();
      } catch {
        resolve();
      }
    });
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

  private panelVisibilityStorageKey(): string {
    const username = this.auth.getUserToken()?.username?.trim() || 'unknown';
    return `${AI_CHAT_PANEL_VISIBILITY_STORAGE_KEY_PREFIX}:${encodeURIComponent(username)}`;
  }

  private panelDockStorageKey(): string {
    return `${AI_CHAT_PANEL_DOCK_STORAGE_KEY_PREFIX}:${encodeURIComponent(this.currentUsername())}`;
  }

  private workspaceStorageKey(): string {
    return `${AI_CHAT_WORKSPACE_STORAGE_KEY_PREFIX}:${encodeURIComponent(this.currentUsername())}`;
  }

  private windowModeStorageKey(): string {
    return `${AI_CHAT_WINDOW_MODE_STORAGE_KEY_PREFIX}:${encodeURIComponent(this.currentUsername())}`;
  }

  private currentUsername(): string {
    return this.auth.getUserToken()?.username?.trim() || 'unknown';
  }

  private boundedNumber(value: unknown, minimum: number, maximum: number,
                        fallback: number): number {
    return typeof value === 'number' && Number.isFinite(value)
      ? Math.min(maximum, Math.max(minimum, value)) : fallback;
  }

  private validAttachments(value: unknown): AiChatAttachment[] {
    if (!Array.isArray(value)) return [];
    const attachments = value.filter((attachment): attachment is AiChatAttachment =>
      this.isRecord(attachment)
      && typeof attachment['name'] === 'string'
      && typeof attachment['mediaType'] === 'string'
      && typeof attachment['size'] === 'number'
      && Number.isFinite(attachment['size'])
      && attachment['size'] >= 0
      && typeof attachment['data'] === 'string'
    ).slice(0, MAX_ATTACHMENTS);
    let totalBytes = 0;
    return attachments.flatMap(attachment => {
      totalBytes += attachment.size;
      return totalBytes <= MAX_TOTAL_ATTACHMENT_BYTES ? [{...attachment}] : [];
    });
  }

  private draftDatabase(): Promise<IDBDatabase | undefined> {
    if (this.draftDatabasePromise) return this.draftDatabasePromise;
    if (typeof indexedDB === 'undefined') return Promise.resolve(undefined);
    this.draftDatabasePromise = new Promise(resolve => {
      try {
        const request = indexedDB.open(AI_CHAT_DRAFT_DATABASE, 1);
        request.onupgradeneeded = () => {
          if (!request.result.objectStoreNames.contains(AI_CHAT_DRAFT_ATTACHMENT_STORE)) {
            request.result.createObjectStore(AI_CHAT_DRAFT_ATTACHMENT_STORE);
          }
        };
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => resolve(undefined);
        request.onblocked = () => resolve(undefined);
      } catch {
        resolve(undefined);
      }
    });
    return this.draftDatabasePromise;
  }

  private isRecord(value: unknown): value is Record<string, unknown> {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
  }

}
