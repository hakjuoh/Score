import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {TestBed} from '@angular/core/testing';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {ActivatedRoute, Router, RouterModule, convertToParamMap} from '@angular/router';
import {MatSnackBar} from '@angular/material/snack-bar';
import {Subject, of, throwError} from 'rxjs';
import {MaterialModule} from '../../material.module';
import {AiModelDetailComponent} from './ai-model-detail.component';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiAdminModel, AiModelProfile, AiProviderView} from './domain/ai-admin-policy';

describe('AiModelDetailComponent', () => {
  const provider: AiProviderView = {aiProviderId: 1, providerName: 'OpenAI',
    providerType: 'openai', baseUrl: 'https://example.com', messagesUrl: null,
    anthropicVersion: null, apiVersion: null, enabled: true, apiKeyConfigured: false,
    catalogVersion: 1};
  const anthropicProvider: AiProviderView = {...provider, aiProviderId: 2,
    providerName: 'Anthropic', providerType: 'anthropic'};
  const efforts = [
    {name: 'disabled', displayName: 'Disabled', description: 'No reasoning.',
      defaultEffort: false, sortOrder: 0},
    {name: 'medium', displayName: 'Medium', description: 'Balanced reasoning.',
      defaultEffort: true, sortOrder: 1}
  ];
  const openAiProfile: AiModelProfile = {
    providerType: 'openai', modelKey: 'gpt-5_6-sol', providerModelName: 'gpt-5.6-sol',
    displayName: 'GPT-5.6 Sol', description: 'Frontier model.', maxTokens: 128000,
    contextWindow: 1050000, maxOutputTokens: 128000, maxContextWindow: 1050000,
    outputReserveTokens: 128000,
    autoCompactThresholdTokens: 850000, emergencyHeadroomTokens: 8192,
    toolOutputTokenLimit: 32000, providerCompactionEnabled: false, temperature: null,
    thinkingBudgetTokens: null, minThinkingBudgetTokens: null, maxThinkingBudgetTokens: null,
    adaptiveThinking: false, outputEffort: null,
    cacheStrategy: null, reasoningModelSupported: true, outputEffortSupported: null,
    verbositySupported: true, temperatureSupported: false, thinkingModes: [],
    defaultThinking: null, reasoningEfforts: efforts,
    configurationConstraints: {
      contextWindow: {defaultValue: 1050000, minimum: 1, maximum: 1050000, optional: false},
      maxOutputTokens: {defaultValue: 128000, minimum: 1, maximum: 128000, optional: true},
      outputReserveTokens: {defaultValue: 128000, minimum: 1, maximum: 1049999, optional: true},
      autoCompactThresholdTokens: {defaultValue: 850000, minimum: 1, maximum: 1049999, optional: true},
      emergencyHeadroomTokens: {defaultValue: 8192, minimum: 0, maximum: 1049999, optional: false},
      toolOutputTokenLimit: {defaultValue: 32000, minimum: 1, maximum: 1049999, optional: false},
      thinkingBudgetTokens: {defaultValue: null, minimum: null, maximum: null, optional: true},
      temperature: {defaultValue: null, minimum: null, maximum: null, optional: true}
    },
    capabilityConstraints: {
      reasoningOptions: {supported: true, defaultEnabled: true},
      outputEffort: {supported: false, defaultEnabled: false},
      verbosity: {supported: true, defaultEnabled: true},
      temperature: {supported: false, defaultEnabled: false},
      adaptiveThinking: {supported: false, defaultEnabled: false},
      providerCompaction: {supported: true, defaultEnabled: false}
    }
  };
  const haikuProfile: AiModelProfile = {
    ...openAiProfile, providerType: 'anthropic', modelKey: 'claude-haiku-4_5',
    providerModelName: 'claude-haiku-4-5', displayName: 'Claude Haiku 4.5',
    description: 'Fast Claude model.', maxTokens: 64000, contextWindow: 200000,
    maxOutputTokens: 64000, maxContextWindow: 200000,
    outputReserveTokens: 64000, autoCompactThresholdTokens: 120000,
    thinkingBudgetTokens: 4096, minThinkingBudgetTokens: 1024,
    maxThinkingBudgetTokens: 63999,
    cacheStrategy: 'conversation-history',
    reasoningModelSupported: null, outputEffortSupported: false, verbositySupported: null,
    thinkingModes: ['enabled', 'disabled'], defaultThinking: 'disabled',
    reasoningEfforts: [],
    configurationConstraints: {
      contextWindow: {defaultValue: 200000, minimum: 1, maximum: 200000, optional: false},
      maxOutputTokens: {defaultValue: 64000, minimum: 1, maximum: 64000, optional: true},
      outputReserveTokens: {defaultValue: 64000, minimum: 1, maximum: 199999, optional: true},
      autoCompactThresholdTokens: {defaultValue: 120000, minimum: 1, maximum: 199999, optional: true},
      emergencyHeadroomTokens: {defaultValue: 8192, minimum: 0, maximum: 199999, optional: false},
      toolOutputTokenLimit: {defaultValue: 32000, minimum: 1, maximum: 199999, optional: false},
      thinkingBudgetTokens: {defaultValue: 4096, minimum: 1024, maximum: 63999, optional: true},
      temperature: {defaultValue: null, minimum: null, maximum: null, optional: true}
    },
    capabilityConstraints: {
      reasoningOptions: {supported: false, defaultEnabled: false},
      outputEffort: {supported: false, defaultEnabled: false},
      verbosity: {supported: false, defaultEnabled: false},
      temperature: {supported: false, defaultEnabled: false},
      adaptiveThinking: {supported: false, defaultEnabled: false},
      providerCompaction: {supported: false, defaultEnabled: false}
    }
  };
  const model: AiAdminModel = {
    aiModelId: 9, provider: provider.providerName, providerId: provider.aiProviderId,
    modelKey: openAiProfile.modelKey, providerModelName: openAiProfile.providerModelName,
    displayName: openAiProfile.displayName, description: openAiProfile.description,
    enabled: true, defaultModel: true, sortOrder: 0, maxTokens: openAiProfile.maxTokens,
    contextWindow: openAiProfile.contextWindow,
    outputReserveTokens: openAiProfile.outputReserveTokens,
    autoCompactThresholdTokens: openAiProfile.autoCompactThresholdTokens,
    emergencyHeadroomTokens: openAiProfile.emergencyHeadroomTokens,
    toolOutputTokenLimit: openAiProfile.toolOutputTokenLimit,
    providerCompactionEnabled: openAiProfile.providerCompactionEnabled,
    temperature: openAiProfile.temperature, thinkingBudgetTokens: openAiProfile.thinkingBudgetTokens,
    adaptiveThinking: openAiProfile.adaptiveThinking, outputEffort: openAiProfile.outputEffort,
    cacheStrategy: openAiProfile.cacheStrategy,
    reasoningModelSupported: openAiProfile.reasoningModelSupported,
    outputEffortSupported: false,
    verbositySupported: openAiProfile.verbositySupported,
    temperatureSupported: openAiProfile.temperatureSupported,
    thinkingModes: openAiProfile.thinkingModes, defaultThinking: openAiProfile.defaultThinking,
    catalogVersion: 2, reasoningEfforts: efforts
  };

  it('loads backend profiles and enables Update only after a change', () => {
    const updated = {...model, sortOrder: 1};
    const service = {providers: () => of([provider]), model: () => of(model),
      modelProfiles: vi.fn(() => of([openAiProfile])), updateModel: vi.fn(() => of(updated))};
    configure(service);
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());

    component.ngOnInit();

    expect(service.modelProfiles).toHaveBeenCalledWith(1);
    expect(component.selectedModelProfile).toEqual(openAiProfile);
    expect(component.saveDisabled).toBe(true);
    component.form.sortOrder = -1;
    expect(component.saveDisabled).toBe(true);
    component.form.sortOrder = 1;
    expect(component.saveDisabled).toBe(false);
    component.save();
    expect(component.isChanged).toBe(false);
  });

  it('offers conflict reload without requiring a change reason', () => {
    const reload = new Subject<void>();
    const service = {providers: () => of([provider]), model: vi.fn(() => of(model)),
      modelProfiles: () => of([openAiProfile]),
      updateModel: vi.fn(() => throwError(() => ({status: 409})))};
    const snackBar = {open: vi.fn(() => ({onAction: () => reload}))};
    configure(service, snackBar);
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();
    component.form.sortOrder = 1;

    component.save();

    expect(service.updateModel.mock.calls[0][1]).not.toHaveProperty('reason');
    expect(snackBar.open).toHaveBeenCalledWith('The model changed elsewhere.', 'Reload',
      {duration: 5000});
    reload.next();
    expect(service.model).toHaveBeenCalledTimes(2);
  });

  it('selects the first backend profile for a new provider and applies its constants', () => {
    const service = {providers: () => of([provider]), modelProfiles: () => of([openAiProfile])};
    configure(service, {open: vi.fn()}, 'new');
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());

    component.ngOnInit();

    expect(component.form.providerId).toBe(1);
    expect(component.form.modelKey).toBe('gpt-5_6-sol');
    expect(component.form.contextWindow).toBe(1050000);
    expect(component.form.reasoningEfforts).toEqual(efforts);
  });

  it('reloads model choices when provider changes', () => {
    const service = {providers: () => of([provider, anthropicProvider]), model: () => of(model),
      modelProfiles: vi.fn((id: number) => of(id === 1 ? [openAiProfile] : [haikuProfile]))};
    configure(service);
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();

    component.setProvider(2);

    expect(service.modelProfiles).toHaveBeenCalledWith(2);
    expect(component.form.modelKey).toBe('claude-haiku-4_5');
    expect(component.form.thinkingBudgetTokens).toBe(4096);
    expect(component.form.reasoningEfforts).toEqual([]);
  });

  it('removes an unsupported legacy Haiku effort and saves editable settings', () => {
    const haikuModel = {...model, provider: 'Anthropic', providerId: 2,
      modelKey: haikuProfile.modelKey, providerModelName: haikuProfile.providerModelName,
      displayName: haikuProfile.displayName, maxTokens: haikuProfile.maxTokens,
      contextWindow: haikuProfile.contextWindow,
      outputReserveTokens: haikuProfile.outputReserveTokens,
      autoCompactThresholdTokens: haikuProfile.autoCompactThresholdTokens,
      thinkingBudgetTokens: haikuProfile.thinkingBudgetTokens,
      cacheStrategy: haikuProfile.cacheStrategy, thinkingModes: haikuProfile.thinkingModes,
      defaultThinking: haikuProfile.defaultThinking, reasoningEfforts: efforts};
    const saved = {...haikuModel, sortOrder: 1, reasoningEfforts: []};
    const service = {providers: () => of([anthropicProvider]), model: () => of(haikuModel),
      modelProfiles: () => of([haikuProfile]), updateModel: vi.fn(() => of(saved))};
    configure(service);
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();
    component.save();

    expect(service.updateModel.mock.calls[0][1]).toMatchObject({expectedVersion: 2, providerId: 2,
      modelKey: 'claude-haiku-4_5', enabled: true, defaultModel: true,
      contextWindow: 200000, reasoningEfforts: []});
  });

  it('preserves an existing context window below the profile maximum', () => {
    const staleModel = {...model, contextWindow: 200000};
    const service = {providers: () => of([provider]), model: () => of(staleModel),
      modelProfiles: () => of([openAiProfile]), updateModel: vi.fn(() => of(model))};
    configure(service);
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());

    component.ngOnInit();

    expect(component.form.contextWindow).toBe(200000);
    expect(component.saveDisabled).toBe(true);
  });

  it('allows limits and supported reasoning efforts to be changed', () => {
    const saved = {...model, contextWindow: 900000, reasoningEfforts: [efforts[1]]};
    const service = {providers: () => of([provider]), model: () => of(model),
      modelProfiles: () => of([openAiProfile]), updateModel: vi.fn(() => of(saved))};
    configure(service);
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();

    component.form.contextWindow = 900000;
    component.form.autoCompactThresholdTokens = 700000;
    component.setEffortEnabled(efforts[0], false);
    component.save();

    expect(service.updateModel.mock.calls[0][1]).toMatchObject({contextWindow: 900000,
      reasoningEfforts: [{name: 'medium', defaultEffort: true, sortOrder: 0}]});
  });

  it('rejects settings beyond the profile maximum', () => {
    const service = {providers: () => of([provider]), model: () => of(model),
      modelProfiles: () => of([openAiProfile])};
    configure(service);
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();

    component.form.contextWindow = openAiProfile.maxContextWindow + 1;

    expect(component.saveDisabled).toBe(true);
  });

  it('rejects a thinking budget below the profile minimum', () => {
    const haikuModel = {...model, provider: 'Anthropic', providerId: 2,
      modelKey: haikuProfile.modelKey, providerModelName: haikuProfile.providerModelName,
      displayName: haikuProfile.displayName, maxTokens: haikuProfile.maxTokens,
      contextWindow: haikuProfile.contextWindow,
      outputReserveTokens: haikuProfile.outputReserveTokens,
      autoCompactThresholdTokens: haikuProfile.autoCompactThresholdTokens,
      thinkingBudgetTokens: haikuProfile.thinkingBudgetTokens,
      cacheStrategy: haikuProfile.cacheStrategy, thinkingModes: haikuProfile.thinkingModes,
      defaultThinking: haikuProfile.defaultThinking, reasoningEfforts: []};
    const service = {providers: () => of([anthropicProvider]), model: () => of(haikuModel),
      modelProfiles: () => of([haikuProfile])};
    configure(service);
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();

    component.form.thinkingBudgetTokens = 512;

    expect(component.saveDisabled).toBe(true);
  });

  it('rejects a thinking budget at or above the configured output limit', () => {
    const haikuModel = {...model, provider: 'Anthropic', providerId: 2,
      modelKey: haikuProfile.modelKey, providerModelName: haikuProfile.providerModelName,
      displayName: haikuProfile.displayName, maxTokens: haikuProfile.maxTokens,
      contextWindow: haikuProfile.contextWindow,
      outputReserveTokens: haikuProfile.outputReserveTokens,
      autoCompactThresholdTokens: haikuProfile.autoCompactThresholdTokens,
      thinkingBudgetTokens: haikuProfile.thinkingBudgetTokens,
      cacheStrategy: haikuProfile.cacheStrategy, thinkingModes: haikuProfile.thinkingModes,
      defaultThinking: haikuProfile.defaultThinking, reasoningEfforts: []};
    const service = {providers: () => of([anthropicProvider]), model: () => of(haikuModel),
      modelProfiles: () => of([haikuProfile])};
    configure(service);
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();

    component.form.maxTokens = 2000;
    component.form.thinkingBudgetTokens = null;

    expect(component.saveDisabled).toBe(true);
    expect(component.validationErrors).toContain(
      'Thinking Token Budget must be smaller than Max Output Tokens.');
  });

  it('rejects fixed thinking without an explicit mode and default', () => {
    const haikuModel = {...model, provider: 'Anthropic', providerId: 2,
      modelKey: haikuProfile.modelKey, providerModelName: haikuProfile.providerModelName,
      displayName: haikuProfile.displayName, maxTokens: haikuProfile.maxTokens,
      contextWindow: haikuProfile.contextWindow,
      outputReserveTokens: haikuProfile.outputReserveTokens,
      autoCompactThresholdTokens: haikuProfile.autoCompactThresholdTokens,
      thinkingBudgetTokens: haikuProfile.thinkingBudgetTokens,
      cacheStrategy: haikuProfile.cacheStrategy, thinkingModes: haikuProfile.thinkingModes,
      defaultThinking: haikuProfile.defaultThinking, reasoningEfforts: []};
    const service = {providers: () => of([anthropicProvider]), model: () => of(haikuModel),
      modelProfiles: () => of([haikuProfile])};
    configure(service);
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();

    component.form.thinkingModes = [];
    component.form.defaultThinking = null;

    expect(component.validationErrors).toContain(
      'Fixed Thinking requires at least one enabled mode and a default mode.');
    expect(component.saveDisabled).toBe(true);
  });

  it('enables synchronization when profile-owned effort metadata changes', () => {
    const staleModel = {...model, reasoningEfforts: efforts.map(effort => ({...effort,
      description: `Stale ${effort.description}`}))};
    const service = {providers: () => of([provider]), model: () => of(staleModel),
      modelProfiles: () => of([openAiProfile])};
    configure(service);
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());

    component.ngOnInit();

    expect(component.form.reasoningEfforts[0].description).toBe(efforts[0].description);
    expect(component.saveDisabled).toBe(false);
  });

  it('clears reasoning efforts when supported reasoning is disabled', () => {
    const service = {providers: () => of([provider]), model: () => of(model),
      modelProfiles: () => of([openAiProfile])};
    configure(service);
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();

    component.setReasoningModelEnabled(false);

    expect(component.form.reasoningModelSupported).toBe(false);
    expect(component.form.reasoningEfforts).toEqual([]);
    expect(component.saveDisabled).toBe(false);
  });

  it('ignores an older profile response for the same provider', () => {
    const requests: Subject<AiModelProfile[]>[] = [];
    const service = {providers: () => of([provider]), model: () => of(model),
      modelProfiles: vi.fn(() => {
        const request = new Subject<AiModelProfile[]>();
        requests.push(request);
        return request;
      })};
    configure(service);
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();
    requests[0].next([openAiProfile]);

    (component as unknown as {loadModelProfiles(id: number, select: boolean): void})
      .loadModelProfiles(1, false);
    (component as unknown as {loadModelProfiles(id: number, select: boolean): void})
      .loadModelProfiles(1, false);
    requests[2].next([openAiProfile]);
    requests[1].error({status: 500});

    expect(component.modelProfiles).toEqual([openAiProfile]);
    expect(component.modelProfileLoadError).toBe('');
    expect(component.modelProfilesLoading).toBe(false);
  });

  it('ignores an older model response after a reload', () => {
    const requests: Subject<AiAdminModel>[] = [];
    const service = {providers: () => of([provider]), model: vi.fn(() => {
      const request = new Subject<AiAdminModel>();
      requests.push(request);
      return request;
    }), modelProfiles: () => of([openAiProfile])};
    configure(service);
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();
    (component as unknown as {load(): void}).load();

    requests[1].next({...model, sortOrder: 2});
    requests[0].next({...model, sortOrder: 1});

    expect(component.form.sortOrder).toBe(2);
  });

  it('ignores an older provider response after a retry', () => {
    const requests: Subject<AiProviderView[]>[] = [];
    const service = {providers: vi.fn(() => {
      const request = new Subject<AiProviderView[]>();
      requests.push(request);
      return request;
    }), model: () => of(model), modelProfiles: () => of([openAiProfile])};
    configure(service);
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();
    (component as unknown as {loadProviders(): void}).loadProviders();

    requests[1].next([{...provider, providerName: 'Current'}]);
    requests[0].next([{...provider, providerName: 'Stale'}]);

    expect(component.providers[0].providerName).toBe('Current');
  });

  it('keeps Create disabled when a provider has no supported profiles', () => {
    const service = {providers: () => of([provider]), modelProfiles: () => of([])};
    configure(service, {open: vi.fn()}, 'new');
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());

    component.ngOnInit();

    expect(component.modelProfiles).toEqual([]);
    expect(component.saveDisabled).toBe(true);
  });

  it('clears derived values when switching to a provider without profiles', () => {
    const emptyProvider = {...provider, aiProviderId: 3, providerName: 'Empty'};
    const service = {providers: () => of([provider, emptyProvider]),
      modelProfiles: (id: number) => of(id === 1 ? [openAiProfile] : [])};
    configure(service, {open: vi.fn()}, 'new');
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();

    component.setProvider(3);

    expect(component.form.modelKey).toBe('');
    expect(component.form.displayName).toBe('');
    expect(component.form.contextWindow).toBe(0);
    expect(component.form.reasoningEfforts).toEqual([]);
    expect(component.saveDisabled).toBe(true);
  });

  it('renders backend capability properties and omits Haiku reasoning efforts', async () => {
    const haikuModel = {...model, provider: 'Anthropic', providerId: 2,
      modelKey: haikuProfile.modelKey, providerModelName: haikuProfile.providerModelName,
      displayName: haikuProfile.displayName, maxTokens: haikuProfile.maxTokens,
      contextWindow: haikuProfile.contextWindow,
      outputReserveTokens: haikuProfile.outputReserveTokens,
      autoCompactThresholdTokens: haikuProfile.autoCompactThresholdTokens,
      thinkingBudgetTokens: haikuProfile.thinkingBudgetTokens,
      cacheStrategy: haikuProfile.cacheStrategy, thinkingModes: haikuProfile.thinkingModes,
      defaultThinking: haikuProfile.defaultThinking, reasoningEfforts: []};
    const service = {providers: () => of([anthropicProvider]), model: () => of(haikuModel),
      modelProfiles: () => of([haikuProfile])};
    await TestBed.configureTestingModule({
      declarations: [AiModelDetailComponent],
      imports: [CommonModule, FormsModule, MaterialModule, NoopAnimationsModule,
        RouterModule.forRoot([])],
      providers: [
        {provide: AiAdminPolicyService, useValue: service},
        {provide: ActivatedRoute,
          useValue: {snapshot: {paramMap: convertToParamMap({id: '9'})}}},
        {provide: MatSnackBar, useValue: {open: vi.fn()}}
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(AiModelDetailComponent);
    fixture.detectChanges(false);
    await fixture.whenStable();
    fixture.detectChanges(false);
    const text = fixture.nativeElement.textContent as string;

    expect(text).toContain('Claude Haiku 4.5');
    expect(text).toContain('Thinking');
    expect(text).toContain('Reasoning Effort');
    expect(text).toContain('Not supported:');
    expect(text).not.toContain('Reasoning Efforts');
    const labels = [...fixture.nativeElement.querySelectorAll('mat-label')]
      .map((label: Element) => label.textContent?.trim());
    expect(labels.indexOf('Provider')).toBeLessThan(labels.indexOf('Model'));
    expect(labels.indexOf('Model')).toBeLessThan(labels.indexOf('Provider Model ID'));
    expect(labels.indexOf('Provider Model ID')).toBeLessThan(labels.indexOf('Display Name'));
    expect(labels.indexOf('Display Name')).toBeLessThan(labels.indexOf('Description'));
    expect(fixture.nativeElement.querySelector('.model-status-row .model-sort-order'))
      .not.toBeNull();
    expect(labels.indexOf('Context Window')).toBeLessThan(labels.indexOf('Max Output Tokens'));
  });

  it('renders an accessible validation summary and grouped effort defaults', async () => {
    const invalidModel = {...model, contextWindow: openAiProfile.maxContextWindow + 1};
    const service = {providers: () => of([provider]), model: () => of(invalidModel),
      modelProfiles: () => of([openAiProfile])};
    await TestBed.configureTestingModule({
      declarations: [AiModelDetailComponent],
      imports: [CommonModule, FormsModule, MaterialModule, NoopAnimationsModule,
        RouterModule.forRoot([])],
      providers: [
        {provide: AiAdminPolicyService, useValue: service},
        {provide: ActivatedRoute,
          useValue: {snapshot: {paramMap: convertToParamMap({id: '9'})}}},
        {provide: MatSnackBar, useValue: {open: vi.fn()}}
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(AiModelDetailComponent);
    fixture.detectChanges(false);
    await fixture.whenStable();
    fixture.detectChanges(false);

    const summary = fixture.nativeElement.querySelector('.model-validation-summary');
    expect(summary.getAttribute('role')).toBe('alert');
    expect(summary.textContent).toContain('Context Window');
    expect(fixture.nativeElement.querySelector(
      'mat-radio-group[aria-label="Default reasoning effort"]')).not.toBeNull();
  });

  function configure(service: object, snackBar: object = {open: vi.fn()}, id = '9'): void {
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id})}}},
      {provide: Router, useValue: {navigate: vi.fn()}},
      {provide: MatSnackBar, useValue: snackBar}
    ]});
  }
});
