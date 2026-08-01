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
import {FontAwesomeModule} from '@fortawesome/angular-fontawesome';

describe('AiProviderDetailComponent', () => {
  const provider: AiProviderView = {aiProviderId: 7, providerName: 'OpenAI',
    providerType: 'openai', baseUrl: 'https://api.openai.com', messagesUrl: null,
    apiVersion: null, enabled: true, apiKeyConfigured: false,
    updaterLoginId: 'admin', lastUpdatedAt: null};

  it('offers a conflict reload', () => {
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
    const service = {provider: vi.fn(() => of(configured)),
      maskedProviderApiKey: vi.fn(() => of({value: '••••••', revealed: false})),
      updateProvider: vi.fn(() => of(configured))};
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
    const service = {provider: vi.fn(() => of(configured)),
      maskedProviderApiKey: vi.fn(() => of({value: '••••••', revealed: false})),
      updateProvider: vi.fn(() => of(provider))};
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
    const service = {provider: vi.fn(() => of(configured)),
      maskedProviderApiKey: vi.fn(() => of({value: '••••••', revealed: false})),
      updateProvider: vi.fn(() => of(configured))};
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
      maskedProviderApiKey: vi.fn(() => of({value: '••••••', revealed: false})),
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
      maskedProviderApiKey: vi.fn(() => of({value: '••••••', revealed: false})),
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

  it('uses a full-length mask and reveals the stored key only on demand', () => {
    const configured = {...provider, apiKeyConfigured: true};
    const storedKey = 'sk-full-length-secret';
    const maskedValue = '•'.repeat(storedKey.length);
    const service = {provider: vi.fn(() => of(configured)),
      maskedProviderApiKey: vi.fn(() => of({value: maskedValue, revealed: false})),
      revealProviderApiKey: vi.fn(() => of({value: storedKey, revealed: true})),
      updateProvider: vi.fn(() => of(configured))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}},
      {provide: MatSnackBar, useValue: {open: vi.fn()}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());

    component.ngOnInit();
    expect(component.apiKey).toBe(maskedValue);
    expect(component.apiKey.length).toBe(storedKey.length);
    expect(component.canRevealStoredApiKey).toBe(true);

    component.toggleApiKeyVisibility();
    expect(service.revealProviderApiKey).toHaveBeenCalledWith(7);
    expect(component.apiKeyVisible).toBe(true);
    expect(component.apiKey).toBe(storedKey);

    component.toggleApiKeyVisibility();
    expect(component.apiKeyVisible).toBe(false);
    expect(component.apiKey).toBe(maskedValue);

    component.form.baseUrl = 'https://gateway.example.com';
    component.save();
    expect(service.updateProvider.mock.calls[0][1].apiKey).toBeUndefined();

    component.apiKey = 'replacement-key';
    expect(component.apiKeyVisible).toBe(false);
    expect(component.canRevealStoredApiKey).toBe(false);
  });

  it('does not restore a revealed key from a response arriving after destruction', () => {
    const configured = {...provider, apiKeyConfigured: true};
    const reveal = new Subject<{value: string; revealed: boolean}>();
    const service = {provider: vi.fn(() => of(configured)),
      maskedProviderApiKey: vi.fn(() => of({value: '••••••', revealed: false})),
      revealProviderApiKey: vi.fn(() => reveal.asObservable())};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}},
      {provide: MatSnackBar, useValue: {open: vi.fn()}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());
    component.ngOnInit();
    component.toggleApiKeyVisibility();

    component.ngOnDestroy();
    reveal.next({value: 'late-secret', revealed: true});

    expect(component.apiKey).toBe('');
    expect(component.apiKeyVisible).toBe(false);
  });

  it('rejects a revealed credential returned by the masking endpoint', () => {
    const configured = {...provider, apiKeyConfigured: true};
    const snackBar = {open: vi.fn()};
    const service = {provider: vi.fn(() => of(configured)),
      maskedProviderApiKey: vi.fn(() => of({value: 'unexpected-secret', revealed: true}))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}},
      {provide: MatSnackBar, useValue: snackBar}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());

    component.ngOnInit();

    expect(component.apiKey).toBe('');
    expect(snackBar.open).toHaveBeenCalledWith(
      'The stored API key mask response was invalid.', '', {duration: 5000});
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
      imports: [CommonModule, FormsModule, MaterialModule, FontAwesomeModule, NoopAnimationsModule,
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
    const settingsPanel = fixture.nativeElement.querySelector(
      'mat-expansion-panel.static-detail-panel') as HTMLElement;
    const settingsHeader = settingsPanel.querySelector(
      'mat-expansion-panel-header') as HTMLElement;
    expect(settingsHeader.textContent).toContain('Provider Settings');
    expect(settingsHeader.getAttribute('aria-expanded')).toBe('true');
    expect(settingsHeader.getAttribute('aria-disabled')).toBe('true');
    expect(settingsHeader.querySelector('.mat-expansion-indicator')).toBeNull();
    expect(fixture.nativeElement.querySelector('[aria-label="Show API key"]')).toBeNull();

    fixture.componentInstance.setProviderType('anthropic');
    fixture.changeDetectorRef.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('API Version');
    expect(fixture.nativeElement.textContent).toContain('anthropic-version');
  });

  it('keeps the single API version when the provider type changes', () => {
    const service = {provider: vi.fn(() => of({...provider, apiVersion: 'v1'}))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}}, {provide: MatSnackBar, useValue: {open: vi.fn()}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());
    component.ngOnInit();

    component.setProviderType('anthropic');
    expect(component.form.apiVersion).toBe('v1');
    component.setProviderType('openai');
    expect(component.form.apiVersion).toBe('v1');
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
