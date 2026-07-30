import {Message} from '@stomp/stompjs';
import {AiChatPanelUiController} from './ai-chat-panel-ui.controller';
import {elicitationNotice} from './domain/ai-elicitation';
import {
  isUnexpiredChangeConfirmation,
  changeConfirmationNotice
} from './domain/ai-change-confirmation';
import {
  AiChangeInteractionCallbacks,
  ChangeRepeatOpportunity
} from './domain/ai-change-interaction.service';
import {
  AiChatAttachment,
  AiChatSocketEvent,
  AiElicitationResponse,
  AiChangeApprovalBatchDecision,
  AiChangeConfirmationAuthorization
} from './domain/ai-chat-panel.model';
import {withoutTextualToolCallPlaceholder} from './domain/ai-chat-event-semantics';
import {
  isUnexpiredChangeApprovalBatch,
  changeApprovalBatchNotice
} from './domain/ai-change-approval-batch';

const MAX_TIMER_DELAY_MS = 2_147_000_000;
const CHANGE_APPROVAL_ACK_TIMEOUT_MS = 15_000;

export abstract class AiChatPanelEventController extends AiChatPanelUiController {
  protected handleSocketEvent(event: AiChatSocketEvent): void {
    const activeRestoreEvent = !!this.activeRestoreRequestId
      && event.requestId === this.activeRestoreRequestId;
    if (this.conversationRestoreService.isRestoreEvent(event)
      || (activeRestoreEvent
        && this.conversationRestoreService.isLegacyRestoreAdmission(event))) {
      if (!activeRestoreEvent) {
        return;
      }
      this.handleConversationRestoreEvent(event);
      return;
    }
    if (this.activeRestoreRequestId
      && event.requestId === this.activeRestoreRequestId
      && event.type === 'system' && event.subtype === 'error') {
      // Restore errors are actionable only when they carry the exact attempt
      // identity. An untagged delayed/legacy error must not terminate a newer
      // restore that happens to use the same request queue.
      return;
    }
    if (this.activeRequestId && event.requestId !== this.activeRequestId) {
      return;
    }
    if (this.activeRestoreRequestId && event.requestId !== this.activeRestoreRequestId) {
      return;
    }
    if (!this.activeRequestId && !this.activeRestoreRequestId
      && event.type !== 'UI_FORMATTED') {
      return;
    }
    const cancellationDisposition = this.cancellationService.handleSocketEventDisposition(event);
    if (cancellationDisposition !== 'unhandled') {
      if (cancellationDisposition === 'admitted') {
        this.acknowledgeRecoveredRequestLiveEvent(event.requestId);
      }
      this.scrollToBottom();
      return;
    }
    if (this.state.cancellation.acknowledged
      && !(event.type === 'system'
        && (event.subtype === 'cancelled'
          || event.subtype === 'data_changed'))) {
      return;
    }
    this.scheduleRequestStatusWatchdog();
    if (event.type === 'system'
      && event.subtype === 'change_approval_batch_required') {
      this.handleChangeApprovalBatchRequired(event);
      this.scrollToBottom(true);
      return;
    }
    if (event.type === 'system'
      && (event.subtype === 'change_approval_decision_accepted'
        || event.subtype === 'change_approval_decision_rejected')) {
      this.handleChangeApprovalDecisionEvent(event);
      this.scrollToBottom(true);
      return;
    }
    if (event.type === 'system'
      && event.subtype === 'change_confirmation_required') {
      this.handleChangeConfirmationNotice(event);
      this.scrollToBottom();
      return;
    }
    if (event.type === 'system' && event.subtype === 'elicitation_required') {
      this.handleElicitationRequired(event);
      this.scrollToBottom(true);
      return;
    }
    if (event.type === 'system'
      && (event.subtype === 'elicitation_decision_accepted'
        || event.subtype === 'elicitation_decision_rejected')) {
      this.handleElicitationDecisionEvent(event);
      this.scrollToBottom();
      return;
    }
    if (!this.isRecognizedRequestEvent(event)) {
      return;
    }
    this.acknowledgeRecoveredRequestLiveEvent(event.requestId);
    // Any admitted backend event is already a response. Do not allow the
    // delayed client-side "Request sent" placeholder to appear afterward or
    // overwrite the chronological position of newer guide/tool messages.
    this.clearResponseTimeout();
    if (this.acknowledgementTimeout) {
      window.clearTimeout(this.acknowledgementTimeout);
      this.acknowledgementTimeout = undefined;
    }
    if (event.type === 'assistant_update' && this.primaryContent(event)) {
      this.clearProviderRecoveryState();
      this.completeProgressMessages();
      this.clearStatusMessage();
      const content = this.primaryContent(event);
      const alreadyShownAsWorkflowResult = this.state.messages.some(message =>
        message.role === 'assistant' && message.eventType === 'workflow_result'
        && message.requestId === event.requestId && message.content === content);
      if (alreadyShownAsWorkflowResult) {
        this.state.currentStatus = 'Working';
        this.scrollToBottom();
        return;
      }
      const lastIndex = this.state.messages.length - 1;
      const last = lastIndex >= 0 ? this.state.messages[lastIndex] : undefined;
      if (last?.role === 'progress' && last.eventType === 'assistant_update'
        && last.requestId === event.requestId) {
        this.state.messages[lastIndex] = {
          ...last, content: last.content + content, inProgress: true
        };
        this.assistantMessageIndexesByRequestId.set(event.requestId, lastIndex);
      } else {
        // Rows appended after the streamed bubble (tool calls, statuses) close
        // that segment: it settles as interim narration and the next segment
        // streams into a fresh bubble so the answer follows its evidence.
        this.state.messages.push({
          role: 'progress', content, eventType: event.type,
          requestId: event.requestId, inProgress: true
        });
        this.assistantMessageIndexesByRequestId.set(event.requestId, this.state.messages.length - 1);
      }
      this.state.currentStatus = 'Working';
    } else if (event.type === 'tool_group') {
      this.upsertToolGroup(event);
    } else if (event.type === 'tool_call') {
      this.handleToolCallEvent(event);
    } else if (event.type === 'assistant_final') {
      this.completeFinalEvent(event);
    } else if (event.type === 'system') {
      this.handleSystemEvent(event);
    } else if (event.type === 'UI_FORMATTED') {
      this.applyFormattedResponse(event);
    }

    this.scrollToBottom();
  }

