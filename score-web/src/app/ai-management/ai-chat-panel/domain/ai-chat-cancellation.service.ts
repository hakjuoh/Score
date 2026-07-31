/**
 * Coordinates the idempotent HTTP-first cancellation handshake and socket fallback.
 */

import {HttpErrorResponse} from '@angular/common/http';
import {Injectable, OnDestroy, inject} from '@angular/core';
import {Subscription} from 'rxjs';
import {
  AiChatCancellationCallbacks,
  AiChatCancellationState,
  CancellationAttempt
} from './ai-chat-cancellation-state';
import {
  AiActiveRequestIdentity,
  AiCancellationCommand,
  AiCancellationDelayReason,
  AiCancellationResponse,
  AiChatSocketEvent,
  AiExecutionStatus,
  AiPublicExecutionRequestStatus
} from './ai-chat-panel.model';
import {AiChatApiService} from './ai-chat-api.service';
import {AiChatTransportService} from './ai-chat-transport.service';

export {AiChatCancellationCallbacks} from './ai-chat-cancellation-state';

export type AiCancellationSocketEventDisposition = 'unhandled' | 'ignored' | 'admitted';

/** Coordinates one idempotent, HTTP-first cancellation handshake. */
@Injectable({
  providedIn: 'root'
})
export class AiChatCancellationService extends AiChatCancellationState implements OnDestroy {

  private api = inject(AiChatApiService);
  private transport = inject(AiChatTransportService);
  private subscriptions = new Subscription();

  ngOnDestroy(): void {
    this.reset();
  }

  isActive(requestId?: string): boolean {
    return !!this.current && (!requestId || this.current.identity.requestId === requestId);
  }

  start(identity: AiActiveRequestIdentity, callbacks: AiChatCancellationCallbacks): AiCancellationCommand {
    if (!identity.requestId.trim()) {
      throw new Error('A request ID is required to start cancellation.');
    }
    if (this.current?.identity.requestId === identity.requestId) {
      return this.command(this.current);
    }

    this.reset();
    const attempt: CancellationAttempt = {
      token: ++this.token,
      identity: this.normalizedIdentity(identity),
      cancellationRequestId: this.createCancellationRequestId(),
      phase: 'requesting',
      acknowledged: false,
      lifecycleEventSequence: 0,
      automaticFallbackPublished: false,
      admissionRetryScheduled: false,
      admissionRetryCount: 0,
      callbacks
    };
    this.current = attempt;
    this.publishState(attempt);
    this.armAcknowledgementDeadline(attempt);
    this.armTerminalDeadline(attempt);
    this.sendHttp(attempt);
    return this.command(attempt);
  }

  updateIdentity(identity: AiActiveRequestIdentity): void {
    const attempt = this.current;
    if (!attempt || attempt.identity.requestId !== identity.requestId) {
      return;
    }
    this.mergeIdentity(attempt, identity);
  }

  retry(): void {
    const attempt = this.current;
    if (!attempt) {
      return;
    }
    this.clearDeadlines(attempt);
    attempt.phase = attempt.acknowledged ? 'acknowledged' : 'requesting';
    attempt.delayReason = undefined;
    attempt.automaticFallbackPublished = false;
    attempt.admissionRetryScheduled = false;
    attempt.admissionRetryCount = 0;
    this.publishState(attempt);
    if (!attempt.acknowledged) {
      this.armAcknowledgementDeadline(attempt);
    }
    this.armTerminalDeadline(attempt);
    this.sendHttp(attempt);
  }

  forceSafeStop(): void {
    const attempt = this.current;
    if (!attempt) {
      return;
    }
    this.delay(attempt, 'force_safe_stop');
    this.publishStompFallback(attempt, true);
    this.clearTerminalDeadline();
    this.armTerminalDeadline(attempt);
    this.queryStatus(attempt);
  }

  /** Returns true when the event belongs to the cancellation protocol. */
  handleSocketEvent(event: AiChatSocketEvent): boolean {
    return this.handleSocketEventDisposition(event) !== 'unhandled';
  }

