import {Component, OnInit, inject} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {forkJoin} from 'rxjs';
import {finalize} from 'rxjs/operators';
import {Sort} from '@angular/material/sort';
import {PageEvent} from '@angular/material/paginator';
import {MatSnackBar} from '@angular/material/snack-bar';
import {MatTableDataSource} from '@angular/material/table';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiAdminModel, AiAdminUsage, AiPolicyUpdate, AiPolicyView} from './domain/ai-admin-policy';
import {hashCode} from '../../common/utility';
import {PageRequest} from '../../basis/basis';
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
  usageLoading = false;
  usageStart: Date | null = null;
  usageEnd: Date | null = null;
  selectedTabIndex = 0;
  usagePage = new PageRequest('time', 'desc', 0, 10);
  private usageLoadSequence = 0;
  private baselineHash = '';
  readonly usageColumns = ['time', 'model', 'kind', 'status', 'charged'];
  readonly usageDataSource = new MatTableDataSource<AiAdminUsage['recentCalls']['list'][number]>();

  ngOnInit(): void {
    this.userId = this.route.snapshot.paramMap.get('id') ?? '';
    this.load();
  }

  private load(): void {
    this.loadFailed = false;
    const usageSequence = ++this.usageLoadSequence;
    forkJoin({models: this.service.models(), policy: this.service.policy(this.userId),
      usage: this.service.usage(this.userId, this.usagePage, this.usageStart, this.usageEnd)})
      .subscribe({
        next: ({models, policy, usage}) => {
          this.models = models.filter(model => model.enabled);
          if (usageSequence === this.usageLoadSequence) this.applyUsage(usage);
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

  get selectedChargedTokens(): number {
    return this.usage?.periodUsage?.chargedTokens ?? 0;
  }

  get selectedPendingTokens(): number {
    return this.usage?.periodUsage?.reservedTokens ?? 0;
  }

  get selectedTokenActivity(): number {
    return this.selectedChargedTokens + this.selectedPendingTokens;
  }

  get selectedChargedPercent(): number {
    return this.percentOf(this.selectedChargedTokens, this.selectedTokenActivity);
  }

  get selectedPendingPercent(): number {
    return this.percentOf(this.selectedPendingTokens, this.selectedTokenActivity);
  }

  get quotaUsedTokens(): number {
    return this.usage?.quota?.consumedTokens ?? 0;
  }

  get quotaReservedTokens(): number {
    return this.usage?.quota?.reservedTokens ?? 0;
  }

  get quotaCommittedTokens(): number {
    return this.quotaUsedTokens + this.quotaReservedTokens;
  }

  get quotaLimitTokens(): number | null {
    return this.usage?.quota?.limitTokens ?? null;
  }

  get quotaAvailableTokens(): number | null {
    if (this.quotaLimitTokens === null) return null;
    return Math.max(this.quotaLimitTokens - this.quotaCommittedTokens, 0);
  }

  get quotaUsedPercent(): number {
    return this.percentOf(this.quotaUsedTokens, this.quotaLimitTokens ?? 0);
  }

  get quotaReservedPercent(): number {
    const remaining = Math.max(100 - this.quotaUsedPercent, 0);
    return Math.min(this.percentOf(this.quotaReservedTokens, this.quotaLimitTokens ?? 0), remaining);
  }

  get quotaAvailablePercent(): number {
    return Math.max(100 - this.quotaUsedPercent - this.quotaReservedPercent, 0);
  }

  get quotaProgressValue(): number {
    if (this.quotaLimitTokens === null) return 0;
    return Math.min(this.quotaCommittedTokens, this.quotaLimitTokens);
  }

  get quotaProgressDescription(): string {
    if (this.quotaLimitTokens === null) return 'No quota limit';
    const overLimit = Math.max(this.quotaCommittedTokens - this.quotaLimitTokens, 0);
    const description = `${this.quotaCommittedTokens} of ${this.quotaLimitTokens} tokens committed`;
    return overLimit > 0 ? `${description}; ${overLimit} tokens over the quota limit` : description;
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
        this.applyOperationalUsage(usage);
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

  get invalidUsageDateRange(): boolean {
    return !!this.usageStart && !!this.usageEnd &&
      this.usageStart.getTime() > this.usageEnd.getTime();
  }

  onUsagePeriodChange(): void {
    if (this.invalidUsageDateRange) return;
    this.usagePage.pageIndex = 0;
    this.loadUsage();
  }

  onUsageSort(sort: Sort): void {
    this.usagePage = new PageRequest(sort.active, sort.direction || 'desc', 0,
      this.usagePage.pageSize);
    this.loadUsage();
  }

  onUsagePage(event: PageEvent): void {
    this.usagePage = new PageRequest(this.usagePage.sortActive,
      this.usagePage.sortDirection, event.pageIndex, event.pageSize);
    this.loadUsage();
  }

  private loadUsage(): void {
    if (this.invalidUsageDateRange) return;
    const sequence = ++this.usageLoadSequence;
    this.usageLoading = true;
    this.service.usage(this.userId, this.usagePage, this.usageStart, this.usageEnd)
      .pipe(finalize(() => {
        if (sequence === this.usageLoadSequence) this.usageLoading = false;
      })).subscribe({
        next: usage => {
          if (sequence === this.usageLoadSequence) this.applyUsage(usage);
        },
        error: () => {
          if (sequence !== this.usageLoadSequence) return;
          notifyAiAdminError(this.snackBar, 'Usage could not be loaded.');
        }
      });
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
    this.usageDataSource.data = usage.recentCalls.list;
  }

  private applyOperationalUsage(usage: AiAdminUsage): void {
    if (!this.usage) {
      this.applyUsage(usage);
      return;
    }
    this.usage = {...this.usage, quota: usage.quota, activeRequests: usage.activeRequests};
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

  private percentOf(value: number, total: number): number {
    if (value <= 0 || total <= 0) return 0;
    return Math.min(value / total * 100, 100);
  }
}
