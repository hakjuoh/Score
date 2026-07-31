import {Component, OnInit, inject} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiAdminModel, AiModelUpdate, AiProviderView} from './domain/ai-admin-policy';
import {MatSnackBar} from '@angular/material/snack-bar';
import {httpErrorMessage, validModelUpdate} from './domain/ai-admin-validation';

@Component({standalone: false, selector: 'score-ai-model-detail', template: `
  <div class="context-section ai-admin-page"><mat-toolbar class="bg-white">
    <a mat-icon-button routerLink="/ai-admin/models"><mat-icon>arrow_back</mat-icon></a>
    <span class="title">{{isNew ? 'Add AI Model' : 'Manage AI Model'}}</span></mat-toolbar>
    <div class="p-3 container-fluid"><mat-card><mat-card-content><fieldset [disabled]="saving" class="admin-fieldset"><div class="policy-grid">
      <mat-form-field appearance="outline"><mat-label>Provider</mat-label><mat-select [(ngModel)]="form.providerId">
        @for (provider of selectableProviders; track provider.aiProviderId) {<mat-option [value]="provider.aiProviderId" [disabled]="!provider.enabled">{{provider.providerName}}{{provider.enabled ? '' : ' (disabled)'}}</mat-option>}
      </mat-select></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Model key</mat-label><input matInput [(ngModel)]="form.modelKey" [disabled]="!isNew"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Provider model/deployment</mat-label><input matInput [(ngModel)]="form.providerModelName"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Display name</mat-label><input matInput [(ngModel)]="form.displayName"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Description</mat-label><input matInput [(ngModel)]="form.description"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Sort order</mat-label><input matInput type="number" [(ngModel)]="form.sortOrder"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Max output tokens</mat-label><input matInput type="number" [(ngModel)]="form.maxTokens"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Context window</mat-label><input matInput type="number" [(ngModel)]="form.contextWindow"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Output reserve tokens</mat-label><input matInput type="number" [(ngModel)]="form.outputReserveTokens"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Auto-compact threshold</mat-label><input matInput type="number" [(ngModel)]="form.autoCompactThresholdTokens"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Emergency headroom</mat-label><input matInput type="number" [(ngModel)]="form.emergencyHeadroomTokens"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Tool output token limit</mat-label><input matInput type="number" [(ngModel)]="form.toolOutputTokenLimit"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Temperature</mat-label><input matInput type="number" step="0.1" [(ngModel)]="form.temperature"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Thinking token budget</mat-label><input matInput type="number" [(ngModel)]="form.thinkingBudgetTokens"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Output effort</mat-label><input matInput [(ngModel)]="form.outputEffort"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Cache strategy</mat-label><input matInput [(ngModel)]="form.cacheStrategy"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Thinking modes (comma-separated)</mat-label><input matInput [ngModel]="form.thinkingModes.join(', ')" (ngModelChange)="setThinkingModes($event)"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Default thinking mode</mat-label><input matInput [(ngModel)]="form.defaultThinking"></mat-form-field>
      <mat-checkbox [(ngModel)]="form.enabled">Enabled</mat-checkbox><mat-checkbox [(ngModel)]="form.defaultModel">Global default</mat-checkbox>
      <mat-checkbox [(ngModel)]="form.adaptiveThinking">Adaptive thinking</mat-checkbox>
      <mat-checkbox [(ngModel)]="form.providerCompactionEnabled">Provider compaction</mat-checkbox>
      <mat-checkbox [ngModel]="form.reasoningModelSupported === true" (ngModelChange)="form.reasoningModelSupported = $event">Reasoning options supported</mat-checkbox>
      <mat-checkbox [ngModel]="form.outputEffortSupported === true" (ngModelChange)="form.outputEffortSupported = $event">Output effort supported</mat-checkbox>
      <mat-checkbox [ngModel]="form.verbositySupported === true" (ngModelChange)="form.verbositySupported = $event">Verbosity supported</mat-checkbox>
      <mat-checkbox [ngModel]="form.temperatureSupported === true" (ngModelChange)="form.temperatureSupported = $event">Temperature supported</mat-checkbox>
      <mat-form-field appearance="outline" class="reason-field"><mat-label>Change reason</mat-label><input matInput [(ngModel)]="form.reason" minlength="10"></mat-form-field>
    </div><h3>Reasoning efforts</h3>
    @for (effort of form.reasoningEfforts; track $index) {<div class="policy-grid">
      <mat-form-field appearance="outline"><mat-label>Name</mat-label><input matInput [(ngModel)]="effort.name"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Display name</mat-label><input matInput [(ngModel)]="effort.displayName"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Description</mat-label><input matInput [(ngModel)]="effort.description"></mat-form-field>
      <mat-form-field appearance="outline"><mat-label>Sort order</mat-label><input matInput type="number" min="0" [(ngModel)]="effort.sortOrder"></mat-form-field>
      <mat-checkbox [(ngModel)]="effort.defaultEffort" (change)="makeDefault($index)">Default</mat-checkbox>
      <button mat-icon-button color="warn" (click)="removeEffort($index)"><mat-icon>delete</mat-icon></button></div>}
    <div class="actions"><button mat-stroked-button (click)="addEffort()">Add effort</button>
      <button mat-flat-button color="primary" (click)="save()" [disabled]="saving || invalid">Save</button></div>
    @if(message){<p role="status">{{message}}</p>}</fieldset></mat-card-content></mat-card></div></div>`,
  styleUrls: ['./ai-admin-policy.component.css']})
export class AiModelDetailComponent implements OnInit {
  private readonly service = inject(AiAdminPolicyService); private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router); private readonly snackBar = inject(MatSnackBar);
  providers: AiProviderView[] = []; model?: AiAdminModel;
  isNew = true; saving = false; message = '';
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
  save(): void { if (this.invalid || this.saving) return; this.saving = true; const wasNew = this.isNew; const request = this.isNew ? this.service.createModel(this.form) : this.service.updateModel(this.model!.aiModelId, this.form);
    request.subscribe({next: m => {this.saving = false; this.apply(m); this.message = 'Model saved.'; if (wasNew) void this.router.navigate(['/ai-admin/models', m.aiModelId]);}, error: error => this.handleError(error)}); }
  private load(): void { this.service.model(Number(this.route.snapshot.paramMap.get('id'))).subscribe({next: m => this.apply(m),
    error: error => this.message = httpErrorMessage(error, 'Model could not be loaded.')}); }
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
    reasoningEfforts: m.reasoningEfforts.map(e => ({...e})), reason: ''}; }
}