  protected handleElicitationRequired(event: AiChatSocketEvent): void {
    const requestId = this.activeRequestId;
    const eventConversationId = typeof event.conversationId === 'string'
      && event.conversationId.trim() === event.conversationId
      ? event.conversationId : undefined;
    const expectedConversationId = this.state.activeRequest?.conversationId
      || this.state.conversationId || eventConversationId;
    if (!requestId) {
      return;
    }
    const notice = elicitationNotice(
      event, requestId, expectedConversationId
    );
    if (!notice || (this.state.elicitation
      && this.state.elicitation.elicitationId !== notice.elicitationId)) {
      return;
    }
    this.acknowledgeRecoveredRequestLiveEvent(event.requestId);
    this.completeProgressMessages();
    this.clearStatusMessage();
    this.state.elicitation = notice;
    this.state.elicitationBusy = false;
    this.state.currentStatus = 'Waiting for your input';
  }

  protected handleChangeApprovalBatchRequired(event: AiChatSocketEvent): void {
    const requestId = this.activeRequestId;
    if (!requestId) return;
    const eventConversationId = typeof event.conversationId === 'string'
      && event.conversationId.trim() === event.conversationId
      ? event.conversationId : undefined;
    const expectedConversationId = this.state.activeRequest?.conversationId
      || this.state.conversationId || eventConversationId;
    const notice = changeApprovalBatchNotice(event, requestId, expectedConversationId);
    if (!notice) {
      return;
    }
    this.acknowledgeRecoveredRequestLiveEvent(event.requestId);
    const active = this.state.changeApprovalBatch;
    if (active?.batchId === notice.batchId
      || this.state.changeApprovalBatchQueue.some(batch => batch.batchId === notice.batchId)) {
      return;
    }
    this.state.messages.push({
      role: 'guide',
      content: notice.items.length === 1
        ? 'Approval requested for one change.'
        : `Approval requested for ${notice.items.length} changes.`
    });
    if (active) {
      this.state.changeApprovalBatchQueue.push(notice);
      return;
    }
    this.completeProgressMessages();
    this.clearStatusMessage();
    this.state.changeApprovalBatch = notice;
    this.state.changeApprovalBatchBusy = false;
    this.scheduleChangeApprovalExpiry();
    this.state.currentStatus = notice.items.length === 1
      ? 'Approval required' : `${notice.items.length} approvals required`;
  }

