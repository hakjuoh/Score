/**
 * Verifies the AI Active Request Recovery service contract, failure handling, and edge cases.
 */

import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {AiActiveRequestRecoveryService} from './ai-active-request-recovery.service';
import {AiChatApiService} from './ai-chat-api.service';
import {ACTIVE_REQUEST_RECOVERY_RETRY_DELAY_MS} from './ai-chat-panel.constants';
import {AiChatTransportService} from './ai-chat-transport.service';

describe('AiActiveRequestRecoveryService', () => {
  const identity = {
    requestId: 'request-1', conversationId: 'conversation-1', generation: 7
  };
  const runningStatus = {
    ...identity,
    status: 'RUNNING' as const,
    deadline: '2026-07-22T20:00:00Z', retryCount: 0,
    createdAt: '2026-07-22T19:00:00Z', updatedAt: '2026-07-22T19:00:05Z',
    lastEventSequence: 1, version: 2
  };
  let service: AiActiveRequestRecoveryService;
  let api: {getRequestStatus: ReturnType<typeof vi.fn>};
  let transport: {reconnectOnce: ReturnType<typeof vi.fn>};

  beforeEach(() => {
    vi.useFakeTimers();
    api = {getRequestStatus: vi.fn(() => of(runningStatus))};
    transport = {reconnectOnce: vi.fn(() => of(undefined))};
    TestBed.configureTestingModule({providers: [
      AiActiveRequestRecoveryService,
      {provide: AiChatApiService, useValue: api},
      {provide: AiChatTransportService, useValue: transport}
    ]});
    service = TestBed.inject(AiActiveRequestRecoveryService);
  });

  afterEach(() => {
    service.cancel();
    vi.useRealTimers();
  });

  it('reconnects and verifies the exact active request identity', () => {
    const onRecovered = vi.fn();
    const onAttempt = vi.fn();

    service.recover(identity, {onAttempt, onRecovered, onFailure: vi.fn()});

    expect(onAttempt).toHaveBeenCalledWith(1, 3);
    expect(transport.reconnectOnce).toHaveBeenCalledOnce();
    expect(api.getRequestStatus).toHaveBeenCalledWith(
      'request-1', 'conversation-1', 7
    );
    expect(onRecovered).toHaveBeenCalledWith(runningStatus);
  });

  it('stops after three failed reconnect attempts', async () => {
    transport.reconnectOnce.mockReturnValue(
      throwError(() => new Error('backend unavailable'))
    );
    const onAttempt = vi.fn();
    const onFailure = vi.fn();

    service.recover(identity, {onAttempt, onRecovered: vi.fn(), onFailure});
    await vi.advanceTimersByTimeAsync(ACTIVE_REQUEST_RECOVERY_RETRY_DELAY_MS);
    await vi.advanceTimersByTimeAsync(ACTIVE_REQUEST_RECOVERY_RETRY_DELAY_MS);

    expect(onAttempt.mock.calls.map(call => call[0])).toEqual([1, 2, 3]);
    expect(transport.reconnectOnce).toHaveBeenCalledTimes(3);
    expect(api.getRequestStatus).not.toHaveBeenCalled();
    expect(onFailure).toHaveBeenCalledWith(3);
  });

  it('keeps conversation verification failures inside the same retry budget', async () => {
    const verify = vi.fn(() => throwError(() => new Error('conversation unavailable')));
    const onFailure = vi.fn();

    service.recover(identity, {
      onAttempt: vi.fn(), verify, onRecovered: vi.fn(), onFailure
    });
    await vi.advanceTimersByTimeAsync(ACTIVE_REQUEST_RECOVERY_RETRY_DELAY_MS * 2);

    expect(transport.reconnectOnce).toHaveBeenCalledTimes(3);
    expect(api.getRequestStatus).toHaveBeenCalledTimes(3);
    expect(verify).toHaveBeenCalledTimes(3);
    expect(onFailure).toHaveBeenCalledWith(3);
  });

  it('cancels a scheduled retry without invoking stale callbacks', async () => {
    transport.reconnectOnce.mockReturnValue(
      throwError(() => new Error('backend unavailable'))
    );
    const onAttempt = vi.fn();
    const onFailure = vi.fn();
    service.recover(identity, {onAttempt, onRecovered: vi.fn(), onFailure});

    service.cancel();
    await vi.advanceTimersByTimeAsync(ACTIVE_REQUEST_RECOVERY_RETRY_DELAY_MS * 3);

    expect(onAttempt).toHaveBeenCalledOnce();
    expect(onFailure).not.toHaveBeenCalled();
  });
});
