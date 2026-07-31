/**
 * Owns the mutable UI and request state used throughout the AI chat panel.
 */

import {SafeHtml} from '@angular/platform-browser';
import {AiAgentActivity} from './ai-agent-activity';
import {
  AiChatAttachment,
  AiActiveRequestIdentity,
  AiCancellationUiState,
  AiChatConversationSummary,
  AiChatConversationDetails,
  AiChatDock,
  AiChatModelInfo,
  AiChatMessage,
  AiMcpStatus,
  AiContextUsage,
  AiElicitationNotice,
  AiChangeApprovalBatchNotice,
  AiChangePermissionMode,
  AiChatPanelTab,
  AiSelfPolicy,
  checkingAiMcpStatus,
  normalizeAiReasoningEffort
} from './ai-chat-panel.model';

export class AiChatPanelState {
  isOpen = false;
  pending = false;
  reconciliationRequired = false;
  activeRequest?: AiActiveRequestIdentity;
  cancellation: AiCancellationUiState = this.idleCancellation();
  dock: AiChatDock = 'right';
  sideSize = 420;
  horizontalSize = 320;
  chatScrollTop = 0;
  historyScrollTop = 0;
  popoutActive = false;
  prompt = '';
  attachments: AiChatAttachment[] = [];
  dragActive = false;
  conversationId?: string;
  availableModels: AiChatModelInfo[] = [];
  policy?: AiSelfPolicy;
  policyLoading = false;
  policyLoadFailed = false;
  mcpStatus: AiMcpStatus = checkingAiMcpStatus();
  selectedModelName = '';
  defaultModelName = '';
  selectedReasoningEffort = '';
  modelSettingsOpen = false;
  modelDraftName = '';
  modelDraftReasoningEffort = '';
  permissionMode: AiChangePermissionMode = 'ask';
  permissionSettingsOpen = false;
  permissionDraft: AiChangePermissionMode = 'ask';
  /** Persistent per-conversation workflow preference; empty means automatic selection. */
  activeWorkflow = '';
  agentActivities: AiAgentActivity[] = [];
  agentListOpen = false;
  agentFocusId?: string;
  agentFocusHistory: string[] = [];
  elicitation?: AiElicitationNotice;
  elicitationBusy = false;
  changeApprovalBatch?: AiChangeApprovalBatchNotice;
  changeApprovalBatchQueue: AiChangeApprovalBatchNotice[] = [];
  changeApprovalBatchBusy = false;
  modelChangePending = false;
  contextUsage?: AiContextUsage;
  currentStatus = 'Ready';
  debugEnabled = false;
  assistantBrand?: SafeHtml;
  messages: AiChatMessage[] = [];
  selectedCommandIndex = 0;
  conversationHistory: AiChatConversationSummary[] = [];
  conversationHistoryLoading = false;
  conversationHistoryLoadFailed = false;
  activePanelTab: AiChatPanelTab = 'chat';
  showScrollToBottomButton = false;
  restoringConversation = false;
  shouldFollowChatScroll = true;

  resetForNewChat(): void {
    this.pending = false;
    this.reconciliationRequired = false;
    this.activeRequest = undefined;
    this.cancellation = this.idleCancellation();
    this.prompt = '';
    this.attachments = [];
    this.chatScrollTop = 0;
    this.historyScrollTop = 0;
    this.conversationId = undefined;
    this.selectedModelName = this.defaultModelName;
    this.selectedReasoningEffort = this.defaultModel()?.defaultReasoningEffort || '';
    this.modelSettingsOpen = false;
    this.modelDraftName = '';
    this.modelDraftReasoningEffort = '';
    this.permissionSettingsOpen = false;
    this.permissionDraft = this.permissionMode;
    this.activeWorkflow = '';
    this.resetAgentActivity();
    this.elicitation = undefined;
    this.elicitationBusy = false;
    this.changeApprovalBatch = undefined;
    this.changeApprovalBatchQueue = [];
    this.changeApprovalBatchBusy = false;
    this.modelChangePending = false;
    this.contextUsage = undefined;
    this.currentStatus = 'Ready';
    this.messages = [];
    this.selectedCommandIndex = 0;
    this.activePanelTab = 'chat';
    this.restoringConversation = false;
    this.showScrollToBottomButton = false;
    this.shouldFollowChatScroll = true;
    this.ensureContextUsageForSelectedModel();
  }

  setAvailableModels(models: AiChatModelInfo[]): void {
    this.availableModels = models;
    this.defaultModelName = this.availableModels.find(model => model.defaultModel)?.name
      || this.availableModels[0]?.name || '';
    if (!this.availableModels.some(model => model.name === this.selectedModelName)) {
      this.selectedModelName = this.defaultModelName;
    }
    const selectedModel = this.availableModels.find(model => model.name === this.selectedModelName);
    this.selectedReasoningEffort = normalizeAiReasoningEffort(this.selectedReasoningEffort);
    if (!selectedModel?.reasoningEfforts.some(effort => effort.name === this.selectedReasoningEffort)) {
      this.selectedReasoningEffort = selectedModel?.defaultReasoningEffort || '';
    }
    this.ensureContextUsageForSelectedModel();
  }

