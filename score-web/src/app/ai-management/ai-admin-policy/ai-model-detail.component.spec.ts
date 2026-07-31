import {TestBed} from '@angular/core/testing';
import {ActivatedRoute, Router, convertToParamMap} from '@angular/router';
import {MatSnackBar} from '@angular/material/snack-bar';
import {Subject, of, throwError} from 'rxjs';
import {AiModelDetailComponent} from './ai-model-detail.component';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiAdminModel, AiProviderView} from './domain/ai-admin-policy';

describe('AiModelDetailComponent', () => {
  const provider: AiProviderView = {aiProviderId: 1, providerName: 'Provider',
    providerType: 'openai', baseUrl: 'https://example.com', messagesUrl: null,
    anthropicVersion: null, apiVersion: null, enabled: true, apiKeyConfigured: false,
    catalogVersion: 1};
  const model: AiAdminModel = {aiModelId: 9, modelKey: 'model', displayName: 'Model',
    provider: 'Provider', providerId: 1, providerModelName: 'model', description: '',
    enabled: true, defaultModel: true, sortOrder: 0, maxTokens: 4096,
    contextWindow: 128000, outputReserveTokens: null, autoCompactThresholdTokens: null,
    emergencyHeadroomTokens: 4096, toolOutputTokenLimit: 32000,
    providerCompactionEnabled: true, temperature: null, thinkingBudgetTokens: null,
    adaptiveThinking: false, outputEffort: null, cacheStrategy: null,
    reasoningModelSupported: true, outputEffortSupported: false, verbositySupported: false,
    temperatureSupported: true, thinkingModes: [], defaultThinking: null, catalogVersion: 2,
    reasoningEfforts: [{name: 'high', displayName: 'High', description: '',
      defaultEffort: true, sortOrder: 0}]};

  it('offers a conflict reload and keeps validation server-aligned', () => {
    const reload = new Subject<void>();
    const service = {providers: vi.fn(() => of([provider])), model: vi.fn(() => of(model)),
      updateModel: vi.fn(() => throwError(() => ({status: 409, error: 'Version conflict'})))};
    const snackBar = {open: vi.fn(() => ({onAction: () => reload}))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '9'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}},
      {provide: MatSnackBar, useValue: snackBar}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();
    component.form.displayName = 'Updated Model';
    component.form.reason = 'Update model catalog';
    expect(component.invalid).toBe(false);

    component.save();
    expect(component.message).toBe('Version conflict');
    reload.next();
    expect(service.model).toHaveBeenCalledTimes(2);
  });

  it('only offers enabled providers and requires switching from a disabled current provider', () => {
    const disabled = {...provider, aiProviderId: 2, providerName: 'Disabled', enabled: false};
    const service = {providers: vi.fn(() => of([provider, disabled])), model: vi.fn(() => of({...model, providerId: 2}))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '9'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}},
      {provide: MatSnackBar, useValue: {open: vi.fn()}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();
    component.form.reason = 'Update model provider';
    expect(component.selectableProviders.map(item => item.aiProviderId)).toEqual([1, 2]);
    expect(component.invalid).toBe(true);
    component.form.providerId = 1;
    expect(component.invalid).toBe(false);
  });

  it('shows a safe server error when disabling a referenced model is rejected', () => {
    const service = {providers: () => of([provider]), model: () => of(model), updateModel: vi.fn(() => throwError(() => ({status: 400, error: {message: 'Model is referenced by a policy.'}})))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service}, {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '9'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}}, {provide: MatSnackBar, useValue: {open: vi.fn()}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent()); component.ngOnInit();
    component.form.enabled = false; component.form.defaultModel = false;
    component.form.reason = 'Disable model now'; component.save();
    expect(component.message).toBe('Model is referenced by a policy.'); expect(component.form.enabled).toBe(false);
  });

  it('enables Update only for changed model properties and resets the hash after saving', () => {
    const updated = {...model, displayName: 'Updated Model'};
    const service = {providers: () => of([provider]), model: () => of(model),
      updateModel: vi.fn(() => of(updated))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '9'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}}, {provide: MatSnackBar, useValue: {open: vi.fn()}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();
    expect(component.isChanged).toBe(false);
    component.form.displayName = 'Updated Model';
    component.form.reason = 'Rename this model';
    expect(component.isChanged).toBe(true);
    component.save();
    expect(component.isChanged).toBe(false);
  });
});
