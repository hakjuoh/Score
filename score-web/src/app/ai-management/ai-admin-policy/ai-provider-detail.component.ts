import {Component, OnInit, inject} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiProviderUpdate, AiProviderView} from './domain/ai-admin-policy';
import {MatSnackBar} from '@angular/material/snack-bar';
import {httpErrorMessage, validProviderUpdate} from './domain/ai-admin-validation';
import {hashCode} from '../../common/utility';
import {notifyAiAdminConflict, notifyAiAdminError,
  notifyAiAdminSuccess} from './domain/ai-admin-notifications';

@Component({
  standalone: false,
  selector: 'score-ai-provider-detail',
  templateUrl: './ai-provider-detail.component.html',
  styleUrls: ['./ai-admin-policy.component.css']
})
export class AiProviderDetailComponent implements OnInit {
  private static readonly STORED_KEY_PLACEHOLDER = '••••••••';
  private readonly service = inject(AiAdminPolicyService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  provider?: AiProviderView;
  isNew = true;
  loading = false;
  saving = false;
  testing = false;
  private baselineHash = '';
  private persistedProviderType = 'openai';
  readonly providerTypes = [
    {value: 'anthropic', label: 'Anthropic'},
    {value: 'openai', label: 'OpenAI'}
  ] as const;
  apiKey = '';
  loadError = '';
  form: AiProviderUpdate = {expectedVersion: null, providerName: '', providerType: 'openai',
    baseUrl: null, messagesUrl: null, anthropicVersion: null, apiVersion: null,
    enabled: false};

  get invalid(): boolean {
    return !validProviderUpdate(this.form);
  }

  get isChanged(): boolean {
    return this.isNew || this.baselineHash !== hashCode(this.editableState());
  }

  get saveDisabled(): boolean {
    return this.loading || this.saving || this.testing || !!this.loadError
      || (this.isNew ? this.invalid : !this.isChanged);
  }

  get testDisabled(): boolean {
    return this.isNew || this.loading || this.saving || this.testing || this.invalid;
  }

  get isAnthropicProvider(): boolean {
    return this.form.providerType === 'anthropic';
  }

  setProviderType(providerType: 'anthropic' | 'openai'): void {
    if (this.form.providerType === providerType) return;
    this.form.providerType = providerType;
    if (this.isAnthropicProvider) this.form.apiVersion = null;
    else this.form.anthropicVersion = null;
  }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    this.isNew = !id || id === 'new';
    if (!this.isNew) this.load();
  }

  save(): void {
    if (this.saving || this.testing || !this.isChanged) return;
    if (this.invalid) {
      notifyAiAdminError(this.snackBar, 'Correct the invalid provider settings before saving.');
      return;
    }
    this.saving = true;
    const wasNew = this.isNew;
    const payload = this.requestPayload();
    const request = this.isNew ? this.service.createProvider(payload)
      : this.service.updateProvider(this.provider!.aiProviderId, payload);
    request.subscribe({next: provider => {
      this.apiKey = ''; this.saving = false; this.apply(provider);
      notifyAiAdminSuccess(this.snackBar, 'Provider saved.');
      if (wasNew) void this.router.navigate(['/ai-admin/providers', provider.aiProviderId]);
    }, error: error => this.handleError(error, 'Provider save failed.')});
  }

  testConnection(): void {
    if (this.testDisabled) return;
    this.testing = true;
    this.service.testProviderConnection(this.provider!.aiProviderId, this.requestPayload())
      .subscribe({
        next: result => {
          this.testing = false;
          if (result.successful) notifyAiAdminSuccess(this.snackBar, result.message);
          else notifyAiAdminError(this.snackBar, result.message);
        },
        error: error => this.handleError(error, 'Provider connection test failed.')
      });
  }

  load(): void {
    this.loading = true;
    this.loadError = '';
    const id = Number(this.route.snapshot.paramMap.get('id'));
    this.service.provider(id).subscribe({
      next: provider => {
        this.loading = false;
        this.apply(provider);
      },
      error: error => {
        this.loading = false;
        this.loadError = httpErrorMessage(error, 'Provider could not be loaded.');
      }
    });
  }

  private handleError(error: unknown, fallback: string): void {
    this.saving = false;
    this.testing = false;
    if ((error as {status?: number})?.status === 409) {
      notifyAiAdminConflict(this.snackBar, 'The provider changed elsewhere.', () => this.load());
      return;
    }
    notifyAiAdminError(this.snackBar, httpErrorMessage(error, fallback));
  }

  private apply(provider: AiProviderView): void {
    this.provider = provider; this.isNew = false;
    this.persistedProviderType = provider.providerType;
    this.apiKey = provider.apiKeyConfigured
      ? AiProviderDetailComponent.STORED_KEY_PLACEHOLDER : '';
    this.form = {expectedVersion: provider.catalogVersion, providerName: provider.providerName,
      providerType: provider.providerType === 'azure-openai' ? 'openai' : provider.providerType,
      baseUrl: provider.baseUrl, messagesUrl: provider.messagesUrl,
      anthropicVersion: provider.anthropicVersion, apiVersion: provider.apiVersion,
      enabled: provider.enabled};
    this.baselineHash = hashCode(this.editableState());
  }

  private editableState(): object {
    const {expectedVersion: _expectedVersion, ...properties} = this.form;
    return {...properties, apiKey: this.apiKey};
  }

  private requestPayload(): AiProviderUpdate {
    const unchangedKey = !this.isNew
      && (this.apiKey === AiProviderDetailComponent.STORED_KEY_PLACEHOLDER
        || !this.provider?.apiKeyConfigured && !this.apiKey);
    const apiKey = unchangedKey ? undefined : this.apiKey;
    const providerType = this.persistedProviderType === 'azure-openai'
      && this.form.providerType === 'openai' ? this.persistedProviderType : this.form.providerType;
    return {...this.form, providerType, apiKey};
  }
}
