import {Component, OnInit, inject} from '@angular/core';
import {MatSnackBar} from '@angular/material/snack-bar';
import {ActivatedRoute, Router} from '@angular/router';
import {hashCode} from '../../common/utility';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiAdminModel, AiModelProfile, AiModelUpdate,
  AiProviderView} from './domain/ai-admin-policy';
import {applyModelProfile, clearModelProfile, constrainModelToProfile, editableModelState,
  modelCommand, modelProfileValidationErrors} from './domain/ai-model-profile-settings';
import {notifyAiAdminConflict, notifyAiAdminError,
  notifyAiAdminSuccess} from './domain/ai-admin-notifications';
import {httpErrorMessage, validModelUpdate} from './domain/ai-admin-validation';

@Component({
  standalone: false,
  selector: 'score-ai-model-detail',
  templateUrl: './ai-model-detail.component.html',
  styleUrls: ['./ai-admin-policy.component.css']
})
export class AiModelDetailComponent implements OnInit {
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
    reasoningEfforts: []
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

  get selectableProviders(): AiProviderView[] {
    return this.providers.filter(provider => provider.enabled ||
      !this.isNew && provider.aiProviderId === this.form.providerId);
  }

  get invalid(): boolean {
    const enabledProviderSelected = this.providers.some(provider => provider.enabled &&
      provider.aiProviderId === this.form.providerId);
    return !this.selectedModelProfile || !validModelUpdate(this.form)
      || !this.validForProfile || !enabledProviderSelected;
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
    this.form.providerId = providerId;
    this.form = clearModelProfile(this.form);
    this.modelProfiles = [];
    this.loadModelProfiles(providerId, true);
  }

  selectModel(modelKey: string): void {
    const profile = this.modelProfiles.find(candidate => candidate.modelKey === modelKey);
    if (!profile) return;
    this.form = this.applyProfile(this.form, profile);
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

  setReasoningModelEnabled(enabled: boolean): void {
    this.form.reasoningModelSupported = enabled;
    if (!enabled) this.form.reasoningEfforts = [];
  }

  setOutputEffortEnabled(enabled: boolean): void {
    this.form.outputEffortSupported = enabled;
    if (!enabled) this.form.outputEffort = null;
  }

  setTemperatureEnabled(enabled: boolean): void {
    this.form.temperatureSupported = enabled;
    if (!enabled) this.form.temperature = null;
  }

  isThinkingModeEnabled(mode: string): boolean {
    return this.form.thinkingModes.includes(mode);
  }

  setThinkingModeEnabled(mode: string, enabled: boolean): void {
    if (enabled && !this.form.thinkingModes.includes(mode)) {
      this.form.thinkingModes = [...this.form.thinkingModes, mode];
      if (!this.form.defaultThinking) this.form.defaultThinking = mode;
    } else if (!enabled) {
      this.form.thinkingModes = this.form.thinkingModes.filter(value => value !== mode);
      if (this.form.defaultThinking === mode) {
        this.form.defaultThinking = this.form.thinkingModes[0] ?? null;
      }
    }
    if (mode === 'adaptive') this.form.adaptiveThinking = enabled;
  }

  capabilityEnabled(value: boolean | null): boolean {
    return value === true;
  }

  get unsupportedCapabilities(): string[] {
    const profile = this.selectedModelProfile;
    if (!profile) return [];
    const unsupported: string[] = [];
    if (profile.reasoningEfforts.length === 0) unsupported.push('Reasoning Effort');
    if (!profile.capabilityConstraints.outputEffort.supported) unsupported.push('Output Effort');
    if (!profile.capabilityConstraints.verbosity.supported) unsupported.push('Verbosity');
    if (!profile.capabilityConstraints.temperature.supported) unsupported.push('Temperature');
    if (!profile.capabilityConstraints.providerCompaction.supported) {
      unsupported.push('Provider Compaction');
    }
    return unsupported;
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
      reasoningEfforts: model.reasoningEfforts.map(effort => ({...effort}))
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
}
