/**
 * Defines the state and callback contracts shared by cancellation workflows.
 */

import {
  CANCELLATION_ACK_TIMEOUT_MS,
  CANCELLATION_ADMISSION_RETRY_MS,
  CANCELLATION_TERMINAL_TIMEOUT_MS,
  MAX_CANCELLATION_ADMISSION_RETRIES
} from './ai-chat-panel.constants';
import {
  AiActiveRequestIdentity,
  AiCancellationCommand,
  AiCancellationDelayReason,
  AiCancellationResponse,
  AiCancellationUiState,
  AiChatSocketEvent,
  AiExecutionStatus
} from './ai-chat-panel.model';

export interface AiChatCancellationCallbacks {
  onStateChange: (state: AiCancellationUiState) => void;
  onIdentityChange: (identity: AiActiveRequestIdentity) => void;
  onTerminal: (status: AiExecutionStatus, response?: AiCancellationResponse) => void;
}

export interface CancellationAttempt {
  token: number;
  identity: AiActiveRequestIdentity;
  cancellationRequestId: string;
  effectiveCancellationRequestId?: string;
  phase: AiCancellationUiState['phase'];
  acknowledged: boolean;
  lifecycleEventSequence: number;
  delayReason?: AiCancellationDelayReason;
  automaticFallbackPublished: boolean;
  admissionRetryScheduled: boolean;
  admissionRetryCount: number;
  acknowledgementDeadlineAt?: number;
  callbacks: AiChatCancellationCallbacks;
}

const TERMINAL_STATUSES = new Set<AiExecutionStatus>([
  'COMPLETED',
  'STEP_LIMIT_REACHED',
  'FAILED',
  'CANCELLED',
  'TIMED_OUT',
  'UNKNOWN_RECONCILIATION_REQUIRED'
]);
const EXECUTION_STATUSES = new Set<AiExecutionStatus>([
  'REGISTERED',
  'RUNNING',
  'CANCELLING',
  ...TERMINAL_STATUSES
]);
const CANCELLATION_DISPOSITIONS = new Set<AiCancellationResponse['disposition']>([
  'ACKNOWLEDGED', 'CANCELLED', 'ALREADY_CANCELLING',
  'ALREADY_TERMINAL', 'STALE_GENERATION'
]);

export abstract class AiChatCancellationState {

  protected current?: CancellationAttempt;
  protected token = 0;
  protected acknowledgementTimeout?: number;
  protected terminalTimeout?: number;
  protected admissionRetryTimeout?: number;

  protected abstract delay(attempt: CancellationAttempt, reason: AiCancellationDelayReason): void;
  protected abstract publishStompFallback(attempt: CancellationAttempt, force?: boolean): void;
  protected abstract queryStatus(attempt: CancellationAttempt): void;
  protected abstract sendHttp(attempt: CancellationAttempt): void;
  protected armAcknowledgementDeadline(attempt: CancellationAttempt): void {
    this.clearAcknowledgementDeadline(attempt);
    const token = attempt.token;
    attempt.acknowledgementDeadlineAt = Date.now() + CANCELLATION_ACK_TIMEOUT_MS;
    this.acknowledgementTimeout = window.setTimeout(() => {
      this.acknowledgementTimeout = undefined;
      if (!this.isCurrent(attempt, token) || attempt.acknowledged) {
        return;
      }
      attempt.acknowledgementDeadlineAt = undefined;
      this.delay(attempt, 'ack_timeout');
      this.publishStompFallback(attempt);
    }, CANCELLATION_ACK_TIMEOUT_MS);
  }

  protected scheduleAdmissionRetry(attempt: CancellationAttempt): void {
    const acknowledgementDeadlineAt = attempt.acknowledgementDeadlineAt;
    if (attempt.admissionRetryScheduled
      || attempt.admissionRetryCount >= MAX_CANCELLATION_ADMISSION_RETRIES
      || acknowledgementDeadlineAt === undefined
      || Date.now() + CANCELLATION_ADMISSION_RETRY_MS >= acknowledgementDeadlineAt) {
      return;
    }
    attempt.admissionRetryScheduled = true;
    const token = attempt.token;
    this.admissionRetryTimeout = window.setTimeout(() => {
      this.admissionRetryTimeout = undefined;
      attempt.admissionRetryScheduled = false;
      if (!this.isCurrent(attempt, token) || attempt.acknowledged
        || attempt.admissionRetryCount >= MAX_CANCELLATION_ADMISSION_RETRIES
        || attempt.acknowledgementDeadlineAt === undefined
        || Date.now() >= attempt.acknowledgementDeadlineAt) {
        return;
      }
      attempt.admissionRetryCount += 1;
      this.sendHttp(attempt);
    }, CANCELLATION_ADMISSION_RETRY_MS);
  }

  protected armTerminalDeadline(attempt: CancellationAttempt): void {
    this.clearTerminalDeadline();
    const token = attempt.token;
    this.terminalTimeout = window.setTimeout(() => {
      this.terminalTimeout = undefined;
      if (!this.isCurrent(attempt, token)) {
        return;
      }
      this.delay(attempt, 'terminal_timeout');
      this.queryStatus(attempt);
    }, CANCELLATION_TERMINAL_TIMEOUT_MS);
  }

  protected clearDeadlines(attempt?: CancellationAttempt): void {
    this.clearAcknowledgementDeadline(attempt);
    this.clearTerminalDeadline();
    this.clearAdmissionRetry(attempt);
  }

