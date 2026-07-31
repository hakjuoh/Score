import {Component, OnInit, inject} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiAdminModel, AiModelUpdate, AiProviderView} from './domain/ai-admin-policy';
import {MatSnackBar} from '@angular/material/snack-bar';
import {httpErrorMessage, validModelUpdate} from './domain/ai-admin-validation';
import {hashCode} from '../../common/utility';

@Component({standalone: false, selector: 'score-ai-model-detail',
  templateUrl: './ai-model-detail.component.html',
  styleUrls: ['./ai-admin-policy.component.css']})
export class AiModelDetailComponent implements OnInit {
  private readonly service = inject(AiAdminPolicyService); private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router); private readonly snackBar = inject(MatSnackBar);
  providers: AiProviderView[] = []; model?: AiAdminModel;
  isNew = true; loading = false; saving = false; message = '';
  private baselineHash = '';
  form: AiModelUpdate = {expectedVersion: null, providerId: 0, modelKey: '', providerModelName: '',
    displayName: '', description: '', enabled: true, defaultModel: false, sortOrder: 0,
    maxTokens: 4096, contextWindow: 128000, outputReserveTokens: null,
    autoCompactThresholdTokens: null, emergencyHeadroomTokens: 4096,
    toolOutputTokenLimit: 32000, providerCompactionEnabled: true, temperature: null,
    thinkingBudgetTokens: null, adaptiveThinking: false, outputEffort: null,
    cacheStrategy: null, reasoningModelSupported: null, outputEffortSupported: null,
    verbositySupported: null, temperatureSupported: null, thinkingModes: [],
    defaultThinking: null, reasoningEfforts: [], reason: ''};
  ngOnInit(): void { this.service.providers().subscribe({next: p => {this.providers = p; if (!this.form.providerId) this.form.providerId = p.find(provider => provider.enabled)?.aiProviderId ?? 0;},
    error: error => this.message = httpErrorMessage(error, 'Providers could not be loaded.')});
    const id = this.route.snapshot.paramMap.get('id'); this.isNew = !id || id === 'new'; if (!this.isNew) this.load(); }
  addEffort(): void { this.form.reasoningEfforts.push({name: '', displayName: '', description: '', defaultEffort: this.form.reasoningEfforts.length === 0, sortOrder: this.form.reasoningEfforts.length}); }
  removeEffort(index: number): void { this.form.reasoningEfforts.splice(index, 1); }
  makeDefault(index: number): void { this.form.reasoningEfforts.forEach((e, i) => e.defaultEffort = i === index); }
  setThinkingModes(value: string): void { this.form.thinkingModes = value.split(',').map(v => v.trim()).filter(Boolean); }
  get selectableProviders(): AiProviderView[] {
    return this.providers.filter(provider => provider.enabled || (!this.isNew && provider.aiProviderId === this.form.providerId));
  }
  get invalid(): boolean {
    return !validModelUpdate(this.form) || !this.providers.some(provider => provider.enabled && provider.aiProviderId === this.form.providerId);
  }
  get isChanged(): boolean { return this.isNew || this.baselineHash !== hashCode(this.editableState()); }
  save(): void { if (this.invalid || this.saving || !this.isChanged) return; this.saving = true; const wasNew = this.isNew; const request = this.isNew ? this.service.createModel(this.form) : this.service.updateModel(this.model!.aiModelId, this.form);
    request.subscribe({next: m => {this.saving = false; this.apply(m); this.message = 'Model saved.'; if (wasNew) void this.router.navigate(['/ai-admin/models', m.aiModelId]);}, error: error => this.handleError(error)}); }
  private load(): void {
    this.loading = true;
    this.service.model(Number(this.route.snapshot.paramMap.get('id'))).subscribe({
      next: model => {
        this.loading = false;
        this.apply(model);
      },
      error: error => {
        this.loading = false;
        this.message = httpErrorMessage(error, 'Model could not be loaded.');
      }
    });
  }
  private handleError(error: unknown): void { this.saving = false; this.message = httpErrorMessage(error, 'Model save failed.');
    if ((error as {status?: number})?.status !== 409) return;
    const notice = this.snackBar.open('The model changed elsewhere.', 'Reload', {duration: 5000});
    notice.onAction().subscribe(() => this.load()); }
  private apply(m: AiAdminModel): void { this.model = m; this.isNew = false; this.form = {expectedVersion: m.catalogVersion, providerId: m.providerId, modelKey: m.modelKey,
    providerModelName: m.providerModelName, displayName: m.displayName, description: m.description,
    enabled: m.enabled, defaultModel: m.defaultModel, sortOrder: m.sortOrder, maxTokens: m.maxTokens,
    contextWindow: m.contextWindow, outputReserveTokens: m.outputReserveTokens,
    autoCompactThresholdTokens: m.autoCompactThresholdTokens, emergencyHeadroomTokens: m.emergencyHeadroomTokens,
    toolOutputTokenLimit: m.toolOutputTokenLimit, providerCompactionEnabled: m.providerCompactionEnabled,
    temperature: m.temperature, thinkingBudgetTokens: m.thinkingBudgetTokens,
    adaptiveThinking: m.adaptiveThinking, outputEffort: m.outputEffort,
    cacheStrategy: m.cacheStrategy, reasoningModelSupported: m.reasoningModelSupported,
    outputEffortSupported: m.outputEffortSupported, verbositySupported: m.verbositySupported,
    temperatureSupported: m.temperatureSupported, thinkingModes: [...m.thinkingModes],
    defaultThinking: m.defaultThinking,
    reasoningEfforts: m.reasoningEfforts.map(e => ({...e})), reason: ''};
    this.baselineHash = hashCode(this.editableState()); }
  private editableState(): object {
    const {expectedVersion: _expectedVersion, reason: _reason, ...properties} = this.form;
    return properties;
  }
}