  protected handleChangeApprovalDecisionEvent(event: AiChatSocketEvent): void {
    const active = this.state.changeApprovalBatch;
    if (!active || event.requestId !== active.requestId
      || event.conversationId !== active.conversationId
      || event.metadata?.['batchId'] !== active.batchId) {
      return;
    }
    this.acknowledgeRecoveredRequestLiveEvent(event.requestId);
    this.clearChangeApprovalAcknowledgementTimeout();
    if (event.subtype === 'change_approval_decision_accepted') {
      this.state.changeApprovalBatch = this.nextChangeApprovalBatch();
      this.state.changeApprovalBatchBusy = false;
      this.scheduleChangeApprovalExpiry();
      this.state.currentStatus = this.state.changeApprovalBatch
        ? (this.state.changeApprovalBatch.items.length === 1
          ? 'Approval required'
          : `${this.state.changeApprovalBatch.items.length} approvals required`)
        : 'Working';
      this.state.messages.push({
        role: 'guide',
        content: this.primaryContent(event).trim()
          || 'Approval decision recorded. Continuing the active request.'
      });
      return;
    }
    this.state.changeApprovalBatchBusy = false;
    if (!isUnexpiredChangeApprovalBatch(active)) {
      this.expireChangeApprovalBatch(active.batchId);
      return;
    }
    this.state.currentStatus = 'Approval required';
    this.state.messages.push({
      role: 'error',
      content: this.primaryContent(event).trim()
        || 'The assistant could not accept that approval decision. Please try again.'
    });
  }

  private nextChangeApprovalBatch() {
    let expired = 0;
    while (this.state.changeApprovalBatchQueue.length > 0) {
      const next = this.state.changeApprovalBatchQueue.shift();
      if (next && isUnexpiredChangeApprovalBatch(next)) {
        this.reportExpiredQueuedApprovals(expired);
        return next;
      }
      if (next) expired += 1;
    }
    this.reportExpiredQueuedApprovals(expired);
    return undefined;
  }

  private reportExpiredQueuedApprovals(expired: number): void {
    if (expired > 0) {
      this.state.messages.push({
        role: 'error',
        content: expired === 1
          ? 'A queued approval request expired before it could be shown.'
          : `${expired} queued approval requests expired before they could be shown.`
      });
    }
  }

  protected scheduleChangeApprovalExpiry(): void {
    if (this.changeApprovalExpiryTimeout !== undefined) {
      window.clearTimeout(this.changeApprovalExpiryTimeout);
      this.changeApprovalExpiryTimeout = undefined;
    }
    const active = this.state.changeApprovalBatch;
    if (!active) return;
    const remaining = Date.parse(active.expiresAt) - Date.now();
    if (remaining <= 0) {
      this.expireChangeApprovalBatch(active.batchId);
      return;
    }
    this.changeApprovalExpiryTimeout = window.setTimeout(() => {
      this.changeApprovalExpiryTimeout = undefined;
      const current = this.state.changeApprovalBatch;
      if (!current || current.batchId !== active.batchId) return;
      if (isUnexpiredChangeApprovalBatch(current)) {
        this.scheduleChangeApprovalExpiry();
      } else {
        this.expireChangeApprovalBatch(current.batchId);
      }
    }, Math.min(remaining, MAX_TIMER_DELAY_MS));
  }

