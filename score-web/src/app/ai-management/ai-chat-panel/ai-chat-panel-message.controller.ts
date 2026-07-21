import {take, takeUntil} from 'rxjs/operators';
import {AiChatPanelEventController} from './ai-chat-panel-event.controller';
import {AiContextBudgetDialogComponent} from './ai-context-budget-dialog.component';
import {AiContextBudgetData} from './ai-context-budget-chart.model';
import {AiConversationRestoreCallbacks} from './domain/ai-conversation-restore.service';
import {
  AiTerminalRequestErrorStatus,
  contextUsageValue,
  isReconciliationRequired,
  providerRetrySemantics,
  terminalRequestErrorStatus,
  toolCallEventSemantics
} from './domain/ai-chat-event-semantics';
import {FORMATTER_META_RESPONSE_PATTERN} from './domain/ai-chat-panel-display.constants';
import {
  agentActivityUpdate,
  isExecutionActivityEvent,
  isFanoutNamespacedEvent,
  isSpecialistToolEvent,
  upsertAgentActivity,
  upsertAgentGuideEvent,
  upsertAgentRetryEvent,
  upsertAgentToolEvent
} from './domain/ai-agent-activity';
import {AiChatSocketEvent} from './domain/ai-chat-panel.model';

export abstract class AiChatPanelMessageController extends AiChatPanelEventController {
  protected handleSystemEvent(event: AiChatSocketEvent): void {
    const content = this.primaryContent(event);
    if (isExecutionActivityEvent(event)) {
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
      if (upsertAgentGuideEvent(this.state.agentActivities, event)) return;
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
    if (event.subtype === 'provider_retry' && this.handleProviderRetryEvent(event)) {
      return;
    }
    if (event.visibility === 'debug') {
      if (this.state.debugEnabled && content) {
        this.state.messages.push({role: 'debug', content});
      }
      return;
    }
    if (event.subtype === 'accepted' && content) {
      if (this.hasActiveStructuredToolRows()) {
        return;
      }
      this.showStatus(content, true);
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

  protected completeCancelledRequest(content?: string): void {
    if (!this.state.pending && !this.activeRequestId) {
      return;
    }
    this.cancellationService.reset();
    this.completeProgressMessages();
    this.settleAgentActivity('cancelled');
    this.clearTimers();
    this.clearStatusMessage();
    this.state.elicitation = undefined;
    this.state.elicitationBusy = false;
    this.pendingContextUpdate = undefined;
    this.confirmedMutationRequests.cancel(this.activeRequestId);
    this.clearMutationRepeatDraft(this.activeRequestId);
    this.activeRequestId = undefined;
    this.clearToolCallTracking();
    this.state.messages.push({role: 'debug', content: content || 'Request cancelled.'});
    this.state.pending = false;
    this.state.reconciliationRequired = false;
    this.state.currentStatus = 'Ready';
    this.requestSubscription?.unsubscribe();
    this.clearCompletedPayloadRecovery();
    if (this.runDeferredNewChat()) {
      return;
    }
    this.focusPrompt();
  }

  protected completeAuthenticationFailure(content?: string): void {
    this.cancellationService.reset();
    this.completeProgressMessages();
    this.settleAgentActivity('failed');
    this.clearTimers();
    this.clearStatusMessage();
    this.state.elicitation = undefined;
    this.state.elicitationBusy = false;
    this.pendingContextUpdate = undefined;
    this.confirmedMutationRequests.cancel(this.activeRequestId);
    this.clearMutationRepeatDraft(this.activeRequestId);
    this.activeRequestId = undefined;
    this.clearToolCallTracking();
    if (this.activeRestoreRequestId) {
      this.cancelConversationRestore();
    }
    this.state.messages.push({role: 'error', content: content || 'Your session is no longer valid.'});
    this.state.pending = false;
    this.state.reconciliationRequired = false;
    this.state.currentStatus = 'Authentication required';
    this.requestSubscription?.unsubscribe();
    this.transportService.cancelReconnect();
    this.transportService.deactivate({force: true}).finally(() => {
      this.snackBar.open('Authentication required', '', {duration: 3000});
      this.auth.logout(window.location.pathname);
    });
  }

  protected completeFailedRequest(content?: string,
                                status: AiTerminalRequestErrorStatus = 'FAILED'): void {
    this.clearCompletedPayloadRecovery();
    this.cancellationService.reset();
    this.completeProgressMessages();
    this.settleAgentActivity('failed');
    this.clearTimers();
    this.clearStatusMessage();
    this.state.elicitation = undefined;
    this.state.elicitationBusy = false;
    this.pendingContextUpdate = undefined;
    this.confirmedMutationRequests.cancel(this.activeRequestId);
    this.clearMutationRepeatDraft(this.activeRequestId);
    this.activeRequestId = undefined;
    this.clearToolCallTracking();
    if (this.activeRestoreRequestId) {
      this.cancelConversationRestore();
    }
    const fallback = status === 'TIMED_OUT'
      ? 'The request deadline was exceeded.'
      : status === 'STEP_LIMIT_REACHED'
        ? 'The request reached its step limit.' : 'The AI chat request failed.';
    this.state.messages.push({role: 'error', content: content || fallback});
    this.state.pending = false;
    this.state.reconciliationRequired = false;
    this.state.currentStatus = status === 'TIMED_OUT'
      ? 'Timed out' : status === 'STEP_LIMIT_REACHED' ? 'Step limit reached' : 'Error';
    this.requestSubscription?.unsubscribe();
    if (this.runDeferredNewChat()) {
      return;
    }
    this.focusPrompt();
  }

  /**
   * Renders one transient alert-styled status line for a retried provider
   * call and ticks its countdown down each second until the next event
   * replaces the line. The partial streamed answer is dropped first because
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
    if (isFanoutNamespacedEvent(event)) {
      upsertAgentRetryEvent(this.state.agentActivities, event);
      return true;
    }
    this.clearProviderRetryCountdown();
    if (this.messageTracker.removeStreamedSegment(this.state, event.requestId)) {
      this.assistantMessageIndexesByRequestId.delete(event.requestId);
    }
    const head = retry.statusCode !== undefined
      ? retry.reason ? `${retry.statusCode} ${retry.reason}` : `${retry.statusCode}`
      : retry.reason || '';
    let secondsRemaining = Math.ceil(retry.delayMillis / 1000);
    const renderCountdown = () => {
      const wait = secondsRemaining > 0
        ? `Retrying in ${secondsRemaining}s · attempt ${retry.attempt}/${retry.maxAttempts}`
        : 'Reconnecting…';
      this.showStatus(head, true, head ? ` · ${wait}` : wait);
    };
    renderCountdown();
    this.state.currentStatus = 'Retrying';
    if (secondsRemaining > 0) {
      this.providerRetryInterval = window.setInterval(() => {
        secondsRemaining -= 1;
        renderCountdown();
        if (secondsRemaining <= 0) {
          this.clearProviderRetryCountdown();
        }
      }, 1000);
    }
    return true;
  }

  protected clearProviderRetryCountdown(): void {
    if (this.providerRetryInterval !== undefined) {
      window.clearInterval(this.providerRetryInterval);
      this.providerRetryInterval = undefined;
    }
  }

  protected upsertToolGroup(event: AiChatSocketEvent): void {
    if (this.divertSpecialistToolEvent(event)) {
      return;
    }
    this.clearProviderRetryCountdown();
    this.messageTracker.upsertToolGroup(this.state, event);
  }

  protected handleToolCallEvent(event: AiChatSocketEvent): void {
    if (this.divertSpecialistToolEvent(event)) {
      return;
    }
    this.clearProviderRetryCountdown();
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
    upsertAgentToolEvent(this.state.agentActivities, event);
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
      return !!this.primaryContent(event);
    }
    if (event.type === 'assistant_final') {
      return typeof event.conversationId === 'string' && !!event.conversationId.trim()
        && !!this.primaryContent(event).trim();
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
        || event.subtype === 'provider_retry'
        || event.subtype === 'context_usage'
        || event.subtype === 'context_compacted'
        || event.subtype === 'guide'
        || isExecutionActivityEvent(event)
        || event.visibility === 'debug'
        || event.metadata?.['inProgress'] === true;
    }
    return event.type === 'UI_FORMATTED' && !!event.response;
  }

  protected hasActiveStructuredToolRows(): boolean {
    return this.messageTracker.hasStructuredToolRows();
  }

  protected primaryContent(event: AiChatSocketEvent): string {
    return event.content || event.response || event.message || '';
  }

  private applyAgentActivity(event: AiChatSocketEvent): void {
    const update = agentActivityUpdate(event);
    if (!update) return;
    const firstActivity = this.state.agentActivities.length === 0;
    if (!upsertAgentActivity(this.state.agentActivities, update)) {
      return;
    }
    if (firstActivity) {
      // One anchor row per fan-out. The anchor keeps a REFERENCE to this
      // fan-out's activity array; request starts replace (never mutate) the
      // state array, so settled anchors keep their own final statuses.
      this.state.messages.push({
        role: update.executionKind === 'parallel' ? 'workflow_group' : 'agent_group',
        content: update.executionKind === 'parallel' ? 'Parallel workflow' : 'Delegated workflow',
        activities: this.state.agentActivities
      });
    }
    this.state.currentStatus = this.aggregateAgentStatus();
  }

  private aggregateAgentStatus(): string {
    const activities = this.state.agentActivities;
    if (activities.some(activity => activity.inProgress)) {
      const parallel = activities.some(activity => activity.executionKind === 'parallel');
      return activities.some(activity => activity.isLead && activity.status === 'synthesizing')
        ? parallel ? 'Synthesizing parallel results' : 'Synthesizing agent results'
        : parallel ? 'Parallel tasks working' : 'Agents working';
    }
    const parallel = activities.some(activity => activity.executionKind === 'parallel');
    return activities.some(activity => activity.status === 'failed')
      ? parallel ? 'Parallel workflow completed with errors' : 'Agent completed with errors'
      : parallel ? 'Parallel tasks finished' : 'Agents finished';
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
      resetRouteContext: () => {
        this.routeRegistrySent = false;
        this.lastPageContextPath = undefined;
      },
      markRouteRegistryRestored: () => this.routeRegistrySent = true,
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
