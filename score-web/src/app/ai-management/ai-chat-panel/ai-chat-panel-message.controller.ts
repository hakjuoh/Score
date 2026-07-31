import {take, takeUntil} from 'rxjs/operators';
import {AiChatPanelEventController} from './ai-chat-panel-event.controller';
import {AiContextBudgetDialogComponent} from './ai-context-budget-dialog.component';
import {AiContextBudgetData} from './ai-context-budget-chart.model';
import {AiConversationRestoreCallbacks} from './domain/ai-conversation-restore.service';
import {
  AiTerminalRequestErrorStatus,
  contextUsageValue,
  isReconciliationRequired,
  primaryContent,
  providerRetrySemantics,
  terminalRequestErrorStatus,
  toolCallEventSemantics
} from './domain/ai-chat-event-semantics';
import {
  FORMATTER_META_RESPONSE_PATTERN,
  WORKING_STATUS_LABEL
} from './domain/ai-chat-panel-display.constants';
import {
  AiAgentActivity,
  isExecutionActivityEvent,
  isSpecialistActivityEvent,
  isSpecialistToolEvent,
  upsertAgentGuideEvent,
  upsertAgentProviderErrorEvent,
  upsertAgentRetryEvent,
  upsertAgentToolEvent
} from './domain/ai-agent-activity';
import {
  AiExecutionComposite,
  appendWorkflowConversation,
  workflowTerminalStatus
} from './domain/ai-execution-composite';
import {AiChatSocketEvent} from './domain/ai-chat-panel.model';

export abstract class AiChatPanelMessageController extends AiChatPanelEventController {
  private readonly liveExecution = new AiExecutionComposite();
  private pendingProviderError?: {requestId: string; content: string};

  protected handleSystemEvent(event: AiChatSocketEvent): void {
    const content = primaryContent(event);
    if (event.subtype === 'workflow_started') {
      this.applyWorkflowStarted(event, content);
      return;
    }
    if (workflowTerminalStatus(event.subtype)) {
      if (this.liveExecution.finishWorkflow(event) && this.state.agentActivities.length > 0) {
        this.state.currentStatus = this.aggregateAgentStatus();
      }
      return;
    }
    if (isExecutionActivityEvent(event)) {
      if (this.state.currentStatus === 'Retrying') {
        this.clearProviderRecoveryState();
      }
      this.applyAgentActivity(event);
      return;
    }
    if (event.subtype === 'guide' && content) {
      if (event.metadata?.['workflow_preference'] === true) {
        // Keep the session-settings region in sync when a natural-language
        // command sets or clears the persistent workflow preference.
        const workflow = event.metadata?.['active_workflow'];
        this.state.activeWorkflow = typeof workflow === 'string' ? workflow.trim() : '';
      }
      if (upsertAgentGuideEvent(this.agentActivitiesFor(event), event)) return;
      // A rolling-upgrade worker may emit its guide immediately before its
      // lifecycle row creates the activity. The lifecycle carries the same
      // status text, so never leak that worker-owned guide into the main chat.
      if (isSpecialistActivityEvent(event) && !this.liveExecution.isPlainEvent(event)) return;
      // A guide is the first substantive assistant message for this stage. It
      // replaces only the generic connection/wait placeholder; later tool
      // status updates are then appended below it in event order.
      if (this.messageTracker.activeToolCallCount === 0) {
        this.clearStatusMessage();
      }
      this.state.messages.push({role: 'guide', content});
      this.scrollToBottom();
      return;
    }
    if (event.subtype === 'context_usage' || event.subtype === 'context_compacted') {
      this.applyContextEvent(event);
      if (event.subtype === 'context_compacted' && event.metadata?.['automatic'] === true) {
        this.snackBar.open('Conversation context compacted automatically.', 'Dismiss', {duration: 2500});
      }
      return;
    }
    if (event.subtype === 'accepted') {
      this.captureAcceptedIdentity(event);
    }
    if (event.subtype === 'data_changed' && event.resource) {
      this.navigationService.handleDataChanged(event);
      if (this.state.cancellation.acknowledged) {
        this.state.currentStatus = 'Cancelling';
      }
      return;
    }
    if (event.subtype === 'data_change_rejected' && content) {
      this.showStatus(content, false);
      return;
    }
    if (event.subtype === 'audit_failed') {
      this.clearStatusMessage();
      this.state.currentStatus = 'Review needed';
      return;
    }
    if (event.subtype === 'cancelled') {
      this.completeCancelledRequest(content);
      return;
    }
    if (event.subtype === 'authentication_failed') {
      this.completeAuthenticationFailure(content);
      return;
    }
    if (event.subtype === 'request_error') {
      const status = terminalRequestErrorStatus(event);
      if (status && this.captureTerminalIdentity(event)) {
        this.completeFailedRequest(content, status);
      }
      return;
    }
    if (event.subtype === 'reconciliation_required') {
      if (isReconciliationRequired(event) && this.captureTerminalIdentity(event)) {
        this.completeCancellationTerminal('UNKNOWN_RECONCILIATION_REQUIRED');
      }
      return;
    }
    if (event.subtype === 'error') {
      if (this.handleLegacyRecoverableToolError(event, content)) {
        return;
      }
      this.completeFailedRequest(content);
      return;
    }
    if (event.subtype === 'model_fallback' && content) {
      this.clearStatusMessage();
      this.state.messages.push({
        role: 'progress',
        content,
        eventType: event.subtype,
        inProgress: false
      });
      this.state.currentStatus = 'Using fallback model';
      return;
    }
    if (event.subtype === 'workflow_result' && content) {
      this.clearProviderRecoveryState();
      for (let index = this.state.messages.length - 1; index >= 0; index--) {
        const message = this.state.messages[index];
        if (message.eventType === 'workflow_result'
          && (message.requestId || '') === (event.requestId || '')) {
          this.state.messages.splice(index, 1);
        }
      }
      this.state.messages.push({
        role: 'assistant', content, eventType: 'workflow_result', requestId: event.requestId
      });
      this.state.currentStatus = 'Reviewing workflow result';
      return;
    }
    if (event.subtype === 'provider_error' && this.handleProviderErrorEvent(event)) {
      return;
    }
    if (event.subtype === 'provider_retry' && this.handleProviderRetryEvent(event)) {
      return;
    }
    if (event.visibility === 'debug') {
      if (this.state.debugEnabled && content) {
        this.state.messages.push({role: 'debug', content});
      }
      return;
    }
    if (event.subtype === 'accepted') {
      if (this.hasActiveStructuredToolRows()) {
        return;
      }
      this.showStatus(WORKING_STATUS_LABEL, true);
      return;
    }
    if (content && event.metadata?.['inProgress'] === true) {
      this.showStatus(content, true);
      return;
    }
    if (content) {
      this.showStatus(content, false);
    }
  }