  /**
   * Separates protocol consumption from a fully admitted lifecycle update.
   * Another tab's command or malformed identity is consumed but must not end
   * this panel's request-recovery polling.
   */
  handleSocketEventDisposition(event: AiChatSocketEvent): AiCancellationSocketEventDisposition {
    const attempt = this.current;
    if (!attempt || event.requestId !== attempt.identity.requestId || event.type !== 'system') {
      return 'unhandled';
    }
    const subtype = event.subtype;
    if (subtype !== 'cancellation_acknowledged'
      && subtype !== 'cancellation_delayed'
      && subtype !== 'cancellation_rejected'
      && subtype !== 'cancellation_current_status'
      && subtype !== 'reconciliation_required'
      && subtype !== 'cancelled') {
      return 'unhandled';
    }

    const eventCancellationId = this.stringMetadata(event, 'cancellationRequestId');
    const terminalControl = subtype === 'cancelled'
      || subtype === 'reconciliation_required'
      || subtype === 'cancellation_current_status';
    if (!terminalControl
      && eventCancellationId && eventCancellationId !== attempt.cancellationRequestId) {
      // Consume another tab's command event without treating it as our ACK.
      return 'ignored';
    }
    const generationValue = event.metadata?.['generation'];
    const generation = this.positiveIntegerMetadata(event, 'generation');
    if (generationValue !== undefined && generation === undefined) {
      if (!attempt.acknowledged) {
        this.reject(attempt);
      }
      return 'ignored';
    }
    if (!this.mergeIdentity(attempt, {
      requestId: event.requestId,
      conversationId: event.conversationId,
      generation
    })) {
      if (!attempt.acknowledged) {
        this.reject(attempt);
      }
      return 'ignored';
    }
    const sequence = this.nonNegativeIntegerMetadata(event, 'lifecycleEventSequence') || 0;
    attempt.lifecycleEventSequence = Math.max(attempt.lifecycleEventSequence, sequence);
    attempt.effectiveCancellationRequestId =
      this.stringMetadata(event, 'effectiveCancellationRequestId')
      || attempt.effectiveCancellationRequestId;

    if (subtype === 'cancelled') {
      this.finish(attempt, 'CANCELLED');
      return 'admitted';
    }
    if (subtype === 'reconciliation_required') {
      this.finish(attempt, 'UNKNOWN_RECONCILIATION_REQUIRED');
      return 'admitted';
    }
    const status = this.executionStatus(this.stringMetadata(event, 'status'));
    if (subtype === 'cancellation_current_status') {
      if (status && this.isTerminal(status)) {
        this.finish(attempt, status);
      } else {
        this.delay(attempt, 'transport_unknown');
      }
      return status ? 'admitted' : 'ignored';
    }
    if (sequence > 0 && sequence < attempt.lifecycleEventSequence) {
      return 'admitted';
    }
    if (subtype === 'cancellation_delayed') {
      if (!attempt.acknowledged) {
        this.delay(attempt, 'transport_unknown');
      }
      return 'admitted';
    }
    if (subtype === 'cancellation_rejected') {
      if (!attempt.acknowledged) {
        this.reject(attempt);
      }
      return 'admitted';
    }

    if (!this.isPositiveSequence(event.sequence)) {
      if (!attempt.acknowledged) {
        this.delay(attempt, 'transport_unknown');
      }
      return 'ignored';
    }
    const terminal = event.metadata?.['terminal'] === true;
    if ((terminal || (status && this.isTerminal(status))) && status) {
      this.finish(attempt, status);
      return 'admitted';
    }
    if (sequence <= 0) {
      if (!attempt.acknowledged) {
        this.delay(attempt, 'transport_unknown');
      }
      return 'ignored';
    }
    this.acknowledge(attempt);
    return 'admitted';
  }

  reset(): void {
    const attempt = this.current;
    this.current = undefined;
    this.token += 1;
    this.clearDeadlines(attempt);
    this.subscriptions.unsubscribe();
    this.subscriptions = new Subscription();
    if (attempt) {
      attempt.callbacks.onStateChange(this.idleState());
    }
  }

  protected sendHttp(attempt: CancellationAttempt): void {
    const requestId = attempt.identity.requestId;
    const token = attempt.token;
    this.subscriptions.add(this.api.cancelRequest(requestId, this.command(attempt)).subscribe({
      next: response => {
        if (this.isCurrent(attempt, token)) {
          const validated = this.cancellationResponse(response);
          if (validated) {
            this.handleResponse(attempt, validated);
          } else if (!attempt.acknowledged) {
            this.reject(attempt);
          }
        }
      },
      error: error => {
        if (this.isCurrent(attempt, token)) {
          this.handleHttpError(attempt, error);
        }
      }
    }));
  }

  private handleHttpError(attempt: CancellationAttempt, error: unknown): void {
    const httpError = error instanceof HttpErrorResponse ? error : undefined;
    if (!httpError || httpError.status === 0 || httpError.status === 503) {
      if (!attempt.acknowledged) {
        this.delay(attempt, 'transport_unknown');
        this.publishStompFallback(attempt);
      }
      return;
    }
    const response = this.cancellationResponse(httpError?.error);
    if (response) {
      this.handleResponse(attempt, response);
      if (!this.isCurrent(attempt, attempt.token)) {
        return;
      }
    }
    if (attempt.acknowledged) {
      return;
    }
    if (httpError?.status === 404 && !this.hasExactIdentity(attempt) && !response) {
      this.delay(attempt, 'transport_unknown');
      this.publishStompFallback(attempt);
      this.scheduleAdmissionRetry(attempt);
      return;
    }
    if (!response) {
      this.reject(attempt);
    }
  }

