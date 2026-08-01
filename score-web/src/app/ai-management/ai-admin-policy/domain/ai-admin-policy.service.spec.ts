import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {AiAdminPolicyService} from './ai-admin-policy.service';

describe('AiAdminPolicyService REST contract', () => {
  let service: AiAdminPolicyService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({providers: [
      AiAdminPolicyService, provideHttpClient(), provideHttpClientTesting()
    ]});
    service = TestBed.inject(AiAdminPolicyService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads the administrator policy user list', () => {
    service.users().subscribe();
    const request = http.expectOne('/api/admin/ai/users');
    expect(request.request.method).toBe('GET');
    request.flush([]);
  });

  it('resets a policy with its optimistic version', () => {
    service.reset('17', 3).subscribe();
    const request = http.expectOne(req => req.url === '/api/admin/ai/users/17/policy');
    expect(request.request.method).toBe('DELETE');
    expect(request.request.params.get('expectedVersion')).toBe('3');
    expect(request.request.params.has('reason')).toBe(false);
    request.flush(null);
  });

  it('updates provider settings and API key through one write endpoint', () => {
    const update = {expectedVersion: 4, providerName: 'OpenAI', providerType: 'openai',
      baseUrl: 'https://api.openai.com', messagesUrl: null, anthropicVersion: null,
      apiVersion: null, enabled: true, apiKey: ''};
    service.updateProvider(2, update).subscribe();
    const request = http.expectOne('/api/admin/ai/providers/2');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual(update);
    request.flush({});
  });

  it('loads a full-length mask and reveals a provider API key through separate endpoints', () => {
    service.maskedProviderApiKey(2).subscribe();
    const maskedRequest = http.expectOne('/api/admin/ai/providers/2/api-key');
    expect(maskedRequest.request.method).toBe('GET');
    maskedRequest.flush({value: '••••••••••', revealed: false});

    service.revealProviderApiKey(2).subscribe();
    const revealRequest = http.expectOne('/api/admin/ai/providers/2/api-key/reveal');
    expect(revealRequest.request.method).toBe('POST');
    expect(revealRequest.request.body).toEqual({});
    revealRequest.flush({value: 'secret-key', revealed: true});
  });

  it('tests draft provider settings without using the write endpoint', () => {
    const update = {expectedVersion: 4, providerName: 'Anthropic', providerType: 'anthropic',
      baseUrl: 'https://api.anthropic.com', messagesUrl: null,
      anthropicVersion: '2023-06-01', apiVersion: null, enabled: true};
    service.testProviderConnection(2, update).subscribe();

    const request = http.expectOne('/api/admin/ai/providers/2/connection-tests');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(update);
    request.flush({successful: true, message: 'Connection successful.', statusCode: 200});
  });

  it('updates a model through the catalog endpoint', () => {
    const update = {expectedVersion: 2, providerId: 1, modelKey: 'model-key',
      enabled: true, defaultModel: false, sortOrder: 1, maxTokens: 4096,
      contextWindow: 128000, outputReserveTokens: 4096,
      autoCompactThresholdTokens: 100000, emergencyHeadroomTokens: 4096,
      toolOutputTokenLimit: 16000, providerCompactionEnabled: false,
      temperature: null, thinkingBudgetTokens: null, adaptiveThinking: false,
      outputEffort: null, cacheStrategy: null, reasoningModelSupported: true,
      outputEffortSupported: false, verbositySupported: true,
      temperatureSupported: false, thinkingModes: [], defaultThinking: null,
      reasoningEfforts: [{name: 'medium', defaultEffort: true, sortOrder: 0}]};
    service.updateModel(8, update).subscribe();
    const request = http.expectOne('/api/admin/ai/models/8');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual(update);
    request.flush({});
  });

  it('loads backend model profiles for the selected provider', () => {
    service.modelProfiles(2).subscribe();

    const request = http.expectOne('/api/admin/ai/providers/2/model-profiles');
    expect(request.request.method).toBe('GET');
    request.flush([]);
  });

  it('loads usage and posts a quota adjustment without a reason', () => {
    service.usage('17').subscribe();
    http.expectOne('/api/admin/ai/users/17/usage').flush({});
    service.adjustQuota('17', -1000).subscribe();
    const request = http.expectOne('/api/admin/ai/users/17/quota-adjustments');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({deltaTokens: -1000});
    request.flush({});
  });

  it('cancels all active requests for an administered user', () => {
    service.cancelActiveRequests('17').subscribe();
    const request = http.expectOne('/api/admin/ai/users/17/cancel-active-requests');
    expect(request.request.method).toBe('POST');
    request.flush({cancelledRequests: 2});
  });
});
