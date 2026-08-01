import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {TestBed} from '@angular/core/testing';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {ActivatedRoute, RouterModule, convertToParamMap} from '@angular/router';
import {MatSnackBar} from '@angular/material/snack-bar';
import {Subject, of, throwError} from 'rxjs';
import {MaterialModule} from '../../material.module';
import {AiPolicyUserDetailComponent} from './ai-policy-user-detail.component';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiAdminModel, AiPolicyView} from './domain/ai-admin-policy';
import {MatSort} from '@angular/material/sort';

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
    temperatureSupported: true, thinkingModes: [], defaultThinking: null,
    reasoningEfforts: [{name: 'high', displayName: 'High', description: '', defaultEffort: true, sortOrder: 0}],
    modelOptions: {}, updaterLoginId: 'admin', lastUpdatedAt: null
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

  beforeEach(async () => {
    service = {
      models: vi.fn(() => of([model('active', true), model('disabled', false)])),
      policy: vi.fn(() => of(policy())), usage: vi.fn(() => of({quota: policy().quota,
        activeRequests: 0, recentCalls: []})), save: vi.fn(() => of(policy())),
      reset: vi.fn(), cancelActiveRequests: vi.fn(), adjustQuota: vi.fn()
    };
    snackBar = {open: vi.fn(() => ({onAction: () => new Subject<void>()}))};
    await TestBed.configureTestingModule({
      declarations: [AiPolicyUserDetailComponent],
      imports: [CommonModule, FormsModule, MaterialModule, NoopAnimationsModule,
        RouterModule.forRoot([])],
      providers: [
        {provide: AiAdminPolicyService, useValue: service},
        {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '17'})}}},
        {provide: MatSnackBar, useValue: snackBar}
      ]
    }).compileComponents();
    component = TestBed.runInInjectionContext(() => new AiPolicyUserDetailComponent());
  });

  it('shows only active catalog models in policy controls', () => {
    component.ngOnInit();
    expect(component.models.map(item => item.modelKey)).toEqual(['active']);
  });

  it('sorts recent calls by the values rendered in each usage column', () => {
    const recentCalls = [
      {callId: '2', modelKey: 'z-model', executionKind: 'SINGLE', agentId: null,
        reservedTokens: 10, chargedTokens: 20, usageComplete: true, status: 'SUCCEEDED',
        failureType: null, reservedAt: '2026-07-31T12:00:00Z', settledAt: null},
      {callId: '1', modelKey: 'a-model', executionKind: 'SINGLE', agentId: null,
        reservedTokens: 10, chargedTokens: 10, usageComplete: true, status: 'SUCCEEDED',
        failureType: null, reservedAt: '2026-07-31T11:00:00Z', settledAt: null}
    ];
    service.usage = vi.fn(() => of({quota: policy().quota, activeRequests: 0, recentCalls}));

    component.ngOnInit();

    const sorted = component.usageDataSource.sortData(recentCalls,
      {active: 'time', direction: 'asc'} as MatSort);
    expect(sorted.map(call => call.callId)).toEqual(['1', '2']);
    expect(component.usageDataSource.data).toEqual(recentCalls);
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
    component.policy!.maxActiveRequests = 9;
    component.save();
    expect(service.save.mock.calls[0][1].allowedReasoningEfforts).toEqual({active: ['high']});
    expect(service.save.mock.calls[0][1]).not.toHaveProperty('reason');
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
    component.policy!.enabled = false;
    component.save(); action.next();
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
    component.adjustment = 1; component.adjustQuota();
    component.reset();
    expect(snackBar.open).toHaveBeenCalledWith('Active requests could not be cancelled.', '', {duration: 5000});
    expect(snackBar.open).toHaveBeenCalledWith('Quota could not be adjusted.', '', {duration: 5000});
    expect(snackBar.open).toHaveBeenCalledWith('The AI policy could not be reset.', '', {duration: 5000});
  });

  it('explains invalid quota adjustments instead of silently ignoring them', () => {
    component.apply(policy());
    component.adjustment = 1.5;

    component.adjustQuota();

    expect(service.adjustQuota).not.toHaveBeenCalled();
    expect(snackBar.open).toHaveBeenCalledWith(
      'Enter a non-zero whole-number quota adjustment.', '', {duration: 5000});
  });

  it('offers to reload after a reset version conflict', () => {
    const action = new Subject<void>();
    service.reset = vi.fn(() => throwError(() => ({status: 409})));
    snackBar.open = vi.fn(() => ({onAction: () => action}));
    component.ngOnInit();

    component.reset();
    expect(snackBar.open).toHaveBeenCalledWith(
      'The policy changed elsewhere. Reload before restoring the default policy.',
      'Reload', {duration: 5000});

    action.next();
    expect(service.models).toHaveBeenCalledTimes(2);
    expect(service.policy).toHaveBeenCalledTimes(2);
    expect(service.usage).toHaveBeenCalledTimes(2);
  });

  it('reports an ordinary policy save server error without overwriting the form', () => {
    service.save = vi.fn(() => throwError(() => ({status: 500})));
    component.apply(policy()); component.policy!.enabled = false;
    component.save();
    expect(snackBar.open).toHaveBeenCalledWith('The AI policy could not be saved.', '', {duration: 5000});
    expect(component.policy!.policyVersion).toBe(3);
  });

  it('keeps the applied default policy read-only until a user override is started', () => {
    const inheritedPolicy = policy();
    inheritedPolicy.inherited = true;
    component.apply(inheritedPolicy);
    component.save();
    expect(service.save).not.toHaveBeenCalled();
    component.beginOverride();
    component.policy!.enabled = false;
    component.save();
    expect(service.save).toHaveBeenCalledOnce();
  });

  it('adjusts quota without requiring a reason', () => {
    component.userId = '17';
    component.apply(policy());
    service.adjustQuota = vi.fn(() => of({quota: policy().quota, activeRequests: 0, recentCalls: []}));
    component.adjustment = -10;
    component.adjustQuota();
    expect(service.adjustQuota).toHaveBeenCalledWith('17', -10);
  });

  it('keeps quota operations based on committed usage while the policy draft changes', () => {
    const committedQuota = {...policy().quota, period: 'MONTHLY' as const,
      limitTokens: 1000, remainingTokens: 1000};
    component.usage = {quota: committedQuota, activeRequests: 0, recentCalls: []};
    component.apply(policy());

    expect(component.quotaAdjustmentAvailable).toBe(true);
    component.setQuotaEnabled(true);
    component.setQuotaEnabled(false);
    expect(component.quotaAdjustmentAvailable).toBe(true);
  });

  it('renders policy quota fields from the draft and operations from committed usage', async () => {
    const draft = policy();
    draft.quota = {...draft.quota, period: 'MONTHLY', limitTokens: 2000};
    service.policy = vi.fn(() => of(draft));
    service.usage = vi.fn(() => of({quota: {...policy().quota, period: 'MONTHLY', limitTokens: 1000},
      activeRequests: 0, recentCalls: []}));
    const fixture = TestBed.createComponent(AiPolicyUserDetailComponent);
    fixture.detectChanges(false);
    await fixture.whenStable();
    fixture.detectChanges(false);
    expect(fixture.nativeElement.querySelectorAll('.policy-quota-field')).toHaveLength(2);

    fixture.componentInstance.setQuotaEnabled(false);
    fixture.changeDetectorRef.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.policy-quota-field')).toHaveLength(0);
    const tabs = fixture.nativeElement.querySelectorAll('[role="tab"]') as NodeListOf<HTMLButtonElement>;
    tabs[1].click();
    await fixture.whenStable();
    fixture.detectChanges(false);

    expect(fixture.nativeElement.querySelector('.quota-adjustment-grid')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('.quota-adjustment-grid mat-form-field'))
      .toHaveLength(1);
    expect(fixture.nativeElement.textContent).not.toContain('Change Reason');
    expect(fixture.nativeElement.textContent).not.toContain('Adjustment Reason');
  });

  it('enables Update only for changed policy properties and resets the hash after saving', () => {
    component.apply(policy());
    expect(component.isChanged).toBe(false);
    component.policy!.enabled = false;
    expect(component.isChanged).toBe(true);
    component.save();
    expect(component.isChanged).toBe(false);
  });

  it('enables the rendered Update button after a policy control changes', async () => {
    const fixture = TestBed.createComponent(AiPolicyUserDetailComponent);
    fixture.detectChanges(false);
    await fixture.whenStable();
    fixture.detectChanges(false);
    const update = fixture.nativeElement.querySelector(
      '[data-id="save-ai-policy"]') as HTMLButtonElement;
    expect(update.disabled).toBe(true);

    fixture.componentInstance.policy!.enabled = false;
    fixture.changeDetectorRef.detectChanges();

    expect(update.disabled).toBe(false);
  });

  it('keeps Update available for an invalid policy change and explains why it cannot save', () => {
    component.apply(policy());
    component.policy!.maxActiveRequests = 0;

    expect(component.updateDisabled).toBe(false);
    component.save();

    expect(service.save).not.toHaveBeenCalled();
    expect(snackBar.open).toHaveBeenCalledWith(
      'Correct the invalid policy settings before updating.', '', {duration: 5000});
  });

  it('does not overwrite an unsaved policy quota after an operational adjustment', () => {
    component.userId = '17';
    component.apply(policy());
    component.setQuotaEnabled(true);
    component.policy!.quota.limitTokens = 2000;
    service.adjustQuota = vi.fn(() => of({quota: {...policy().quota, period: 'MONTHLY',
      limitTokens: 1000}, activeRequests: 0, recentCalls: []}));

    component.adjustment = 10;
    component.adjustQuota();

    expect(component.policy!.quota.limitTokens).toBe(2000);
    expect(component.usage!.quota.limitTokens).toBe(1000);
  });
});