  /** Renders a recoverable provider failure without terminating the active request. */
  protected handleProviderErrorEvent(event: AiChatSocketEvent): boolean {
    const content = primaryContent(event).trim();
    if (!content) return false;
    if (this.state.cancellation.phase !== 'idle') return true;
    if (isSpecialistActivityEvent(event) && !this.liveExecution.isPlainEvent(event)) {
      upsertAgentProviderErrorEvent(this.agentActivitiesFor(event), event);
      return true;
    }
    this.clearProviderRecoveryState();
    this.pendingProviderError = {requestId: event.requestId, content};
    this.showStatus(content, true, {eventType: 'provider_retry', tone: 'error'});
    this.scrollToBottom();
    return true;
  }

  protected completeCancelledRequest(content?: string): void {
    if (!this.state.pending && !this.activeRequestId) {
      return;
    }
    const requestId = this.activeRequestId;
    this.transitionActiveRequest({
      agentStatus: 'cancelled', reconciliationRequired: false,
      cancellation: 'reset', completedPayload: 'clear', toolGroups: 'preserve',
      confirmedChange: requestId
        ? {kind: 'cancel', requestId} : {kind: 'preserve'},
      changeRepeat: requestId
        ? {kind: 'clear-request', requestId} : {kind: 'clear-all'}
    });
    this.state.messages.push({role: 'debug', content: content || 'Request cancelled.'});
    this.state.currentStatus = 'Ready';
    if (this.runDeferredNewChat()) {
      return;
    }
    this.focusPrompt();
  }