  private expireChangeApprovalBatch(batchId: string): void {
    const active = this.state.changeApprovalBatch;
    if (!active || active.batchId !== batchId) return;
    const acknowledgementMissing = this.state.changeApprovalBatchBusy;
    this.clearChangeApprovalAcknowledgementTimeout();
    this.state.changeApprovalBatch = this.nextChangeApprovalBatch();
    this.state.changeApprovalBatchBusy = false;
    this.state.currentStatus = this.state.changeApprovalBatch
      ? (this.state.changeApprovalBatch.items.length === 1
        ? 'Approval required'
        : `${this.state.changeApprovalBatch.items.length} approvals required`)
      : 'Approval expired';
    this.state.messages.push({
      role: 'error',
      content: this.state.changeApprovalBatch
        ? 'The previous approval request expired. Showing the next pending approval.'
        : acknowledgementMissing
          ? 'No acknowledgement was received before this approval request expired.'
          : 'This approval request expired before a decision was sent.'
    });
    this.scheduleChangeApprovalExpiry();
    this.scrollToBottom();
  }

  decideChangeApprovalBatch(
    decision: 'APPROVE' | 'DENY' | AiChangeApprovalBatchDecision[]
  ): void {
    const active = this.state.changeApprovalBatch;
    if (!active || this.state.changeApprovalBatchBusy || !this.state.pending
      || this.activeRequestId !== active.requestId
      || this.state.cancellation.phase !== 'idle') {
      return;
    }
    if (!isUnexpiredChangeApprovalBatch(active)) {
      this.expireChangeApprovalBatch(active.batchId);
      return;
    }
    this.state.changeApprovalBatchBusy = true;
    this.state.currentStatus = 'Sending approval decision';
    try {
      this.transportService.publish('/app/ai/chat/change-approval', {
        requestId: active.requestId,
        conversationId: active.conversationId,
        batchId: active.batchId,
        decisions: Array.isArray(decision) ? decision : active.items.map(item => ({
          confirmationRequestId: item.confirmationRequestId, decision
        }))
      });
      this.scheduleChangeApprovalAcknowledgementTimeout(active.batchId);
    } catch {
      this.state.changeApprovalBatchBusy = false;
      this.state.currentStatus = 'Approval required';
      this.state.messages.push({
        role: 'error', content: 'Could not send the approval decision.'
      });
      this.scrollToBottom();
    }
  }

  private scheduleChangeApprovalAcknowledgementTimeout(batchId: string): void {
    this.clearChangeApprovalAcknowledgementTimeout();
    const active = this.state.changeApprovalBatch;
    if (!active || active.batchId !== batchId) return;
    const remaining = Date.parse(active.expiresAt) - Date.now();
    const delay = Math.max(1, Math.min(CHANGE_APPROVAL_ACK_TIMEOUT_MS, remaining));
    this.changeApprovalAcknowledgementTimeout = window.setTimeout(() => {
      this.changeApprovalAcknowledgementTimeout = undefined;
      const current = this.state.changeApprovalBatch;
      if (!current || current.batchId !== batchId
        || !this.state.changeApprovalBatchBusy) {
        return;
      }
      this.state.changeApprovalBatchBusy = false;
      if (!isUnexpiredChangeApprovalBatch(current)) {
        this.expireChangeApprovalBatch(batchId);
        return;
      }
      this.state.currentStatus = 'Approval required';
      this.state.messages.push({
        role: 'error',
        content: 'No acknowledgement was received. You can retry the approval decision.'
      });
      this.scheduleChangeApprovalExpiry();
      this.scrollToBottom();
    }, delay);
  }

  private clearChangeApprovalAcknowledgementTimeout(): void {
    if (this.changeApprovalAcknowledgementTimeout !== undefined) {
      window.clearTimeout(this.changeApprovalAcknowledgementTimeout);
      this.changeApprovalAcknowledgementTimeout = undefined;
    }
  }

  protected handleElicitationDecisionEvent(event: AiChatSocketEvent): void {
    const active = this.state.elicitation;
    if (!active || event.requestId !== active.requestId
      || event.conversationId !== active.conversationId
      || event.metadata?.['elicitationId'] !== active.elicitationId) {
      return;
    }
    this.acknowledgeRecoveredRequestLiveEvent(event.requestId);
    if (event.subtype === 'elicitation_decision_accepted') {
      this.state.elicitation = undefined;
      this.state.elicitationBusy = false;
      this.state.currentStatus = 'Working';
      this.showStatus('Response sent. Continuing.', true);
      return;
    }
    this.showElicitationResponseError(
      this.primaryContent(event).trim()
      || 'The assistant could not accept that response. Please try again.'
    );
  }

