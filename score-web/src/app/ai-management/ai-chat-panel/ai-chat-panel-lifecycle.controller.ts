import {take, timeout} from 'rxjs/operators';
import {AiChatPanelConversationController} from './ai-chat-panel-conversation.controller';
import {AiChatCancellationCallbacks} from './domain/ai-chat-cancellation.service';
import {
  COMPLETED_PAYLOAD_WAIT_MS,
  REQUEST_DEADLINE_GRACE_MS,
  REQUEST_STATUS_WATCHDOG_MS
} from './domain/ai-chat-panel.constants';
import {
  AiActiveRequestIdentity,
  AiCancellationResponse,
  AiChatContextUpdate,
  AiChatSocketEvent,
  AiChatStatusOptions,
  AiExecutionStatus
} from './domain/ai-chat-panel.model';

export abstract class AiChatPanelLifecycleController extends AiChatPanelConversationController {
  protected showStatus(content: string, inProgress = false,
                       options: AiChatStatusOptions = {}): void {
    this.messageTracker.showStatus(this.state, content, inProgress, options);
  }

  protected completeProgressMessages(): void {
    this.messageTracker.completeProgressMessages(this.state);
  }

  protected completeToolGroupMessages(): void {
    this.messageTracker.completeToolGroupMessages(this.state);
  }

  protected clearToolCallTracking(): void {
    this.messageTracker.clearToolCallTracking();
  }

  protected clearStatusMessage(eventType?: string): void {
    this.messageTracker.clearStatusMessage(this.state, eventType);
  }

  protected refreshBranding(): void {
    const brand = this.webPageInfo.brand;
    this.state.assistantBrand = brand
      ? this.sanitizer.bypassSecurityTrustHtml(brand)
      : undefined;
  }

  protected clearTimers(): void {
    this.activeRequestRecovery.cancel();
    this.clearRequestStatusWatchdog();
    this.clearResponseTimeout();
    this.clearProviderRecoveryState();
    if (this.mutationApprovalExpiryTimeout !== undefined) {
      window.clearTimeout(this.mutationApprovalExpiryTimeout);
      this.mutationApprovalExpiryTimeout = undefined;
    }
    if (this.mutationApprovalAcknowledgementTimeout !== undefined) {
      window.clearTimeout(this.mutationApprovalAcknowledgementTimeout);
      this.mutationApprovalAcknowledgementTimeout = undefined;
    }
    if (this.acknowledgementTimeout) {
      window.clearTimeout(this.acknowledgementTimeout);
      this.acknowledgementTimeout = undefined;
    }
  }

  protected cancellationCallbacks(): AiChatCancellationCallbacks {
    return {
      onStateChange: state => {
        this.state.cancellation = state;
        this.clearProviderRecoveryState();
        if (state.phase === 'requesting') {
          this.showStatus('Cancelling request.', true);
          this.state.currentStatus = 'Cancelling';
        } else if (state.phase === 'acknowledged') {
          this.showStatus('Cancellation acknowledged.', true);
          this.state.currentStatus = 'Cancelling';
        } else if (state.phase === 'delayed') {
          this.showStatus('Cancellation delayed.', false);
          this.state.currentStatus = 'Cancellation delayed';
        }
      },
      onIdentityChange: identity => this.updateActiveIdentity(identity),
      onTerminal: (status, response) => this.completeCancellationTerminal(status, response)
    };
  }

  protected updateActiveIdentity(identity: AiActiveRequestIdentity): void {
    if (this.activeRequestId !== identity.requestId) {
      return;
    }
    this.state.activeRequest = {...identity};
    if (identity.conversationId) {
      this.state.conversationId = identity.conversationId;
      this.sessionPersistence.rememberLastConversation(identity.conversationId);
    }
  }

