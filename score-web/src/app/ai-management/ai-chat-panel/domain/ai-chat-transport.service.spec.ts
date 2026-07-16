import {TestBed} from '@angular/core/testing';
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

  beforeEach(() => {
    vi.useFakeTimers();
    stompService = new FakeRxStompService();
    TestBed.configureTestingModule({
      providers: [
        AiChatTransportService,
        {provide: RxStompService, useValue: stompService}
      ]
    });
    service = TestBed.inject(AiChatTransportService);
  });

  afterEach(() => {
    service.cancelReconnect();
    vi.useRealTimers();
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
});
