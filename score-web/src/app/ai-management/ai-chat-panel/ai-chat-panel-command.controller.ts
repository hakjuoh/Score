import {Message} from '@stomp/stompjs';
import {AiChatPanelMessageController} from './ai-chat-panel-message.controller';
import {AiAgentActivity, agentActivityElapsedLabel} from './domain/ai-agent-activity';
import {AiLocalCommand} from './domain/ai-chat-command.service';
import {AiChatSocketEvent} from './domain/ai-chat-panel.model';

export abstract class AiChatPanelCommandController extends AiChatPanelMessageController {
  protected handleLocalCommand(localCommand: AiLocalCommand, commandText: string): void {
    if (localCommand === 'clear') {
      this.startNewChat();
      return;
    }
    if (localCommand === 'cancel') {
      this.state.prompt = '';
      this.resizePromptInput();
      this.cancelActiveRequest();
      return;
    }
    if (localCommand === 'model') {
      this.openModelSettings(commandText);
      return;
    }
    if (localCommand === 'permissions') {
      this.openPermissionSettings(commandText);
      return;
    }
    if (localCommand === 'debug') {
      this.state.prompt = '';
      this.state.debugEnabled = !this.state.debugEnabled;
      this.state.messages.push({role: 'user', content: commandText});
      this.state.messages.push({
        role: 'debug',
        content: `Debug logging ${this.state.debugEnabled ? 'enabled' : 'disabled'} for this chat session.`
      });
      this.scrollToBottom(true);
      this.focusPrompt();
    }
  }

  protected openModelSettings(commandText: string): void {
    this.settingsService.openModel(this.state, commandText);
    this.resizePromptInput();
    this.scrollToBottom(true);
  }

  protected finishModelSettings(displayName: string, reasoningEffort: string): void {
    this.settingsService.finishModel(this.state, displayName, reasoningEffort);
    this.scrollToBottom(true);
    this.focusPrompt();
  }

  protected openPermissionSettings(commandText: string): void {
    this.settingsService.openPermission(this.state, commandText);
    this.resizePromptInput();
    this.scrollToBottom(true);
  }

  applyPermissionSettings(): void {
    if (this.settingsService.applyPermission(this.state)) {
      this.scrollToBottom(true);
      this.focusPrompt();
    }
  }

  closePermissionSettings(): void {
    this.settingsService.closePermission(this.state);
    this.focusPrompt();
  }

  /** The roster strip accompanies a live fan-out only: it appears when the
   *  first agent starts and disappears once every agent has settled. Settled
   *  runs stay inspectable through the inline agent group block. */
  get agentStripVisible(): boolean {
    return this.state.agentActivities.some(activity => activity.inProgress);
  }

  get agentStripLabel(): string {
    const activities = this.state.agentActivities;
    if (activities.length === 0) {
      return 'Agent workflow';
    }
    const specialists = activities.filter(activity => !activity.isLead);
    const lead = activities.find(activity => activity.isLead);
    const total = Math.max(specialists.length, lead?.plannedAgentCount || 0);
    const settled = specialists.filter(activity => !activity.inProgress).length;
    const phase = specialists.some(activity => activity.inProgress)
      ? 'working'
      : lead?.status === 'synthesizing'
        ? 'synthesizing'
        : activities.some(activity => activity.status === 'failed') ? 'failed'
          : activities.some(activity => activity.status === 'cancelled') ? 'stopped'
            : lead?.inProgress ? 'working' : 'finished';
    const unit = activities.some(activity => activity.executionKind === 'parallel')
      ? 'Tasks' : 'Agents';
    return `${unit} ${settled}/${total} · ${phase}`;
  }

  get focusedAgentActivity(): AiAgentActivity | undefined {
    const agentFocusId = this.state.agentFocusId;
    return agentFocusId ? this.findAgentActivity(agentFocusId) : undefined;
  }

  get activityListLabel(): string {
    return this.state.agentActivities.some(activity => activity.executionKind === 'parallel')
      ? 'Parallel tasks in this request' : 'Agents in this request';
  }

  onAgentStripClick(): void {
    this.state.agentListOpen = !this.state.agentListOpen;
  }

  focusAgentActivity(agentId: string): void {
    if (!this.findAgentActivity(agentId)) {
      return;
    }
    this.state.agentFocusId = agentId;
    this.state.agentListOpen = false;
  }

  closeAgentFocus(): void {
    this.state.agentFocusId = undefined;
  }

  /** Finds an agent in the live fan-out first, then in settled group anchors. */
  private findAgentActivity(agentId: string): AiAgentActivity | undefined {
    const live = this.state.agentActivities.find(activity => activity.agentId === agentId);
    if (live) {
      return live;
    }
    for (let index = this.state.messages.length - 1; index >= 0; index--) {
      const message = this.state.messages[index];
      if (message.role !== 'agent_group' && message.role !== 'workflow_group') {
        continue;
      }
      const historical = message.activities?.find(activity => activity.agentId === agentId);
      if (historical) {
        return historical;
      }
    }
    return undefined;
  }

  agentElapsedLabel(activity: AiAgentActivity): string {
    return agentActivityElapsedLabel(activity);
  }

