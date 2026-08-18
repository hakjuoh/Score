import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {AiAdminPolicyService} from './ai-admin-policy.service';
import {AiModelListRequest, AiPolicyUserListRequest, AiProviderListRequest} from './ai-admin-policy';
import {PageRequest} from '../../../basis/basis';

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

  it('searches provider, model, and policy lists with pagination and detailed filters', () => {
    const providers = new AiProviderListRequest();
    providers.filters = {...providers.filters, name: 'provider', type: 'anthropic',
      endpoint: 'example.test', enabled: [true], updaterLoginIdList: ['admin']};
    service.searchProviders(providers).subscribe();
    let request = http.expectOne(req => req.url === '/api/admin/ai/providers/search');
    expect(request.request.params.get('name')).toBe('provider');
    expect(request.request.params.get('type')).toBe('anthropic');
    expect(request.request.params.get('enabled')).toBe('true');
    expect(request.request.params.get('updaterLoginIdList')).toBe('admin');
    expect(request.request.params.get('pageSize')).toBe('10');
    request.flush({list: [], page: 0, size: 10, length: 0});

    const models = new AiModelListRequest();
    models.filters = {...models.filters, provider: 'OpenAI', enabled: [true],
      defaultModel: [false], lightweightModel: [true], defaultEffort: 'medium', effort: 'high'};
    service.searchModels(models).subscribe();
    request = http.expectOne(req => req.url === '/api/admin/ai/models/search');
    expect(request.request.params.get('provider')).toBe('OpenAI');
    expect(request.request.params.get('defaultModel')).toBe('false');
    expect(request.request.params.get('lightweightModel')).toBe('true');
    expect(request.request.params.get('effort')).toBe('high');
    request.flush({list: [], page: 0, size: 10, length: 0});

    const users = new AiPolicyUserListRequest();
    const before = new Date(2026, 6, 31);
    users.filters = {...users.filters, loginId: 'alice', organization: 'OAGi',
      model: 'GPT-5', multiAgentEnabled: [true], quotaTokens: 1000, activeRequests: 1,
      updatedBefore: before};
    service.searchUsers(users).subscribe();
    request = http.expectOne(req => req.url === '/api/admin/ai/users/search');
    expect(request.request.params.get('loginId')).toBe('alice');
    expect(request.request.params.get('model')).toBe('GPT-5');
    expect(request.request.params.get('quotaTokens')).toBe('1000');
    expect(request.request.params.get('activeRequests')).toBe('1');
    const expectedExclusiveBefore = new Date(before);
    expectedExclusiveBefore.setDate(expectedExclusiveBefore.getDate() + 1);
    expect(request.request.params.get('updatedBefore')).toBe(expectedExclusiveBefore.toISOString());
    request.flush({list: [], page: 0, size: 10, length: 0});
  });

  it('omits a boolean filter when both values are selected', () => {
    const providers = new AiProviderListRequest();
    providers.filters.enabled = [true, false];

    service.searchProviders(providers).subscribe();

    const request = http.expectOne(req => req.url === '/api/admin/ai/providers/search');
    expect(request.request.params.has('enabled')).toBe(false);
    expect(request.request.params.get('orderBy')).toBe('-updatedOn');
    request.flush({list: [], page: 0, size: 10, length: 0});
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
    const update = {providerName: 'OpenAI', providerType: 'openai',
      baseUrl: 'https://api.openai.com', messagesUrl: null,
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
    const update = {providerName: 'Anthropic', providerType: 'anthropic',
      baseUrl: 'https://api.anthropic.com', messagesUrl: null,
      apiVersion: '2023-06-01', enabled: true};
    service.testProviderConnection(2, update).subscribe();

    const request = http.expectOne('/api/admin/ai/providers/2/connection-tests');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(update);
    request.flush({successful: true, message: 'Connection successful.', statusCode: 200});
  });

  it('updates a model through the catalog endpoint', () => {
    const update = {providerId: 1, modelKey: 'model-key',
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
    const usageRequest = http.expectOne(req => req.url === '/api/admin/ai/users/17/usage');
    expect(usageRequest.request.params.get('orderBy')).toBe('-time');
    expect(usageRequest.request.params.get('pageSize')).toBe('10');
    usageRequest.flush({});
    service.adjustQuota('17', -1000).subscribe();
    const request = http.expectOne('/api/admin/ai/users/17/quota-adjustments');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({deltaTokens: -1000});
    request.flush({});
  });

  it('loads a paginated usage period with an inclusive end date', () => {
    const start = new Date(2026, 6, 1);
    const end = new Date(2026, 6, 31);
    service.usage('17', new PageRequest('charged', 'asc', 2, 25), start, end).subscribe();

    const request = http.expectOne(req => req.url === '/api/admin/ai/users/17/usage');
    expect(request.request.params.get('orderBy')).toBe('+charged');
    expect(request.request.params.get('pageIndex')).toBe('2');
    expect(request.request.params.get('pageSize')).toBe('25');
    expect(request.request.params.get('start')).toBe(start.toISOString());
    const exclusiveEnd = new Date(end);
    exclusiveEnd.setDate(exclusiveEnd.getDate() + 1);
    expect(request.request.params.get('end')).toBe(exclusiveEnd.toISOString());
    request.flush({});
  });

  it('cancels all active requests for an administered user', () => {
    service.cancelActiveRequests('17').subscribe();
    const request = http.expectOne('/api/admin/ai/users/17/cancel-active-requests');
    expect(request.request.method).toBe('POST');
    request.flush({cancelledRequests: 2});
  });
});