  protected completeAuthenticationFailure(content?: string): void {
    const requestId = this.activeRequestId;
    this.transitionActiveRequest({
      agentStatus: 'failed', reconciliationRequired: false,
      cancellation: 'reset', completedPayload: 'preserve', toolGroups: 'preserve',
      confirmedChange: requestId
        ? {kind: 'cancel', requestId} : {kind: 'preserve'},
      changeRepeat: requestId
        ? {kind: 'clear-request', requestId} : {kind: 'clear-all'}
    });
    if (this.activeRestoreRequestId) {
      this.cancelConversationRestore();
    }
    this.state.messages.push({role: 'error', content: content || 'Your session is no longer valid.'});
    this.state.currentStatus = 'Authentication required';
    this.transportService.cancelReconnect();
    this.transportService.deactivate({force: true}).finally(() => {
      this.snackBar.open('Authentication required', '', {duration: 3000});
      this.auth.logout(window.location.pathname);
    });
  }

  protected completeFailedRequest(content?: string,
                                status: AiTerminalRequestErrorStatus = 'FAILED'): void {
    const requestId = this.activeRequestId;
    this.transitionActiveRequest({
      agentStatus: 'failed', reconciliationRequired: false,
      cancellation: 'reset', completedPayload: 'clear', toolGroups: 'preserve',
      confirmedChange: requestId
        ? {kind: 'cancel', requestId} : {kind: 'preserve'},
      changeRepeat: requestId
        ? {kind: 'clear-request', requestId} : {kind: 'clear-all'}
    });
    if (this.activeRestoreRequestId) {
      this.cancelConversationRestore();
    }
    const fallback = status === 'TIMED_OUT'
      ? 'The request deadline was exceeded.'
      : status === 'STEP_LIMIT_REACHED'
        ? 'The request reached its step limit.' : 'The AI chat request failed.';
    this.state.messages.push({role: 'error', content: content || fallback});
    this.state.currentStatus = status === 'TIMED_OUT'
      ? 'Timed out' : status === 'STEP_LIMIT_REACHED' ? 'Step limit reached' : 'Error';
    if (this.runDeferredNewChat()) {
      return;
    }
    this.focusPrompt();
  }

  /**
   * Coalesces one provider failure and its retry narration into a transient,
   * error-toned status line. The partial streamed answer is dropped first because
   * the retried call re-streams the whole current segment. A retry from a
   * fan-out WORKER shares the lead's request identity and belongs to that
   * agent's timeline; it must never disturb the main status row or bubble.
   * While a cancellation is in progress the cancellation status owns the
   * row, so retries are ignored entirely.
   */
  protected handleProviderRetryEvent(event: AiChatSocketEvent): boolean {
    const retry = providerRetrySemantics(event);
    if (!retry) {
      return false;
    }
    if (this.state.cancellation.phase !== 'idle') {
      return true;
    }
    if (isSpecialistActivityEvent(event) && !this.liveExecution.isPlainEvent(event)) {
      upsertAgentRetryEvent(this.agentActivitiesFor(event), event);
      return true;
    }
    const pendingReason = this.pendingProviderError?.requestId === event.requestId
      ? this.pendingProviderError.content : undefined;
    this.clearProviderRecoveryState();
    if (this.messageTracker.removeStreamedSegment(this.state, event.requestId)) {
      this.assistantMessageIndexesByRequestId.delete(event.requestId);
    }
    const retryMessage = primaryContent(event).trim()
      || `The model provider request failed; retrying (attempt ${retry.attempt} of ${retry.maxAttempts}).`;
    const reason = pendingReason || (typeof event.metadata?.['reason'] === 'string'
      ? event.metadata['reason'].trim() : '');
    const reasonWithStop = reason && !/[.!?]$/.test(reason) ? `${reason}.` : reason;
    this.showStatus(reasonWithStop || retryMessage, true, {
      eventType: 'provider_retry', tone: 'error',
      ...(reasonWithStop ? {suffix: retryMessage} : {})
    });
    this.state.currentStatus = 'Retrying';
    return true;
  }

  protected clearProviderRecoveryState(): void {
    this.pendingProviderError = undefined;
    this.clearStatusMessage('provider_retry');
  }

  protected upsertToolGroup(event: AiChatSocketEvent): void {
    if (this.divertSpecialistToolEvent(event)) {
      return;
    }
    this.clearProviderRecoveryState();
    this.messageTracker.upsertToolGroup(this.state, event);
  }

  protected handleToolCallEvent(event: AiChatSocketEvent): void {
    if (this.divertSpecialistToolEvent(event)) {
      return;
    }
    this.clearProviderRecoveryState();
    this.messageTracker.handleToolCall(this.state, event);
  }

