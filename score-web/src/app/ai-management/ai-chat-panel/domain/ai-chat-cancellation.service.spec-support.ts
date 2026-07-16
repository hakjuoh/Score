import {TestBed} from '@angular/core/testing';
import {Subject} from 'rxjs';
import {AiChatApiService} from './ai-chat-api.service';
import {
  AiChatCancellationCallbacks,
  AiChatCancellationService
} from './ai-chat-cancellation.service';
import {
  AiCancellationResponse,
  AiCancellationUiState,
  AiExecutionStatus,
  AiPublicExecutionRequestStatus
} from './ai-chat-panel.model';
import {AiChatTransportService} from './ai-chat-transport.service';

export let service: AiChatCancellationService;
export let cancelResponses: Subject<AiCancellationResponse>[];
export let statusResponses: Subject<AiPublicExecutionRequestStatus>[];
export let api: {
  cancelRequest: ReturnType<typeof vi.fn>;
  getRequestStatus: ReturnType<typeof vi.fn>;
};
export let transport: {publish: ReturnType<typeof vi.fn>};
export let states: AiCancellationUiState[];
export let terminals: AiExecutionStatus[];
export let callbacks: AiChatCancellationCallbacks;

export function setupCancellationServiceSpec(): void {
  vi.useFakeTimers();
  cancelResponses = [];
  statusResponses = [];
  api = {
    cancelRequest: vi.fn(() => {
      const response = new Subject<AiCancellationResponse>();
      cancelResponses.push(response);
      return response;
    }),
    getRequestStatus: vi.fn(() => {
      const response = new Subject<AiPublicExecutionRequestStatus>();
      statusResponses.push(response);
      return response;
    })
  };
  transport = {publish: vi.fn()};
  TestBed.configureTestingModule({
    providers: [
      AiChatCancellationService,
      {provide: AiChatApiService, useValue: api},
      {provide: AiChatTransportService, useValue: transport}
    ]
  });
  service = TestBed.inject(AiChatCancellationService);
  vi.spyOn(service as any, 'createCancellationRequestId').mockReturnValue('cancel-1');
  states = [];
  terminals = [];
  callbacks = {
    onStateChange: state => states.push({...state}),
    onIdentityChange: vi.fn(),
    onTerminal: status => terminals.push(status)
  };
}

export function teardownCancellationServiceSpec(): void {
  service.reset();
  vi.useRealTimers();
}

export function cancellationResponse(
  overrides: Partial<AiCancellationResponse> = {}
): AiCancellationResponse {
  return {
    requestId: 'request-1',
    conversationId: 'conversation-1',
    generation: 7,
    cancellationRequestId: 'cancel-1',
    effectiveCancellationRequestId: 'cancel-1',
    disposition: 'ACKNOWLEDGED',
    status: 'CANCELLING',
    acknowledged: true,
    terminal: false,
    lifecycleEventSequence: 3,
    ...overrides
  };
}

export function publicStatus(status: AiExecutionStatus): AiPublicExecutionRequestStatus {
  return {
    conversationId: 'conversation-1',
    requestId: 'request-1',
    generation: 7,
    agentName: 'score',
    status,
    deadline: '2026-07-14T13:05:00Z',
    retryCount: 0,
    createdAt: '2026-07-14T13:00:00Z',
    updatedAt: '2026-07-14T13:00:05Z',
    terminalAt: status === 'CANCELLED' ? '2026-07-14T13:00:05Z' : undefined,
    cancellationRequestId: 'cancel-1',
    cancellationAcknowledgedAt: '2026-07-14T13:00:01Z',
    lastEventSequence: 4,
    version: 3
  };
}