  protected captureAcceptedIdentity(event: AiChatSocketEvent): void {
    const identity = this.acceptedIdentity(event);
    if (!identity || !this.matchesActiveIdentity(identity)) {
      return;
    }
    if (this.pendingMutationConfirmation
      && this.mutationRepeatDraft?.requestId === identity.requestId
      && this.pendingMutationConfirmation.conversationId
        !== identity.conversationId) {
      this.pendingMutationConfirmation = undefined;
      this.rejectedMutationConfirmationRequestId = identity.requestId;
    }
    this.updateActiveIdentity(identity);
    this.cancellationService.updateIdentity(identity);
    this.scheduleRequestStatusWatchdog();
  }

  protected scheduleRequestStatusWatchdog(): void {
    this.clearRequestStatusWatchdog();
    const active = this.state.activeRequest;
    if (!this.state.pending || !active?.conversationId
      || active.generation === undefined || this.destroyed) {
      return;
    }
    this.requestStatusWatchdogTimeout = window.setTimeout(() => {
      this.requestStatusWatchdogTimeout = undefined;
      const expected = this.state.activeRequest;
      if (!this.state.pending || !expected?.conversationId
        || expected.generation === undefined || expected.requestId !== active.requestId) {
        return;
      }
      const recoveryIdentity = {
        ...expected,
        conversationId: expected.conversationId,
        generation: expected.generation
      };
      this.requestStatusWatchdogSubscription = this.api.getRequestStatus(
        expected.requestId, expected.conversationId, expected.generation
      ).pipe(take(1)).subscribe({
        next: status => {
          if (this.activeRequestId !== status.requestId) {
            return;
          }
          if (this.isTerminalExecutionStatus(status.status)) {
            this.loadTerminalRecoveredConversation(status);
          } else if (this.isRequestOverdue(status.deadline || expected.deadline)) {
            // The backend keeps reporting a request it can no longer settle.
            // Polling it forever would leave the turn pending without end.
            this.abandonActiveRequest(status.requestId,
              'The assistant stopped reporting the outcome of this request after its deadline. '
              + 'Reopen the assistant to reconcile it.', 'Outcome unknown');
          } else {
            this.scheduleRequestStatusWatchdog();
          }
        },
        error: () => this.recoverActiveRequestConnection(
          recoveryIdentity, () => this.scheduleRequestStatusWatchdog()
        )
      });
    }, REQUEST_STATUS_WATCHDOG_MS);
  }

  protected isRequestOverdue(deadline?: string): boolean {
    if (!deadline) {
      return false;
    }
    const parsed = Date.parse(deadline);
    return !Number.isNaN(parsed) && Date.now() > parsed + REQUEST_DEADLINE_GRACE_MS;
  }

  protected clearRequestStatusWatchdog(): void {
    if (this.requestStatusWatchdogTimeout !== undefined) {
      window.clearTimeout(this.requestStatusWatchdogTimeout);
      this.requestStatusWatchdogTimeout = undefined;
    }
    this.requestStatusWatchdogSubscription?.unsubscribe();
    this.requestStatusWatchdogSubscription = undefined;
  }

  protected acceptedIdentity(event: AiChatSocketEvent): AiActiveRequestIdentity | undefined {
    const conversationId = typeof event.conversationId === 'string'
      && event.conversationId.trim() ? event.conversationId.trim() : undefined;
    const generationValue = event.metadata?.['generation'];
    const generation = typeof generationValue === 'number'
      && Number.isSafeInteger(generationValue) && generationValue > 0
      ? generationValue : undefined;
    const deadlineValue = event.metadata?.['deadline'];
    const deadline = typeof deadlineValue === 'string'
      && !Number.isNaN(Date.parse(deadlineValue)) ? deadlineValue : undefined;
    if (!conversationId || generation === undefined || !deadline) {
      return undefined;
    }
    return {
      requestId: event.requestId,
      conversationId,
      generation,
      deadline
    };
  }

  protected captureTerminalIdentity(event: AiChatSocketEvent): boolean {
    const identity = this.terminalIdentity(event);
    if (!identity || !this.matchesTerminalIdentity(event)) {
      return false;
    }
    this.updateActiveIdentity(identity);
    return true;
  }

