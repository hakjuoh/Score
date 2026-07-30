import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {TimeoutError} from 'rxjs';
import {AiChatApiService} from './ai-chat-api.service';
import {REQUEST_STATUS_TIMEOUT_MS} from './ai-chat-panel.constants';
import {AiChatModelInfo, AiConversationModelResponse} from './ai-chat-panel.model';
import {HANDLE_HTTP_ERROR_LOCALLY} from '../../../authentication/auth.service';

describe('AiChatApiService cancellation contract', () => {
  let service: AiChatApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        AiChatApiService,
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    service = TestBed.inject(AiChatApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('posts one explicit cancellation command with the exact refresh identity', () => {
    service.cancelRequest('request/one', {
      cancellationRequestId: 'cancel-1',
      conversationId: 'conversation-1',
      expectedGeneration: 7
    }).subscribe();

    const request = httpTesting.expectOne('/api/ai/chat/request%2Fone/cancel');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      cancellationRequestId: 'cancel-1',
      conversationId: 'conversation-1',
      expectedGeneration: 7
    });
    request.flush({
      requestId: 'request/one',
      conversationId: 'conversation-1',
      generation: 7,
      cancellationRequestId: 'cancel-1',
      effectiveCancellationRequestId: 'cancel-1',
      disposition: 'ACKNOWLEDGED',
      status: 'CANCELLING',
      acknowledged: true,
      terminal: false,
      lifecycleEventSequence: 3
    });
  });

  it('queries status with an encoded exact conversation and generation fence', () => {
    service.getRequestStatus('request/one', 'conversation/one', 1).subscribe();

    const request = httpTesting.expectOne(candidate =>
      candidate.url === '/api/ai/chat/request%2Fone/status'
      && candidate.params.get('conversationId') === 'conversation/one'
      && candidate.params.get('expectedGeneration') === '1'
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.context.get(HANDLE_HTTP_ERROR_LOCALLY)).toBe(true);
    request.flush({
      conversationId: 'conversation/one',
      requestId: 'request/one',
      generation: 1,
      status: 'CANCELLED',
      deadline: '2026-07-14T13:05:00Z',
      retryCount: 0,
      createdAt: '2026-07-14T13:00:00Z',
      updatedAt: '2026-07-14T13:00:02Z',
      lastEventSequence: 4,
      version: 2
    });
  });

  it('fails the status poll when the backend never answers it', () => {
    vi.useFakeTimers();
    try {
      let failure: unknown;
      service.getRequestStatus('request-1', 'conversation-1', 7)
        .subscribe({error: error => failure = error});
      httpTesting.expectOne(candidate => candidate.url === '/api/ai/chat/request-1/status');

      vi.advanceTimersByTime(REQUEST_STATUS_TIMEOUT_MS);

      expect(failure).toBeInstanceOf(TimeoutError);
    } finally {
      vi.useRealTimers();
    }
  });

  it('rejects partial or non-positive refresh identities before transport', () => {
    expect(() => service.cancelRequest('request-1', {
      cancellationRequestId: 'cancel-1',
      conversationId: 'conversation-1'
    })).toThrowError(/supplied together/);
    expect(() => service.getRequestStatus('request-1', 'conversation-1', 0))
      .toThrowError(/positive expectedGeneration/);
    httpTesting.expectNone('/api/ai/chat/request-1/cancel');
    httpTesting.expectNone('/api/ai/chat/request-1/status');
  });

  it('loads an owner-scoped conversation for completed payload recovery', () => {
    service.getConversation('conversation/one').subscribe();

    const request = httpTesting.expectOne('/api/ai/chat/conversations/conversation%2Fone');
    expect(request.request.method).toBe('GET');
    request.flush({
      conversationId: 'conversation/one',
      title: 'Recovered',
      messages: [{index: 1, role: 'assistant', content: 'Recovered answer.'}],
      contextMessages: []
    });
  });

  it('loads configured assistant models', () => {
    service.getAvailableModels().subscribe();

    const request = httpTesting.expectOne('/api/ai/chat/models');
    expect(request.request.method).toBe('GET');
    request.flush([
      {name: 'claude-fable-5', displayName: 'Claude Fable 5', description: 'Claude model.',
        provider: 'azure-foundry', defaultModel: true, defaultReasoningEffort: 'high', reasoningEfforts: [
          {name: 'low', displayName: 'Low', description: 'Fast responses.'},
          {name: 'high', displayName: 'High', description: 'Greater reasoning.'}
        ]},
      {name: 'gpt-5_6-sol', displayName: 'GPT-5.6 SOL', description: 'GPT model.',
        provider: 'azure-openai', defaultModel: false, defaultReasoningEffort: 'medium', reasoningEfforts: [
          {name: 'medium', displayName: 'Medium', description: 'Balanced reasoning.'},
          {name: 'high', displayName: 'High', description: 'Greater reasoning.'}
        ]}
    ]);
  });

  it('normalizes legacy reasoning effort names for display', () => {
    let models: AiChatModelInfo[] = [];
    service.getAvailableModels().subscribe(response => models = response);

    const request = httpTesting.expectOne('/api/ai/chat/models');
    request.flush([{
      name: 'gpt-5_6-sol', displayName: 'GPT-5.6 SOL',
      provider: 'azure-openai', defaultModel: true, defaultReasoningEffort: 'none',
      reasoningEfforts: ['none', 'low', 'medium', 'high', 'xhigh']
    }]);

    expect(models[0].reasoningEfforts.map(effort => effort.displayName))
      .toEqual(['Disabled', 'Low', 'Medium', 'High', 'Extra High']);
    expect(models[0].defaultReasoningEffort).toBe('disabled');
    expect(models[0].reasoningEfforts[0].description).toContain('disabled');
    expect(models[0].reasoningEfforts[1].description).toContain('lighter reasoning');
    expect(models[0].description).toContain('Azure OpenAI');
  });

  it('updates the model for an owner-scoped conversation', () => {
    let response: AiConversationModelResponse | undefined;
    service.updateConversationModel('conversation/one', 'gpt-5_6-sol', 'high')
      .subscribe(value => response = value);

    const request = httpTesting.expectOne(
      '/api/ai/chat/conversations/conversation%2Fone/model'
    );
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({
      modelName: 'gpt-5_6-sol', reasoningEffort: 'high'
    });
    request.flush({
      conversationId: 'conversation/one', modelName: 'gpt-5_6-sol',
      reasoningEffort: 'high'
    });
    expect(response?.reasoningEffort).toBe('high');
  });

  it('normalizes a legacy none name in structured reasoning effort data', () => {
    let models: AiChatModelInfo[] = [];
    service.getAvailableModels().subscribe(response => models = response);

    const request = httpTesting.expectOne('/api/ai/chat/models');
    request.flush([{
      name: 'claude-sonnet-5', displayName: 'Claude Sonnet 5',
      provider: 'azure-foundry', defaultModel: true, defaultReasoningEffort: 'none',
      reasoningEfforts: [{name: 'none', displayName: 'Legacy None', description: 'Legacy setting.'}]
    }]);

    expect(models[0].defaultReasoningEffort).toBe('disabled');
    expect(models[0].reasoningEfforts).toEqual([{
      name: 'disabled', displayName: 'Disabled', description: 'Legacy setting.'
    }]);
  });

  it('normalizes a legacy none reasoning effort in model updates', () => {
    let response: AiConversationModelResponse | undefined;
    service.updateConversationModel('conversation/one', 'claude-sonnet-5', 'disabled')
      .subscribe(value => response = value);

    const request = httpTesting.expectOne(
      '/api/ai/chat/conversations/conversation%2Fone/model'
    );
    request.flush({
      conversationId: 'conversation/one', modelName: 'claude-sonnet-5',
      reasoningEffort: 'none'
    });

    expect(response?.reasoningEffort).toBe('disabled');
  });

  it.each(['APPROVE', 'DENY'] as const)(
    'posts one %s change decision to the encoded owner endpoint', decision => {
      service.decideChangeConfirmation(
        'conversation/one', 'confirmation/one', decision
      ).subscribe();

      const request = httpTesting.expectOne(
        '/api/ai/chat/conversations/conversation%2Fone'
        + '/change-confirmations/confirmation%2Fone/decision'
      );
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({decision});
      request.flush({
        confirmationRequestId: 'confirmation/one',
        conversationId: 'conversation/one',
        status: decision === 'APPROVE' ? 'APPROVED' : 'DENIED',
        disposition: decision === 'APPROVE' ? 'APPROVED' : 'DENIED'
      });
    }
  );

  it('binds a revised approval to the user revision prompt', () => {
    service.decideChangeConfirmation(
      'conversation/one', 'confirmation/one', 'APPROVE',
      'Use the name Revised Business Context'
    ).subscribe();

    const request = httpTesting.expectOne(
      '/api/ai/chat/conversations/conversation%2Fone'
      + '/change-confirmations/confirmation%2Fone/decision'
    );
    expect(request.request.body).toEqual({
      decision: 'APPROVE', revisionPrompt: 'Use the name Revised Business Context'
    });
    request.flush({
      confirmationRequestId: 'confirmation/one',
      conversationId: 'conversation/one',
      status: 'APPROVED', disposition: 'APPROVED'
    });
  });

  it('rejects a blank change decision identity before transport', () => {
    expect(() => service.decideChangeConfirmation(
      '', 'confirmation-1', 'APPROVE'
    )).toThrowError(/conversationId/);
    expect(() => service.decideChangeConfirmation(
      'conversation-1', ' ', 'DENY'
    )).toThrowError(/confirmationRequestId/);
    httpTesting.expectNone(request => request.url.includes('change-confirmations'));
  });
});
