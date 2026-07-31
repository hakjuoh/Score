import {TestBed} from '@angular/core/testing';
import {ActivatedRoute, Router, convertToParamMap} from '@angular/router';
import {MatSnackBar} from '@angular/material/snack-bar';
import {Subject, of, throwError} from 'rxjs';
import {AiPolicyUserDetailComponent} from './ai-policy-user-detail.component';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiAdminModel, AiPolicyView} from './domain/ai-admin-policy';

describe('AiPolicyUserDetailComponent', () => {
  let component: AiPolicyUserDetailComponent;
  let service: any;
  let snackBar: any;

  const model = (modelKey: string, enabled: boolean): AiAdminModel => ({
    aiModelId: modelKey === 'active' ? 1 : 2, modelKey, displayName: modelKey,
    provider: 'test', providerId: 1, providerModelName: modelKey, description: '', enabled,
    defaultModel: modelKey === 'active', sortOrder: 0, maxTokens: 4096, contextWindow: 128000,
    outputReserveTokens: null, autoCompactThresholdTokens: null, emergencyHeadroomTokens: 4096,
    toolOutputTokenLimit: 32000, providerCompactionEnabled: true, temperature: null,
    thinkingBudgetTokens: null, adaptiveThinking: false, outputEffort: null, cacheStrategy: null,
    reasoningModelSupported: true, outputEffortSupported: false, verbositySupported: false,
    temperatureSupported: true, thinkingModes: [], defaultThinking: null, catalogVersion: 1,
    reasoningEfforts: [{name: 'high', displayName: 'High', description: '', defaultEffort: true, sortOrder: 0}]
  });

  const policy = (): AiPolicyView => ({
    userId: '17', inherited: false, policyVersion: 3, enabled: true,
    modelAccessMode: 'ALLOW_LIST', effectiveDefaultModelKey: 'active',
    effectiveAllowedModelKeys: ['active'], allowedReasoningEfforts: {active: ['high']},
    multiAgentEnabled: true, maxAgentsPerRequest: 4, maxActiveRequests: 8,
    hasActiveRequests: false, maxOutputTokensPerCall: null, maxTotalTokensPerRequest: null,
    quota: {period: null, limitTokens: null, consumedTokens: 0, reservedTokens: 0,
      remainingTokens: null, periodStart: null, periodEnd: null}
  });

  beforeEach(() => {
    service = {
      models: vi.fn(() => of([model('active', true), model('disabled', false)])),
      policy: vi.fn(() => of(policy())), usage: vi.fn(() => of({quota: policy().quota,
        activeRequests: 0, recentCalls: []})), save: vi.fn(() => of(policy())),
      reset: vi.fn(), cancelActiveRequests: vi.fn(), adjustQuota: vi.fn()
    };
    snackBar = {open: vi.fn(() => ({onAction: () => new Subject<void>()}))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '17'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}},
      {provide: MatSnackBar, useValue: snackBar}
    ]});
    component = TestBed.runInInjectionContext(() => new AiPolicyUserDetailComponent());
  });

  it('shows only active catalog models in policy controls', () => {
    component.ngOnInit();
    expect(component.models.map(item => item.modelKey)).toEqual(['active']);
  });

  it('removes reasoning restrictions when an allow-listed model is deselected', () => {
    component.apply(policy());
    component.setModel('active', false);
    expect(component.policy!.allowedReasoningEfforts.active).toBeUndefined();
  });

  it('filters stale reasoning restrictions from the save payload', () => {
    const view = policy();
    view.allowedReasoningEfforts.disabled = ['high'];
    component.models = [model('active', true)];
    component.apply(view);
    component.reason = 'Save test policy';
    component.save();
    expect(service.save.mock.calls[0][1].allowedReasoningEfforts).toEqual({active: ['high']});
  });

  it('reloads models, policy, and usage together after a load failure or conflict', () => {
    component.ngOnInit();
    component.reloadPolicy();
    expect(service.models).toHaveBeenCalledTimes(2);
    expect(service.policy).toHaveBeenCalledTimes(2);
    expect(service.usage).toHaveBeenCalledTimes(2);
  });

  it('rejects unsafe integers, invalid token caps, and incomplete quota pairs', () => {
    component.apply(policy());
    component.policy!.maxAgentsPerRequest = 1.5;
    expect(component.invalid).toBe(true);
    component.policy!.maxAgentsPerRequest = 2;
    component.policy!.maxOutputTokensPerCall = 0;
    expect(component.invalid).toBe(true);
    component.policy!.maxOutputTokensPerCall = null;
    component.quotaEnabled = true;
    component.policy!.quota.period = 'MONTHLY';
    component.policy!.quota.limitTokens = null;
    expect(component.invalid).toBe(true);
  });

  it('round-trips unlimited caps and reloads all policy data from a 409 action', () => {
    const action = new Subject<void>();
    service.save = vi.fn(() => throwError(() => ({status: 409})));
    snackBar.open = vi.fn(() => ({onAction: () => action}));
    component.ngOnInit();
    component.setOutputUnlimited(false); component.setRequestUnlimited(false);
    expect(component.policy!.maxOutputTokensPerCall).toBe(4096);
    expect(component.policy!.maxTotalTokensPerRequest).toBe(100000);
    component.setOutputUnlimited(true); component.setRequestUnlimited(true);
    expect(component.policy!.maxOutputTokensPerCall).toBeNull();
    expect(component.policy!.maxTotalTokensPerRequest).toBeNull();
    component.reason = 'Save policy conflict'; component.save(); action.next();
    expect(service.models).toHaveBeenCalledTimes(2);
    expect(service.policy).toHaveBeenCalledTimes(2);
    expect(service.usage).toHaveBeenCalledTimes(2);
  });

  it('reports failures from cancel, quota adjustment, and reset operations', () => {
    service.cancelActiveRequests = vi.fn(() => throwError(() => new Error('cancel')));
    service.adjustQuota = vi.fn(() => throwError(() => new Error('adjust')));
    service.reset = vi.fn(() => throwError(() => new Error('reset')));
    component.apply(policy());
    component.cancelActive();
    component.adjustment = 1; component.reason = 'Adjust quota test'; component.adjustQuota();
    component.reason = 'Reset policy test'; component.reset();
    expect(snackBar.open).toHaveBeenCalledWith('Active requests could not be cancelled.', '', {duration: 5000});
    expect(snackBar.open).toHaveBeenCalledWith('Quota could not be adjusted.', '', {duration: 5000});
    expect(snackBar.open).toHaveBeenCalledWith('The AI policy could not be reset.', '', {duration: 5000});
  });

  it('reports an ordinary policy save server error without overwriting the form', () => {
    service.save = vi.fn(() => throwError(() => ({status: 500})));
    component.apply(policy()); component.reason = 'Save policy server error'; component.save();
    expect(snackBar.open).toHaveBeenCalledWith('The AI policy could not be saved.', '', {duration: 5000});
    expect(component.policy!.policyVersion).toBe(3);
  });
});
