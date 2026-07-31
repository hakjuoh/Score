import {TestBed} from '@angular/core/testing';
import {ActivatedRoute, Router, convertToParamMap} from '@angular/router';
import {MatSnackBar} from '@angular/material/snack-bar';
import {Subject, of, throwError} from 'rxjs';
import {AiProviderDetailComponent} from './ai-provider-detail.component';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiProviderView} from './domain/ai-admin-policy';

describe('AiProviderDetailComponent', () => {
  const provider: AiProviderView = {aiProviderId: 7, providerName: 'OpenAI',
    providerType: 'openai', baseUrl: 'https://api.openai.com', messagesUrl: null,
    anthropicVersion: null, apiVersion: null, enabled: true, apiKeyConfigured: false,
    catalogVersion: 3};

  it('offers a conflict reload and refreshes the catalog version', () => {
    const reload = new Subject<void>();
    const service = {provider: vi.fn(() => of(provider)), updateProvider: vi.fn(() =>
      throwError(() => ({status: 409, error: {message: 'Version conflict'}})))};
    const snackBar = {open: vi.fn(() => ({onAction: () => reload}))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}},
      {provide: MatSnackBar, useValue: snackBar}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());
    component.ngOnInit();
    component.form.baseUrl = 'https://gateway.example.com';
    component.form.reason = 'Update provider endpoint';

    component.save();
    expect(component.message).toBe('Version conflict');
    expect(snackBar.open).toHaveBeenCalledWith('The provider changed elsewhere.', 'Reload',
      {duration: 5000});
    reload.next();
    expect(service.provider).toHaveBeenCalledTimes(2);
  });

  it('updates a replacement key through the provider Update action', () => {
    const configured = {...provider, apiKeyConfigured: true};
    const service = {provider: vi.fn(() => of(configured)), updateProvider: vi.fn(() => of(configured))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}}, {provide: MatSnackBar, useValue: {open: vi.fn()}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());
    component.ngOnInit(); component.apiKey = 'secret'; component.form.reason = 'Rotate provider key';
    component.save();
    expect(service.updateProvider.mock.calls[0][1].apiKey).toBe('secret');
  });

  it('removes a configured key when its masked value is cleared before Update', () => {
    const configured = {...provider, apiKeyConfigured: true};
    const service = {provider: vi.fn(() => of(configured)), updateProvider: vi.fn(() => of(provider))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service}, {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}}, {provide: MatSnackBar, useValue: {open: vi.fn()}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());
    component.ngOnInit(); component.apiKey = ''; component.form.reason = 'Remove provider key';
    component.save();
    expect(service.updateProvider.mock.calls[0][1].apiKey).toBe('');
    expect(component.provider!.apiKeyConfigured).toBe(false);
  });

  it('keeps a configured key when its masked value is unchanged', () => {
    const configured = {...provider, apiKeyConfigured: true};
    const service = {provider: vi.fn(() => of(configured)), updateProvider: vi.fn(() => of(configured))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service}, {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}}, {provide: MatSnackBar, useValue: {open: vi.fn()}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());
    component.ngOnInit(); component.form.baseUrl = 'https://gateway.example.com';
    component.form.reason = 'Update provider endpoint'; component.save();
    expect(service.updateProvider.mock.calls[0][1].apiKey).toBeUndefined();
  });

  it('does not report a key deletion when an unconfigured key field stays empty', () => {
    const updated = {...provider, baseUrl: 'https://gateway.example.com'};
    const service = {provider: vi.fn(() => of(provider)), updateProvider: vi.fn(() => of(updated))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}}, {provide: MatSnackBar, useValue: {open: vi.fn()}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());
    component.ngOnInit(); component.form.baseUrl = updated.baseUrl;
    component.form.reason = 'Update provider endpoint'; component.save();
    expect(service.updateProvider.mock.calls[0][1].apiKey).toBeUndefined();
  });

  it('enables Update only for changed provider properties and resets the hash after saving', () => {
    const updated = {...provider, providerName: 'OpenAI Updated'};
    const service = {provider: vi.fn(() => of(provider)), updateProvider: vi.fn(() => of(updated))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}}, {provide: MatSnackBar, useValue: {open: vi.fn()}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());
    component.ngOnInit();
    expect(component.isChanged).toBe(false);
    component.form.providerName = 'OpenAI Updated';
    component.form.reason = 'Rename this provider';
    expect(component.isChanged).toBe(true);
    component.save();
    expect(component.isChanged).toBe(false);
  });

  it('shows a safe server error when disabling a provider is rejected', () => {
    const service = {provider: () => of(provider), updateProvider: vi.fn(() => throwError(() => ({status: 400, error: {message: 'Active models use this provider.'}})))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service}, {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}}, {provide: MatSnackBar, useValue: {open: vi.fn()}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent()); component.ngOnInit();
    component.form.enabled = false; component.form.reason = 'Disable provider now'; component.save();
    expect(component.message).toBe('Active models use this provider.'); expect(component.form.enabled).toBe(false);
  });
});
