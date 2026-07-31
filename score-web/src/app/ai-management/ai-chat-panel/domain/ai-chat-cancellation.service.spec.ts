/**
 * Verifies the AI Chat Cancellation service contract, failure handling, and edge cases.
 */

import {HttpErrorResponse} from '@angular/common/http';
import {AiChatCancellationCallbacks} from './ai-chat-cancellation.service';
import {
  api,
  callbacks,
  cancellationResponse,
  cancelResponses,
  publicStatus,
  service,
  setupCancellationServiceSpec,
  states,
  statusResponses,
  teardownCancellationServiceSpec,
  terminals,
  transport
} from './ai-chat-cancellation.service.spec-support';
import {
  CANCELLATION_ADMISSION_RETRY_MS,
  CANCELLATION_ACK_TIMEOUT_MS,
  CANCELLATION_TERMINAL_TIMEOUT_MS
} from './ai-chat-panel.constants';
import {
  AiCancellationCommand,
  AiExecutionStatus
} from './ai-chat-panel.model';

describe('AiChatCancellationService', () => {
  beforeEach(setupCancellationServiceSpec);
  afterEach(teardownCancellationServiceSpec);

  it('keeps HTTP authoritative until the two-second ACK deadline', () => {
    service.start({requestId: 'request-1'}, callbacks);

    expect(api.cancelRequest).toHaveBeenCalledWith('request-1', {
      cancellationRequestId: 'cancel-1'
    });
    expect(transport.publish).not.toHaveBeenCalled();

    vi.advanceTimersByTime(CANCELLATION_ACK_TIMEOUT_MS - 1);
    expect(states.at(-1)?.phase).toBe('requesting');
    expect(transport.publish).not.toHaveBeenCalled();

    vi.advanceTimersByTime(1);
    expect(states.at(-1)?.phase).toBe('delayed');
    expect(states.at(-1)?.delayReason).toBe('ack_timeout');
    expect(transport.publish).toHaveBeenCalledWith('/app/ai/chat/cancel', {
      requestId: 'request-1',
      cancellationRequestId: 'cancel-1'
    });
  });

  it('reuses the same command ID after accepted identity becomes available', () => {
    service.start({requestId: 'request-1'}, callbacks);
    service.updateIdentity({
      requestId: 'request-1',
      conversationId: 'conversation-1',
      generation: 7,
      deadline: '2026-07-14T13:05:00Z'
    });

    service.retry();

    expect(api.cancelRequest).toHaveBeenNthCalledWith(2, 'request-1', {
      cancellationRequestId: 'cancel-1',
      conversationId: 'conversation-1',
      expectedGeneration: 7
    });
  });

  it('uses an acknowledged response identity for the five-second status read-back', () => {
    service.start({requestId: 'request-1'}, callbacks);
    cancelResponses[0].next(cancellationResponse());

    expect(states.at(-1)).toMatchObject({phase: 'acknowledged', acknowledged: true});
    vi.advanceTimersByTime(CANCELLATION_TERMINAL_TIMEOUT_MS);

    expect(api.getRequestStatus).toHaveBeenCalledWith('request-1', 'conversation-1', 7);
    statusResponses[0].next(publicStatus('CANCELLED'));
    expect(terminals).toEqual(['CANCELLED']);
    expect(service.isActive()).toBe(false);
  });

  it('falls back with the same ID after an ambiguous HTTP transport failure', () => {
    service.start({requestId: 'request-1'}, callbacks);

    cancelResponses[0].error(new HttpErrorResponse({status: 0, statusText: 'Offline'}));

    expect(states.at(-1)?.delayReason).toBe('transport_unknown');
    expect(transport.publish).toHaveBeenCalledWith('/app/ai/chat/cancel', {
      requestId: 'request-1',
      cancellationRequestId: 'cancel-1'
    });
  });

  it('does not blindly fall back after a definitive conflict response', () => {
    service.start({requestId: 'request-1'}, callbacks);
    const response = cancellationResponse({
      disposition: 'STALE_GENERATION',
      status: 'RUNNING',
      acknowledged: false,
      lifecycleEventSequence: 0
    });

    cancelResponses[0].error(new HttpErrorResponse({status: 409, error: response}));
    vi.advanceTimersByTime(CANCELLATION_ACK_TIMEOUT_MS + 1);

    expect(states.at(-1)?.delayReason).toBe('rejected');
    expect(transport.publish).not.toHaveBeenCalled();
  });

  it('orders a same-ID STOMP fallback after a transient pre-admission 404', () => {
    service.start({requestId: 'request-1'}, callbacks);
    cancelResponses[0].error(new HttpErrorResponse({
      status: 404,
      error: {}
    }));

    expect(states.at(-1)).toMatchObject({
      phase: 'delayed', delayReason: 'transport_unknown'
    });
    expect(transport.publish).toHaveBeenCalledWith('/app/ai/chat/cancel', {
      requestId: 'request-1', cancellationRequestId: 'cancel-1'
    });
    vi.advanceTimersByTime(CANCELLATION_ADMISSION_RETRY_MS);
    expect(api.cancelRequest).toHaveBeenNthCalledWith(2, 'request-1', {
      cancellationRequestId: 'cancel-1'
    });
    cancelResponses[1].next(cancellationResponse());
    expect(states.at(-1)?.phase).toBe('acknowledged');
    vi.advanceTimersByTime(CANCELLATION_ACK_TIMEOUT_MS + 1);
    expect(transport.publish).toHaveBeenCalledTimes(1);
  });

  it('retries consecutive pre-admission 404s with the same ID until durable ACK', () => {
    service.start({requestId: 'request-1'}, callbacks);

    for (let responseIndex = 0; responseIndex < 2; responseIndex++) {
      cancelResponses[responseIndex].error(new HttpErrorResponse({
        status: 404,
        error: {}
      }));
      vi.advanceTimersByTime(CANCELLATION_ADMISSION_RETRY_MS);
    }

    expect(api.cancelRequest).toHaveBeenCalledTimes(3);
    for (const [, command] of api.cancelRequest.mock.calls) {
      expect(command).toEqual({cancellationRequestId: 'cancel-1'});
    }

    cancelResponses[2].next(cancellationResponse());
    expect(states.at(-1)).toMatchObject({phase: 'acknowledged', acknowledged: true});
    vi.advanceTimersByTime(CANCELLATION_ACK_TIMEOUT_MS);
    expect(api.cancelRequest).toHaveBeenCalledTimes(3);
  });

  it('keeps an exact-identity refresh 404 definitive without fallback', () => {
    service.start({
      requestId: 'request-1', conversationId: 'conversation-1', generation: 7
    }, callbacks);
    cancelResponses[0].error(new HttpErrorResponse({
      status: 404,
      error: {}
    }));

    vi.advanceTimersByTime(CANCELLATION_ACK_TIMEOUT_MS + 1);
    expect(states.at(-1)?.delayReason).toBe('rejected');
    expect(transport.publish).not.toHaveBeenCalled();
  });

  it('keeps a durable ACK monotonic when a retry receives a weaker 503 outcome', () => {
    service.start({requestId: 'request-1'}, callbacks);
    cancelResponses[0].next(cancellationResponse());
    service.retry();

    cancelResponses[1].error(new HttpErrorResponse({
      status: 503,
      error: cancellationResponse({
        disposition: 'ACKNOWLEDGED',
        status: 'CANCELLED',
        acknowledged: false,
        terminal: true,
        lifecycleEventSequence: 0
      })
    }));

    expect(states.at(-1)).toMatchObject({phase: 'acknowledged', acknowledged: true});
    expect(terminals).toEqual([]);
    expect(transport.publish).not.toHaveBeenCalled();
  });

  it('does not let a late pre-admission 404 regress a durable ACK', () => {
    service.start({requestId: 'request-1'}, callbacks);
    service.retry();
    cancelResponses[1].next(cancellationResponse());

    cancelResponses[0].error(new HttpErrorResponse({
      status: 404,
      error: {}
    }));

    expect(states.at(-1)).toMatchObject({phase: 'acknowledged', acknowledged: true});
    expect(service.isActive('request-1')).toBe(true);
  });

  it('does not promote a 503 process-local terminal hint to durable terminal', () => {
    service.start({requestId: 'request-1'}, callbacks);
    const response = cancellationResponse({
      disposition: 'ACKNOWLEDGED',
      status: 'CANCELLED',
      acknowledged: false,
      terminal: true,
      lifecycleEventSequence: 0
    });

    cancelResponses[0].error(new HttpErrorResponse({
      status: 503,
      statusText: 'Service Unavailable',
      error: response
    }));

    expect(terminals).toEqual([]);
    expect(service.isActive('request-1')).toBe(true);
    expect(states.at(-1)).toMatchObject({
      phase: 'delayed', delayReason: 'transport_unknown'
    });
    expect(transport.publish).toHaveBeenCalledWith('/app/ai/chat/cancel', {
      requestId: 'request-1',
      cancellationRequestId: 'cancel-1'
    });
  });

  it.each(['STALE_GENERATION'] as const)(
    'gives %s rejection precedence over a terminal-looking snapshot', disposition => {
      service.start({
        requestId: 'request-1',
        conversationId: 'conversation-1',
        generation: 7
      }, callbacks);

      cancelResponses[0].next(cancellationResponse({
        disposition,
        status: 'FAILED',
        acknowledged: false,
        terminal: true,
        lifecycleEventSequence: 9
      }));

      expect(terminals).toEqual([]);
      expect(service.isActive('request-1')).toBe(true);
      expect(states.at(-1)).toMatchObject({
        phase: 'delayed', delayReason: 'rejected'
      });
    }
  );

  it('accepts WebSocket ACK and terminal events without phase regression', () => {
    service.start({requestId: 'request-1'}, callbacks);

    expect(service.handleSocketEvent({
      requestId: 'request-1',
      conversationId: 'conversation-1',
      type: 'system',
      subtype: 'cancellation_acknowledged',
      sequence: 1,
      metadata: {
        cancellationRequestId: 'cancel-1',
        effectiveCancellationRequestId: 'cancel-1',
        generation: 7,
        lifecycleEventSequence: 4,
        status: 'CANCELLING',
        acknowledged: true,
        terminal: false
      }
    })).toBe(true);
    expect(states.at(-1)).toMatchObject({
      phase: 'acknowledged', lifecycleEventSequence: 4
    });

    service.handleSocketEvent({
      requestId: 'request-1',
      type: 'system',
      subtype: 'cancellation_acknowledged',
      sequence: 1,
      metadata: {cancellationRequestId: 'cancel-1', lifecycleEventSequence: 3}
    });
    expect(states.at(-1)?.lifecycleEventSequence).toBe(4);
    service.handleSocketEvent({
      requestId: 'request-1',
      type: 'system',
      subtype: 'cancellation_acknowledged',
      sequence: 1,
      metadata: {cancellationRequestId: 'cancel-1'}
    });
    expect(states.at(-1)?.phase).toBe('acknowledged');
    service.handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'cancellation_acknowledged', sequence: 1,
      metadata: {
        cancellationRequestId: 'cancel-1', generation: 0,
        lifecycleEventSequence: 6
      }
    });
    service.handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'cancellation_rejected', sequence: 1,
      metadata: {
        cancellationRequestId: 'cancel-1', generation: 8,
        lifecycleEventSequence: 7
      }
    });
    expect(states.at(-1)).toMatchObject({phase: 'acknowledged', acknowledged: true});
    service.handleSocketEvent({
      requestId: 'request-1', type: 'system', subtype: 'cancellation_delayed',
      metadata: {cancellationRequestId: 'cancel-1'}
    });
    service.handleSocketEvent({
      requestId: 'request-1', type: 'system', subtype: 'cancellation_rejected',
      metadata: {cancellationRequestId: 'cancel-1'}
    });
    expect(states.at(-1)?.phase).toBe('acknowledged');

    service.handleSocketEvent({
      requestId: 'request-1',
      type: 'system',
      subtype: 'cancelled'
    });
    expect(terminals).toEqual(['CANCELLED']);
  });

  it('distinguishes consumed stale cancellation events from admitted updates', () => {
    service.start({
      requestId: 'request-1', conversationId: 'conversation-1', generation: 7
    }, callbacks);

    expect(service.handleSocketEventDisposition({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'cancellation_acknowledged', sequence: 1,
      metadata: {
        cancellationRequestId: 'another-tabs-command', generation: 7,
        lifecycleEventSequence: 4, status: 'CANCELLING'
      }
    })).toBe('ignored');
    expect(service.handleSocketEventDisposition({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'cancellation_acknowledged', sequence: 1,
      metadata: {
        cancellationRequestId: 'cancel-1', generation: 7,
        lifecycleEventSequence: 4, status: 'CANCELLING'
      }
    })).toBe('admitted');
  });

  it('accepts a shared terminal despite another command ID and an older event sequence', () => {
    service.start({requestId: 'request-1'}, callbacks);
    service.handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
      subtype: 'cancellation_acknowledged', sequence: 5,
      metadata: {
        cancellationRequestId: 'cancel-1', generation: 7,
        lifecycleEventSequence: 5, status: 'CANCELLING'
      }
    });

    service.handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
      subtype: 'cancellation_current_status',
      metadata: {
        cancellationRequestId: 'another-tabs-command', generation: 7,
        lifecycleEventSequence: 2, status: 'CANCELLED', terminal: true
      }
    });

    expect(terminals).toEqual(['CANCELLED']);
    expect(service.isActive()).toBe(false);
  });

  it('does not treat an unsequenced response as a durable acknowledgement', () => {
    service.start({requestId: 'request-1'}, callbacks);

    cancelResponses[0].next(cancellationResponse({lifecycleEventSequence: 0}));

    expect(states.at(-1)).toMatchObject({
      phase: 'delayed', acknowledged: false, delayReason: 'transport_unknown'
    });
    service.handleSocketEvent({
      requestId: 'request-1',
      conversationId: 'conversation-1',
      type: 'system',
      subtype: 'cancellation_acknowledged',
      metadata: {
        cancellationRequestId: 'cancel-1',
        generation: 7,
        lifecycleEventSequence: 4,
        status: 'CANCELLING'
      }
    });
    expect(states.at(-1)).toMatchObject({phase: 'delayed', acknowledged: false});
  });

  it('handles current-status and reconciliation control replies explicitly', () => {
    service.start({requestId: 'request-1'}, callbacks);
    service.handleSocketEvent({
      requestId: 'request-1',
      type: 'system',
      subtype: 'cancellation_current_status',
      metadata: {
        cancellationRequestId: 'cancel-1',
        status: 'COMPLETED',
        terminal: true,
        generation: 7,
        lifecycleEventSequence: 5
      },
      conversationId: 'conversation-1'
    });
    expect(terminals).toEqual(['COMPLETED']);

    terminals.length = 0;
    service.start({requestId: 'request-2'}, callbacks);
    service.handleSocketEvent({
      requestId: 'request-2',
      type: 'system',
      subtype: 'reconciliation_required',
      metadata: {
        cancellationRequestId: 'cancel-1',
        status: 'UNKNOWN_RECONCILIATION_REQUIRED',
        terminal: true,
        generation: 8,
        lifecycleEventSequence: 6
      },
      conversationId: 'conversation-2'
    });
    expect(terminals).toEqual(['UNKNOWN_RECONCILIATION_REQUIRED']);
    expect(service.isActive()).toBe(false);
  });

  it('never sends a zero generation in a cancellation command', () => {
    service.start({
      requestId: 'request-1', conversationId: 'conversation-1', generation: 0
    }, callbacks);

    expect(api.cancelRequest).toHaveBeenCalledWith('request-1', {
      cancellationRequestId: 'cancel-1'
    });
  });

  it('force-safe-stop reads exact status and uses the original ID on the alternate transport', () => {
    service.start({
      requestId: 'request-1', conversationId: 'conversation-1', generation: 7
    }, callbacks);

    service.forceSafeStop();

    expect(states.at(-1)?.delayReason).toBe('force_safe_stop');
    expect(transport.publish).toHaveBeenCalledWith('/app/ai/chat/cancel', {
      requestId: 'request-1',
      cancellationRequestId: 'cancel-1',
      conversationId: 'conversation-1',
      expectedGeneration: 7
    });
    expect(api.getRequestStatus).toHaveBeenCalledWith('request-1', 'conversation-1', 7);
  });

  it('completes twenty repeated stops without changing an ID during retry', () => {
    let id = 0;
    (service as any).createCancellationRequestId.mockImplementation(() => `cancel-${++id}`);
    const commandIds = new Set<string>();

    for (let iteration = 1; iteration <= 20; iteration++) {
      const iterationTerminals: AiExecutionStatus[] = [];
      const iterationCallbacks: AiChatCancellationCallbacks = {
        onStateChange: vi.fn(),
        onIdentityChange: vi.fn(),
        onTerminal: status => iterationTerminals.push(status)
      };
      const command = service.start({requestId: `request-${iteration}`}, iterationCallbacks);
      commandIds.add(command.cancellationRequestId);
      service.retry();
      const retryCommand = api.cancelRequest.mock.calls.at(-1)?.[1] as AiCancellationCommand;
      expect(retryCommand.cancellationRequestId).toBe(command.cancellationRequestId);
      service.handleSocketEvent({
        requestId: `request-${iteration}`,
        type: 'system',
        subtype: 'cancelled'
      });
      expect(iterationTerminals).toEqual(['CANCELLED']);
      expect(service.isActive()).toBe(false);
    }

    expect(commandIds.size).toBe(20);
  });

});