  protected clearAcknowledgementDeadline(attempt?: CancellationAttempt): void {
    if (this.acknowledgementTimeout !== undefined) {
      window.clearTimeout(this.acknowledgementTimeout);
      this.acknowledgementTimeout = undefined;
    }
    if (attempt) {
      attempt.acknowledgementDeadlineAt = undefined;
    }
  }

  protected clearTerminalDeadline(): void {
    if (this.terminalTimeout !== undefined) {
      window.clearTimeout(this.terminalTimeout);
      this.terminalTimeout = undefined;
    }
  }

  protected clearAdmissionRetry(attempt?: CancellationAttempt): void {
    if (this.admissionRetryTimeout !== undefined) {
      window.clearTimeout(this.admissionRetryTimeout);
      this.admissionRetryTimeout = undefined;
    }
    if (attempt) {
      attempt.admissionRetryScheduled = false;
    }
  }

  protected mergeIdentity(attempt: CancellationAttempt,
                        identity: AiActiveRequestIdentity): boolean {
    if (attempt.identity.requestId !== identity.requestId) {
      return false;
    }
    if (identity.generation !== undefined && !this.isPositiveGeneration(identity.generation)) {
      return false;
    }
    if (attempt.identity.conversationId && identity.conversationId
      && attempt.identity.conversationId !== identity.conversationId) {
      return false;
    }
    if (attempt.identity.generation !== undefined && identity.generation !== undefined
      && attempt.identity.generation !== identity.generation) {
      return false;
    }
    attempt.identity = {
      requestId: attempt.identity.requestId,
      conversationId: identity.conversationId || attempt.identity.conversationId,
      generation: identity.generation ?? attempt.identity.generation,
      deadline: identity.deadline || attempt.identity.deadline
    };
    attempt.callbacks.onIdentityChange({...attempt.identity});
    return true;
  }

  protected command(attempt: CancellationAttempt): AiCancellationCommand {
    const command: AiCancellationCommand = {
      cancellationRequestId: attempt.cancellationRequestId
    };
    if (attempt.identity.conversationId
      && attempt.identity.generation !== undefined
      && this.isPositiveGeneration(attempt.identity.generation)) {
      command.conversationId = attempt.identity.conversationId;
      command.expectedGeneration = attempt.identity.generation;
    }
    return command;
  }

  protected publishState(attempt: CancellationAttempt): void {
    attempt.callbacks.onStateChange({
      phase: attempt.phase,
      requestId: attempt.identity.requestId,
      cancellationRequestId: attempt.cancellationRequestId,
      effectiveCancellationRequestId: attempt.effectiveCancellationRequestId,
      acknowledged: attempt.acknowledged,
      lifecycleEventSequence: attempt.lifecycleEventSequence,
      delayReason: attempt.delayReason
    });
  }

  protected idleState(): AiCancellationUiState {
    return {
      phase: 'idle',
      acknowledged: false,
      lifecycleEventSequence: 0
    };
  }

  protected isCurrent(attempt: CancellationAttempt, token: number): boolean {
    return this.current === attempt && attempt.token === token;
  }

  protected isTerminal(status: AiExecutionStatus): boolean {
    return TERMINAL_STATUSES.has(status);
  }

  protected executionStatus(value?: string): AiExecutionStatus | undefined {
    return value && EXECUTION_STATUSES.has(value as AiExecutionStatus)
      ? value as AiExecutionStatus : undefined;
  }

  protected stringMetadata(event: AiChatSocketEvent, key: string): string | undefined {
    const value = event.metadata?.[key];
    return typeof value === 'string' && value.trim() ? value : undefined;
  }

  protected nonNegativeIntegerMetadata(event: AiChatSocketEvent, key: string): number | undefined {
    const value = event.metadata?.[key];
    return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
      ? value : undefined;
  }

  protected positiveIntegerMetadata(event: AiChatSocketEvent, key: string): number | undefined {
    const value = event.metadata?.[key];
    return typeof value === 'number' && this.isPositiveGeneration(value) ? value : undefined;
  }

  protected normalizedIdentity(identity: AiActiveRequestIdentity): AiActiveRequestIdentity {
    const generation = identity.generation !== undefined
      && this.isPositiveGeneration(identity.generation) ? identity.generation : undefined;
    return {
      requestId: identity.requestId.trim(),
      conversationId: generation !== undefined ? identity.conversationId : undefined,
      generation: identity.conversationId ? generation : undefined,
      deadline: identity.deadline
    };
  }

  protected isPositiveGeneration(value: number): boolean {
    return Number.isSafeInteger(value) && value > 0;
  }

  protected isPositiveSequence(value?: number): boolean {
    return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
  }

  protected hasExactIdentity(attempt: CancellationAttempt): boolean {
    return !!attempt.identity.conversationId
      && attempt.identity.generation !== undefined
      && this.isPositiveGeneration(attempt.identity.generation);
  }

  protected cancellationResponse(value: unknown): AiCancellationResponse | undefined {
    if (!value || typeof value !== 'object') {
      return undefined;
    }
    const candidate = value as Partial<AiCancellationResponse>;
    return typeof candidate.requestId === 'string'
      && typeof candidate.cancellationRequestId === 'string'
      && CANCELLATION_DISPOSITIONS.has(candidate.disposition as AiCancellationResponse['disposition'])
      && typeof candidate.acknowledged === 'boolean'
      && typeof candidate.terminal === 'boolean'
      ? candidate as AiCancellationResponse
      : undefined;
  }

  protected createCancellationRequestId(): string {
    if (window.crypto && 'randomUUID' in window.crypto) {
      return window.crypto.randomUUID();
    }
    return Date.now() + '-' + Math.random().toString(16).slice(2);
  }
}
