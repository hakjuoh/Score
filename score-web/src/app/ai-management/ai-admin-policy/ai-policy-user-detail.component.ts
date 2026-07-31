import {Component, OnInit, ViewChild, inject} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {forkJoin} from 'rxjs';
import {finalize} from 'rxjs/operators';
import {MatSort} from '@angular/material/sort';
import {MatSnackBar} from '@angular/material/snack-bar';
import {MatTableDataSource} from '@angular/material/table';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiAdminModel, AiAdminUsage, AiPolicyUpdate, AiPolicyView} from './domain/ai-admin-policy';
import {hashCode} from '../../common/utility';
import {notifyAiAdminConflict, notifyAiAdminError,
  notifyAiAdminSuccess} from './domain/ai-admin-notifications';

@Component({
  standalone: false,
  selector: 'score-ai-policy-user-detail',
  templateUrl: './ai-policy-user-detail.component.html',
  styleUrls: ['./ai-admin-policy.component.css']
})
export class AiPolicyUserDetailComponent implements OnInit {
  private readonly service = inject(AiAdminPolicyService);
  private readonly route = inject(ActivatedRoute);
  private readonly snackBar = inject(MatSnackBar);

  userId = '';
  models: AiAdminModel[] = [];
  policy?: AiPolicyView;
  saving = false;
  editingOverride = false;
  selectedModels = new Set<string>();
  quotaEnabled = false;
  usage?: AiAdminUsage;
  adjustment = 0;
  loadFailed = false;
  private baselineHash = '';
  readonly usageColumns = ['time', 'model', 'kind', 'status', 'charged'];
  readonly usageDataSource = new MatTableDataSource<AiAdminUsage['recentCalls'][number]>();

  @ViewChild('usageSort') set usageSort(sort: MatSort | undefined) {
    if (sort) this.usageDataSource.sort = sort;
  }

  constructor() {
    this.usageDataSource.sortingDataAccessor = (call, column) => {
      switch (column) {
        case 'time': return Date.parse(call.reservedAt);
        case 'model': return call.modelKey.toLowerCase();
        case 'kind': return call.executionKind.toLowerCase();
        case 'status': return call.status.toLowerCase();
        case 'charged': return call.chargedTokens;
        default: return '';
      }
    };
  }

  ngOnInit(): void {
    this.userId = this.route.snapshot.paramMap.get('id') ?? '';
    this.load();
  }

  private load(): void {
    this.loadFailed = false;
    forkJoin({models: this.service.models(), policy: this.service.policy(this.userId),
      usage: this.service.usage(this.userId)})
      .subscribe({
        next: ({models, policy, usage}) => {
          this.models = models.filter(model => model.enabled);
          this.applyUsage(usage);
          this.apply(policy);
        },
        error: () => this.loadFailed = true
      });
  }

  apply(policy: AiPolicyView): void {
    this.policy = policy;
    this.selectedModels = new Set(policy.effectiveAllowedModelKeys);
    this.quotaEnabled = !!policy.quota.period;
    this.editingOverride = !policy.inherited;
    this.baselineHash = hashCode(this.editableState());
  }

  beginOverride(): void {
    this.editingOverride = true;
  }

  modelChecked(modelKey: string): boolean {
    return this.selectedModels.has(modelKey);
  }

  setModel(modelKey: string, checked: boolean): void {
    if (checked) this.selectedModels.add(modelKey);
    else {
      this.selectedModels.delete(modelKey);
      if (this.policy) delete this.policy.allowedReasoningEfforts[modelKey];
    }
    if (this.policy?.effectiveDefaultModelKey &&
      !this.selectedModels.has(this.policy.effectiveDefaultModelKey)) {
      this.policy.effectiveDefaultModelKey = this.selectedModels.values().next().value ?? '';
    }
  }

  setQuotaEnabled(enabled: boolean): void {
    this.quotaEnabled = enabled;
    if (!this.policy) return;
    if (enabled) {
      this.policy.quota.period ??= 'MONTHLY';
      this.policy.quota.limitTokens ??= 1000000;
    } else {
      this.policy.quota.period = null;
      this.policy.quota.limitTokens = null;
    }
  }

