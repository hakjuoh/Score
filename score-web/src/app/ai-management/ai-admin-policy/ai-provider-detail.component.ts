import {Component, OnInit, inject} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiProviderUpdate, AiProviderView} from './domain/ai-admin-policy';
import {MatSnackBar} from '@angular/material/snack-bar';
import {httpErrorMessage, validProviderUpdate} from './domain/ai-admin-validation';
import {hashCode} from '../../common/utility';

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
  private baselineHash = '';
  apiKey = '';
  message = '';
  form: AiProviderUpdate = {expectedVersion: null, providerName: '', providerType: 'azure-openai',
    baseUrl: null, messagesUrl: null, anthropicVersion: null, apiVersion: null,
    enabled: false, reason: ''};

  get invalid(): boolean {
    return !validProviderUpdate(this.form);
  }

  get isChanged(): boolean {
    return this.isNew || this.baselineHash !== hashCode(this.editableState());
  }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    this.isNew = !id || id === 'new';
    if (!this.isNew) this.load();
  }

  save(): void {
    if (this.invalid || this.saving || !this.isChanged) return;
    this.saving = true; this.message = '';
    const wasNew = this.isNew;
    const unchangedKey = !this.isNew && (this.apiKey === AiProviderDetailComponent.STORED_KEY_PLACEHOLDER
      || !this.provider?.apiKeyConfigured && !this.apiKey);
    const apiKey = unchangedKey ? undefined : this.apiKey;
    const payload = {...this.form, apiKey};
    const request = this.isNew ? this.service.createProvider(payload)
      : this.service.updateProvider(this.provider!.aiProviderId, payload);
    request.subscribe({next: provider => {
      this.apiKey = ''; this.saving = false; this.apply(provider); this.message = 'Provider saved.';
      if (wasNew) void this.router.navigate(['/ai-admin/providers', provider.aiProviderId]);
    }, error: error => this.handleError(error, 'Provider save failed.')});
  }

  private load(): void {
    this.loading = true;
    const id = Number(this.route.snapshot.paramMap.get('id'));
    this.service.provider(id).subscribe({
      next: provider => {
        this.loading = false;
        this.apply(provider);
      },
      error: error => {
        this.loading = false;
        this.message = httpErrorMessage(error, 'Provider could not be loaded.');
      }
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
    this.apiKey = provider.apiKeyConfigured
      ? AiProviderDetailComponent.STORED_KEY_PLACEHOLDER : '';
    this.form = {expectedVersion: provider.catalogVersion, providerName: provider.providerName,
      providerType: provider.providerType, baseUrl: provider.baseUrl, messagesUrl: provider.messagesUrl,
      anthropicVersion: provider.anthropicVersion, apiVersion: provider.apiVersion,
      enabled: provider.enabled, reason: ''};
    this.baselineHash = hashCode(this.editableState());
  }

  private editableState(): object {
    const {expectedVersion: _expectedVersion, reason: _reason, ...properties} = this.form;
    return {...properties, apiKey: this.apiKey};
  }
}
