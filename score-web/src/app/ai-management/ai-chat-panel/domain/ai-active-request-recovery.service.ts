/**
 * Reconciles a server-side active request with the panel and retries recoverable probes.
 */

import {Injectable, inject} from '@angular/core';
import {Observable, of, Subscription, map, switchMap, take} from 'rxjs';
import {AiChatApiService} from './ai-chat-api.service';
import {
  ACTIVE_REQUEST_RECOVERY_RETRY_DELAY_MS,
  MAX_ACTIVE_REQUEST_RECOVERY_ATTEMPTS
} from './ai-chat-panel.constants';
import {AiActiveRequestIdentity, AiPublicExecutionRequestStatus} from './ai-chat-panel.model';
import {AiChatTransportService} from './ai-chat-transport.service';

export interface AiActiveRequestRecoveryCallbacks {
  onAttempt(attempt: number, maxAttempts: number): void;
  verify?(status: AiPublicExecutionRequestStatus): Observable<void>;
  onRecovered(status: AiPublicExecutionRequestStatus): void;
  onFailure(maxAttempts: number): void;
}

type RecoverableRequestIdentity = AiActiveRequestIdentity & {
  conversationId: string;
  generation: number;
};

@Injectable()
export class AiActiveRequestRecoveryService {
  private api = inject(AiChatApiService);
  private transport = inject(AiChatTransportService);
  private attemptSubscription?: Subscription;
  private retryTimeout?: number;
  private generation = 0;

  recover(identity: RecoverableRequestIdentity,
          callbacks: AiActiveRequestRecoveryCallbacks): void {
    this.cancel();
    this.tryRecover(identity, callbacks, 1, this.generation);
  }

  cancel(): void {
    this.generation += 1;
    this.attemptSubscription?.unsubscribe();
    this.attemptSubscription = undefined;
    if (this.retryTimeout !== undefined) {
      window.clearTimeout(this.retryTimeout);
      this.retryTimeout = undefined;
    }
  }

  private tryRecover(identity: RecoverableRequestIdentity,
                     callbacks: AiActiveRequestRecoveryCallbacks,
                     attempt: number, generation: number): void {
    if (generation !== this.generation) {
      return;
    }
    callbacks.onAttempt(attempt, MAX_ACTIVE_REQUEST_RECOVERY_ATTEMPTS);
    if (generation !== this.generation) {
      return;
    }
    this.attemptSubscription = this.transport.reconnectOnce().pipe(
      switchMap(() => this.api.getRequestStatus(
        identity.requestId, identity.conversationId, identity.generation
      )),
      switchMap(status => (callbacks.verify?.(status) ?? of(undefined)).pipe(
        map(() => status)
      )),
      take(1)
    ).subscribe({
      next: status => {
        if (generation === this.generation) {
          callbacks.onRecovered(status);
        }
      },
      error: () => {
        if (generation !== this.generation) {
          return;
        }
        if (attempt >= MAX_ACTIVE_REQUEST_RECOVERY_ATTEMPTS) {
          callbacks.onFailure(MAX_ACTIVE_REQUEST_RECOVERY_ATTEMPTS);
          return;
        }
        this.retryTimeout = window.setTimeout(() => {
          this.retryTimeout = undefined;
          this.tryRecover(identity, callbacks, attempt + 1, generation);
        }, ACTIVE_REQUEST_RECOVERY_RETRY_DELAY_MS);
      }
    });
  }
}
