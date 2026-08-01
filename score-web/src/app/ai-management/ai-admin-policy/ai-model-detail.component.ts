import {Component, OnInit, ViewChild, inject} from '@angular/core';
import {MatExpansionPanel} from '@angular/material/expansion';
import {MatSnackBar} from '@angular/material/snack-bar';
import {ActivatedRoute, Router} from '@angular/router';
import {hashCode} from '../../common/utility';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiAdminModel, AiModelOption, AiModelOptionValue, AiModelProfile, AiModelUpdate,
  AiNumericConstraint,
  AiProviderView} from './domain/ai-admin-policy';
import {applyModelProfile, clearModelProfile, constrainModelToProfile, editableModelState,
  AiModelValidationField, modelCommand, modelProfileValidationErrors,
  modelProfileValidationIssues, isImplicitUnsetEffort} from './domain/ai-model-profile-settings';
import {notifyAiAdminConflict, notifyAiAdminError,
  notifyAiAdminSuccess} from './domain/ai-admin-notifications';
import {httpErrorMessage, validModelUpdate} from './domain/ai-admin-validation';

// Defensive compatibility for profile responses produced before the server-side ownership filter.
const NON_EDITABLE_OPTION_KEYS = new Set([
  'model', 'deploymentName', 'maxTokens', 'maxCompletionTokens', 'temperature',
  'thinkingBudgetTokens', 'reasoningEffort', 'outputEffort',
  'apiKey', 'baseUrl', 'credential', 'microsoftFoundryServiceVersion',
  'organizationId', 'projectId', 'microsoftFoundry', 'gitHubModels', 'timeout',
  'maxRetries', 'proxy', 'customHeaders', 'httpHeaders', 'toolCallbacks', 'toolContext',
  'tools', 'contentLengthFunction', 'toolChoice', 'toolChoiceName',
  'disableParallelToolUse', 'parallelToolCalls', 'cacheToolResults', 'webSearchTool',
  'maxUses', 'allowedDomains', 'blockedDomains', 'userLocation'
]);

type TokenLimitField = 'contextWindow' | 'maxTokens' | 'outputReserveTokens' |
  'autoCompactThresholdTokens' | 'emergencyHeadroomTokens' | 'thinkingBudgetTokens';

@Component({
  standalone: false,
  selector: 'score-ai-model-detail',
  templateUrl: './ai-model-detail.component.html',
  styleUrls: ['./ai-admin-policy.component.css']
})
export class AiModelDetailComponent implements OnInit {
  @ViewChild('modelOptionsPanel') private modelOptionsPanel?: MatExpansionPanel;
  private readonly service = inject(AiAdminPolicyService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);

  providers: AiProviderView[] = [];
  modelProfiles: AiModelProfile[] = [];
  model?: AiAdminModel;
  isNew = true;
  loading = false;
  saving = false;
  modelProfilesLoading = false;
  providerLoadError = '';
  modelLoadError = '';
  modelProfileLoadError = '';
  modelOptionQuery = '';
  modelOptionsExpanded = false;
  private readonly jsonOptionDrafts = new Map<string, string>();
  private readonly jsonOptionErrors = new Map<string, string>();
  private modelProfileRequestId = 0;
  private modelRequestId = 0;
  private providerRequestId = 0;
  private baselineHash = '';
  form: AiModelUpdate = {
    expectedVersion: null,
    providerId: 0,
    modelKey: '',
    providerModelName: '',
    displayName: '',
    description: '',
    enabled: true,
    defaultModel: false,
    sortOrder: 0,
    maxTokens: 4096,
    contextWindow: 128000,
    outputReserveTokens: null,
    autoCompactThresholdTokens: null,
    emergencyHeadroomTokens: 4096,
    toolOutputTokenLimit: 32000,
    providerCompactionEnabled: true,
    temperature: null,
    thinkingBudgetTokens: null,
    adaptiveThinking: false,
    outputEffort: null,
    cacheStrategy: null,
    reasoningModelSupported: null,
    outputEffortSupported: null,
    verbositySupported: null,
    temperatureSupported: null,
    thinkingModes: [],
    defaultThinking: null,
    reasoningEfforts: [],
    modelOptions: {}
  };

  get loadError(): string {
    return this.modelLoadError || this.providerLoadError || this.modelProfileLoadError;
  }

  get selectedModelProfile(): AiModelProfile | undefined {
    return this.modelProfiles.find(profile => profile.modelKey === this.form.modelKey);
  }

  get selectedProvider(): AiProviderView | undefined {
    return this.providers.find(provider => provider.aiProviderId === this.form.providerId);
  }