  private handleResponse(attempt: CancellationAttempt, response: AiCancellationResponse): void {
    if (response.requestId !== attempt.identity.requestId
      || response.cancellationRequestId !== attempt.cancellationRequestId) {
      return;
    }
    if (!this.mergeIdentity(attempt, {
      requestId: response.requestId,
      conversationId: response.conversationId || undefined,
      generation: response.generation ?? undefined
    })) {
      if (!attempt.acknowledged) {
        this.reject(attempt);
      }
      return;
    }
    if (response.lifecycleEventSequence > 0
      && response.lifecycleEventSequence < attempt.lifecycleEventSequence) {
      return;
    }
    attempt.lifecycleEventSequence = Math.max(
      attempt.lifecycleEventSequence, response.lifecycleEventSequence || 0
    );
    attempt.effectiveCancellationRequestId =
      response.effectiveCancellationRequestId || attempt.effectiveCancellationRequestId;
    const status = response.status || undefined;
    if (response.disposition === 'STALE_GENERATION') {
      if (attempt.acknowledged) {
        return;
      }
      this.reject(attempt);
      return;
    }
    if (status === 'UNKNOWN_RECONCILIATION_REQUIRED') {
      this.finish(attempt, 'UNKNOWN_RECONCILIATION_REQUIRED', response);
      return;
    }
    if ((response.terminal || (status && this.isTerminal(status))) && status) {
      this.finish(attempt, status, response);
      return;
    }
    // A durable acknowledgement is monotonic. A slower in-flight HTTP attempt
    // cannot regress an acknowledgement already accepted for the command ID.
    if (attempt.acknowledged) {
      return;
    }
    if (response.acknowledged
      || response.disposition === 'ACKNOWLEDGED'
      || response.disposition === 'ALREADY_CANCELLING'
      || response.disposition === 'CANCELLED') {
      if (response.lifecycleEventSequence > 0) {
        this.acknowledge(attempt);
      } else if (!attempt.acknowledged) {
        this.delay(attempt, 'transport_unknown');
      }
      return;
    }
    if (!attempt.acknowledged) {
      this.reject(attempt);
    }
  }

  private acknowledge(attempt: CancellationAttempt): void {
    if (!this.isCurrent(attempt, attempt.token)) {
      return;
    }
    this.clearAcknowledgementDeadline(attempt);
    this.clearAdmissionRetry(attempt);
    attempt.acknowledged = true;
    attempt.phase = 'acknowledged';
    attempt.delayReason = undefined;
    this.publishState(attempt);
  }

  protected delay(attempt: CancellationAttempt, reason: AiCancellationDelayReason): void {
    if (!this.isCurrent(attempt, attempt.token)) {
      return;
    }
    attempt.phase = 'delayed';
    attempt.delayReason = reason;
    this.publishState(attempt);
  }

  private reject(attempt: CancellationAttempt): void {
    this.clearAcknowledgementDeadline(attempt);
    this.clearAdmissionRetry(attempt);
    this.delay(attempt, 'rejected');
  }

  private finish(attempt: CancellationAttempt, status: AiExecutionStatus,
                 response?: AiCancellationResponse): void {
    if (!this.isCurrent(attempt, attempt.token)) {
      return;
    }
    const callbacks = attempt.callbacks;
    this.current = undefined;
    this.token += 1;
    this.clearDeadlines(attempt);
    this.subscriptions.unsubscribe();
    this.subscriptions = new Subscription();
    callbacks.onStateChange(this.idleState());
    callbacks.onTerminal(status, response);
  }

  protected queryStatus(attempt: CancellationAttempt): void {
    const {conversationId, generation, requestId} = attempt.identity;
    if (!conversationId || generation === undefined) {
      return;
    }
    const token = attempt.token;
    this.subscriptions.add(this.api.getRequestStatus(
      requestId, conversationId, generation
    ).subscribe({
      next: status => {
        if (this.isCurrent(attempt, token)) {
          this.handleStatus(attempt, status);
        }
      },
      error: () => {
        if (this.isCurrent(attempt, token)) {
          this.delay(attempt, 'transport_unknown');
        }
      }
    }));
  }

  private handleStatus(attempt: CancellationAttempt, status: AiPublicExecutionRequestStatus): void {
    if (status.requestId !== attempt.identity.requestId
      || !this.mergeIdentity(attempt, {
        requestId: status.requestId,
        conversationId: status.conversationId,
        generation: status.generation,
        deadline: status.deadline
      })) {
      this.delay(attempt, 'rejected');
      return;
    }
    attempt.lifecycleEventSequence = Math.max(
      attempt.lifecycleEventSequence, status.lastEventSequence || 0
    );
    attempt.effectiveCancellationRequestId =
      status.cancellationRequestId || attempt.effectiveCancellationRequestId;
    if (this.isTerminal(status.status)) {
      this.finish(attempt, status.status);
      return;
    }
    if (status.lastEventSequence > 0
      && (status.status === 'CANCELLING' || status.cancellationAcknowledgedAt)) {
      attempt.acknowledged = true;
      this.clearAcknowledgementDeadline(attempt);
      this.clearAdmissionRetry(attempt);
    }
    this.delay(attempt, 'terminal_timeout');
  }

  protected publishStompFallback(attempt: CancellationAttempt, force = false): void {
    if (!force && attempt.automaticFallbackPublished) {
      return;
    }
    try {
      this.transport.publish('/app/ai/chat/cancel', {
        requestId: attempt.identity.requestId,
        ...this.command(attempt)
      });
      if (!force) {
        attempt.automaticFallbackPublished = true;
      }
    } catch {
      this.delay(attempt, 'transport_unknown');
    }
  }

}
