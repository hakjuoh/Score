import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {TestBed} from '@angular/core/testing';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {ActivatedRoute, Router, RouterModule, convertToParamMap} from '@angular/router';
import {MatSnackBar} from '@angular/material/snack-bar';
import {Subject, of, throwError} from 'rxjs';
import {MaterialModule} from '../../material.module';
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

    component.save();
    expect(component.loadError).toBe('');
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
    component.ngOnInit(); component.apiKey = 'secret';
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
    component.ngOnInit(); component.apiKey = '';
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
    component.save();
    expect(service.updateProvider.mock.calls[0][1].apiKey).toBeUndefined();
  });

  it('tests the current draft with the stored key without saving changes', () => {
    const configured = {...provider, apiKeyConfigured: true};
    const service = {provider: vi.fn(() => of(configured)),
      testProviderConnection: vi.fn(() => of({successful: true,
        message: 'Connection successful.', statusCode: 200}))};
    const snackBar = {open: vi.fn()};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}},
      {provide: MatSnackBar, useValue: snackBar}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());
    component.ngOnInit();
    component.form.baseUrl = 'https://gateway.example.com';

    component.testConnection();

    expect(service.testProviderConnection).toHaveBeenCalledWith(7,
      expect.objectContaining({baseUrl: 'https://gateway.example.com', apiKey: undefined}));
    expect(snackBar.open).toHaveBeenCalledWith('Connection successful.', '', {duration: 3000});
    expect(component.testing).toBe(false);
    expect(component.isChanged).toBe(true);
  });

  it('tests a replacement key without exposing the masked stored value', () => {
    const configured = {...provider, apiKeyConfigured: true};
    const service = {provider: vi.fn(() => of(configured)),
      testProviderConnection: vi.fn(() => of({successful: false,
        message: 'Authentication failed.', statusCode: 401}))};
    const snackBar = {open: vi.fn()};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}},
      {provide: MatSnackBar, useValue: snackBar}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());
    component.ngOnInit(); component.apiKey = 'replacement-key';

    component.testConnection();

    expect(service.testProviderConnection.mock.calls[0][1].apiKey).toBe('replacement-key');
    expect(snackBar.open).toHaveBeenCalledWith('Authentication failed.', '', {duration: 5000});
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
    component.save();
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
    expect(component.isChanged).toBe(true);
    component.save();
    expect(component.isChanged).toBe(false);
  });

  it('updates a changed provider without a change reason', () => {
    const updated = {...provider, providerName: 'OpenAI Updated'};
    const service = {provider: vi.fn(() => of(provider)), updateProvider: vi.fn(() => of(updated))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}}, {provide: MatSnackBar, useValue: {open: vi.fn()}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());
    component.ngOnInit();
    expect(component.saveDisabled).toBe(true);

    component.form.providerName = 'OpenAI Updated';

    expect(component.saveDisabled).toBe(false);
    component.save();
    expect(service.updateProvider.mock.calls[0][1]).not.toHaveProperty('reason');
  });

  it('shows only the version field for the selected provider type', async () => {
    const service = {provider: vi.fn(() => of(provider)),
      testProviderConnection: vi.fn(() => of({successful: true,
        message: 'Connection successful.', statusCode: 200}))};
    await TestBed.configureTestingModule({
      declarations: [AiProviderDetailComponent],
      imports: [CommonModule, FormsModule, MaterialModule, NoopAnimationsModule,
        RouterModule.forRoot([])],
      providers: [
        {provide: AiAdminPolicyService, useValue: service},
        {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
        {provide: MatSnackBar, useValue: {open: vi.fn()}}
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(AiProviderDetailComponent);
    fixture.detectChanges(false);
    await fixture.whenStable();
    fixture.detectChanges(false);
    expect(fixture.componentInstance.providerTypes.map(type => type.label))
      .toEqual(['Anthropic', 'OpenAI']);
    expect(fixture.nativeElement.textContent).toContain('Test Connection');
    expect(fixture.nativeElement.textContent).toContain('API Version');
    expect(fixture.nativeElement.textContent).not.toContain('Anthropic Version');

    fixture.componentInstance.setProviderType('anthropic');
    fixture.changeDetectorRef.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Anthropic Version');
    expect(fixture.nativeElement.textContent).not.toContain('API Version');
  });

  it('presents a legacy Azure OpenAI provider as OpenAI without changing its adapter', () => {
    const legacy = {...provider, providerType: 'azure-openai', apiVersion: '2025-04-01-preview'};
    const updated = {...legacy, providerName: 'Azure OpenAI Updated'};
    const service = {provider: vi.fn(() => of(legacy)), updateProvider: vi.fn(() => of(updated))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}}, {provide: MatSnackBar, useValue: {open: vi.fn()}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());
    component.ngOnInit();
    expect(component.form.providerType).toBe('openai');
    expect(component.isAnthropicProvider).toBe(false);

    component.form.providerName = updated.providerName;
    component.save();

    expect(service.updateProvider.mock.calls[0][1].providerType).toBe('azure-openai');
  });

  it('clears the version setting that does not belong to the selected provider type', () => {
    const service = {provider: vi.fn(() => of({...provider, apiVersion: 'v1'}))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}}, {provide: MatSnackBar, useValue: {open: vi.fn()}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());
    component.ngOnInit();

    component.setProviderType('anthropic');
    expect(component.form.apiVersion).toBeNull();
    component.form.anthropicVersion = '2023-06-01';
    component.setProviderType('openai');
    expect(component.form.anthropicVersion).toBeNull();
  });

  it('shows a safe server error when disabling a provider is rejected', () => {
    const service = {provider: () => of(provider), updateProvider: vi.fn(() => throwError(() => ({status: 400, error: {message: 'Active models use this provider.'}})))};
    const snackBar = {open: vi.fn()};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service}, {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}}, {provide: MatSnackBar, useValue: snackBar}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent()); component.ngOnInit();
    component.form.enabled = false; component.save();
    expect(snackBar.open).toHaveBeenCalledWith('Active models use this provider.', '', {duration: 5000});
    expect(component.loadError).toBe(''); expect(component.form.enabled).toBe(false);
  });
});
