import {Message} from '@stomp/stompjs';
import {AiChatPanelUiController} from './ai-chat-panel-ui.controller';
import {elicitationNotice} from './domain/ai-elicitation';
import {
  isUnexpiredMutationConfirmation,
  mutationConfirmationNotice
} from './domain/ai-mutation-confirmation';
import {
  AiMutationInteractionCallbacks,
  MutationRepeatOpportunity
} from './domain/ai-mutation-interaction.service';
import {
  AiChatAttachment,
  AiChatSocketEvent,
  AiElicitationResponse,
  AiMutationApprovalBatchDecision,
  AiMutationConfirmationAuthorization
} from './domain/ai-chat-panel.model';
import {withoutTextualToolCallPlaceholder} from './domain/ai-chat-event-semantics';
import {
  isUnexpiredMutationApprovalBatch,
  mutationApprovalBatchNotice
} from './domain/ai-mutation-approval-batch';

const MAX_TIMER_DELAY_MS = 2_147_000_000;
const MUTATION_APPROVAL_ACK_TIMEOUT_MS = 15_000;

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
      && event.subtype === 'mutation_approval_batch_required') {
      this.handleMutationApprovalBatchRequired(event);
      this.scrollToBottom(true);
      return;
    }
    if (event.type === 'system'
      && (event.subtype === 'mutation_approval_decision_accepted'
        || event.subtype === 'mutation_approval_decision_rejected')) {
      this.handleMutationApprovalDecisionEvent(event);
      this.scrollToBottom(true);
      return;
    }
    if (event.type === 'system'
      && event.subtype === 'mutation_confirmation_required') {
      this.handleMutationConfirmationNotice(event);
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
      this.clearProviderRetryCountdown();
      this.completeProgressMessages();
      this.clearStatusMessage();
      const content = this.primaryContent(event);
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

  protected handleMutationApprovalBatchRequired(event: AiChatSocketEvent): void {
    const requestId = this.activeRequestId;
    if (!requestId) return;
    const eventConversationId = typeof event.conversationId === 'string'
      && event.conversationId.trim() === event.conversationId
      ? event.conversationId : undefined;
    const expectedConversationId = this.state.activeRequest?.conversationId
      || this.state.conversationId || eventConversationId;
    const notice = mutationApprovalBatchNotice(event, requestId, expectedConversationId);
    if (!notice) {
      return;
    }
    this.acknowledgeRecoveredRequestLiveEvent(event.requestId);
    const active = this.state.mutationApprovalBatch;
    if (active?.batchId === notice.batchId
      || this.state.mutationApprovalBatchQueue.some(batch => batch.batchId === notice.batchId)) {
      return;
    }
    this.state.messages.push({
      role: 'guide',
      content: notice.items.length === 1
        ? 'Approval requested for one data-changing action.'
        : `Approval requested for ${notice.items.length} data-changing actions.`
    });
    if (active) {
      this.state.mutationApprovalBatchQueue.push(notice);
      return;
    }
    this.completeProgressMessages();
    this.clearStatusMessage();
    this.state.mutationApprovalBatch = notice;
    this.state.mutationApprovalBatchBusy = false;
    this.scheduleMutationApprovalExpiry();
    this.state.currentStatus = notice.items.length === 1
      ? 'Approval required' : `${notice.items.length} approvals required`;
  }

  protected handleMutationApprovalDecisionEvent(event: AiChatSocketEvent): void {
    const active = this.state.mutationApprovalBatch;
    if (!active || event.requestId !== active.requestId
      || event.conversationId !== active.conversationId
      || event.metadata?.['batchId'] !== active.batchId) {
      return;
    }
    this.acknowledgeRecoveredRequestLiveEvent(event.requestId);
    this.clearMutationApprovalAcknowledgementTimeout();
    if (event.subtype === 'mutation_approval_decision_accepted') {
      this.state.mutationApprovalBatch = this.nextMutationApprovalBatch();
      this.state.mutationApprovalBatchBusy = false;
      this.scheduleMutationApprovalExpiry();
      this.state.currentStatus = this.state.mutationApprovalBatch
        ? (this.state.mutationApprovalBatch.items.length === 1
          ? 'Approval required'
          : `${this.state.mutationApprovalBatch.items.length} approvals required`)
        : 'Working';
      this.state.messages.push({
        role: 'guide',
        content: this.primaryContent(event).trim()
          || 'Approval decision recorded. Continuing the active request.'
      });
      return;
    }
    this.state.mutationApprovalBatchBusy = false;
    if (!isUnexpiredMutationApprovalBatch(active)) {
      this.expireMutationApprovalBatch(active.batchId);
      return;
    }
    this.state.currentStatus = 'Approval required';
    this.state.messages.push({
      role: 'error',
      content: this.primaryContent(event).trim()
        || 'The assistant could not accept that approval decision. Please try again.'
    });
  }

  private nextMutationApprovalBatch() {
    let expired = 0;
    while (this.state.mutationApprovalBatchQueue.length > 0) {
      const next = this.state.mutationApprovalBatchQueue.shift();
      if (next && isUnexpiredMutationApprovalBatch(next)) {
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

  protected scheduleMutationApprovalExpiry(): void {
    if (this.mutationApprovalExpiryTimeout !== undefined) {
      window.clearTimeout(this.mutationApprovalExpiryTimeout);
      this.mutationApprovalExpiryTimeout = undefined;
    }
    const active = this.state.mutationApprovalBatch;
    if (!active) return;
    const remaining = Date.parse(active.expiresAt) - Date.now();
    if (remaining <= 0) {
      this.expireMutationApprovalBatch(active.batchId);
      return;
    }
    this.mutationApprovalExpiryTimeout = window.setTimeout(() => {
      this.mutationApprovalExpiryTimeout = undefined;
      const current = this.state.mutationApprovalBatch;
      if (!current || current.batchId !== active.batchId) return;
      if (isUnexpiredMutationApprovalBatch(current)) {
        this.scheduleMutationApprovalExpiry();
      } else {
        this.expireMutationApprovalBatch(current.batchId);
      }
    }, Math.min(remaining, MAX_TIMER_DELAY_MS));
  }

  private expireMutationApprovalBatch(batchId: string): void {
    const active = this.state.mutationApprovalBatch;
    if (!active || active.batchId !== batchId) return;
    const acknowledgementMissing = this.state.mutationApprovalBatchBusy;
    this.clearMutationApprovalAcknowledgementTimeout();
    this.state.mutationApprovalBatch = this.nextMutationApprovalBatch();
    this.state.mutationApprovalBatchBusy = false;
    this.state.currentStatus = this.state.mutationApprovalBatch
      ? (this.state.mutationApprovalBatch.items.length === 1
        ? 'Approval required'
        : `${this.state.mutationApprovalBatch.items.length} approvals required`)
      : 'Approval expired';
    this.state.messages.push({
      role: 'error',
      content: this.state.mutationApprovalBatch
        ? 'The previous approval request expired. Showing the next pending approval.'
        : acknowledgementMissing
          ? 'No acknowledgement was received before this approval request expired.'
          : 'This approval request expired before a decision was sent.'
    });
    this.scheduleMutationApprovalExpiry();
    this.scrollToBottom();
  }

  decideMutationApprovalBatch(
    decision: 'APPROVE' | 'DENY' | AiMutationApprovalBatchDecision[]
  ): void {
    const active = this.state.mutationApprovalBatch;
    if (!active || this.state.mutationApprovalBatchBusy || !this.state.pending
      || this.activeRequestId !== active.requestId
      || this.state.cancellation.phase !== 'idle') {
      return;
    }
    if (!isUnexpiredMutationApprovalBatch(active)) {
      this.expireMutationApprovalBatch(active.batchId);
      return;
    }
    this.state.mutationApprovalBatchBusy = true;
    this.state.currentStatus = 'Sending approval decision';
    try {
      this.transportService.publish('/app/ai/chat/mutation-approval', {
        requestId: active.requestId,
        conversationId: active.conversationId,
        batchId: active.batchId,
        decisions: Array.isArray(decision) ? decision : active.items.map(item => ({
          confirmationRequestId: item.confirmationRequestId, decision
        }))
      });
      this.scheduleMutationApprovalAcknowledgementTimeout(active.batchId);
    } catch {
      this.state.mutationApprovalBatchBusy = false;
      this.state.currentStatus = 'Approval required';
      this.state.messages.push({
        role: 'error', content: 'Could not send the approval decision.'
      });
      this.scrollToBottom();
    }
  }

  private scheduleMutationApprovalAcknowledgementTimeout(batchId: string): void {
    this.clearMutationApprovalAcknowledgementTimeout();
    const active = this.state.mutationApprovalBatch;
    if (!active || active.batchId !== batchId) return;
    const remaining = Date.parse(active.expiresAt) - Date.now();
    const delay = Math.max(1, Math.min(MUTATION_APPROVAL_ACK_TIMEOUT_MS, remaining));
    this.mutationApprovalAcknowledgementTimeout = window.setTimeout(() => {
      this.mutationApprovalAcknowledgementTimeout = undefined;
      const current = this.state.mutationApprovalBatch;
      if (!current || current.batchId !== batchId
        || !this.state.mutationApprovalBatchBusy) {
        return;
      }
      this.state.mutationApprovalBatchBusy = false;
      if (!isUnexpiredMutationApprovalBatch(current)) {
        this.expireMutationApprovalBatch(batchId);
        return;
      }
      this.state.currentStatus = 'Approval required';
      this.state.messages.push({
        role: 'error',
        content: 'No acknowledgement was received. You can retry the approval decision.'
      });
      this.scheduleMutationApprovalExpiry();
      this.scrollToBottom();
    }, delay);
  }

  private clearMutationApprovalAcknowledgementTimeout(): void {
    if (this.mutationApprovalAcknowledgementTimeout !== undefined) {
      window.clearTimeout(this.mutationApprovalAcknowledgementTimeout);
      this.mutationApprovalAcknowledgementTimeout = undefined;
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
    this.clearMutationApprovalBatch();
    const confirmationConversationId =
      this.pendingMutationConfirmation?.conversationId;
    if (confirmationConversationId
      && event.conversationId !== confirmationConversationId) {
      this.completeConflictingMutationConfirmationFinal(
        event.requestId, confirmationConversationId
      );
      return;
    }
    this.settleAgentActivity('completed');
    this.state.conversationId = confirmationConversationId
      || event.conversationId || this.state.conversationId;
    this.sessionPersistence.rememberLastConversation(this.state.conversationId);
    this.confirmedMutationRequests.cancel(event.requestId);
    this.activeRequestId = undefined;
    const content = withoutTextualToolCallPlaceholder(this.primaryContent(event));
    if (content) {
      // Locate the live streamed bubble by identity, not by remembered index:
      // out-of-order tool rows may have been spliced in before it. Interim
      // segments of the same request are earlier, so the last match wins.
      // (Reverse loop instead of findLastIndex: the build targets ES2022.)
      let streamedIndex = -1;
      for (let index = this.state.messages.length - 1; index >= 0; index--) {
        const message = this.state.messages[index];
        if (message.role === 'progress' && message.eventType === 'assistant_update'
          && message.requestId === event.requestId) {
          streamedIndex = index;
          break;
        }
      }
      if (streamedIndex === this.state.messages.length - 1 && streamedIndex >= 0) {
        this.state.messages[streamedIndex] = {role: 'assistant', content};
      } else {
        if (streamedIndex >= 0) {
          // The answer always renders as the turn's last row, after every tool
          // row that produced it. Tool tracking is cleared below, so removing
          // the stale streamed bubble cannot desynchronize row indexes.
          this.state.messages.splice(streamedIndex, 1);
        }
        this.state.messages.push({role: 'assistant', content});
      }
      this.assistantMessageIndexesByRequestId.set(
        event.requestId, this.state.messages.length - 1);
    }
    this.clearToolCallTracking();
    this.state.pending = false;
    this.state.reconciliationRequired = false;
    this.state.currentStatus = event.continuationRequired ? 'More processing is needed' : 'Ready';
    if (this.runDeferredNewChat()) {
      return;
    }
    this.finishMutationRepeatOpportunity(
      event.requestId, event.conversationId
    );
    this.loadConversationHistory();
    if (!this.mutationDecisionOpen) {
      this.focusPrompt();
    }
  }

  protected beginMutationRepeatDraft(
    requestId: string,
    prompt: string,
    attachments: AiChatAttachment[]
  ): void {
    this.clearMutationRepeatDraft();
    this.mutationRepeatDraft = {
      requestId,
      prompt,
      attachments: attachments.map(attachment => ({...attachment}))
    };
  }

  protected handleMutationConfirmationNotice(event: AiChatSocketEvent): void {
    const requestId = this.activeRequestId;
    if (!requestId
      || this.rejectedMutationConfirmationRequestId === requestId) {
      return;
    }
    if (this.pendingMutationConfirmation) {
      // First notice wins. Each confirmation is bound server-side to one
      // exact tool-and-arguments digest, so a later notice on the same
      // request (the model attempting a second mutation in one step) cannot
      // change what this approval grants. Nothing is lost by ignoring it:
      // after the approved repeat executes, the guard freshly re-blocks any
      // remaining mutation and emits a new notice on that later turn.
      return;
    }
    const draft = this.mutationRepeatDraft;
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
    const notice = mutationConfirmationNotice(
      event, requestId, expectedConversationId
    );
    if (!draft || draft.requestId !== requestId
      || !notice) {
      this.rejectedMutationConfirmationRequestId = requestId;
      return;
    }
    this.acknowledgeRecoveredRequestLiveEvent(event.requestId);
    this.pendingMutationConfirmation = {
      conversationId: expectedConversationId!,
      notice
    };
  }

  protected completeConflictingMutationConfirmationFinal(
    requestId: string,
    confirmationConversationId: string
  ): void {
    this.settleAgentActivity('failed');
    this.pendingMutationConfirmation = undefined;
    this.confirmedMutationRequests.cancel(requestId);
    this.clearMutationRepeatDraft(requestId);
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

  protected finishMutationRepeatOpportunity(
    requestId: string,
    terminalConversationId: string | undefined
  ): void {
    const draft = this.mutationRepeatDraft;
    const boundNotice = this.pendingMutationConfirmation;
    const rejected = this.rejectedMutationConfirmationRequestId === requestId;
    this.clearMutationRepeatDraft(requestId);
    if (rejected || !draft || draft.requestId !== requestId
      || !boundNotice
      || terminalConversationId !== boundNotice.conversationId
      || !isUnexpiredMutationConfirmation(boundNotice.notice)) {
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
    this.showMutationRepeatInteraction(opportunity);
  }

  protected showMutationRepeatInteraction(opportunity: MutationRepeatOpportunity): void {
    this.mutationInteractions.showConfirmation(
      this.state, opportunity, this.mutationInteractionCallbacks()
    );
  }

  approveMutationInteraction(): void {
    this.mutationInteractions.approve(this.state, this.mutationInteractionCallbacks());
  }

  denyMutationInteraction(): void {
    this.mutationInteractions.deny(this.state, this.mutationInteractionCallbacks());
  }

  requestMutationChange(): void {
    if (!this.canRequestMutationChange()) {
      return;
    }
    this.focusPrompt();
  }

  revokeMutationInteraction(): void {
    this.mutationInteractions.revoke(this.state, this.mutationInteractionCallbacks());
  }

  dismissMutationInteraction(): void {
    this.mutationInteractions.dismiss(this.state);
  }

  protected canRequestMutationChange(): boolean {
    return this.mutationInteractions.canRequestChange(this.state);
  }

  protected sendMutationChangeRequest(
    prompt: string,
    attachments: AiChatAttachment[]
  ): void {
    this.mutationInteractions.sendChange(
      this.state, prompt, attachments, this.mutationInteractionCallbacks()
    );
  }

  protected showLostGrantInteraction(opportunity: MutationRepeatOpportunity): void {
    this.mutationInteractions.showLostGrant(
      this.state, opportunity, this.mutationInteractionCallbacks()
    );
  }

  protected mutationInteractionCallbacks(): AiMutationInteractionCallbacks {
    return {
      sendConfirmedMutationRepeat: (opportunity, confirmationGrant, revision) =>
        this.sendConfirmedMutationRepeat(opportunity, confirmationGrant, revision),
      focusPrompt: () => this.focusPrompt(),
      scrollToBottom: () => this.scrollToBottom(true)
    };
  }

  protected sendConfirmedMutationRepeat(
    opportunity: MutationRepeatOpportunity,
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
    this.state.currentStatus = 'Sending approved action';
    this.beginMutationRepeatDraft(requestId, prompt, attachments);
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
    const mutationConfirmation: AiMutationConfirmationAuthorization = revision ? {
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
      requestId, prompt, attachments, mutationConfirmation
    );
    return true;
  }

  protected clearMutationRepeatDraft(requestId?: string): void {
    if (requestId && this.mutationRepeatDraft
      && this.mutationRepeatDraft.requestId !== requestId) {
      return;
    }
    this.mutationRepeatDraft = undefined;
    this.pendingMutationConfirmation = undefined;
    this.rejectedMutationConfirmationRequestId = undefined;
  }

}