  /**
   * Specialist tool activity never renders as main-chat tool rows. It is
   * appended to the owning agent's timeline for the focused view instead.
   * Returns true when the event was claimed by a specialist.
   */
  protected divertSpecialistToolEvent(event: AiChatSocketEvent): boolean {
    if (!isSpecialistToolEvent(event)) {
      return false;
    }
    if (this.liveExecution.isPlainEvent(event)) {
      return false;
    }
    upsertAgentToolEvent(this.agentActivitiesFor(event), event);
    return true;
  }

  protected handleLegacyRecoverableToolError(event: AiChatSocketEvent, content: string): boolean {
    return this.messageTracker.handleLegacyRecoverableToolError(this.state, event, content);
  }

  protected isToolDiscoveryName(value: unknown): boolean {
    return value === 'tool_search_agent';
  }

  protected isRecognizedRequestEvent(event: AiChatSocketEvent): boolean {
    if (event.type === 'assistant_update') {
      return !!primaryContent(event);
    }
    if (event.type === 'assistant_final') {
      return typeof event.conversationId === 'string' && !!event.conversationId.trim()
        && !!primaryContent(event).trim();
    }
    if (event.type === 'tool_call') {
      return !!toolCallEventSemantics(event);
    }
    if (event.type === 'tool_group') {
      return !!event.groupId && (event.subtype === 'started' || event.subtype === 'progress'
        || event.subtype === 'completed' || event.subtype === 'failed');
    }
    if (event.type === 'system') {
      if (event.subtype === 'request_error') {
        return !!terminalRequestErrorStatus(event) && this.matchesTerminalIdentity(event);
      }
      if (event.subtype === 'reconciliation_required') {
        return isReconciliationRequired(event) && this.matchesTerminalIdentity(event);
      }
      if (event.subtype === 'accepted') {
        const identity = this.acceptedIdentity(event);
        return !!identity && this.matchesActiveIdentity(identity);
      }
      return event.subtype === 'data_changed'
        || event.subtype === 'data_change_rejected'
        || event.subtype === 'audit_failed'
        || event.subtype === 'cancelled'
        || event.subtype === 'authentication_failed'
        || event.subtype === 'error'
        || event.subtype === 'model_fallback'
        || event.subtype === 'provider_error'
        || event.subtype === 'provider_retry'
        || event.subtype === 'workflow_result'
        || event.subtype === 'context_usage'
        || event.subtype === 'context_compacted'
        || event.subtype === 'guide'
        || event.subtype === 'workflow_started'
        || !!workflowTerminalStatus(event.subtype)
        || isExecutionActivityEvent(event)
        || event.visibility === 'debug'
        || event.metadata?.['inProgress'] === true;
    }
    return event.type === 'UI_FORMATTED' && !!event.response;
  }

  protected hasActiveStructuredToolRows(): boolean {
    return this.messageTracker.hasStructuredToolRows();
  }

  private applyAgentActivity(event: AiChatSocketEvent): void {
    const placement = this.liveExecution.placeAgent(event, this.state.messages);
    if (!placement) {
      const plain = this.liveExecution.upsertPlainActivity(event, this.state.messages);
      if (plain?.created) plain.container.push(plain.message);
      return;
    }
    const {activities, anchor, createdRootAnchor, rootGroup} = placement;
    if (createdRootAnchor && anchor) {
      // One anchor row per fan-out. The anchor keeps a REFERENCE to this
      // fan-out's activity array; request starts replace (never mutate) the
      // state array, so settled anchors keep their own final statuses.
      this.state.messages.push(anchor);
    }
    if (rootGroup) {
      this.state.agentActivities = activities;
      this.state.currentStatus = this.aggregateAgentStatus();
    }
  }

  private agentActivitiesFor(event: AiChatSocketEvent): AiAgentActivity[] {
    return this.liveExecution.activitiesFor(event) || this.state.agentActivities;
  }

