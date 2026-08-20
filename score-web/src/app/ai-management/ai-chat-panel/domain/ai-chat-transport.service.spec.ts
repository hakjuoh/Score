/**
 * Verifies the AI Chat Transport service contract, failure handling, and edge cases.
 */

import {NgZone} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {Message} from '@stomp/stompjs';
import {RxStompState} from '@stomp/rx-stomp';
import {Subject, of} from 'rxjs';
import {RxStompService} from '../../../common/score-rx-stomp';
import {MAX_STOMP_RECONNECT_ATTEMPTS, STOMP_RECONNECT_ATTEMPT_TIMEOUT_MS} from './ai-chat-panel.constants';
import {AiChatTransportService} from './ai-chat-transport.service';

class FakeRxStompService {
  connected$ = new Subject<RxStompState>();
  watch = vi.fn(() => of());
  publish = vi.fn();
  deactivate = vi.fn(() => Promise.resolve());
  activate = vi.fn();
  connected = vi.fn(() => false);
}

describe('AiChatTransportService', () => {
  let service: AiChatTransportService;
  let stompService: FakeRxStompService;
  let ngZone: {run: ReturnType<typeof vi.fn>};

  beforeEach(() => {
    vi.useFakeTimers();
    stompService = new FakeRxStompService();
    ngZone = {run: vi.fn(callback => callback())};
    TestBed.configureTestingModule({
      providers: [
        AiChatTransportService,
        {provide: RxStompService, useValue: stompService},
        {provide: NgZone, useValue: ngZone}
      ]
    });
    service = TestBed.inject(AiChatTransportService);
  });

  afterEach(() => {
    service.cancelReconnect();
    vi.useRealTimers();
  });

  it('delivers STOMP messages inside Angular', () => {
    const messages = new Subject<Message>();
    stompService.watch.mockReturnValue(messages);
    const receivedInAngularZone = vi.fn();

    service.watch('/user/queue/ai/chat/request-1').subscribe(() => {
      receivedInAngularZone();
    });
    messages.next({body: '{}'} as Message);

    expect(ngZone.run).toHaveBeenCalledOnce();
    expect(receivedInAngularZone).toHaveBeenCalledOnce();
  });

  it('delivers terminal notifications inside Angular', () => {
    const failedMessages = new Subject<Message>();
    const failure = new Error('socket failed');
    const error = vi.fn();
    stompService.watch.mockReturnValue(failedMessages);
    service.watch('/user/queue/ai/chat/request-1').subscribe({error});

    failedMessages.error(failure);

    expect(error).toHaveBeenCalledWith(failure);
    expect(ngZone.run).toHaveBeenCalledOnce();

    ngZone.run.mockClear();
    const completedMessages = new Subject<Message>();
    const complete = vi.fn();
    stompService.watch.mockReturnValue(completedMessages);
    service.watch('/user/queue/ai/chat/request-2').subscribe({complete});

    completedMessages.complete();

    expect(complete).toHaveBeenCalledOnce();
    expect(ngZone.run).toHaveBeenCalledOnce();
  });

  it('tears down the source STOMP subscription when the watcher unsubscribes', () => {
    const messages = new Subject<Message>();
    stompService.watch.mockReturnValue(messages);

    const subscription = service.watch('/user/queue/ai/chat/request-1').subscribe();
    expect(messages.observed).toBe(true);

    subscription.unsubscribe();

    expect(messages.observed).toBe(false);
  });

  it('publishes after a shared reconnect flow reaches OPEN', async () => {
    const publish = vi.fn();
    const onConnected = vi.fn();

    service.publishWhenConnected({
      active: () => true,
      onReconnectStatus: vi.fn(),
      onConnected,
      publish,
      onReconnectFailure: vi.fn()
    });

    await Promise.resolve();
    stompService.connected$.next(RxStompState.OPEN);

    expect(onConnected).toHaveBeenCalledOnce();
    expect(publish).toHaveBeenCalledOnce();
  });

  it('fails after the configured reconnect attempts are exhausted', async () => {
    const onReconnectFailure = vi.fn();
    const onReconnectStatus = vi.fn();

    service.publishWhenConnected({
      active: () => true,
      onReconnectStatus,
      onConnected: vi.fn(),
      publish: vi.fn(),
      onReconnectFailure
    });

    for (let i = 0; i < MAX_STOMP_RECONNECT_ATTEMPTS; i++) {
      await Promise.resolve();
      await vi.advanceTimersByTimeAsync(STOMP_RECONNECT_ATTEMPT_TIMEOUT_MS);
    }

    expect(onReconnectStatus).toHaveBeenCalledTimes(MAX_STOMP_RECONNECT_ATTEMPTS);
    expect(onReconnectFailure).toHaveBeenCalledOnce();
  });

  it('active predicate suppresses a publish waiting for the initial connection', async () => {
    const publish = vi.fn();
    const onReconnectFailure = vi.fn();
    let active = true;
    service.publishWhenConnected({
      active: () => active,
      onReconnectStatus: vi.fn(),
      onConnected: vi.fn(),
      publish,
      onReconnectFailure
    });
    await Promise.resolve();

    active = false;
    stompService.connected$.next(RxStompState.OPEN);
    await vi.advanceTimersByTimeAsync(
      STOMP_RECONNECT_ATTEMPT_TIMEOUT_MS * MAX_STOMP_RECONNECT_ATTEMPTS
    );

    expect(publish).not.toHaveBeenCalled();
    expect(onReconnectFailure).not.toHaveBeenCalled();
  });

  it('explicit cancellation invalidates an in-flight deactivate activation', async () => {
    let finishDeactivation!: () => void;
    stompService.deactivate.mockReturnValue(new Promise<void>(resolve => {
      finishDeactivation = resolve;
    }));
    service.publishWhenConnected({
      active: () => true,
      onReconnectStatus: vi.fn(),
      onConnected: vi.fn(),
      publish: vi.fn(),
      onReconnectFailure: vi.fn()
    });

    service.cancelReconnect();
    finishDeactivation();
    await Promise.resolve();

    expect(stompService.activate).not.toHaveBeenCalled();
  });

  it('cancels a scheduled publish after a reconnect retry has started', async () => {
    const publish = vi.fn();
    const onReconnectStatus = vi.fn();
    const onReconnectFailure = vi.fn();
    let active = true;
    service.publishWhenConnected({
      active: () => active,
      onReconnectStatus,
      onConnected: vi.fn(),
      publish,
      onReconnectFailure
    });
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(STOMP_RECONNECT_ATTEMPT_TIMEOUT_MS);
    expect(onReconnectStatus).toHaveBeenCalledTimes(2);

    active = false;
    service.cancelReconnect();
    stompService.connected$.next(RxStompState.OPEN);
    await vi.advanceTimersByTimeAsync(
      STOMP_RECONNECT_ATTEMPT_TIMEOUT_MS * MAX_STOMP_RECONNECT_ATTEMPTS
    );

    expect(publish).not.toHaveBeenCalled();
    expect(onReconnectFailure).not.toHaveBeenCalled();
  });

  it('forces an existing connection through a fresh reconnect for active recovery', async () => {
    stompService.connected.mockReturnValue(true);
    const recovered = vi.fn();

    service.reconnectOnce().subscribe({next: recovered});
    await Promise.resolve();

    expect(stompService.deactivate).toHaveBeenCalledWith({force: true});
    expect(stompService.activate).toHaveBeenCalledOnce();
    expect(recovered).not.toHaveBeenCalled();

    stompService.connected$.next(RxStompState.OPEN);
    expect(recovered).toHaveBeenCalledOnce();
  });

  it('bounds one active recovery connection attempt by the reconnect timeout', async () => {
    const error = vi.fn();

    service.reconnectOnce().subscribe({error});
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(STOMP_RECONNECT_ATTEMPT_TIMEOUT_MS);

    expect(error).toHaveBeenCalledOnce();
  });
});
