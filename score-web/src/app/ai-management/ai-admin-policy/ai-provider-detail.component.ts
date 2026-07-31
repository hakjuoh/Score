import {Component, OnInit, inject} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiProviderUpdate, AiProviderView} from './domain/ai-admin-policy';
import {ConfirmDialogService} from '../../common/confirm-dialog/confirm-dialog.service';
import {MatSnackBar} from '@angular/material/snack-bar';
import {httpErrorMessage, validProviderUpdate} from './domain/ai-admin-validation';

@Component({
  standalone: false,
  selector: 'score-ai-provider-detail',
  template: `
    <div class="context-section ai-admin-page">
      <mat-toolbar class="bg-white"><a mat-icon-button routerLink="/ai-admin/providers"><mat-icon>arrow_back</mat-icon></a>
        <span class="title">{{isNew ? 'Add AI Provider' : 'Manage AI Provider'}}</span></mat-toolbar>
      <div class="p-3 container-fluid"><mat-card><mat-card-content><fieldset [disabled]="saving" class="admin-fieldset">
        <div class="policy-grid">
          <mat-form-field appearance="outline"><mat-label>Provider name</mat-label><input matInput [(ngModel)]="form.providerName"></mat-form-field>
          <mat-form-field appearance="outline"><mat-label>Provider type</mat-label><mat-select [(ngModel)]="form.providerType">
            <mat-option value="anthropic">Anthropic</mat-option><mat-option value="azure-openai">Azure OpenAI</mat-option>
            <mat-option value="openai">OpenAI</mat-option></mat-select></mat-form-field>
          <mat-form-field appearance="outline"><mat-label>Base URL</mat-label><input matInput [(ngModel)]="form.baseUrl"></mat-form-field>
          <mat-form-field appearance="outline"><mat-label>Messages URL</mat-label><input matInput [(ngModel)]="form.messagesUrl"></mat-form-field>
          <mat-form-field appearance="outline"><mat-label>Anthropic version</mat-label><input matInput [(ngModel)]="form.anthropicVersion"></mat-form-field>
          <mat-form-field appearance="outline"><mat-label>API version</mat-label><input matInput [(ngModel)]="form.apiVersion"></mat-form-field>
          <mat-checkbox [(ngModel)]="form.enabled">Enabled</mat-checkbox>
          <mat-form-field appearance="outline" class="reason-field"><mat-label>Change reason</mat-label>
            <input matInput [(ngModel)]="form.reason" minlength="10"></mat-form-field>
        </div>
        @if (isNew) {
          <mat-form-field appearance="outline" class="reason-field"><mat-label>API key (optional)</mat-label>
            <input matInput type="password" [(ngModel)]="apiKey" autocomplete="new-password"></mat-form-field>
        }
        <div class="actions"><button mat-flat-button color="primary" (click)="save()" [disabled]="saving || invalid">Save</button></div>
        @if (!isNew && provider) {
          <hr><h3>API key</h3><p>{{provider.apiKeyConfigured ? 'Configured' : 'Not configured'}}</p>
          <mat-form-field appearance="outline" class="reason-field"><mat-label>New API key</mat-label>
            <input matInput type="password" [(ngModel)]="apiKey" autocomplete="new-password"></mat-form-field>
          <div class="actions"><button mat-stroked-button (click)="confirmRotateKey()" [disabled]="!apiKey.trim() || form.reason.trim().length < 10 || saving">Replace key</button>
            <button mat-stroked-button color="warn" (click)="confirmRemoveKey()" [disabled]="!provider.apiKeyConfigured || form.reason.trim().length < 10 || saving">Remove key</button></div>
        }
        @if (message) { <p class="mt-3" role="status">{{message}}</p> }
      </fieldset></mat-card-content></mat-card></div>
    </div>`,
  styleUrls: ['./ai-admin-policy.component.css']
})
export class AiProviderDetailComponent implements OnInit {
  private readonly service = inject(AiAdminPolicyService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly confirmDialog = inject(ConfirmDialogService);
  private readonly snackBar = inject(MatSnackBar);
  provider?: AiProviderView;
  isNew = true;
  saving = false;
  apiKey = '';
  message = '';
  form: AiProviderUpdate = {expectedVersion: null, providerName: '', providerType: 'azure-openai',
    baseUrl: null, messagesUrl: null, anthropicVersion: null, apiVersion: null,
    enabled: false, reason: ''};

  get invalid(): boolean {
    return !validProviderUpdate(this.form);
  }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    this.isNew = !id || id === 'new';
    if (!this.isNew) this.load();
  }