  protected matchesTerminalIdentity(event: AiChatSocketEvent): boolean {
    const identity = this.terminalIdentity(event);
    return !!identity && this.matchesActiveIdentity(identity);
  }

  protected matchesActiveIdentity(identity: AiActiveRequestIdentity): boolean {
    const active = this.state.activeRequest;
    return !!active && active.requestId === identity.requestId
      && (!active.conversationId || active.conversationId === identity.conversationId)
      && (active.generation === undefined || active.generation === identity.generation);
  }

  protected terminalIdentity(event: AiChatSocketEvent): AiActiveRequestIdentity | undefined {
    const conversationId = typeof event.conversationId === 'string'
      && event.conversationId.trim() ? event.conversationId.trim() : undefined;
    const generation = event.metadata?.['generation'];
    if (!conversationId || typeof generation !== 'number'
      || !Number.isSafeInteger(generation) || generation < 1) {
      return undefined;
    }
    return {requestId: event.requestId, conversationId, generation};
  }

  protected completeCancellationTerminal(status: AiExecutionStatus,
                                       response?: AiCancellationResponse): void {
    this.confirmedMutationRequests.cancel(this.activeRequestId);
    if (status === 'CANCELLED') {
      this.completeCancelledRequest('Request cancelled.');
      return;
    }
    if (status === 'COMPLETED') {
      this.awaitCompletedPayload(response);
      return;
    }
    this.clearCompletedPayloadRecovery();
    this.completeProgressMessages();
    this.settleAgentActivity('failed');
    this.clearTimers();
    this.clearStatusMessage();
    this.state.elicitation = undefined;
    this.state.elicitationBusy = false;
    this.clearMutationApprovalBatch();
    this.clearMutationRepeatDraft(this.activeRequestId);
    this.activeRequestId = undefined;
    this.clearToolCallTracking();
    this.state.pending = false;
    this.state.reconciliationRequired = status === 'UNKNOWN_RECONCILIATION_REQUIRED';
    if (this.state.reconciliationRequired) {
      this.deferredNewChatTab = undefined;
    }
    this.requestSubscription?.unsubscribe();
    if (status === 'TIMED_OUT') {
      this.state.messages.push({role: 'error', content: 'The request deadline was exceeded.'});
      this.state.currentStatus = 'Timed out';
    } else if (status === 'UNKNOWN_RECONCILIATION_REQUIRED') {
      this.state.messages.push({
        role: 'error',
        content: 'The request outcome requires reconciliation before retry.'
      });
      this.state.currentStatus = 'Review needed';
    } else {
      this.state.messages.push({
        role: 'error',
        content: response?.disposition === 'ALREADY_TERMINAL'
          ? `The request had already ended with status ${status}.`
          : `The request ended with status ${status}.`
      });
      this.state.currentStatus = 'Error';
    }
    if (!this.state.reconciliationRequired && this.runDeferredNewChat()) {
      return;
    }
    this.loadConversationHistory();
    this.focusPrompt();
    this.scrollToBottom();
  }

  protected awaitCompletedPayload(response?: AiCancellationResponse): void {
    const requestId = this.activeRequestId || response?.requestId;
    if (!requestId) {
      return;
    }
    this.clearCompletedPayloadRecovery();
    this.completedPayloadRequestId = requestId;
    const conversationId = response?.conversationId
      || this.state.activeRequest?.conversationId
      || this.state.conversationId;
    this.showStatus('Request completed. Restoring the final response.', true);
    this.state.currentStatus = 'Restoring completed response';
    this.completedPayloadTimeout = window.setTimeout(() => {
      this.completedPayloadTimeout = undefined;
      if (this.completedPayloadRequestId !== requestId) {
        return;
      }
      if (!conversationId) {
        this.finishCompletedPayload(requestId, undefined, undefined);
        return;
      }
      this.completedPayloadSubscription = this.api.getConversation(conversationId).pipe(
        timeout(COMPLETED_PAYLOAD_WAIT_MS)
      ).subscribe({
        next: details => {
          if (this.completedPayloadRequestId !== requestId) {
            return;
          }
          const answer = [...(details.messages || [])]
            .sort((left, right) => right.index - left.index)
            .find(message => message.role === 'assistant' && !!message.content.trim());
          this.finishCompletedPayload(requestId, details.conversationId, answer?.content);
        },
        error: () => {
          if (this.completedPayloadRequestId === requestId) {
            this.finishCompletedPayload(requestId, conversationId, undefined);
          }
        }
      });
    }, COMPLETED_PAYLOAD_WAIT_MS);
  }

