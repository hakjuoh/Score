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
  AiContextUsage,
  AiElicitationNotice,
  AiMutationPermissionMode,
  AiChatPanelTab,
  AiRuntimeOptions
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
  selectedModelName = '';
  defaultModelName = '';
  selectedReasoningEffort = '';
  selectedRuntime = 'default';
  selectedRuntimeOptions: AiRuntimeOptions = {};
  modelSettingsOpen = false;
  modelDraftName = '';
  modelDraftReasoningEffort = '';
  runtimeSettingsOpen = false;
  runtimeDraft = '';
  runtimeDraftOptions: AiRuntimeOptions = {};
  permissionMode: AiMutationPermissionMode = 'ask';
  permissionSettingsOpen = false;
  permissionDraft: AiMutationPermissionMode = 'ask';
  /** Persistent per-conversation workflow preference; empty means automatic selection. */
  activeWorkflow = '';
  agentActivities: AiAgentActivity[] = [];
  agentListOpen = false;
  agentFocusId?: string;
  elicitation?: AiElicitationNotice;
  elicitationBusy = false;
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
    this.selectedRuntime = this.defaultModel()?.defaultRuntime || 'default';
    this.selectedRuntimeOptions = this.runtimeOptionsFor(this.selectedRuntime);
    this.modelSettingsOpen = false;
    this.modelDraftName = '';
    this.modelDraftReasoningEffort = '';
    this.runtimeSettingsOpen = false;
    this.runtimeDraft = '';
    this.runtimeDraftOptions = {};
    this.permissionSettingsOpen = false;
    this.permissionDraft = this.permissionMode;
    this.activeWorkflow = '';
    this.resetAgentActivity();
    this.elicitation = undefined;
    this.elicitationBusy = false;
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
    this.availableModels = models.map(model => ({
      ...model,
      defaultRuntime: model.defaultRuntime || 'default',
      runtimes: model.runtimes?.length ? model.runtimes.map(runtime => ({
        ...runtime, settings: runtime.settings || []
      })) : [{
        name: 'default', displayName: 'Default', description: 'Uses the Default runtime.', settings: []
      }]
    }));
    this.defaultModelName = this.availableModels.find(model => model.defaultModel)?.name
      || this.availableModels[0]?.name || '';
    if (!this.availableModels.some(model => model.name === this.selectedModelName)) {
      this.selectedModelName = this.defaultModelName;
    }
    const selectedModel = this.availableModels.find(model => model.name === this.selectedModelName);
    if (!selectedModel?.reasoningEfforts.some(effort => effort.name === this.selectedReasoningEffort)) {
      this.selectedReasoningEffort = selectedModel?.defaultReasoningEffort || '';
    }
    const previousRuntime = this.selectedRuntime;
    if (!selectedModel?.runtimes?.some(runtime => runtime.name === this.selectedRuntime)) {
      this.selectedRuntime = selectedModel?.defaultRuntime || 'default';
    }
    this.selectedRuntimeOptions = this.runtimeOptionsFor(
      this.selectedRuntime, previousRuntime === this.selectedRuntime ? this.selectedRuntimeOptions : {}
    );
    if (!selectedModel?.runtimes?.some(runtime => runtime.name === this.runtimeDraft)) {
      this.runtimeDraft = this.selectedRuntime;
    }
    this.runtimeDraftOptions = this.runtimeOptionsFor(
      this.runtimeDraft, this.runtimeDraft === this.selectedRuntime
        ? this.selectedRuntimeOptions : this.runtimeDraftOptions
    );
    this.ensureContextUsageForSelectedModel();
  }

  selectRuntime(runtime: string, options: AiRuntimeOptions = {}): void {
    this.selectedRuntime = runtime;
    this.selectedRuntimeOptions = this.runtimeOptionsFor(runtime, options);
  }

  restoreConversationSettings(settings: Pick<AiChatConversationDetails,
    'modelName' | 'reasoningEffort' | 'runtime' | 'runtimeOptions' | 'permissionMode'
    | 'activeWorkflow'>): void {
    const previousModelName = this.selectedModelName;
    const model = settings.modelName
      ? this.availableModels.find(candidate => candidate.name === settings.modelName)
      : this.selectedModel();
    if (!model && settings.modelName) {
      this.selectedModelName = settings.modelName;
      if (settings.reasoningEffort) this.selectedReasoningEffort = settings.reasoningEffort;
      if (settings.runtime) {
        this.selectedRuntime = settings.runtime;
        this.selectedRuntimeOptions = {...(settings.runtimeOptions || {})};
      }
    } else if (model) {
      this.selectedModelName = model.name;
      const modelChanged = previousModelName !== model.name;
      if (settings.reasoningEffort || modelChanged) {
        this.selectedReasoningEffort = model.reasoningEfforts
          .some(effort => effort.name === settings.reasoningEffort)
          ? settings.reasoningEffort! : model.defaultReasoningEffort;
      }
      const runtime = settings.runtime
        ? model.runtimes.some(candidate => candidate.name === settings.runtime)
          ? settings.runtime : model.defaultRuntime || 'default'
        : modelChanged ? model.defaultRuntime || 'default' : this.selectedRuntime;
      this.selectRuntime(runtime, this.runtimeOptionsFor(
        runtime, settings.runtimeOptions || (modelChanged ? {} : this.selectedRuntimeOptions), model
      ));
    }
    if (settings.permissionMode === 'ask' || settings.permissionMode === 'auto'
      || settings.permissionMode === 'full_access') {
      this.permissionMode = settings.permissionMode;
    }
    this.permissionDraft = this.permissionMode;
    this.activeWorkflow = settings.activeWorkflow || '';
    this.resetContextUsageForSelectedModel();
  }

  draftRuntime(runtime: string, options?: AiRuntimeOptions): void {
    this.runtimeDraft = runtime;
    this.runtimeDraftOptions = this.runtimeOptionsFor(
      runtime, options || (runtime === this.selectedRuntime ? this.selectedRuntimeOptions : {})
    );
  }

  setRuntimeDraftOption(name: string, value: unknown): void {
    this.runtimeDraftOptions = {...this.runtimeDraftOptions, [name]: value};
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
    this.runtimeSettingsOpen = false;
    this.runtimeDraft = '';
    this.runtimeDraftOptions = {};
    this.permissionSettingsOpen = false;
    this.permissionDraft = this.permissionMode;
    this.activeWorkflow = '';
    this.resetAgentActivity();
    this.elicitation = undefined;
    this.elicitationBusy = false;
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
  }

  private idleCancellation(): AiCancellationUiState {
    return {
      phase: 'idle',
      acknowledged: false,
      lifecycleEventSequence: 0
    };
  }

  runtimeOptionsFor(runtimeName: string, requested: AiRuntimeOptions = {},
                    model = this.selectedModel()): AiRuntimeOptions {
    const runtime = model?.runtimes.find(candidate => candidate.name === runtimeName);
    if (!runtime) {
      return {...requested};
    }
    return Object.fromEntries((runtime.settings || []).flatMap(setting => {
      const requestedValue = requested[setting.name];
      const value = this.validRuntimeOption(setting, requestedValue)
        ? requestedValue : setting.defaultValue;
      return value == null ? [] : [[setting.name, value]];
    }));
  }

  private validRuntimeOption(
    setting: AiChatModelInfo['runtimes'][number]['settings'][number], value: unknown
  ): boolean {
    if (setting.type === 'boolean') {
      return typeof value === 'boolean';
    }
    if (setting.type === 'number') {
      return typeof value === 'number' && Number.isFinite(value)
        && (setting.minimum == null || value >= setting.minimum)
        && (setting.maximum == null || value <= setting.maximum);
    }
    return typeof value === 'string'
      && setting.options.some(option => option.value === value);
  }
}