  save(): void {
    if (this.invalid) return;
    this.saving = true; this.message = '';
    const wasNew = this.isNew;
    const payload = {...this.form, apiKey: this.isNew && this.apiKey ? this.apiKey : undefined};
    const request = this.isNew ? this.service.createProvider(payload)
      : this.service.updateProvider(this.provider!.aiProviderId, payload);
    request.subscribe({next: provider => {
      this.apiKey = ''; this.saving = false; this.apply(provider); this.message = 'Provider saved.';
      if (wasNew) void this.router.navigate(['/ai-admin/providers', provider.aiProviderId]);
    }, error: error => this.handleError(error, 'Provider save failed.')});
  }

  rotateKey(): void {
    if (!this.provider || !this.apiKey.trim() || this.form.reason.trim().length < 10) return;
    this.saving = true;
    this.service.rotateProviderKey(this.provider.aiProviderId, this.provider.catalogVersion,
      this.apiKey, this.form.reason).subscribe({next: provider => {
        this.apiKey = ''; this.saving = false; this.apply(provider); this.message = 'API key replaced.';
      }, error: error => this.handleError(error, 'API key replacement failed.')});
  }

  confirmRotateKey(): void {
    if (!this.provider || !this.apiKey.trim() || this.form.reason.trim().length < 10) return;
    const config = this.confirmDialog.newConfig();
    config.data.header = 'Replace provider API key?';
    config.data.content = ['The previous key will stop being used for all new AI requests.'];
    config.data.action = 'Replace key';
    this.confirmDialog.open(config).afterClosed().subscribe(confirmed => {
      if (confirmed) this.rotateKey();
    });
  }

  removeKey(): void {
    if (!this.provider || this.form.reason.trim().length < 10) return;
    this.saving = true;
    this.service.removeProviderKey(this.provider.aiProviderId, this.provider.catalogVersion,
      this.form.reason).subscribe({next: provider => {
        this.apiKey = ''; this.saving = false; this.apply(provider); this.message = 'API key removed.';
      }, error: error => this.handleError(error, 'API key removal failed.')});
  }

  confirmRemoveKey(): void {
    if (!this.provider || !this.provider.apiKeyConfigured || this.form.reason.trim().length < 10) return;
    const config = this.confirmDialog.newConfig();
    config.data.header = 'Remove provider API key?';
    config.data.content = ['New AI requests using this provider will be unavailable until a key is configured.'];
    config.data.action = 'Remove key';
    this.confirmDialog.open(config).afterClosed().subscribe(confirmed => {
      if (confirmed) this.removeKey();
    });
  }

  private load(): void {
    const id = Number(this.route.snapshot.paramMap.get('id'));
    this.service.provider(id).subscribe({
      next: provider => this.apply(provider),
      error: error => this.message = httpErrorMessage(error, 'Provider could not be loaded.')
    });
  }

  private handleError(error: unknown, fallback: string): void {
    this.saving = false;
    this.message = httpErrorMessage(error, fallback);
    if ((error as {status?: number})?.status !== 409) return;
    const notice = this.snackBar.open('The provider changed elsewhere.', 'Reload', {duration: 5000});
    notice.onAction().subscribe(() => this.load());
  }

  private apply(provider: AiProviderView): void {
    this.provider = provider; this.isNew = false;
    this.form = {expectedVersion: provider.catalogVersion, providerName: provider.providerName,
      providerType: provider.providerType, baseUrl: provider.baseUrl, messagesUrl: provider.messagesUrl,
      anthropicVersion: provider.anthropicVersion, apiVersion: provider.apiVersion,
      enabled: provider.enabled, reason: ''};
  }
}