  setOutputUnlimited(unlimited: boolean): void {
    if (this.policy) this.policy.maxOutputTokensPerCall = unlimited
      ? null : this.policy.maxOutputTokensPerCall ?? 4096;
  }

  setRequestUnlimited(unlimited: boolean): void {
    if (this.policy) this.policy.maxTotalTokensPerRequest = unlimited
      ? null : this.policy.maxTotalTokensPerRequest ?? 100000;
  }

  get quotaAdjustmentAvailable(): boolean {
    return !!this.usage?.quota?.period;
  }

  get isChanged(): boolean {
    return !!this.policy && this.baselineHash !== hashCode(this.editableState());
  }

  get updateDisabled(): boolean {
    return this.saving || !this.isChanged;
  }

  effortChecked(model: AiAdminModel, effort: string): boolean {
    const restricted = this.policy?.allowedReasoningEfforts[model.modelKey];
    return !restricted || restricted.includes(effort);
  }

  setEffort(model: AiAdminModel, effort: string, checked: boolean): void {
    if (!this.policy) return;
    let values = this.policy.allowedReasoningEfforts[model.modelKey];
    if (!values) values = model.reasoningEfforts.map(value => value.name);
    const next = new Set(values);
    if (checked) next.add(effort); else next.delete(effort);
    this.policy.allowedReasoningEfforts[model.modelKey] = [...next];
  }

  cancelActive(): void {
    if (this.saving) return;
    this.saving = true;
    this.service.cancelActiveRequests(this.userId).pipe(finalize(() => this.saving = false))
      .subscribe({next: result => {
        if (this.usage) this.usage.activeRequests = 0;
        notifyAiAdminSuccess(this.snackBar, `${result.cancelledRequests} active request(s) cancelled.`);
      }, error: () => notifyAiAdminError(this.snackBar, 'Active requests could not be cancelled.')});
  }

  adjustQuota(): void {
    if (this.saving) return;
    if (!Number.isSafeInteger(this.adjustment) || this.adjustment === 0) {
      notifyAiAdminError(this.snackBar, 'Enter a non-zero whole-number quota adjustment.');
      return;
    }
    this.saving = true;
    this.service.adjustQuota(this.userId, this.adjustment)
      .pipe(finalize(() => this.saving = false)).subscribe({next: usage => {
        this.applyUsage(usage);
        this.adjustment = 0;
        notifyAiAdminSuccess(this.snackBar, 'Quota adjusted.');
      }, error: () => notifyAiAdminError(this.snackBar, 'Quota could not be adjusted.')});
  }

  get invalid(): boolean {
    if (!this.policy) return true;
    if (this.policy.modelAccessMode === 'ALLOW_LIST' && this.policy.enabled &&
      this.selectedModels.size === 0) return true;
    if (this.policy.effectiveDefaultModelKey &&
      this.policy.modelAccessMode === 'ALLOW_LIST' &&
      !this.selectedModels.has(this.policy.effectiveDefaultModelKey)) return true;
    if (Object.values(this.policy.allowedReasoningEfforts)
      .some(efforts => efforts.length === 0)) return true;
    const positiveOptional = (value: number | null) => value === null ||
      Number.isSafeInteger(value) && value > 0;
    const quotaPairValid = this.quotaEnabled
      ? !!this.policy.quota.period && positiveOptional(this.policy.quota.limitTokens)
        && this.policy.quota.limitTokens !== null
      : this.policy.quota.period === null && this.policy.quota.limitTokens === null;
    return !Number.isSafeInteger(this.policy.maxAgentsPerRequest)
      || this.policy.maxAgentsPerRequest < 1 || this.policy.maxAgentsPerRequest > 4
      || !Number.isSafeInteger(this.policy.maxActiveRequests)
      || this.policy.maxActiveRequests < 1 || this.policy.maxActiveRequests > 32
      || !positiveOptional(this.policy.maxOutputTokensPerCall)
      || !positiveOptional(this.policy.maxTotalTokensPerRequest) || !quotaPairValid;
  }