  respondToElicitation(response: AiElicitationResponse): void {
    const active = this.state.elicitation;
    if (!active || this.state.elicitationBusy || !this.state.pending
      || this.activeRequestId !== active.requestId) {
      return;
    }
    this.state.elicitationBusy = true;
    this.state.currentStatus = 'Sending your response';
    try {
      this.transportService.publish('/app/ai/chat/elicitation', {
        requestId: active.requestId,
        conversationId: active.conversationId,
        elicitationId: active.elicitationId,
        action: response.action,
        content: response.action === 'ACCEPT' ? response.content : {}
      });
    } catch {
      this.showElicitationResponseError('Could not send your response.');
      this.scrollToBottom();
    }
  }

  private showElicitationResponseError(content: string): void {
    this.state.elicitationBusy = false;
    this.state.currentStatus = 'Waiting for your input';
    this.state.messages.push({role: 'error', content});
  }

  protected completeFinalEvent(event: AiChatSocketEvent): void {
    this.clearCompletedPayloadRecovery();
    this.cancellationService.reset();
    this.completeProgressMessages();
    this.completeToolGroupMessages();
    this.clearTimers();
    this.clearStatusMessage();
    this.state.elicitation = undefined;
    this.state.elicitationBusy = false;
    this.clearChangeApprovalBatch();
    const confirmationConversationId =
      this.pendingChangeConfirmation?.conversationId;
    if (confirmationConversationId
      && event.conversationId !== confirmationConversationId) {
      this.completeConflictingChangeConfirmationFinal(
        event.requestId, confirmationConversationId
      );
      return;
    }
    this.settleAgentActivity('completed');
    this.state.conversationId = confirmationConversationId
      || event.conversationId || this.state.conversationId;
    this.sessionPersistence.rememberLastConversation(this.state.conversationId);
    this.confirmedChangeRequests.cancel(event.requestId);
    this.activeRequestId = undefined;
    const content = withoutTextualToolCallPlaceholder(this.primaryContent(event));
    if (content) {
      this.commitAssistantMessage(event.requestId, content, event.files);
    }
    this.clearToolCallTracking();
    this.state.pending = false;
    this.state.reconciliationRequired = false;
    this.state.currentStatus = event.continuationRequired ? 'More processing is needed' : 'Ready';
    if (this.runDeferredNewChat()) {
      return;
    }
    this.finishChangeRepeatOpportunity(
      event.requestId, event.conversationId
    );
    this.loadConversationHistory();
    if (!this.changeDecisionOpen) {
      this.focusPrompt();
    }
  }

  /**
   * Commits one canonical assistant answer across WebSocket and REST. An
   * identical workflow preview and any streamed copy are replaced by one final
   * row at the chronological end of the turn.
   */
  protected commitAssistantMessage(requestId: string, content: string,
                                   files?: import('./domain/ai-chat-panel.model').AiChatFile[]): number {
    const workflowResultIndexes: number[] = [];
    let streamedIndex = -1;
    for (let index = this.state.messages.length - 1; index >= 0; index--) {
      const message = this.state.messages[index];
      if (message.role === 'assistant' && message.eventType === 'workflow_result'
        && message.requestId === requestId) {
        workflowResultIndexes.push(index);
      }
      if (streamedIndex < 0 && message.role === 'progress'
        && message.eventType === 'assistant_update' && message.requestId === requestId) {
        streamedIndex = index;
      }
    }

    const assistantMessage = files?.length
      ? {role: 'assistant' as const, content, files}
      : {role: 'assistant' as const, content};
    if (workflowResultIndexes.length > 0) {
      for (const index of [...workflowResultIndexes, streamedIndex]
        .filter(candidate => candidate >= 0)
        .sort((left, right) => right - left)) {
        this.state.messages.splice(index, 1);
      }
      this.state.messages.push(assistantMessage);
    } else if (streamedIndex === this.state.messages.length - 1 && streamedIndex >= 0) {
      this.state.messages[streamedIndex] = assistantMessage;
    } else {
      if (streamedIndex >= 0) this.state.messages.splice(streamedIndex, 1);
      this.state.messages.push(assistantMessage);
    }
    const index = this.state.messages.length - 1;
    this.assistantMessageIndexesByRequestId.set(requestId, index);
    return index;
  }

