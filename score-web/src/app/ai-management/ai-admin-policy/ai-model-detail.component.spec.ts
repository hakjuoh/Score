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
import {JsonOptionEditorComponent} from './json-option-editor.component';

describe('AiModelDetailComponent', () => {
  const provider: AiProviderView = {aiProviderId: 1, providerName: 'OpenAI',
    providerType: 'openai', baseUrl: 'https://example.com', messagesUrl: null,
    apiVersion: null, enabled: true, apiKeyConfigured: false,
    updaterLoginId: 'admin', lastUpdatedAt: null};
  const anthropicProvider: AiProviderView = {...provider, aiProviderId: 2,
    providerName: 'Anthropic', providerType: 'anthropic'};
  const efforts = [
    {name: 'medium', displayName: 'Medium', description: 'Balanced reasoning.',
      defaultEffort: true, sortOrder: 0}
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
    chatCompletionsCompatible: true,
    options: [
      {key: 'maxCompletionTokens', type: 'integer', value: 128000,
        description: 'Maximum completion tokens.', allowedValues: []},
      {key: 'reasoningEffort', type: 'enum', value: 'medium',
        description: 'Reasoning effort.', allowedValues: ['disabled', 'medium']},
      {key: 'store', type: 'boolean', value: false,
        description: 'Store the completion.', allowedValues: []},
      {key: 'serviceTier', type: 'enum', value: null,
        description: 'Processing service tier.', allowedValues: ['auto', 'flex', 'priority']}
    ],
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
    options: [
      {key: 'model', type: 'string', value: 'claude-haiku-4-5',
        description: 'Provider model name.', allowedValues: []},
      {key: 'maxTokens', type: 'integer', value: 64000,
        description: 'Maximum output tokens.', allowedValues: []},
      {key: 'apiKey', type: 'string', value: '',
        description: 'Provider API key.', allowedValues: []},
      {key: 'baseUrl', type: 'string', value: '',
        description: 'Provider base URL.', allowedValues: []},
      {key: 'timeout', type: 'string', value: '60s',
        description: 'Provider request timeout.', allowedValues: []},
      {key: 'citationsEnabled', type: 'boolean', value: false,
        description: 'Enable citations.', allowedValues: []},
      {key: 'logprobs', type: 'boolean', value: null,
        description: 'Return token probabilities.', allowedValues: []},
      {key: 'thinkingBudgetTokens', type: 'integer', value: 4096,
        description: 'Thinking token budget.', allowedValues: []},
      {key: 'temperature', type: 'decimal', value: 1.0,
        description: 'Sampling randomness.', allowedValues: []},
      {key: 'topP', type: 'decimal', value: null,
        description: 'Nucleus sampling.', allowedValues: []},
      {key: 'topK', type: 'integer', value: null,
        description: 'Top-k sampling.', allowedValues: []},
      {key: 'plainText', type: 'string', value: '',
        description: 'Plain-text citation source.', allowedValues: []},
      {key: 'maxUses', type: 'integer', value: null,
        description: 'Maximum web searches.', allowedValues: []},
      {key: 'metadata', type: 'json', value: null,
        description: 'JSON request metadata.', allowedValues: []},
      {key: 'toolChoiceName', type: 'string', value: '',
        description: 'Named tool choice.', allowedValues: []},
      {key: 'cacheStrategy', type: 'enum', value: 'CONVERSATION_HISTORY',
        description: 'Prompt cache strategy.',
        allowedValues: ['NONE', 'CONVERSATION_HISTORY']},
      {key: 'serviceTier', type: 'enum', value: null,
        description: 'Processing service tier.', allowedValues: ['auto', 'default']}
    ],
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
    enabled: true, defaultModel: true, lightweightModel: false, sortOrder: 0, maxTokens: openAiProfile.maxTokens,
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
    reasoningEfforts: efforts, modelOptions: {store: false},
    updaterLoginId: 'admin', lastUpdatedAt: null
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
    component.setOptionValue(haikuProfile.options.find(
      option => option.key === 'citationsEnabled')!, true);
    component.save();
    expect(service.updateModel.mock.calls[0][1]).toMatchObject({providerId: 2,
      modelKey: 'claude-haiku-4_5', enabled: true, defaultModel: true, lightweightModel: false,
      contextWindow: 200000, reasoningEfforts: [],
      modelOptions: expect.objectContaining({citationsEnabled: true})});
  });

  it('saves lightweight model toggle changes', () => {
    const service = {providers: () => of([provider]), model: () => of(model),
      modelProfiles: () => of([openAiProfile]), updateModel: vi.fn().mockReturnValue(of({...model, lightweightModel: true}))};
    configure(service, {open: vi.fn()}, '9');
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.ngOnInit();
    component.form.lightweightModel = true;
    component.save();

    expect(service.updateModel).toHaveBeenCalledWith(9, expect.objectContaining({
      lightweightModel: true
    }));
  });

  it('resets option search and expansion state when the provider changes', () => {
    const service = {providers: () => of([provider, anthropicProvider]),
      modelProfiles: () => of([haikuProfile])};
    configure(service, {open: vi.fn()}, 'new');
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.form.providerId = provider.aiProviderId;
    component.modelOptionsExpanded = true;
    component.modelOptionQuery = 'cache';

    component.setProvider(anthropicProvider.aiProviderId);

    expect(component.modelOptionsExpanded).toBe(false);
    expect(component.modelOptionQuery).toBe('');
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
    const saved = {...model, contextWindow: 900000, reasoningEfforts: [efforts[0]]};
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
      reasoningEfforts: []});
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

  it('filters Claude output effort duplicated by the Reasoning Efforts editor', () => {
    const claude46Profile: AiModelProfile = {
      ...haikuProfile,
      modelKey: 'claude-sonnet-4_6',
      providerModelName: 'claude-sonnet-4-6',
      displayName: 'Claude Sonnet 4.6',
      reasoningEfforts: [{name: 'high', displayName: 'High', description: 'High effort.',
        defaultEffort: true, sortOrder: 0}],
      options: [
        ...haikuProfile.options,
        {key: 'outputEffort', type: 'enum', value: 'HIGH',
          description: 'Output effort.', allowedValues: ['LOW', 'MEDIUM', 'HIGH', 'MAX']},
        {key: 'outputSchema', type: 'json', value: null,
          description: 'Structured output schema.', allowedValues: []}
      ]
    };
    const service = {providers: () => of([anthropicProvider]), modelProfiles: () => of([])};
    configure(service, {open: vi.fn()}, 'new');
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.modelProfiles = [claude46Profile];
    component.form.modelKey = claude46Profile.modelKey;

    expect(component.modelOptions.map(option => option.key)).not.toContain('outputEffort');
    expect(component.modelOptions.map(option => option.key)).toContain('outputSchema');
  });

  it('omits implicit unset entries from model-specific reasoning efforts', () => {
    const profile: AiModelProfile = {...openAiProfile, reasoningEfforts: [
      {name: 'none', displayName: 'None', description: 'Unset.', defaultEffort: false,
        sortOrder: 0},
      {name: 'disabled', displayName: 'None', description: 'Unset.', defaultEffort: false,
        sortOrder: 1},
      ...efforts
    ]};
    const service = {providers: () => of([provider]), modelProfiles: () => of([])};
    configure(service, {open: vi.fn()}, 'new');
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());
    component.modelProfiles = [profile];
    component.form.modelKey = profile.modelKey;

    expect(component.availableReasoningEfforts.map(effort => effort.name)).toEqual(['medium']);
    component.selectModel(profile.modelKey);
    expect(component.form.reasoningEfforts.map(effort => effort.name)).toEqual(['medium']);
    component.setEffortEnabled(efforts[0], false);
    expect(component.form.reasoningEfforts).toEqual([]);
    expect(component.form.reasoningModelSupported).toBe(false);
  });

  it('formats technical option acronyms consistently', () => {
    const service = {providers: () => of([provider]), modelProfiles: () => of([])};
    configure(service, {open: vi.fn()}, 'new');
    const component = TestBed.runInInjectionContext(() => new AiModelDetailComponent());

    expect(component.optionLabel('messageTypeTtl')).toBe('Message Type TTL');
    expect(component.optionLabel('pdf')).toBe('PDF');
    expect(component.optionLabel('requestId')).toBe('Request ID');
  });

  it('renders only model-specific options without duplicate or provider settings', async () => {
    const renderedProfile = structuredClone(haikuProfile);
    const haikuModel = {...model, provider: 'Anthropic', providerId: 2,
      modelKey: renderedProfile.modelKey, providerModelName: renderedProfile.providerModelName,
      displayName: renderedProfile.displayName, maxTokens: renderedProfile.maxTokens,
      contextWindow: renderedProfile.contextWindow,
      outputReserveTokens: renderedProfile.outputReserveTokens,
      autoCompactThresholdTokens: renderedProfile.autoCompactThresholdTokens,
      thinkingBudgetTokens: renderedProfile.thinkingBudgetTokens,
      cacheStrategy: renderedProfile.cacheStrategy, thinkingModes: renderedProfile.thinkingModes,
      defaultThinking: renderedProfile.defaultThinking, reasoningEfforts: [],
      modelOptions: {citationsEnabled: false, cacheStrategy: 'CONVERSATION_HISTORY'}};
    const service = {providers: () => of([anthropicProvider]), model: () => of(haikuModel),
      modelProfiles: () => of([renderedProfile])};
    await TestBed.configureTestingModule({
      declarations: [AiModelDetailComponent, JsonOptionEditorComponent],
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
    expect(text).toContain('Model Options');
    expect(text).not.toContain('Spring AI Chat Options');
    expect(text).not.toContain('Model Capabilities');
    expect(text).not.toContain('Provider default');
    expect(text).not.toContain('Provider Default');
    expect(text).toContain('Citations Enabled');
    expect(text).toContain('Enable citations.');
    expect(text).not.toContain('Not supported:');
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
    const renderedOptions = Array.from<HTMLElement>(
      fixture.nativeElement.querySelectorAll('.profile-option'));
    const renderedKeys = renderedOptions.map(option => option.dataset['optionKey']);
    expect(renderedKeys).not.toContain('model');
    expect(renderedKeys).not.toContain('maxTokens');
    expect(renderedKeys).not.toContain('temperature');
    expect(renderedKeys).not.toContain('thinkingBudgetTokens');
    expect(renderedKeys).not.toContain('apiKey');
    expect(renderedKeys).not.toContain('baseUrl');
    expect(renderedKeys).not.toContain('timeout');
    expect(renderedKeys).not.toContain('maxUses');
    expect(renderedKeys).not.toContain('toolChoiceName');
    expect(renderedKeys).toContain('cacheStrategy');
    expect(renderedKeys).toEqual([...renderedKeys].sort((left, right) => {
      const leftLabel = fixture.componentInstance.optionLabel(left ?? '');
      const rightLabel = fixture.componentInstance.optionLabel(right ?? '');
      return leftLabel.localeCompare(rightLabel, 'en', {sensitivity: 'base'});
    }));
    const values = new Map(renderedOptions.map(option => {
      const label = option.querySelector('.profile-option-label label')?.textContent?.trim()
        ?? '';
      const control = option.querySelector('select, input, textarea') as
        HTMLSelectElement | HTMLInputElement | HTMLTextAreaElement;
      const value = (option.dataset['optionType'] === 'boolean'
          || option.dataset['optionType'] === 'enum')
        ? control.value === ''
          ? (control as HTMLSelectElement).selectedOptions[0]?.text
          : (control as HTMLSelectElement).selectedOptions[0]?.text
        : control.value;
      return [label, value];
    }));
    expect(new Set(renderedOptions.map(option => option.dataset['optionType'])))
      .toEqual(new Set(['boolean', 'integer', 'decimal', 'string', 'json', 'enum']));
    expect(values.get('Citations Enabled')).toBe('Disabled');
    expect(values.get('Logprobs')).toBe('');
    expect(values.get('Top P')).toBe('');
    expect(values.get('Metadata')).toBe('');
    expect(values.get('Cache Strategy')).toBe('CONVERSATION_HISTORY');
    expect(values.get('Service Tier')).toBe('');
    renderedOptions.forEach(option => {
      const control = option.querySelector('select, input, textarea') as
        HTMLSelectElement | HTMLInputElement | HTMLTextAreaElement;
      const descriptionId = control.getAttribute('aria-describedby');
      if (option.dataset['optionType'] === 'boolean' || option.dataset['optionType'] === 'enum') {
        expect((control as HTMLSelectElement).disabled).toBe(false);
      } else {
        expect((control as HTMLInputElement).readOnly).toBe(false);
      }
      expect(descriptionId).toBeTruthy();
      expect(option.querySelector(`#${descriptionId}`)?.textContent?.trim()).not.toBe('');
    });
    expect(fixture.nativeElement.querySelector('[data-option-type="boolean"] select'))
      .not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-option-type="enum"] select'))
      .not.toBeNull();
    const nullDecimal = fixture.nativeElement.querySelector(
      '[data-option-key="topP"] input') as HTMLInputElement;
    expect(nullDecimal.type).toBe('number');
    expect(nullDecimal.step).toBe('any');
    expect(nullDecimal.value).toBe('');
    const nullInteger = fixture.nativeElement.querySelector(
      '[data-option-key="topK"] input') as HTMLInputElement;
    expect(nullInteger.type).toBe('number');
    expect(nullInteger.step).toBe('1');
    expect(nullInteger.value).toBe('');
    nullInteger.value = '4';
    nullInteger.dispatchEvent(new Event('input'));
    fixture.detectChanges(false);
    expect(fixture.componentInstance.form.modelOptions['topK']).toBe(4);
    expect(text).toContain('Allowed: NONE, CONVERSATION_HISTORY.');

    const cacheStrategy = fixture.nativeElement.querySelector(
      '[data-option-key="cacheStrategy"] select') as HTMLSelectElement;
    expect([...cacheStrategy.options].map(option => option.value))
      .toEqual(['', 'NONE', 'CONVERSATION_HISTORY']);
    cacheStrategy.value = 'NONE';
    cacheStrategy.dispatchEvent(new Event('change'));
    fixture.detectChanges(false);
    expect(cacheStrategy.value).toBe('NONE');
    expect(fixture.componentInstance.form.modelOptions['cacheStrategy']).toBe('NONE');
    expect(fixture.componentInstance.form.cacheStrategy).toBeNull();

    const booleanOption = fixture.nativeElement.querySelector(
      '[data-option-key="citationsEnabled"] select') as HTMLSelectElement;
    expect([...booleanOption.options].map(option => [option.value, option.text]))
      .toEqual([['', ''], ['true', 'Enabled'], ['false', 'Disabled']]);
    booleanOption.value = 'true';
    booleanOption.dispatchEvent(new Event('change'));
    fixture.detectChanges(false);
    expect(fixture.componentInstance.form.modelOptions['citationsEnabled'])
      .toBe(true);
    booleanOption.value = '';
    booleanOption.dispatchEvent(new Event('change'));
    fixture.detectChanges(false);
    expect(fixture.componentInstance.form.modelOptions['citationsEnabled']).toBeUndefined();

    const metadata = fixture.nativeElement.querySelector(
      '[data-option-key="metadata"] textarea') as HTMLTextAreaElement;
    expect(metadata).not.toBeNull();
    expect(fixture.nativeElement.querySelector(
      '[data-option-key="metadata"] pre.json-editor-highlight')).not.toBeNull();
    expect(metadata.getAttribute('aria-label')).toBeNull();
    expect(fixture.nativeElement.querySelector(
      `label[for="${metadata.id}"]`)?.textContent).toContain('Metadata');
    metadata.value = '{"team":"search"}';
    metadata.dispatchEvent(new Event('input'));
    fixture.detectChanges(false);
    expect(renderedProfile.options.find(option => option.key === 'metadata')?.value)
      .toBeNull();
    expect(fixture.componentInstance.form.modelOptions['metadata'])
      .toEqual({team: 'search'});
    expect(metadata.getAttribute('aria-invalid')).toBe('false');
    metadata.value = '{"html":"<script>alert(1)</script>"}';
    metadata.dispatchEvent(new Event('input'));
    fixture.detectChanges(false);
    const highlighted = fixture.nativeElement.querySelector(
      '[data-option-key="metadata"] pre.json-editor-highlight') as HTMLElement;
    expect(highlighted.querySelector('script')).toBeNull();
    expect(highlighted.textContent).toBe(metadata.value);
    metadata.value = '{invalid';
    metadata.dispatchEvent(new Event('input'));
    fixture.detectChanges(false);
    expect(metadata.getAttribute('aria-invalid')).toBe('true');
    expect(fixture.componentInstance.saveDisabled).toBe(true);
    expect(fixture.nativeElement.querySelector(
      '[data-option-key="metadata"] [role="alert"]')?.textContent).toContain('valid JSON');
  });

  it('renders inline validation, bounded number inputs, and grouped effort defaults', async () => {
    const invalidModel = {...model, contextWindow: openAiProfile.maxContextWindow + 1};
    const service = {providers: () => of([provider]), model: () => of(invalidModel),
      modelProfiles: () => of([openAiProfile])};
    await TestBed.configureTestingModule({
      declarations: [AiModelDetailComponent, JsonOptionEditorComponent],
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
    const component = fixture.componentInstance;
    fixture.detectChanges(false);
    await fixture.whenStable();
    fixture.detectChanges(false);

    expect(fixture.nativeElement.querySelector('.model-validation-summary')).toBeNull();
    const contextLimit = fixture.nativeElement.querySelector('.token-limit-field') as HTMLElement;
    const contextField = contextLimit.querySelector('mat-form-field') as HTMLElement;
    const contextInput = contextField.querySelector('input[matinput]') as HTMLInputElement;
    expect(contextField.querySelector('mat-label')?.textContent).toContain('Context Window');
    expect(contextLimit.querySelector('mat-slider')).toBeNull();
    expect(Number(contextInput.max)).toBe(openAiProfile.maxContextWindow);
    contextInput.dispatchEvent(new Event('input'));
    contextInput.dispatchEvent(new Event('blur'));
    fixture.detectChanges(false);
    const contextError = contextField.parentElement?.querySelector('mat-error') as HTMLElement;
    expect(contextError?.textContent).toContain('Context Window');
    expect(contextInput.getAttribute('aria-describedby')).toBe(contextError.id);
    component.setTokenInputValue('contextWindow', 200000);
    expect(component.tokenMaximum('autoCompactThresholdTokens')).toBe(63808);
    const staticPanels = [...fixture.nativeElement.querySelectorAll(
      'mat-expansion-panel.static-detail-panel')] as HTMLElement[];
    expect(staticPanels).toHaveLength(3);
    staticPanels.forEach(panel => {
      const header = panel.querySelector('mat-expansion-panel-header') as HTMLElement;
      expect(header.getAttribute('aria-expanded')).toBe('true');
      expect(header.getAttribute('aria-disabled')).toBe('true');
      expect(header.querySelector('.mat-expansion-indicator')).toBeNull();
    });
    const reasoningCard = fixture.nativeElement.querySelector(
      '.reasoning-efforts-card') as HTMLElement;
    const optionsPanel = fixture.nativeElement.querySelector(
      '.model-options-panel') as HTMLElement;
    expect(reasoningCard).not.toBeNull();
    expect(optionsPanel).not.toBeNull();
    expect(reasoningCard.compareDocumentPosition(optionsPanel)
      & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);

    const panelHeader = optionsPanel.querySelector('mat-expansion-panel-header') as HTMLElement;
    expect(panelHeader.getAttribute('aria-expanded')).toBe('false');
    expect(optionsPanel.querySelector('[data-testid="model-option-search"]')).toBeNull();
    panelHeader.click();
    fixture.detectChanges(false);
    await fixture.whenStable();
    expect(panelHeader.getAttribute('aria-expanded')).toBe('true');

    const search = optionsPanel.querySelector(
      '[data-testid="model-option-search"]') as HTMLInputElement;
    expect(search.closest('mat-panel-description')).not.toBeNull();
    expect(search.closest('mat-form-field')?.getAttribute('appearance')).toBeNull();
    expect(search.closest('mat-form-field')?.textContent).toContain('Search');
    expect(search.closest('mat-panel-description')?.lastElementChild?.textContent)
      .toContain('options');
    search.value = 'priority';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges(false);
    await fixture.whenStable();
    fixture.detectChanges(false);
    expect(component.modelOptionQuery).toBe('priority');
    expect([...optionsPanel.querySelectorAll<HTMLElement>('.profile-option')]
      .map(option => option.dataset['optionKey'])).toEqual(['serviceTier']);

    search.value = 'not-a-model-option';
    search.dispatchEvent(new Event('input'));
    fixture.detectChanges(false);
    await fixture.whenStable();
    fixture.detectChanges(false);
    expect(optionsPanel.querySelector('.profile-option')).toBeNull();
    expect(optionsPanel.querySelector('[role="status"]')?.textContent)
      .toContain('No model options match your search.');

    component.selectModel(openAiProfile.modelKey);
    fixture.detectChanges(false);
    await fixture.whenStable();
    fixture.detectChanges(false);
    expect(panelHeader.getAttribute('aria-expanded')).toBe('false');
    expect(optionsPanel.querySelector('[data-testid="model-option-search"]')).toBeNull();
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