  save(): void {
    if (!this.policy || !this.editingOverride || !this.isChanged) return;
    if (this.invalid) {
      notifyAiAdminError(this.snackBar, 'Correct the invalid policy settings before updating.');
      return;
    }
    const selectedModels = this.policy.modelAccessMode === 'ALL'
      ? new Set(this.models.map(model => model.modelKey)) : this.selectedModels;
    const allowedReasoningEfforts = Object.fromEntries(
      Object.entries(this.policy.allowedReasoningEfforts)
        .filter(([modelKey]) => selectedModels.has(modelKey))
    );
    const update: AiPolicyUpdate = {
      expectedVersion: this.policy.inherited ? null : this.policy.policyVersion,
      aiEnabled: this.policy.enabled,
      modelAccessMode: this.policy.modelAccessMode,
      defaultModelKey: this.policy.effectiveDefaultModelKey || null,
      allowedModelKeys: [...selectedModels],
      allowedReasoningEfforts,
      multiAgentEnabled: this.policy.multiAgentEnabled,
      maxAgentsPerRequest: this.policy.multiAgentEnabled
        ? this.policy.maxAgentsPerRequest : 1,
      maxActiveRequests: this.policy.maxActiveRequests,
      maxOutputTokensPerCall: this.policy.maxOutputTokensPerCall,
      maxTotalTokensPerRequest: this.policy.maxTotalTokensPerRequest,
      quotaPeriod: this.quotaEnabled ? this.policy.quota.period : null,
      quotaTokens: this.quotaEnabled ? this.policy.quota.limitTokens : null
    };
    this.saving = true;
    this.service.save(this.userId, update).pipe(finalize(() => this.saving = false))
      .subscribe({
        next: policy => {
          this.apply(policy);
          notifyAiAdminSuccess(this.snackBar, 'AI policy saved.');
        },
        error: error => {
          const conflict = error.status === 409;
          if (conflict) {
            notifyAiAdminConflict(this.snackBar,
              'The policy changed elsewhere. Reload before saving again.', () => this.reloadPolicy());
          } else notifyAiAdminError(this.snackBar, 'The AI policy could not be saved.');
        }
      });
  }

  reloadPolicy(): void {
    this.load();
  }

  reset(): void {
    if (!this.policy || this.policy.inherited) return;
    this.saving = true;
    this.service.reset(this.userId, this.policy.policyVersion)
      .pipe(finalize(() => this.saving = false)).subscribe({
      next: () => {
        notifyAiAdminSuccess(this.snackBar, 'Default AI policy restored.');
        this.reloadPolicy();
      },
      error: error => {
        if (error.status === 409) {
          notifyAiAdminConflict(this.snackBar,
            'The policy changed elsewhere. Reload before restoring the default policy.',
            () => this.reloadPolicy());
        } else notifyAiAdminError(this.snackBar, 'The AI policy could not be reset.');
      }
    });
  }

  private applyUsage(usage: AiAdminUsage): void {
    this.usage = usage;
    this.usageDataSource.data = usage.recentCalls;
  }

  private editableState(): object {
    if (!this.policy) return {};
    const selectedModels = [...this.selectedModels].sort();
    const allowedReasoningEfforts = Object.fromEntries(
      Object.entries(this.policy.allowedReasoningEfforts)
        .filter(([modelKey]) => selectedModels.includes(modelKey))
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([modelKey, efforts]) => [modelKey, [...efforts].sort()])
    );
    return {
      enabled: this.policy.enabled,
      modelAccessMode: this.policy.modelAccessMode,
      defaultModelKey: this.policy.effectiveDefaultModelKey || null,
      allowedModelKeys: selectedModels,
      allowedReasoningEfforts,
      multiAgentEnabled: this.policy.multiAgentEnabled,
      maxAgentsPerRequest: this.policy.multiAgentEnabled ? this.policy.maxAgentsPerRequest : 1,
      maxActiveRequests: this.policy.maxActiveRequests,
      maxOutputTokensPerCall: this.policy.maxOutputTokensPerCall,
      maxTotalTokensPerRequest: this.policy.maxTotalTokensPerRequest,
      quotaPeriod: this.quotaEnabled ? this.policy.quota.period : null,
      quotaTokens: this.quotaEnabled ? this.policy.quota.limitTokens : null
    };
  }
}
