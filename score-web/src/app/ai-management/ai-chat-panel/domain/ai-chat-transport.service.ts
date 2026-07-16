import {Injectable, inject} from '@angular/core';
import {Message} from '@stomp/stompjs';
import {RxStompState} from '@stomp/rx-stomp';
import {Observable, Subscription} from 'rxjs';
import {filter, take} from 'rxjs/operators';
import {RxStompService} from '../../../common/score-rx-stomp';
import {
  MAX_STOMP_RECONNECT_ATTEMPTS,
  STOMP_RECONNECT_ATTEMPT_TIMEOUT_MS
} from './ai-chat-panel.constants';

export interface AiChatPublishWhenConnectedOptions {
  active: () => boolean;
  onReconnectStatus: (attempt: number, maxAttempts: number) => void;
  onConnected: () => void;
  publish: () => void;
  onReconnectFailure: () => void;
  onPublishError?: () => void;
}

@Injectable({
  providedIn: 'root'
})
export class AiChatTransportService {

  private stompService = inject(RxStompService);
  private publishSubscription?: Subscription;
  private reconnectAttemptTimeout?: number;
  private reconnectGeneration = 0;

  watch(destination: string): Observable<Message> {
    return this.stompService.watch(destination);
  }

  publish(destination: string, body: unknown): void {
    this.stompService.publish({
      destination,
      body: JSON.stringify(body)
    });
  }

  deactivate(options?: {force?: boolean}): Promise<void> {
    return this.stompService.deactivate(options);
  }

  publishWhenConnected(options: AiChatPublishWhenConnectedOptions): void {
    this.cancelReconnect();
    this.tryReconnect(options, 1, this.reconnectGeneration);
  }

  cancelReconnect(): void {
    this.reconnectGeneration += 1;
    this.clearReconnectAttempt();
  }

  private clearReconnectAttempt(): void {
    this.publishSubscription?.unsubscribe();
    this.publishSubscription = undefined;
    if (this.reconnectAttemptTimeout) {
      window.clearTimeout(this.reconnectAttemptTimeout);
      this.reconnectAttemptTimeout = undefined;
    }
  }

  private tryReconnect(options: AiChatPublishWhenConnectedOptions, attempt: number,
                       generation: number): void {
    if (generation !== this.reconnectGeneration || !options.active()) {
      return;
    }
    this.clearReconnectAttempt();
    options.onReconnectStatus(attempt, MAX_STOMP_RECONNECT_ATTEMPTS);

    let opened = false;
    this.publishSubscription = this.stompService.connected$.pipe(
      filter(state => state === RxStompState.OPEN),
      take(1)
    ).subscribe(() => {
      opened = true;
      this.clearReconnectAttemptTimeout();
      this.publishAfterReconnect(options, generation);
    });

    const activate = () => {
      if (generation !== this.reconnectGeneration || !options.active()) {
        return;
      }
      try {
        this.stompService.activate();
      } catch (e) {
        this.scheduleReconnectRetry(options, attempt, generation);
      }
    };

    if (this.stompService.connected()) {
      activate();
    } else {
      this.stompService.deactivate({force: true}).then(activate).catch(activate);
    }

    this.reconnectAttemptTimeout = window.setTimeout(() => {
      if (generation !== this.reconnectGeneration || !options.active() || opened) {
        return;
      }
      this.scheduleReconnectRetry(options, attempt, generation);
    }, STOMP_RECONNECT_ATTEMPT_TIMEOUT_MS);
  }

  private scheduleReconnectRetry(options: AiChatPublishWhenConnectedOptions, attempt: number,
                                 generation: number): void {
    this.clearReconnectAttempt();
    if (generation !== this.reconnectGeneration || !options.active()) {
      return;
    }
    if (attempt < MAX_STOMP_RECONNECT_ATTEMPTS) {
      this.tryReconnect(options, attempt + 1, generation);
      return;
    }
    options.onReconnectFailure();
  }

  private publishAfterReconnect(options: AiChatPublishWhenConnectedOptions,
                                generation: number): void {
    if (generation !== this.reconnectGeneration || !options.active()) {
      return;
    }
    options.onConnected();
    try {
      options.publish();
    } catch (e) {
      if (options.onPublishError) {
        options.onPublishError();
      }
    }
  }

  private clearReconnectAttemptTimeout(): void {
    if (this.reconnectAttemptTimeout) {
      window.clearTimeout(this.reconnectAttemptTimeout);
      this.reconnectAttemptTimeout = undefined;
    }
  }
}