  cancelActiveRequest(): void {
    if (!this.state.pending || !this.activeRequestId) {
      return;
    }
    if (this.completedPayloadRequestId === this.activeRequestId) {
      return;
    }
    const requestId = this.activeRequestId;
    this.clearMutationRepeatDraft(requestId);
    if (!this.activeRequestPublished) {
      // A disconnected RxStomp client queues publishes. Sending cancel here
      // would put it ahead of the original chat, so the server would reject the
      // cancel and then start the request when the connection opens. Cancel the
      // scheduled publish locally and make any leaked callback fail closed.
      this.transportService.cancelReconnect();
      this.completeCancelledRequest('Request cancelled before it was sent.');
      return;
    }
    if (this.cancellationService.isActive(requestId)) {
      return;
    }
    // Once Stop is issued, the original chat admission/response watchdogs no
    // longer own the UI. Cancellation has independent 2s/5s deadlines.
    this.clearTimers();
    if (this.state.mutationApprovalBatch) {
      this.scheduleMutationApprovalExpiry();
    }
    this.cancellationService.start(
      this.state.activeRequest || {requestId}, this.cancellationCallbacks()
    );
  }

  retryCancellation(): void {
    this.cancellationService.retry();
  }

  forceSafeStop(): void {
    this.cancellationService.forceSafeStop();
  }

  loadConversation(conversationId: string): void {
    if (this.mutationDecisionOpen || this.mutationDecisionInFlight) {
      this.snackBar.open(
        'Finish the action approval decision before opening another chat.',
        'Dismiss', {duration: 3500}
      );
      return;
    }
    if (this.state.reconciliationRequired) {
      this.snackBar.open(
        'Resolve the request outcome before opening another chat.',
        'Dismiss', {duration: 3500}
      );
      return;
    }
    if (this.state.pending) {
      this.snackBar.open('Stop the active request before restoring another chat.', 'Dismiss', {duration: 3000});
      return;
    }
    if (this.state.conversationId === conversationId) {
      this.sessionPersistence.rememberLastConversation(conversationId);
      this.state.activePanelTab = 'chat';
      this.focusPrompt();
      this.updateScrollToBottomButton();
      return;
    }
    this.cancelConversationRestore();
    this.invalidateAttachmentReads();
    this.clearStatusMessage();
    this.clearToolCallTracking();
    const requestId = this.createRequestId();
    this.activeRestoreRequestId = requestId;
    this.sessionPersistence.rememberLastConversation(conversationId);
    this.state.prepareConversationRestore(conversationId);
    this.resizePromptInput();
    this.scrollChatPaneToTop();

    this.requestSubscription?.unsubscribe();
    this.requestSubscription = this.transportService.watch('/user/queue/ai/chat/' + requestId).subscribe((message: Message) => {
      this.handleSocketEvent(JSON.parse(message.body) as AiChatSocketEvent);
    });
    this.publishConversationRestoreWhenReady(requestId, conversationId);
  }

  protected publishConversationRestoreWhenReady(requestId: string, conversationId: string): void {
    this.transportService.publishWhenConnected({
      active: () => this.activeRestoreRequestId === requestId,
      onReconnectStatus: (attempt, maxAttempts) => {
        if (this.activeRestoreRequestId !== requestId) {
          return;
        }
        this.showStatus(`Reconnecting... (${attempt}/${maxAttempts})`, true);
      },
      onConnected: () => {
        if (this.activeRestoreRequestId !== requestId) {
          return;
        }
        this.showStatus('Connected. Restoring conversation.', true);
      },
      publish: () => this.publishConversationRestoreAttempt(requestId, conversationId),
      onReconnectFailure: () => this.failConversationRestoreReconnect(requestId),
      onPublishError: () => this.failConversationRestoreReconnect(requestId)
    });
  }

  protected publishConversationRestoreAttempt(requestId: string, conversationId: string): void {
    if (this.activeRestoreRequestId !== requestId) {
      return;
    }
    if (this.restoreAttemptSequence >= Number.MAX_SAFE_INTEGER) {
      throw new Error('Conversation restore sequence exhausted.');
    }
    const restoreToken = this.createRestoreToken();
    const restoreSequence = ++this.restoreAttemptSequence;
    // Register the exact lineage before publish: a synchronous broker/test
    // callback can deliver HISTORY_START from inside publish().
    this.conversationRestoreService.expectAttempt(restoreToken, restoreSequence);
    this.transportService.publish('/app/ai/chat/conversation', {
      requestId, conversationId, restoreToken, restoreSequence
    });
  }

  protected failConversationRestoreReconnect(requestId: string): void {
    if (this.activeRestoreRequestId !== requestId) {
      return;
    }
    this.clearStatusMessage();
    this.state.messages.push({
      role: 'error',
      content: 'Could not reconnect to WebSocket after 3 attempts. Check that score-http is running and the /ws proxy is active, then try again.'
    });
    this.state.restoringConversation = false;
    this.conversationRestoreService.reset();
    this.activeRestoreRequestId = undefined;
    this.clearToolCallTracking();
    this.state.currentStatus = 'Error';
    this.requestSubscription?.unsubscribe();
    this.transportService.cancelReconnect();
    this.focusPrompt();
    this.updateScrollToBottomButton();
  }

  protected finishConversationRestore(): void {
    this.activeRestoreRequestId = undefined;
    this.clearToolCallTracking();
    this.requestSubscription?.unsubscribe();
    this.transportService.cancelReconnect();
  }

  protected cancelConversationRestore(): void {
    this.state.restoringConversation = false;
    this.conversationRestoreService.cancel();
    this.activeRestoreRequestId = undefined;
    this.clearToolCallTracking();
    if (!this.state.pending) {
      this.transportService.cancelReconnect();
    }
  }

  protected scrollChatPaneToTop(): void {
    this.viewport.scrollToTop(() => this.chatTerminalPane?.nativeElement);
  }

}
