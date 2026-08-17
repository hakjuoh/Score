import {Component, DestroyRef, OnDestroy, OnInit, inject, ChangeDetectionStrategy} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiProviderUpdate, AiProviderView} from './domain/ai-admin-policy';
import {MatSnackBar} from '@angular/material/snack-bar';
import {httpErrorMessage, validProviderUpdate} from './domain/ai-admin-validation';
import {hashCode} from '../../common/utility';
import {notifyAiAdminConflict, notifyAiAdminError,
  notifyAiAdminSuccess} from './domain/ai-admin-notifications';
import {faEye, faEyeSlash} from '@fortawesome/free-regular-svg-icons';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';

@Component({
  changeDetection: ChangeDetectionStrategy.Eager,
  standalone: false,
  selector: 'score-ai-provider-detail',
  templateUrl: './ai-provider-detail.component.html',
  styleUrls: ['./ai-admin-policy.component.css']
})
export class AiProviderDetailComponent implements OnInit, OnDestroy {
  private readonly service = inject(AiAdminPolicyService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  provider?: AiProviderView;
  isNew = true;
  loading = false;
  saving = false;
  testing = false;
  apiKeyLoading = false;
  apiKeyVisible = false;
  private baselineHash = '';
  private apiKeyValue = '';
  private maskedApiKey = '';
  private apiKeyEdited = false;
  private destroyed = false;
  readonly providerTypes = [
    {value: 'anthropic', label: 'Anthropic'},
    {value: 'openai', label: 'OpenAI'}
  ] as const;
  readonly visibilityIcon = faEye;
  readonly visibilityOffIcon = faEyeSlash;
  loadError = '';
  form: AiProviderUpdate = {providerName: '', providerType: 'openai',
    baseUrl: null, messagesUrl: null, apiVersion: null,
    enabled: false};

  get invalid(): boolean {
    return !validProviderUpdate(this.form);
  }

  get isChanged(): boolean {
    return this.isNew || this.apiKeyEdited
      || this.baselineHash !== hashCode(this.editableState());
  }

  get saveDisabled(): boolean {
    return this.loading || this.saving || this.testing || this.apiKeyLoading || !!this.loadError
      || (this.isNew ? this.invalid : !this.isChanged);
  }

  get testDisabled(): boolean {
    return this.isNew || this.loading || this.saving || this.testing || this.apiKeyLoading
      || this.invalid;
  }

  get apiKey(): string {
    return this.apiKeyValue;
  }

  set apiKey(value: string) {
    this.apiKeyValue = value;
    this.apiKeyEdited = true;
    this.apiKeyVisible = false;
  }

  get canRevealStoredApiKey(): boolean {
    return !this.isNew && !this.apiKeyEdited && !!this.provider?.apiKeyConfigured;
  }

  get isAnthropicProvider(): boolean {
    return this.form.providerType === 'anthropic';
  }

  setProviderType(providerType: 'anthropic' | 'openai'): void {
    if (this.form.providerType === providerType) return;
    this.form.providerType = providerType;
  }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    this.isNew = !id || id === 'new';
    if (!this.isNew) this.load();
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.apiKeyLoading = false;
    this.apiKeyVisible = false;
    this.apiKeyEdited = false;
    this.apiKeyValue = '';
    this.maskedApiKey = '';
  }

  toggleApiKeyVisibility(): void {
    if (this.destroyed || this.apiKeyLoading || !this.canRevealStoredApiKey) return;
    if (this.apiKeyVisible) {
      this.apiKeyVisible = false;
      if (!this.apiKeyEdited) this.apiKeyValue = this.maskedApiKey;
      return;
    }
    this.apiKeyLoading = true;
    this.service.revealProviderApiKey(this.provider!.aiProviderId)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: result => {
        this.apiKeyLoading = false;
        if (this.destroyed || this.apiKeyEdited) return;
        if (!result.revealed) {
          notifyAiAdminError(this.snackBar, 'The stored API key reveal response was invalid.');
          return;
        }
        this.apiKeyValue = result.value;
        this.apiKeyVisible = true;
      },
      error: error => {
        this.apiKeyLoading = false;
        notifyAiAdminError(this.snackBar,
          httpErrorMessage(error, 'The stored API key could not be revealed.'));
      }
    });
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
      this.saving = false; this.apply(provider);
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
    this.apiKeyLoading = false;
    if ((error as {status?: number})?.status === 409) {
      notifyAiAdminConflict(this.snackBar, 'The provider changed elsewhere.', () => this.load());
      return;
    }
    notifyAiAdminError(this.snackBar, httpErrorMessage(error, fallback));
  }

  private apply(provider: AiProviderView): void {
    this.provider = provider; this.isNew = false;
    this.apiKeyVisible = false;
    this.apiKeyEdited = false;
    this.apiKeyValue = '';
    this.maskedApiKey = '';
    this.form = {providerName: provider.providerName,
      providerType: provider.providerType,
      baseUrl: provider.baseUrl, messagesUrl: provider.messagesUrl,
      apiVersion: provider.apiVersion,
      enabled: provider.enabled};
    this.baselineHash = hashCode(this.editableState());
    if (provider.apiKeyConfigured) this.loadMaskedApiKey(provider.aiProviderId);
  }

  private loadMaskedApiKey(providerId: number): void {
    if (this.destroyed) return;
    this.apiKeyLoading = true;
    this.service.maskedProviderApiKey(providerId)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: result => {
        this.apiKeyLoading = false;
        if (this.destroyed || this.provider?.aiProviderId !== providerId
          || this.apiKeyEdited) return;
        if (result.revealed) {
          notifyAiAdminError(this.snackBar, 'The stored API key mask response was invalid.');
          return;
        }
        this.maskedApiKey = result.value;
        this.apiKeyValue = result.value;
      },
      error: error => {
        this.apiKeyLoading = false;
        notifyAiAdminError(this.snackBar,
          httpErrorMessage(error, 'The stored API key mask could not be loaded.'));
      }
    });
  }

  private editableState(): object {
    return this.form;
  }

  private requestPayload(): AiProviderUpdate {
    const unchangedKey = !this.isNew && !this.apiKeyEdited;
    const apiKey = unchangedKey ? undefined : this.apiKeyValue;
    return {...this.form, apiKey};
  }
}