  protected beginChangeRepeatDraft(
    requestId: string,
    prompt: string,
    attachments: AiChatAttachment[]
  ): void {
    this.clearChangeRepeatDraft();
    this.changeRepeatDraft = {
      requestId,
      prompt,
      attachments: attachments.map(attachment => ({...attachment}))
    };
  }

  protected handleChangeConfirmationNotice(event: AiChatSocketEvent): void {
    const requestId = this.activeRequestId;
    if (!requestId
      || this.rejectedChangeConfirmationRequestId === requestId) {
      return;
    }
    if (this.pendingChangeConfirmation) {
      // First notice wins. Each confirmation is bound server-side to one
      // exact tool-and-arguments digest, so a later notice on the same
      // request (the model attempting a second change in one step) cannot
      // change what this approval grants. Nothing is lost by ignoring it:
      // after the approved repeat executes, the guard freshly re-blocks any
      // remaining change and emits a new notice on that later turn.
      return;
    }
    const draft = this.changeRepeatDraft;
    const eventConversationId = typeof event.conversationId === 'string'
      && event.conversationId.trim() === event.conversationId
      && event.conversationId.length > 0
      ? event.conversationId : undefined;
    // The broker does not guarantee that the accepted frame reaches the
    // browser before later lifecycle frames. For a brand-new conversation,
    // bind a strictly parsed notice to its own safe conversation field, then
    // cross-check that binding when the accepted identity arrives.
    const expectedConversationId = this.state.activeRequest?.conversationId
      || this.state.conversationId || eventConversationId;
    const notice = changeConfirmationNotice(
      event, requestId, expectedConversationId
    );
    if (!draft || draft.requestId !== requestId
      || !notice) {
      this.rejectedChangeConfirmationRequestId = requestId;
      return;
    }
    this.acknowledgeRecoveredRequestLiveEvent(event.requestId);
    this.pendingChangeConfirmation = {
      conversationId: expectedConversationId!,
      notice
    };
  }

  protected completeConflictingChangeConfirmationFinal(
    requestId: string,
    confirmationConversationId: string
  ): void {
    this.settleAgentActivity('failed');
    this.pendingChangeConfirmation = undefined;
    this.confirmedChangeRequests.cancel(requestId);
    this.clearChangeRepeatDraft(requestId);
    this.state.conversationId = this.state.activeRequest?.conversationId
      || this.state.conversationId || confirmationConversationId;
    this.activeRequestId = undefined;
    this.clearToolCallTracking();
    this.state.messages.push({
      role: 'error',
      content: 'The request returned a conflicting conversation identity and requires reconciliation.'
    });
    this.state.pending = false;
    this.state.reconciliationRequired = true;
    this.state.currentStatus = 'Review needed';
    this.requestSubscription?.unsubscribe();
    this.loadConversationHistory();
    this.scrollToBottom();
  }

  protected finishChangeRepeatOpportunity(
    requestId: string,
    terminalConversationId: string | undefined
  ): void {
    const draft = this.changeRepeatDraft;
    const boundNotice = this.pendingChangeConfirmation;
    const rejected = this.rejectedChangeConfirmationRequestId === requestId;
    this.clearChangeRepeatDraft(requestId);
    if (rejected || !draft || draft.requestId !== requestId
      || !boundNotice
      || terminalConversationId !== boundNotice.conversationId
      || !isUnexpiredChangeConfirmation(boundNotice.notice)) {
      return;
    }
    const opportunity = {
      conversationId: boundNotice.conversationId,
      draft,
      notice: boundNotice.notice
    };
    if (boundNotice.notice.status === 'APPROVED') {
      this.showLostGrantInteraction(opportunity);
      return;
    }
    this.showChangeRepeatInteraction(opportunity);
  }

  protected showChangeRepeatInteraction(opportunity: ChangeRepeatOpportunity): void {
    this.changeInteractions.showConfirmation(
      this.state, opportunity, this.changeInteractionCallbacks()
    );
  }

  approveChangeInteraction(): void {
    this.changeInteractions.approve(this.state, this.changeInteractionCallbacks());
  }

  denyChangeInteraction(): void {
    this.changeInteractions.deny(this.state, this.changeInteractionCallbacks());
  }

