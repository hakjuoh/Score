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

  reconnectOnce(): Observable<void> {
    return this.connectOnce(true);
  }

  private clearReconnectAttempt(): void {
    this.publishSubscription?.unsubscribe();
    this.publishSubscription = undefined;
  }

  private tryReconnect(options: AiChatPublishWhenConnectedOptions, attempt: number,
                       generation: number): void {
    if (generation !== this.reconnectGeneration || !options.active()) {
      return;
    }
    this.clearReconnectAttempt();
    options.onReconnectStatus(attempt, MAX_STOMP_RECONNECT_ATTEMPTS);

    this.publishSubscription = this.connectOnce(false).subscribe({
      next: () => this.publishAfterReconnect(options, generation),
      error: () => this.scheduleReconnectRetry(options, attempt, generation)
    });
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

  private connectOnce(forceRestart: boolean): Observable<void> {
    return new Observable<void>(subscriber => {
      let connectionSubscription: Subscription | undefined;
      const timeout = window.setTimeout(() => {
        subscriber.error(new Error('WebSocket reconnect attempt timed out.'));
      }, STOMP_RECONNECT_ATTEMPT_TIMEOUT_MS);
      const complete = () => {
        subscriber.next();
        subscriber.complete();
      };
      const activate = () => {
        if (subscriber.closed) {
          return;
        }
        connectionSubscription = this.stompService.connected$.pipe(
          filter(state => state === RxStompState.OPEN),
          take(1)
        ).subscribe({next: complete, error: error => subscriber.error(error)});
        try {
          this.stompService.activate();
        } catch (error) {
          subscriber.error(error);
        }
      };

      if (this.stompService.connected() && !forceRestart) {
        complete();
      } else {
        this.stompService.deactivate({force: true}).then(activate).catch(activate);
      }

      return () => {
        window.clearTimeout(timeout);
        connectionSubscription?.unsubscribe();
      };
    });
  }
}
