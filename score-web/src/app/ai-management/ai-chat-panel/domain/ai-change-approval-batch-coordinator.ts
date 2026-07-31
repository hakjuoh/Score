import {Injectable, OnDestroy} from '@angular/core';
import {AiChatPanelState} from './ai-chat-panel-state';
import {AiChatTransportService} from './ai-chat-transport.service';
import {
  AiChangeApprovalBatchDecision,
  AiChatSocketEvent
} from './ai-chat-panel.model';
import {exactOptionalText, primaryContent} from './ai-chat-event-semantics';
import {
  changeApprovalBatchNotice,
  changeApprovalBatchStatus,
  isUnexpiredChangeApprovalBatch
} from './ai-change-approval-batch';

const MAX_TIMER_DELAY_MS = 2_147_000_000;
const ACKNOWLEDGEMENT_TIMEOUT_MS = 15_000;

export interface AiChangeApprovalBatchCallbacks {
  acknowledge(requestId: string): void;
  completeProgress(): void;
  clearStatus(): void;
  scrollToBottom(): void;
}

/** Owns approval queue transitions and their expiry/acknowledgement timers. */
@Injectable()
export class AiChangeApprovalBatchCoordinator implements OnDestroy {
  private expiryTimeout?: number;
  private acknowledgementTimeout?: number;

  constructor(private readonly transport: AiChatTransportService) {}

  handleRequired(
    state: AiChatPanelState,
    event: AiChatSocketEvent,
    activeRequestId: string | undefined,
    callbacks: AiChangeApprovalBatchCallbacks
  ): void {
    if (!activeRequestId) return;
    const eventConversationId = exactOptionalText(event.conversationId);
    const expectedConversationId = state.activeRequest?.conversationId
      || state.conversationId || eventConversationId;
    const notice = changeApprovalBatchNotice(event, activeRequestId, expectedConversationId);
    if (!notice) return;

    callbacks.acknowledge(event.requestId);
    const active = state.changeApprovalBatch;
    if (active?.batchId === notice.batchId
      || state.changeApprovalBatchQueue.some(batch => batch.batchId === notice.batchId)) {
      return;
    }
    state.messages.push({
      role: 'guide',
      content: notice.items.length === 1
        ? 'Approval requested for one change.'
        : `Approval requested for ${notice.items.length} changes.`
    });
    if (active) {
      state.changeApprovalBatchQueue.push(notice);
      return;
    }
    callbacks.completeProgress();
    callbacks.clearStatus();
    state.changeApprovalBatch = notice;
    state.changeApprovalBatchBusy = false;
    this.scheduleExpiry(state, callbacks);
    state.currentStatus = changeApprovalBatchStatus(notice);
  }

  handleDecision(
    state: AiChatPanelState,
    event: AiChatSocketEvent,
    callbacks: AiChangeApprovalBatchCallbacks
  ): void {
    const active = state.changeApprovalBatch;
    if (!active || event.requestId !== active.requestId
      || event.conversationId !== active.conversationId
      || event.metadata?.['batchId'] !== active.batchId) {
      return;
    }
    callbacks.acknowledge(event.requestId);
    this.clearAcknowledgementTimeout();
    if (event.subtype === 'change_approval_decision_accepted') {
      state.changeApprovalBatch = this.nextBatch(state);
      state.changeApprovalBatchBusy = false;
      this.scheduleExpiry(state, callbacks);
      state.currentStatus = state.changeApprovalBatch
        ? changeApprovalBatchStatus(state.changeApprovalBatch) : 'Working';
      state.messages.push({
        role: 'guide',
        content: primaryContent(event).trim()
          || 'Approval decision recorded. Continuing the active request.'
      });
      return;
    }
    state.changeApprovalBatchBusy = false;
    if (!isUnexpiredChangeApprovalBatch(active)) {
      this.expire(state, active.batchId, callbacks);
      return;
    }
    state.currentStatus = changeApprovalBatchStatus(active);
    state.messages.push({
      role: 'error',
      content: primaryContent(event).trim()
        || 'The assistant could not accept that approval decision. Please try again.'
    });
  }

  decide(
    state: AiChatPanelState,
    decision: 'APPROVE' | 'DENY' | AiChangeApprovalBatchDecision[],
    activeRequestId: string | undefined,
    callbacks: AiChangeApprovalBatchCallbacks
  ): void {
    const active = state.changeApprovalBatch;
    if (!active || state.changeApprovalBatchBusy || !state.pending
      || activeRequestId !== active.requestId || state.cancellation.phase !== 'idle') {
      return;
    }
    if (!isUnexpiredChangeApprovalBatch(active)) {
      this.expire(state, active.batchId, callbacks);
      return;
    }
    state.changeApprovalBatchBusy = true;
    state.currentStatus = 'Sending approval decision';
    try {
      this.transport.publish('/app/ai/chat/change-approval', {
        requestId: active.requestId,
        conversationId: active.conversationId,
        batchId: active.batchId,
        decisions: Array.isArray(decision) ? decision : active.items.map(item => ({
          confirmationRequestId: item.confirmationRequestId, decision
        }))
      });
      this.scheduleAcknowledgementTimeout(state, active.batchId, callbacks);
    } catch {
      state.changeApprovalBatchBusy = false;
      state.currentStatus = changeApprovalBatchStatus(active);
      state.messages.push({role: 'error', content: 'Could not send the approval decision.'});
      callbacks.scrollToBottom();
    }
  }