  get modelOptions(): AiModelOption[] {
    return (this.selectedModelProfile?.options ?? [])
      .filter(option => !NON_EDITABLE_OPTION_KEYS.has(option.key))
      .sort((left, right) => this.optionLabel(left.key).localeCompare(
        this.optionLabel(right.key), 'en', {sensitivity: 'base'}));
  }

  get filteredModelOptions(): AiModelOption[] {
    const query = this.modelOptionQuery.trim().toLowerCase();
    if (!query) return this.modelOptions;
    return this.modelOptions.filter(option => [option.key, this.optionLabel(option.key),
      option.type, option.description, ...option.allowedValues]
      .some(value => value.toLowerCase().includes(query)));
  }

  get selectableProviders(): AiProviderView[] {
    return this.providers.filter(provider => provider.enabled ||
      !this.isNew && provider.aiProviderId === this.form.providerId);
  }

  get invalid(): boolean {
    const enabledProviderSelected = this.providers.some(provider => provider.enabled &&
      provider.aiProviderId === this.form.providerId);
    return !this.selectedModelProfile || !validModelUpdate(this.form)
      || !this.validForProfile || this.jsonOptionErrors.size > 0 || !enabledProviderSelected;
  }

  get isChanged(): boolean {
    return this.isNew || this.baselineHash !== hashCode(this.editableState());
  }

  get saveDisabled(): boolean {
    return this.loading || this.modelProfilesLoading || this.saving || !!this.loadError ||
      this.invalid || !this.isChanged;
  }

  get validationErrors(): string[] {
    const profile = this.selectedModelProfile;
    return profile ? modelProfileValidationErrors(this.form, profile) : [];
  }

  get availableReasoningEfforts(): AiModelProfile['reasoningEfforts'] {
    return (this.selectedModelProfile?.reasoningEfforts ?? [])
      .filter(effort => !isImplicitUnsetEffort(effort));
  }