  private applyWorkflowStarted(event: AiChatSocketEvent, content: string): boolean {
    const placement = this.liveExecution.startWorkflow(event, this.state.messages);
    if (!placement) return false;
    if (!placement.created) return true;
    if (placement.presentation === 'hidden') return true;
    if (placement.presentation === 'message') {
      if (placement.anchor) placement.container.push(placement.anchor);
      return true;
    }
    if (!placement.anchor) return false;
    if (placement.root) {
      this.clearStatusMessage();
      if (content.trim()) this.state.messages.push({role: 'guide', content: content.trim()});
      this.state.messages.push(placement.anchor);
      this.showStatus(WORKING_STATUS_LABEL, true);
      this.state.agentActivities = placement.anchor.activities || [];
      return true;
    }
    appendWorkflowConversation(placement.container, content, placement.anchor, true);
    if (this.state.agentActivities.length > 0) {
      this.state.currentStatus = this.aggregateAgentStatus();
    }
    return true;
  }

  private aggregateAgentStatus(): string {
    const activities = this.state.agentActivities;
    if (activities.some(activity => activity.inProgress)) {
      const parallel = activities.some(activity => activity.workflowType === 'parallel');
      return activities.some(activity => activity.isLead && activity.status === 'synthesizing')
        ? parallel ? 'Synthesizing task results' : 'Synthesizing agent results'
        : parallel ? 'Tasks working' : 'Agents working';
    }
    const parallel = activities.some(activity => activity.workflowType === 'parallel');
    return activities.some(activity => activity.status === 'failed')
      ? parallel ? 'Tasks completed with errors' : 'Agent completed with errors'
      : activities.some(activity => activity.status === 'cancelled')
        ? parallel ? 'Tasks stopped' : 'Agent workflow stopped'
        : parallel ? 'Tasks finished' : 'Agents finished';
  }

  protected handleConversationRestoreEvent(event: AiChatSocketEvent): void {
    this.conversationRestoreService.handleEvent(event, this.conversationRestoreCallbacks());
  }

  protected conversationRestoreCallbacks(): AiConversationRestoreCallbacks {
    return {
      setConversationId: conversationId => {
        this.state.conversationId = conversationId;
        this.sessionPersistence.rememberLastConversation(conversationId);
      },
      setSettings: settings => this.state.restoreConversationSettings(settings),
      setContextUsage: contextUsage => this.state.setContextUsage(contextUsage),
      resetMessages: () => {
        this.state.messages = [];
        this.clearToolCallTracking();
      },
      setRestoring: restoring => this.state.restoringConversation = restoring,
      setCurrentStatus: status => this.state.currentStatus = status,
      clearStatus: () => this.clearStatusMessage(),
      pushMessage: message => {
        this.state.messages.push(message);
        return this.state.messages.length - 1;
      },
      setMessage: (index, message) => this.state.messages[index] = message,
      hasMessage: index => !!this.state.messages[index],
      scrollTop: () => this.scrollChatPaneToTop(),
      updateScrollButton: () => this.updateScrollToBottomButton(),
      focusPrompt: () => this.focusPrompt(),
      finish: () => this.finishConversationRestore()
    };
  }

  requestManualCompact(): void {
    if (!this.state.conversationId || this.interactionBlocked) return;
    if (this.attachmentQueue.pending) {
      this.snackBar.open('Wait for attachments to finish loading.', 'Dismiss', {duration: 3000});
      return;
    }
    const draft = this.state.prompt;
    const attachments = [...this.state.attachments];
    this.startChatRequest('/compact', []);
    this.state.prompt = draft;
    this.state.attachments = attachments;
    this.resizePromptInput();
  }

  openContextBudgetDialog(): void {
    const data = this.contextBudgetData();
    if (!data) return;
    this.closeContextBudgetHover(true);
    this.dialog.open(AiContextBudgetDialogComponent, {
      data,
      width: '408px',
      maxWidth: 'calc(100vw - 24px)',
      autoFocus: false,
      restoreFocus: true,
      ariaLabel: 'Context budget details'
    });
  }

  contextBudgetData(): AiContextBudgetData | undefined {
    const usage = this.state.contextUsage;
    if (!usage) return undefined;
    const model = this.state.selectedModel();
    const reservedTokens = Math.max(0, usage.contextWindow - usage.safeInputLimit);
    const emergencyHeadroomTokens = Math.max(0, model?.emergencyHeadroomTokens || 0);
    const outputReserveTokens = Math.max(0, model?.outputReserveTokens
      ?? reservedTokens - emergencyHeadroomTokens);
    return {usage, outputReserveTokens, emergencyHeadroomTokens};
  }

  showContextBudgetHover(): void {
    this.viewport.showContextBudgetHover(() => !!this.state.contextUsage);
  }

