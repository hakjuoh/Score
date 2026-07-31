import {AiChatPanelViewController} from './ai-chat-panel-view.controller';
import {AiConversationRestoreCallbacks} from './domain/ai-conversation-restore.service';
import {
  AiTerminalRequestErrorStatus,
  isReconciliationRequired,
  normalizedDisplayText,
  primaryContent,
  terminalRequestErrorStatus
} from './domain/ai-chat-event-semantics';
import {requestEventAdmission} from './domain/ai-chat-event-admission';
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
import {AiProviderRecoveryCoordinator} from './domain/ai-provider-recovery-coordinator';

export abstract class AiChatPanelMessageController extends AiChatPanelViewController {
  private readonly liveExecution = new AiExecutionComposite();
  private readonly providerRecovery = new AiProviderRecoveryCoordinator();

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
    const action = this.providerRecovery.handleError(
      event, {
        cancelling: this.state.cancellation.phase !== 'idle',
        specialist: isSpecialistActivityEvent(event) && !this.liveExecution.isPlainEvent(event)
      }
    );
    if (action.kind === 'unhandled') return false;
    if (action.kind === 'ignored') return true;
    if (action.kind === 'specialist') {
      upsertAgentProviderErrorEvent(this.agentActivitiesFor(event), event);
      return true;
    }
    this.clearStatusMessage('provider_retry');
    this.showStatus(action.content, true, {eventType: 'provider_retry', tone: 'error'});
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
    const action = this.providerRecovery.handleRetry(
      event, {
        cancelling: this.state.cancellation.phase !== 'idle',
        specialist: isSpecialistActivityEvent(event) && !this.liveExecution.isPlainEvent(event)
      }
    );
    if (action.kind === 'unhandled') return false;
    if (action.kind === 'ignored') return true;
    if (action.kind === 'specialist') {
      upsertAgentRetryEvent(this.agentActivitiesFor(event), event);
      return true;
    }
    this.clearStatusMessage('provider_retry');
    if (this.messageTracker.removeStreamedSegment(this.state, event.requestId)) {
      this.assistantMessageIndexesByRequestId.delete(event.requestId);
    }
    this.showStatus(action.reason || action.retryMessage, true, {
      eventType: 'provider_retry', tone: 'error',
      ...(action.reason ? {suffix: action.retryMessage} : {})
    });
    this.state.currentStatus = 'Retrying';
    return true;
  }

  protected clearProviderRecoveryState(): void {
    this.providerRecovery.clear();
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
    const admission = requestEventAdmission(event);
    if (admission === 'admit') return true;
    if (admission === 'requires-terminal-identity') {
      return this.matchesTerminalIdentity(event);
    }
    if (admission === 'requires-active-identity') {
      const identity = this.acceptedIdentity(event);
      return !!identity && this.matchesActiveIdentity(identity);
    }
    return false;
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
    if (normalizedDisplayText(previousContent) === normalizedDisplayText(event.response)) {
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

}
