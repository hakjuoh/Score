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

  it('resets a policy with its optimistic version and reason', () => {
    service.reset('17', 3, 'Policy reset for test').subscribe();
    const request = http.expectOne(req => req.url === '/api/admin/ai/users/17/policy');
    expect(request.request.method).toBe('DELETE');
    expect(request.request.params.get('expectedVersion')).toBe('3');
    expect(request.request.params.get('reason')).toBe('Policy reset for test');
    request.flush(null);
  });

  it('rotates a provider key only through the write endpoint', () => {
    service.rotateProviderKey(2, 4, 'new-secret', 'Rotate key for test').subscribe();
    const request = http.expectOne('/api/admin/ai/providers/2/api-key');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({
      expectedVersion: 4, apiKey: 'new-secret', reason: 'Rotate key for test'
    });
    request.flush({});
  });

  it('removes a provider key without putting secret material in the URL', () => {
    service.removeProviderKey(2, 5, 'Remove key for test').subscribe();
    const request = http.expectOne(req => req.url === '/api/admin/ai/providers/2/api-key');
    expect(request.request.method).toBe('DELETE');
    expect(request.request.params.keys().sort()).toEqual(['expectedVersion', 'reason']);
    request.flush({});
  });

  it('updates a model through the catalog endpoint', () => {
    const update = {expectedVersion: 2, providerId: 1, modelKey: 'model-key',
      providerModelName: 'deployment', displayName: 'Model', description: '', enabled: true,
      defaultModel: false, sortOrder: 1, maxTokens: 4096, contextWindow: 128000,
      outputReserveTokens: null, autoCompactThresholdTokens: null,
      emergencyHeadroomTokens: 4096, toolOutputTokenLimit: 32000,
      providerCompactionEnabled: true, temperature: null, thinkingBudgetTokens: null,
      adaptiveThinking: false, outputEffort: null, cacheStrategy: null,
      reasoningModelSupported: null, outputEffortSupported: null,
      verbositySupported: null, temperatureSupported: null, thinkingModes: [],
      defaultThinking: null, reasoningEfforts: [], reason: 'Update model for test'};
    service.updateModel(8, update).subscribe();
    const request = http.expectOne('/api/admin/ai/models/8');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual(update);
    request.flush({});
  });

  it('loads usage and posts an audited quota adjustment', () => {
    service.usage('17').subscribe();
    http.expectOne('/api/admin/ai/users/17/usage').flush({});
    service.adjustQuota('17', -1000, 'Credit quota for test').subscribe();
    const request = http.expectOne('/api/admin/ai/users/17/quota-adjustments');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({deltaTokens: -1000, reason: 'Credit quota for test'});
    request.flush({});
  });

  it('cancels all active requests for an administered user', () => {
    service.cancelActiveRequests('17').subscribe();
    const request = http.expectOne('/api/admin/ai/users/17/cancel-active-requests');
    expect(request.request.method).toBe('POST');
    request.flush({cancelledRequests: 2});
  });
});