  scheduleExpiry(state: AiChatPanelState, callbacks: AiChangeApprovalBatchCallbacks): void {
    this.clearExpiryTimeout();
    const active = state.changeApprovalBatch;
    if (!active) return;
    const remaining = Date.parse(active.expiresAt) - Date.now();
    if (remaining <= 0) {
      this.expire(state, active.batchId, callbacks);
      return;
    }
    this.expiryTimeout = window.setTimeout(() => {
      this.expiryTimeout = undefined;
      const current = state.changeApprovalBatch;
      if (!current || current.batchId !== active.batchId) return;
      if (isUnexpiredChangeApprovalBatch(current)) {
        this.scheduleExpiry(state, callbacks);
      } else {
        this.expire(state, current.batchId, callbacks);
      }
    }, Math.min(remaining, MAX_TIMER_DELAY_MS));
  }

  clear(state: AiChatPanelState): void {
    this.clearTimers();
    state.changeApprovalBatch = undefined;
    state.changeApprovalBatchQueue = [];
    state.changeApprovalBatchBusy = false;
  }

  clearTimers(): void {
    this.clearExpiryTimeout();
    this.clearAcknowledgementTimeout();
  }

  ngOnDestroy(): void {
    this.clearTimers();
  }

  private expire(
    state: AiChatPanelState,
    batchId: string,
    callbacks: AiChangeApprovalBatchCallbacks
  ): void {
    const active = state.changeApprovalBatch;
    if (!active || active.batchId !== batchId) return;
    const acknowledgementMissing = state.changeApprovalBatchBusy;
    this.clearAcknowledgementTimeout();
    state.changeApprovalBatch = this.nextBatch(state);
    state.changeApprovalBatchBusy = false;
    state.currentStatus = state.changeApprovalBatch
      ? changeApprovalBatchStatus(state.changeApprovalBatch) : 'Approval expired';
    state.messages.push({
      role: 'error',
      content: state.changeApprovalBatch
        ? 'The previous approval request expired. Showing the next pending approval.'
        : acknowledgementMissing
          ? 'No acknowledgement was received before this approval request expired.'
          : 'This approval request expired before a decision was sent.'
    });
    this.scheduleExpiry(state, callbacks);
    callbacks.scrollToBottom();
  }

  private nextBatch(state: AiChatPanelState) {
    let expired = 0;
    while (state.changeApprovalBatchQueue.length > 0) {
      const next = state.changeApprovalBatchQueue.shift();
      if (next && isUnexpiredChangeApprovalBatch(next)) {
        this.reportExpiredQueuedApprovals(state, expired);
        return next;
      }
      if (next) expired += 1;
    }
    this.reportExpiredQueuedApprovals(state, expired);
    return undefined;
  }

  private reportExpiredQueuedApprovals(state: AiChatPanelState, expired: number): void {
    if (expired === 0) return;
    state.messages.push({
      role: 'error',
      content: expired === 1
        ? 'A queued approval request expired before it could be shown.'
        : `${expired} queued approval requests expired before they could be shown.`
    });
  }

  private scheduleAcknowledgementTimeout(
    state: AiChatPanelState,
    batchId: string,
    callbacks: AiChangeApprovalBatchCallbacks
  ): void {
    this.clearAcknowledgementTimeout();
    const active = state.changeApprovalBatch;
    if (!active || active.batchId !== batchId) return;
    const remaining = Date.parse(active.expiresAt) - Date.now();
    const delay = Math.max(1, Math.min(ACKNOWLEDGEMENT_TIMEOUT_MS, remaining));
    this.acknowledgementTimeout = window.setTimeout(() => {
      this.acknowledgementTimeout = undefined;
      const current = state.changeApprovalBatch;
      if (!current || current.batchId !== batchId || !state.changeApprovalBatchBusy) return;
      state.changeApprovalBatchBusy = false;
      if (!isUnexpiredChangeApprovalBatch(current)) {
        this.expire(state, batchId, callbacks);
        return;
      }
      state.currentStatus = changeApprovalBatchStatus(current);
      state.messages.push({
        role: 'error',
        content: 'No acknowledgement was received. You can retry the approval decision.'
      });
      this.scheduleExpiry(state, callbacks);
      callbacks.scrollToBottom();
    }, delay);
  }

  private clearExpiryTimeout(): void {
    if (this.expiryTimeout !== undefined) {
      window.clearTimeout(this.expiryTimeout);
      this.expiryTimeout = undefined;
    }
  }

  private clearAcknowledgementTimeout(): void {
    if (this.acknowledgementTimeout !== undefined) {
      window.clearTimeout(this.acknowledgementTimeout);
      this.acknowledgementTimeout = undefined;
    }
  }

}