  protected finishCompletedPayload(requestId: string, conversationId?: string,
                                 content?: string): void {
    if (this.completedPayloadRequestId !== requestId) {
      return;
    }
    this.clearCompletedPayloadRecovery();
    this.completeProgressMessages();
    this.completeToolGroupMessages();
    this.settleAgentActivity('completed');
    this.clearTimers();
    this.clearStatusMessage();
    this.state.elicitation = undefined;
    this.state.elicitationBusy = false;
    this.clearMutationApprovalBatch();
    this.confirmedMutationRequests.cancel(requestId);
    this.clearMutationRepeatDraft(requestId);
    this.state.conversationId = conversationId || this.state.conversationId;
    this.sessionPersistence.rememberLastConversation(this.state.conversationId);
    this.activeRequestId = undefined;
    this.clearToolCallTracking();
    this.state.pending = false;
    this.state.reconciliationRequired = false;
    this.requestSubscription?.unsubscribe();
    this.requestSubscription = undefined;
    if (content) {
      this.state.messages.push({role: 'assistant', content});
      this.assistantMessageIndexesByRequestId.set(requestId, this.state.messages.length - 1);
    } else {
      this.state.messages.push({
        role: 'debug',
        content: 'The request completed before cancellation took effect.'
      });
    }
    this.state.currentStatus = 'Ready';
    if (this.runDeferredNewChat()) {
      return;
    }
    this.loadConversationHistory();
    this.focusPrompt();
    this.scrollToBottom();
  }

  protected clearCompletedPayloadRecovery(): void {
    if (this.completedPayloadTimeout !== undefined) {
      window.clearTimeout(this.completedPayloadTimeout);
      this.completedPayloadTimeout = undefined;
    }
    this.completedPayloadSubscription?.unsubscribe();
    this.completedPayloadSubscription = undefined;
    this.completedPayloadRequestId = undefined;
  }

  protected runDeferredNewChat(): boolean {
    const activePanelTab = this.deferredNewChatTab;
    if (!activePanelTab) {
      return false;
    }
    this.deferredNewChatTab = undefined;
    this.startNewChat(undefined, activePanelTab);
    return true;
  }

  protected createRequestId(): string {
    if (window.crypto && 'randomUUID' in window.crypto) {
      return window.crypto.randomUUID();
    }
    return Date.now() + '-' + Math.random().toString(16).slice(2);
  }

  protected createRestoreToken(): string {
    const browserCrypto = window.crypto as (Pick<Crypto, 'getRandomValues'> & {
      randomUUID?: () => string;
    }) | undefined;
    if (!browserCrypto) {
      throw new Error('Secure randomness is unavailable.');
    }
    if (typeof browserCrypto.randomUUID === 'function') {
      return browserCrypto.randomUUID();
    }
    const bytes = new Uint8Array(16);
    browserCrypto.getRandomValues(bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0'));
    return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-` +
      `${hex.slice(6, 8).join('')}-${hex.slice(8, 10).join('')}-` +
      hex.slice(10).join('');
  }

  protected nextContextUpdate(): AiChatContextUpdate {
    return this.contextService.nextContextUpdate();
  }

  protected invalidateAttachmentReads(): void {
    this.attachmentQueue.invalidate();
  }

  protected updateMainPanelInset(): void {
    this.layoutService.updateMainPanelInset(
      this.state.isOpen && !this.state.popoutActive && !this.popoutMode,
      this.state.dock, this.state.sideSize, this.state.horizontalSize
    );
  }
}