  requestChangeRevision(): void {
    if (!this.canRequestChangeRevision()) {
      return;
    }
    this.focusPrompt();
  }

  revokeChangeInteraction(): void {
    this.changeInteractions.revoke(this.state, this.changeInteractionCallbacks());
  }

  dismissChangeInteraction(): void {
    this.changeInteractions.dismiss(this.state);
  }

  protected canRequestChangeRevision(): boolean {
    return this.changeInteractions.canRequestChange(this.state);
  }

  protected sendChangeRevisionRequest(
    prompt: string,
    attachments: AiChatAttachment[]
  ): void {
    this.changeInteractions.sendChange(
      this.state, prompt, attachments, this.changeInteractionCallbacks()
    );
  }

  protected showLostGrantInteraction(opportunity: ChangeRepeatOpportunity): void {
    this.changeInteractions.showLostGrant(
      this.state, opportunity, this.changeInteractionCallbacks()
    );
  }

  protected changeInteractionCallbacks(): AiChangeInteractionCallbacks {
    return {
      sendConfirmedChangeRepeat: (opportunity, confirmationGrant, revision) =>
        this.sendConfirmedChangeRepeat(opportunity, confirmationGrant, revision),
      focusPrompt: () => this.focusPrompt(),
      scrollToBottom: () => this.scrollToBottom(true)
    };
  }

  protected sendConfirmedChangeRepeat(
    opportunity: ChangeRepeatOpportunity,
    confirmationGrant: string,
    revision?: {prompt: string; attachments: AiChatAttachment[]}
  ): boolean {
    if (this.destroyed || this.state.pending || this.state.reconciliationRequired
      || this.state.conversationId !== opportunity.conversationId) {
      return false;
    }
    const requestId = this.createRequestId();
    const prompt = revision?.prompt ?? opportunity.draft.prompt;
    const attachments = (revision?.attachments ?? opportunity.draft.attachments)
      .map(attachment => ({...attachment}));
    this.requestSubscription?.unsubscribe();
    this.transportService.cancelReconnect();
    this.activeRequestId = requestId;
    this.activeRequestPublished = false;
    this.clearToolCallTracking();
    this.state.resetAgentActivity();
    this.state.activePanelTab = 'chat';
    this.state.pending = true;
    this.state.currentStatus = 'Sending approved change';
    this.beginChangeRepeatDraft(requestId, prompt, attachments);
    if (revision) {
      // Match the normal send path: once the revised approval is accepted and
      // promoted to a request, the composer no longer owns that draft.
      this.state.prompt = '';
      this.state.attachments = [];
      this.resizePromptInput();
    }
    this.state.messages.push({
      role: 'user',
      content: this.attachmentService.userMessageContent(
        prompt, attachments
      )
    });
    this.scrollToBottom(true);
    const changeConfirmation: AiChangeConfirmationAuthorization = revision ? {
      confirmationRequestId: opportunity.notice.confirmationRequestId,
      confirmationGrant,
      toolName: opportunity.notice.toolName,
      approvalMode: 'REVISED',
      revisionPrompt: prompt
    } : {
      confirmationRequestId: opportunity.notice.confirmationRequestId,
      confirmationGrant,
      toolName: opportunity.notice.toolName,
      arguments: opportunity.notice.argumentsSummary
    };
    const destination = '/user/queue/ai/chat/' + requestId;
    this.showStatus('Waiting for WebSocket connection.', true);
    this.requestSubscription = this.transportService.watch(destination).subscribe((message: Message) => {
      this.handleSocketEvent(JSON.parse(message.body) as AiChatSocketEvent);
    });
    // Connection attempts may repeat before publish, but the one-time grant is
    // included in exactly one chat publish and is never replayed after publish.
    this.connectAndPublishWhenReady(
      requestId, prompt, attachments, changeConfirmation
    );
    return true;
  }

  protected clearChangeRepeatDraft(requestId?: string): void {
    if (requestId && this.changeRepeatDraft
      && this.changeRepeatDraft.requestId !== requestId) {
      return;
    }
    this.changeRepeatDraft = undefined;
    this.pendingChangeConfirmation = undefined;
    this.rejectedChangeConfirmationRequestId = undefined;
  }

}