  restoreConversationSettings(settings: Pick<AiChatConversationDetails,
    'modelName' | 'reasoningEffort' | 'permissionMode' | 'activeWorkflow'>): void {
    const previousModelName = this.selectedModelName;
    const model = settings.modelName
      ? this.availableModels.find(candidate => candidate.name === settings.modelName)
      : this.selectedModel();
    if (!model && settings.modelName) {
      this.selectedModelName = settings.modelName;
      if (settings.reasoningEffort) {
        this.selectedReasoningEffort = normalizeAiReasoningEffort(settings.reasoningEffort);
      }
    } else if (model) {
      this.selectedModelName = model.name;
      const modelChanged = previousModelName !== model.name;
      if (settings.reasoningEffort || modelChanged) {
        const reasoningEffort = normalizeAiReasoningEffort(settings.reasoningEffort || '');
        this.selectedReasoningEffort = model.reasoningEfforts
          .some(effort => effort.name === reasoningEffort)
          ? reasoningEffort : model.defaultReasoningEffort;
      }
    }
    if (settings.permissionMode === 'ask' || settings.permissionMode === 'auto'
      || settings.permissionMode === 'full_access') {
      this.permissionMode = settings.permissionMode;
    }
    this.permissionDraft = this.permissionMode;
    this.activeWorkflow = settings.activeWorkflow || '';
    this.resetContextUsageForSelectedModel();
  }

  selectedModel(): AiChatModelInfo | undefined {
    return this.availableModels.find(model => model.name === this.selectedModelName);
  }

  setContextUsage(usage: AiContextUsage | undefined): void {
    if (!usage || usage.modelName !== this.selectedModelName) {
      return;
    }
    this.contextUsage = usage;
  }

  resetContextUsageForSelectedModel(): void {
    this.contextUsage = undefined;
    this.ensureContextUsageForSelectedModel();
  }

  contextUsageLabel(): string {
    const usage = this.contextUsage;
    if (!usage) return 'Context unavailable';
    const prefix = usage.estimated ? '~' : '';
    return `Context ${prefix}${Math.round(usage.usedPercent)}% · ${this.compactTokens(usage.remainingTokens)} safe left`;
  }

  private ensureContextUsageForSelectedModel(): void {
    if (this.contextUsage?.modelName === this.selectedModelName) return;
    const model = this.selectedModel();
    if (!model?.contextWindow) {
      this.contextUsage = undefined;
      return;
    }
    const reserve = model.outputReserveTokens || 0;
    const headroom = model.emergencyHeadroomTokens || 0;
    const safeInputLimit = Math.max(1, model.contextWindow - reserve - headroom);
    this.contextUsage = {
      modelName: model.name,
      currentInputTokens: 0,
      contextWindow: model.contextWindow,
      safeInputLimit,
      remainingTokens: safeInputLimit,
      usedPercent: 0,
      estimated: true,
      source: 'configured'
    };
  }

  private compactTokens(tokens: number): string {
    if (tokens >= 1_000_000) return `${(tokens / 1_000_000).toFixed(tokens >= 10_000_000 ? 0 : 1)}m`;
    if (tokens >= 1_000) return `${(tokens / 1_000).toFixed(tokens >= 100_000 ? 0 : 1)}k`;
    return String(tokens);
  }

  defaultModel(): AiChatModelInfo | undefined {
    return this.availableModels.find(model => model.name === this.defaultModelName);
  }

  prepareConversationRestore(conversationId: string, activePanelTab: AiChatPanelTab = 'chat',
                             preserveDraft = false): void {
    if (!preserveDraft) {
      this.prompt = '';
      this.attachments = [];
      this.chatScrollTop = 0;
    }
    this.conversationId = conversationId;
    this.modelSettingsOpen = false;
    this.modelDraftName = '';
    this.modelDraftReasoningEffort = '';
    this.permissionSettingsOpen = false;
    this.permissionDraft = this.permissionMode;
    this.activeWorkflow = '';
    this.resetAgentActivity();
    this.elicitation = undefined;
    this.elicitationBusy = false;
    this.changeApprovalBatch = undefined;
    this.changeApprovalBatchQueue = [];
    this.changeApprovalBatchBusy = false;
    this.modelChangePending = false;
    this.contextUsage = undefined;
    this.activePanelTab = activePanelTab;
    this.currentStatus = 'Restoring';
    this.restoringConversation = true;
    this.shouldFollowChatScroll = false;
    this.showScrollToBottomButton = false;
    this.messages = [];
  }

  resetCancellation(): void {
    this.cancellation = this.idleCancellation();
  }

  resetAgentActivity(): void {
    this.agentActivities = [];
    this.agentListOpen = false;
    this.agentFocusId = undefined;
    this.agentFocusHistory = [];
  }

  private idleCancellation(): AiCancellationUiState {
    return {
      phase: 'idle',
      acknowledged: false,
      lifecycleEventSequence: 0
    };
  }

}