  get defaultEffortName(): string | null {
    return this.form.reasoningEfforts.find(effort => effort.defaultEffort)?.name ?? null;
  }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    this.isNew = !id || id === 'new';
    this.loadProviders();
    if (!this.isNew) this.load();
  }

  setProvider(providerId: number): void {
    if (this.form.providerId === providerId) return;
    this.resetModelOptionEditor();
    this.form.providerId = providerId;
    this.form = clearModelProfile(this.form);
    this.modelProfiles = [];
    this.loadModelProfiles(providerId, true);
  }

  selectModel(modelKey: string): void {
    const profile = this.modelProfiles.find(candidate => candidate.modelKey === modelKey);
    if (!profile) return;
    this.resetModelOptionEditor();
    this.form = {...this.applyProfile(this.form, profile),
      modelOptions: this.defaultModelOptionValues(profile)};
  }

  isEffortEnabled(name: string): boolean {
    return this.form.reasoningEfforts.some(effort => effort.name === name);
  }

  isDefaultEffort(name: string): boolean {
    return this.form.reasoningEfforts.some(
      effort => effort.name === name && effort.defaultEffort);
  }

  setEffortEnabled(profileEffort: AiModelProfile['reasoningEfforts'][number],
                   enabled: boolean): void {
    if (enabled && !this.isEffortEnabled(profileEffort.name)) {
      if (this.selectedModelProfile?.capabilityConstraints.reasoningOptions.supported) {
        this.form.reasoningModelSupported = true;
      }
      this.form.reasoningEfforts.push({...profileEffort,
        defaultEffort: this.form.reasoningEfforts.length === 0,
        sortOrder: this.form.reasoningEfforts.length});
    } else if (!enabled) {
      const removedDefault = this.form.reasoningEfforts.find(
        effort => effort.name === profileEffort.name)?.defaultEffort;
      this.form.reasoningEfforts = this.form.reasoningEfforts.filter(
        effort => effort.name !== profileEffort.name);
      this.form.reasoningEfforts.forEach((effort, index) => effort.sortOrder = index);
      if (removedDefault && this.form.reasoningEfforts.length > 0) {
        this.form.reasoningEfforts[0].defaultEffort = true;
      }
      if (this.form.reasoningEfforts.length === 0
        && this.selectedModelProfile?.capabilityConstraints.reasoningOptions.supported) {
        this.form.reasoningModelSupported = false;
      }
    }
  }

  setDefaultEffort(name: string): void {
    this.form.reasoningEfforts.forEach(effort => effort.defaultEffort = effort.name === name);
  }

  optionLabel(key: string): string {
    const spaced = key.replace(/([a-z0-9])([A-Z])/g, '$1 $2');
    const label = spaced.charAt(0).toUpperCase() + spaced.slice(1);
    return label.replace(/\b(?:Ttl|Pdf|Id|Api|Url|Http|Json)\b/g, acronym => ({
      Ttl: 'TTL', Pdf: 'PDF', Id: 'ID', Api: 'API', Url: 'URL', Http: 'HTTP', Json: 'JSON'
    })[acronym] ?? acronym);
  }

  validationError(field: AiModelValidationField): string {
    const profile = this.selectedModelProfile;
    return profile ? modelProfileValidationIssues(this.form, profile)
      .find(issue => issue.field === field)?.message ?? '' : '';
  }

  validationErrorId(field: AiModelValidationField): string {
    return `model-${field.replace(/([A-Z])/g, '-$1').toLowerCase()}-error`;
  }

  tokenMinimum(field: TokenLimitField): number {
    return this.tokenConstraint(field)?.minimum ?? 0;
  }

  tokenMaximum(field: TokenLimitField): number {
    const constraint = this.tokenConstraint(field);
    const profileMaximum = constraint?.maximum ?? this.tokenMinimum(field);
    const constraints = this.selectedModelProfile?.configurationConstraints;
    const reserve = this.form.outputReserveTokens
      ?? constraints?.outputReserveTokens.defaultValue ?? 0;
    const safeInput = this.form.contextWindow - reserve - this.form.emergencyHeadroomTokens;
    const dependentMaximum = switchTokenMaximum(field, this.form.contextWindow,
      this.form.maxTokens ?? constraints?.maxOutputTokens.defaultValue, reserve,
      this.form.emergencyHeadroomTokens, safeInput);
    return Math.max(this.tokenMinimum(field), Math.min(profileMaximum, dependentMaximum));
  }

  setTokenInputValue(field: TokenLimitField, value: number | null): void {
    switch (field) {
      case 'contextWindow': this.form.contextWindow = value ?? 0; break;
      case 'maxTokens': this.form.maxTokens = value; break;
      case 'outputReserveTokens': this.form.outputReserveTokens = value; break;
      case 'autoCompactThresholdTokens': this.form.autoCompactThresholdTokens = value; break;
      case 'emergencyHeadroomTokens': this.form.emergencyHeadroomTokens = value ?? 0; break;
      case 'thinkingBudgetTokens': this.form.thinkingBudgetTokens = value; break;
    }
    this.normalizeManagedToolLimit();
  }

  optionInputValue(option: AiModelOption): boolean | number | string | null {
    const value = this.form.modelOptions[option.key];
    return typeof value === 'boolean' || typeof value === 'number' || typeof value === 'string'
      ? value : null;
  }

  booleanOptionValue(option: AiModelOption): string {
    const value = this.form.modelOptions[option.key];
    if (typeof value !== 'boolean') return '';
    return value ? 'true' : 'false';
  }

  setOptionValue(option: AiModelOption, value: boolean | number | string | null): void {
    if (value === '' || value === null) {
      this.updateModelOption(option.key, null);
      return;
    }
    if (option.type === 'boolean') {
      this.updateModelOption(option.key, value === true || value === 'true');
      return;
    }
    if (option.type === 'integer' || option.type === 'decimal') {
      const numericValue = typeof value === 'number' ? value : Number(value);
      const valid = Number.isFinite(numericValue)
        && (option.type !== 'integer' || Number.isSafeInteger(numericValue));
      this.updateModelOption(option.key, valid ? numericValue : null);
      return;
    }
    this.updateModelOption(option.key, value);
  }

  jsonOptionText(option: AiModelOption): string {
    const draft = this.jsonOptionDrafts.get(option.key);
    if (draft !== undefined) return draft;
    const value = this.form.modelOptions[option.key];
    return value !== undefined && value !== null ? JSON.stringify(value, null, 2) : '';
  }

  setJsonOptionValue(option: AiModelOption, value: string): void {
    this.jsonOptionDrafts.set(option.key, value);
    if (!value.trim()) {
      this.updateModelOption(option.key, null);
      this.jsonOptionErrors.delete(option.key);
      return;
    }
    try {
      const parsed: unknown = JSON.parse(value);
      this.updateModelOption(option.key, parsed as AiModelOptionValue);
      this.jsonOptionErrors.delete(option.key);
    } catch {
      this.jsonOptionErrors.set(option.key, 'Enter valid JSON.');
    }
  }

  jsonOptionError(key: string): string {
    return this.jsonOptionErrors.get(key) ?? '';
  }

  optionDescriptionId(key: string): string {
    return `profile-option-${key.replace(/[^a-zA-Z0-9_-]/g, '-')}-description`;
  }

  optionControlId(key: string): string {
    return `profile-option-${key.replace(/[^a-zA-Z0-9_-]/g, '-')}-control`;
  }

  optionErrorId(key: string): string {
    return `profile-option-${key.replace(/[^a-zA-Z0-9_-]/g, '-')}-error`;
  }

  optionAriaDescribedBy(option: AiModelOption): string {
    const descriptionId = this.optionDescriptionId(option.key);
    return this.jsonOptionError(option.key)
      ? `${descriptionId} ${this.optionErrorId(option.key)}` : descriptionId;
  }

  retryLoad(): void {
    this.loadProviders();
    if (!this.isNew) this.load();
  }

  save(): void {
    if (this.saving || !this.isChanged) return;
    if (this.invalid) {
      notifyAiAdminError(this.snackBar, 'Correct the invalid model settings before saving.');
      return;
    }

    this.saving = true;
    const wasNew = this.isNew;
    const update = this.command();
    const request = this.isNew
      ? this.service.createModel(update)
      : this.service.updateModel(this.model!.aiModelId, update);
    request.subscribe({
      next: model => {
        this.saving = false;
        this.apply(model);
        notifyAiAdminSuccess(this.snackBar, 'Model saved.');
        if (wasNew) void this.router.navigate(['/ai-admin/models', model.aiModelId]);
      },
      error: error => this.handleError(error)
    });
  }

  private load(): void {
    const requestId = ++this.modelRequestId;
    this.loading = true;
    this.modelLoadError = '';
    this.service.model(Number(this.route.snapshot.paramMap.get('id'))).subscribe({
      next: model => {
        if (requestId !== this.modelRequestId) return;
        this.loading = false;
        this.apply(model);
      },
      error: error => {
        if (requestId !== this.modelRequestId) return;
        this.loading = false;
        this.modelLoadError = httpErrorMessage(error, 'Model could not be loaded.');
      }
    });
  }

  private loadProviders(): void {
    const requestId = ++this.providerRequestId;
    this.providerLoadError = '';
    this.service.providers().subscribe({
      next: providers => {
        if (requestId !== this.providerRequestId) return;
        this.providers = providers;
        if (this.isNew && !this.form.providerId) {
          this.setProvider(providers.find(provider => provider.enabled)?.aiProviderId ?? 0);
        } else if (this.form.providerId) {
          this.loadModelProfiles(this.form.providerId, false);
        }
      },
      error: error => {
        if (requestId !== this.providerRequestId) return;
        this.providerLoadError = httpErrorMessage(error, 'Providers could not be loaded.');
      }
    });
  }

  private handleError(error: unknown): void {
    this.saving = false;
    if ((error as {status?: number})?.status === 409) {
      notifyAiAdminConflict(this.snackBar, 'The model changed elsewhere.', () => this.load());
      return;
    }
    notifyAiAdminError(this.snackBar, httpErrorMessage(error, 'Model save failed.'));
  }

  private apply(model: AiAdminModel): void {
    this.resetModelOptionEditor();
    this.model = model;
    this.isNew = false;
    this.form = {
      expectedVersion: model.catalogVersion,
      providerId: model.providerId,
      modelKey: model.modelKey,
      providerModelName: model.providerModelName,
      displayName: model.displayName,
      description: model.description,
      enabled: model.enabled,
      defaultModel: model.defaultModel,
      sortOrder: model.sortOrder,
      maxTokens: model.maxTokens,
      contextWindow: model.contextWindow,
      outputReserveTokens: model.outputReserveTokens,
      autoCompactThresholdTokens: model.autoCompactThresholdTokens,
      emergencyHeadroomTokens: model.emergencyHeadroomTokens,
      toolOutputTokenLimit: model.toolOutputTokenLimit,
      providerCompactionEnabled: model.providerCompactionEnabled,
      temperature: model.temperature,
      thinkingBudgetTokens: model.thinkingBudgetTokens,
      adaptiveThinking: model.adaptiveThinking,
      outputEffort: model.outputEffort,
      cacheStrategy: model.cacheStrategy,
      reasoningModelSupported: model.reasoningModelSupported,
      outputEffortSupported: model.outputEffortSupported,
      verbositySupported: model.verbositySupported,
      temperatureSupported: model.temperatureSupported,
      thinkingModes: [...model.thinkingModes],
      defaultThinking: model.defaultThinking,
      reasoningEfforts: model.reasoningEfforts.map(effort => ({...effort})),
      modelOptions: structuredClone(model.modelOptions ?? {})
    };
    this.baselineHash = hashCode(this.editableState());
    if (this.providers.some(provider => provider.aiProviderId === model.providerId)) {
      this.loadModelProfiles(model.providerId, false);
    }
  }

  private editableState(): object {
    return editableModelState(this.form);
  }

  private command() {
    return modelCommand(this.form);
  }

  private loadModelProfiles(providerId: number, selectDefault: boolean): void {
    if (!providerId) return;
    const requestId = ++this.modelProfileRequestId;
    this.modelProfilesLoading = true;
    this.modelProfileLoadError = '';
    this.service.modelProfiles(providerId).subscribe({
      next: profiles => {
        if (this.form.providerId !== providerId || requestId !== this.modelProfileRequestId) return;
        this.modelProfilesLoading = false;
        this.modelProfiles = profiles;
        const selected = profiles.find(profile => profile.modelKey === this.form.modelKey);
        if (selected && !selectDefault) {
          this.form = constrainModelToProfile(this.form, selected);
        }
        if (selectDefault && !selected && profiles.length > 0) this.selectModel(profiles[0].modelKey);
        if (!selectDefault && this.form.modelKey && !selected) {
          this.modelProfileLoadError = 'The configured model is not supported by this provider.';
        }
      },
      error: error => {
        if (this.form.providerId !== providerId || requestId !== this.modelProfileRequestId) return;
        this.modelProfilesLoading = false;
        this.modelProfileLoadError = httpErrorMessage(error, 'Model profiles could not be loaded.');
      }
    });
  }

  private applyProfile(form: AiModelUpdate, profile: AiModelProfile): AiModelUpdate {
    return applyModelProfile(form, profile);
  }

  private get validForProfile(): boolean {
    return this.validationErrors.length === 0;
  }

  private defaultModelOptionValues(profile: AiModelProfile): Record<string, AiModelOptionValue> {
    return Object.fromEntries(profile.options
      .filter(option => !NON_EDITABLE_OPTION_KEYS.has(option.key)
        && option.value !== null && option.value !== '')
      .map(option => [option.key, structuredClone(option.value)]));
  }

  private updateModelOption(key: string, value: AiModelOptionValue): void {
    const modelOptions = {...this.form.modelOptions};
    if (value === null || value === '') delete modelOptions[key];
    else modelOptions[key] = value;
    const cacheStrategy = key === 'cacheStrategy'
      ? typeof value === 'string' && value.toUpperCase() !== 'NONE'
        ? value.toLowerCase().replaceAll('_', '-') : null
      : this.form.cacheStrategy;
    const thinkingChanged = key === 'thinking' && typeof value === 'string';
    const thinking = thinkingChanged ? value as string : null;
    this.form = {...this.form, modelOptions, cacheStrategy,
      defaultThinking: thinkingChanged ? thinking : this.form.defaultThinking};
  }

  private tokenConstraint(field: TokenLimitField): AiNumericConstraint | undefined {
    const constraints = this.selectedModelProfile?.configurationConstraints;
    if (!constraints) return undefined;
    return field === 'maxTokens' ? constraints.maxOutputTokens : constraints[field];
  }

  private normalizeManagedToolLimit(): void {
    const reserve = this.form.outputReserveTokens
      ?? this.selectedModelProfile?.configurationConstraints.outputReserveTokens.defaultValue ?? 0;
    const safeInput = this.form.contextWindow - reserve - this.form.emergencyHeadroomTokens;
    const configuredDefault =
      this.selectedModelProfile?.configurationConstraints.toolOutputTokenLimit.defaultValue ?? 0;
    this.form.toolOutputTokenLimit = Math.max(1, Math.min(configuredDefault, safeInput));
  }

  private resetModelOptionEditor(): void {
    this.modelOptionsPanel?.close();
    this.modelOptionQuery = '';
    this.modelOptionsExpanded = false;
    this.jsonOptionDrafts.clear();
    this.jsonOptionErrors.clear();
  }
}

function switchTokenMaximum(field: TokenLimitField, contextWindow: number,
                            maxTokens: number | null, reserve: number,
                            headroom: number, safeInput: number): number {
  switch (field) {
    case 'contextWindow': return Number.MAX_SAFE_INTEGER;
    case 'maxTokens': return Math.max(0, contextWindow - 1);
    case 'outputReserveTokens': return Math.max(0, contextWindow - headroom - 1);
    case 'autoCompactThresholdTokens': return Math.max(0,
      Math.min(safeInput, contextWindow - 1));
    case 'emergencyHeadroomTokens': return Math.max(0, contextWindow - reserve - 1);
    case 'thinkingBudgetTokens': return Math.max(0, (maxTokens ?? 0) - 1);
  }
}