  keepContextBudgetHoverOpen(): void {
    this.viewport.keepContextBudgetHoverOpen();
  }

  closeContextBudgetHover(immediately = false): void {
    this.viewport.closeContextBudgetHover(immediately);
  }

  protected applyContextEvent(event: AiChatSocketEvent): void {
    const usage = contextUsageValue(event.metadata?.['contextUsage'], this.state.selectedModelName);
    this.state.setContextUsage(usage);
  }

  protected loadAvailableModels(): void {
    this.api.getAvailableModels().pipe(take(1), takeUntil(this.destroyed$)).subscribe({
      next: models => {
        this.state.setAvailableModels(models);
        if (!this.state.conversationId) {
          this.sessionPersistence.restoreSelection(this.state);
        }
      },
      error: () => this.state.setAvailableModels([])
    });
  }

  protected applyFormattedResponse(event: AiChatSocketEvent): void {
    if (!event.response) {
      return;
    }
    if (FORMATTER_META_RESPONSE_PATTERN.test(event.response)) {
      return;
    }
    const messageIndex = this.assistantMessageIndexesByRequestId.get(event.requestId);
    if (messageIndex === undefined || !this.state.messages[messageIndex]) {
      return;
    }
    const previousContent = this.state.messages[messageIndex].content;
    if (this.normalizedDisplayText(previousContent) === this.normalizedDisplayText(event.response)) {
      return;
    }
    this.state.messages[messageIndex] = {
      role: 'assistant',
      content: event.response,
      formatting: true,
      formattingPreviousContent: previousContent
    };
    this.state.currentStatus = 'Ready';
    window.setTimeout(() => {
      if (this.state.messages[messageIndex]) {
        this.state.messages[messageIndex] = {
          role: this.state.messages[messageIndex].role,
          content: this.state.messages[messageIndex].content
        };
      }
    }, 900);
  }

  protected normalizedDisplayText(value?: string): string {
    return (value || '').trim().replace(/\s+/g, ' ');
  }

  scrollChatToBottom(event?: MouseEvent): void {
    event?.preventDefault();
    event?.stopPropagation();
    this.scrollToBottom(true);
    this.focusPrompt();
  }

  onChatPaneScroll(): void {
    const element = this.chatTerminalPane?.nativeElement;
    if (element) this.state.chatScrollTop = element.scrollTop;
    this.viewport.onChatPaneScroll(this.state, element);
  }

  onHistoryScrollTopChange(scrollTop: number): void {
    this.state.historyScrollTop = scrollTop;
  }

  protected scrollToBottom(force = false): void {
    this.viewport.scrollToBottom(
      this.state, () => this.chatTerminalPane?.nativeElement, force
    );
  }

  protected restoreChatScrollPosition(consumePending = true): void {
    const scrollTop = this.state.chatScrollTop;
    window.setTimeout(() => {
      window.requestAnimationFrame(() => {
        const element = this.chatTerminalPane?.nativeElement;
        if (!element) return;
        element.scrollTop = Math.min(
          scrollTop, Math.max(0, element.scrollHeight - element.clientHeight)
        );
        this.state.chatScrollTop = element.scrollTop;
        this.state.shouldFollowChatScroll =
          element.scrollHeight - element.scrollTop - element.clientHeight <= 48;
        this.updateScrollToBottomButton();
      });
    });
    if (consumePending) this.restoreChatScrollPending = false;
  }

  protected updateScrollToBottomButton(): void {
    this.viewport.updateScrollButton(this.state, this.chatTerminalPane?.nativeElement);
  }

  focusPrompt(): void {
    this.composer?.focus();
  }

  focusPromptIfNoSelection(): void {
    setTimeout(() => {
      const selection = window.getSelection();
      if (selection && selection.toString()) {
        return;
      }
      this.composer?.focus();
    });
  }

  onPanelClick(event: MouseEvent): void {
    event.stopPropagation();
    this.focusPromptIfNoSelection();
  }

  protected resizePromptInput(): void {
    this.composer?.resize();
  }

  onMessageListClick(event: MouseEvent): void {
    event.stopPropagation();
    const target = event.target as HTMLElement | null;
    const link = target?.closest('a[href]') as HTMLAnchorElement | null;
    if (link && this.navigationService.navigateLink(link)) {
      event.preventDefault();
    }
  }

}
