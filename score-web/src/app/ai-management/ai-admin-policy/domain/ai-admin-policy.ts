import {PageRequest} from '../../../basis/basis';
import {HttpParams} from '@angular/common/http';
import {ParamMap} from '@angular/router';
import {base64Decode, base64Encode} from '../../../common/utility';

export interface AiPolicyUserSummary {
  userId: string;
  loginId: string;
  name: string;
  organization: string;
  inherited: boolean;
  enabled: boolean;
  multiAgentEnabled: boolean;
  allowedModelCount: number;
  availableModels: string[];
  quotaLimitTokens: number | null;
  quotaConsumedTokens: number;
  quotaReservedTokens: number;
  quotaRemainingTokens: number | null;
  activeRequests: number;
  updaterLoginId: string | null;
  lastUpdatedAt: string | null;
}

export interface AiReasoningEffort {
  name: string;
  displayName: string;
  description: string;
  defaultEffort: boolean;
  sortOrder: number;
}

export interface AiNumericConstraint {
  defaultValue: number | null;
  minimum: number | null;
  maximum: number | null;
  optional: boolean;
}

export interface AiModelConfigurationConstraints {
  contextWindow: AiNumericConstraint;
  maxOutputTokens: AiNumericConstraint;
  outputReserveTokens: AiNumericConstraint;
  autoCompactThresholdTokens: AiNumericConstraint;
  emergencyHeadroomTokens: AiNumericConstraint;
  toolOutputTokenLimit: AiNumericConstraint;
  thinkingBudgetTokens: AiNumericConstraint;
  temperature: AiNumericConstraint;
}

export interface AiCapabilityConstraint {
  supported: boolean;
  defaultEnabled: boolean;
}

export type AiModelOptionType = 'boolean' | 'integer' | 'decimal' | 'string' | 'json' | 'enum';

export interface AiModelOption {
  key: string;
  type: AiModelOptionType;
  value: AiModelOptionValue;
  description: string;
  allowedValues: string[];
}

export type AiModelOptionValue = boolean | number | string |
  Record<string, unknown> | unknown[] | null;

export interface AiModelCapabilityConstraints {
  reasoningOptions: AiCapabilityConstraint;
  outputEffort: AiCapabilityConstraint;
  verbosity: AiCapabilityConstraint;
  temperature: AiCapabilityConstraint;
  adaptiveThinking: AiCapabilityConstraint;
  providerCompaction: AiCapabilityConstraint;
}

export interface AiModelProfile {
  providerType: string;
  modelKey: string;
  providerModelName: string;
  displayName: string;
  description: string;
  maxTokens: number | null;
  contextWindow: number;
  maxOutputTokens: number | null;
  maxContextWindow: number;
  outputReserveTokens: number | null;
  autoCompactThresholdTokens: number | null;
  emergencyHeadroomTokens: number;
  toolOutputTokenLimit: number;
  providerCompactionEnabled: boolean;
  temperature: number | null;
  thinkingBudgetTokens: number | null;
  minThinkingBudgetTokens: number | null;
  maxThinkingBudgetTokens: number | null;
  adaptiveThinking: boolean;
  outputEffort: string | null;
  cacheStrategy: string | null;
  reasoningModelSupported: boolean | null;
  outputEffortSupported: boolean | null;
  verbositySupported: boolean | null;
  temperatureSupported: boolean | null;
  thinkingModes: string[];
  defaultThinking: string | null;
  reasoningEfforts: AiReasoningEffort[];
  options: AiModelOption[];
  chatCompletionsCompatible: boolean;
  configurationConstraints: AiModelConfigurationConstraints;
  capabilityConstraints: AiModelCapabilityConstraints;
}

export interface AiAdminModel {
  aiModelId: number;
  modelKey: string;
  displayName: string;
  provider: string;
  providerId: number;
  providerModelName: string;
  description: string;
  enabled: boolean;
  defaultModel: boolean;
  sortOrder: number;
  maxTokens: number | null;
  contextWindow: number;
  outputReserveTokens: number | null;
  autoCompactThresholdTokens: number | null;
  emergencyHeadroomTokens: number;
  toolOutputTokenLimit: number;
  providerCompactionEnabled: boolean;
  temperature: number | null;
  thinkingBudgetTokens: number | null;
  adaptiveThinking: boolean;
  outputEffort: string | null;
  cacheStrategy: string | null;
  reasoningModelSupported: boolean | null;
  outputEffortSupported: boolean | null;
  verbositySupported: boolean | null;
  temperatureSupported: boolean | null;
  thinkingModes: string[];
  defaultThinking: string | null;
  reasoningEfforts: AiReasoningEffort[];
  modelOptions: Record<string, AiModelOptionValue>;
  updaterLoginId: string | null;
  lastUpdatedAt: string | null;
}

export interface AiModelUpdate {
  providerId: number;
  modelKey: string;
  providerModelName: string;
  displayName: string;
  description: string;
  enabled: boolean;
  defaultModel: boolean;
  sortOrder: number;
  maxTokens: number | null;
  contextWindow: number;
  outputReserveTokens: number | null;
  autoCompactThresholdTokens: number | null;
  emergencyHeadroomTokens: number;
  toolOutputTokenLimit: number;
  providerCompactionEnabled: boolean;
  temperature: number | null;
  thinkingBudgetTokens: number | null;
  adaptiveThinking: boolean;
  outputEffort: string | null;
  cacheStrategy: string | null;
  reasoningModelSupported: boolean | null;
  outputEffortSupported: boolean | null;
  verbositySupported: boolean | null;
  temperatureSupported: boolean | null;
  thinkingModes: string[];
  defaultThinking: string | null;
  reasoningEfforts: AiReasoningEffort[];
  modelOptions: Record<string, AiModelOptionValue>;
}

export interface AiModelCommand {
  providerId: number;
  modelKey: string;
  enabled: boolean;
  defaultModel: boolean;
  sortOrder: number;
  maxTokens: number | null;
  contextWindow: number;
  outputReserveTokens: number | null;
  autoCompactThresholdTokens: number | null;
  emergencyHeadroomTokens: number;
  toolOutputTokenLimit: number;
  providerCompactionEnabled: boolean;
  temperature: number | null;
  thinkingBudgetTokens: number | null;
  adaptiveThinking: boolean;
  outputEffort: string | null;
  cacheStrategy: string | null;
  reasoningModelSupported: boolean | null;
  outputEffortSupported: boolean | null;
  verbositySupported: boolean | null;
  temperatureSupported: boolean | null;
  thinkingModes: string[];
  defaultThinking: string | null;
  reasoningEfforts: Array<{name: string; defaultEffort: boolean; sortOrder: number}>;
  modelOptions: Record<string, AiModelOptionValue>;
}

export interface AiAdminUsage {
  quota: AiQuotaView;
  activeRequests: number;
  periodUsage: {chargedTokens: number; reservedTokens: number; modelCalls: number;
    start: string | null; end: string | null};
  recentCalls: {list: Array<{callId: string; modelKey: string; executionKind: string; agentId: string | null;
    reservedTokens: number; chargedTokens: number; usageComplete: boolean; status: string;
    failureType: string | null; reservedAt: string; settledAt: string | null}>;
    page: number; size: number; length: number};
}

export interface AiQuotaView {
  period: 'DAILY' | 'MONTHLY' | null;
  limitTokens: number | null;
  consumedTokens: number;
  reservedTokens: number;
  remainingTokens: number | null;
  periodStart: string | null;
  periodEnd: string | null;
}

export interface AiPolicyView {
  userId: string;
  inherited: boolean;
  policyVersion: number;
  enabled: boolean;
  modelAccessMode: 'ALL' | 'ALLOW_LIST';
  effectiveDefaultModelKey: string;
  effectiveAllowedModelKeys: string[];
  allowedReasoningEfforts: Record<string, string[]>;
  multiAgentEnabled: boolean;
  maxAgentsPerRequest: number;
  maxActiveRequests: number;
  hasActiveRequests: boolean;
  maxOutputTokensPerCall: number | null;
  maxTotalTokensPerRequest: number | null;
  quota: AiQuotaView;
}

export interface AiPolicyUpdate {
  expectedVersion: number | null;
  aiEnabled: boolean;
  modelAccessMode: 'ALL' | 'ALLOW_LIST';
  defaultModelKey: string | null;
  allowedModelKeys: string[];
  allowedReasoningEfforts: Record<string, string[]>;
  multiAgentEnabled: boolean;
  maxAgentsPerRequest: number;
  maxActiveRequests: number;
  maxOutputTokensPerCall: number | null;
  maxTotalTokensPerRequest: number | null;
  quotaPeriod: 'DAILY' | 'MONTHLY' | null;
  quotaTokens: number | null;
}

export interface AiProviderView {
  aiProviderId: number;
  providerName: string;
  providerType: string;
  baseUrl: string | null;
  messagesUrl: string | null;
  apiVersion: string | null;
  enabled: boolean;
  apiKeyConfigured: boolean;
  updaterLoginId: string | null;
  lastUpdatedAt: string | null;
}

export interface AiProviderUpdate {
  providerName: string;
  providerType: string;
  baseUrl: string | null;
  messagesUrl: string | null;
  apiVersion: string | null;
  enabled: boolean;
  apiKey?: string;
}

export class AiProviderListRequest {
  filters = {
    name: '', type: '', endpoint: '', enabled: [] as boolean[],
    updaterLoginIdList: [] as string[], updatedAfter: null as Date | null,
    updatedBefore: null as Date | null
  };
  page = new PageRequest('updatedOn', 'desc', 0, 10);

  constructor(paramMap?: ParamMap) {
    const params = aiListParams(paramMap);
    this.page = aiListPage(params);
    this.filters = {
      name: params.get('name') || '',
      type: params.get('type') || '',
      endpoint: params.get('endpoint') || '',
      enabled: booleanList(params, 'enabled'),
      updaterLoginIdList: stringList(params, 'updaterLoginIdList'),
      updatedAfter: dateValue(params, 'updatedAfter'),
      updatedBefore: dateValue(params, 'updatedBefore')
    };
  }

  toQuery(): string {
    let params = aiListPageParams(this.page);
    params = setText(params, 'name', this.filters.name);
    params = setText(params, 'type', this.filters.type);
    params = setText(params, 'endpoint', this.filters.endpoint);
    params = setList(params, 'enabled', this.filters.enabled);
    params = setList(params, 'updaterLoginIdList', this.filters.updaterLoginIdList);
    params = setDate(params, 'updatedAfter', this.filters.updatedAfter);
    params = setDate(params, 'updatedBefore', this.filters.updatedBefore);
    return encodedAiListQuery(params);
  }
}

export class AiModelListRequest {
  filters = {
    model: '', provider: '', enabled: [] as boolean[],
    defaultModel: [] as boolean[], defaultEffort: '', effort: '',
    updaterLoginIdList: [] as string[], updatedAfter: null as Date | null,
    updatedBefore: null as Date | null
  };
  page = new PageRequest('updatedOn', 'desc', 0, 10);

  constructor(paramMap?: ParamMap) {
    const params = aiListParams(paramMap);
    this.page = aiListPage(params);
    this.filters = {
      model: params.get('model') || '',
      provider: params.get('provider') || '',
      enabled: booleanList(params, 'enabled'),
      defaultModel: booleanList(params, 'defaultModel'),
      defaultEffort: params.get('defaultEffort') || '',
      effort: params.get('effort') || '',
      updaterLoginIdList: stringList(params, 'updaterLoginIdList'),
      updatedAfter: dateValue(params, 'updatedAfter'),
      updatedBefore: dateValue(params, 'updatedBefore')
    };
  }

  toQuery(): string {
    let params = aiListPageParams(this.page);
    params = setText(params, 'model', this.filters.model);
    params = setText(params, 'provider', this.filters.provider);
    params = setList(params, 'enabled', this.filters.enabled);
    params = setList(params, 'defaultModel', this.filters.defaultModel);
    params = setText(params, 'defaultEffort', this.filters.defaultEffort);
    params = setText(params, 'effort', this.filters.effort);
    params = setList(params, 'updaterLoginIdList', this.filters.updaterLoginIdList);
    params = setDate(params, 'updatedAfter', this.filters.updatedAfter);
    params = setDate(params, 'updatedBefore', this.filters.updatedBefore);
    return encodedAiListQuery(params);
  }
}

export class AiPolicyUserListRequest {
  filters = {
    loginId: '', name: '', organization: '', enabled: [] as boolean[],
    model: '', multiAgentEnabled: [] as boolean[],
    quotaTokens: null as number | null, activeRequests: null as number | null,
    updaterLoginIdList: [] as string[], updatedAfter: null as Date | null,
    updatedBefore: null as Date | null
  };
  page = new PageRequest('updatedOn', 'desc', 0, 10);

  constructor(paramMap?: ParamMap) {
    const params = aiListParams(paramMap);
    this.page = aiListPage(params);
    this.filters = {
      loginId: params.get('loginId') || '',
      name: params.get('name') || '',
      organization: params.get('organization') || '',
      enabled: booleanList(params, 'enabled'),
      model: params.get('model') || '',
      multiAgentEnabled: booleanList(params, 'multiAgentEnabled'),
      quotaTokens: numberValue(params, 'quotaTokens'),
      activeRequests: numberValue(params, 'activeRequests'),
      updaterLoginIdList: stringList(params, 'updaterLoginIdList'),
      updatedAfter: dateValue(params, 'updatedAfter'),
      updatedBefore: dateValue(params, 'updatedBefore')
    };
  }

  toQuery(): string {
    let params = aiListPageParams(this.page);
    params = setText(params, 'loginId', this.filters.loginId);
    params = setText(params, 'name', this.filters.name);
    params = setText(params, 'organization', this.filters.organization);
    params = setList(params, 'enabled', this.filters.enabled);
    params = setText(params, 'model', this.filters.model);
    params = setList(params, 'multiAgentEnabled', this.filters.multiAgentEnabled);
    params = setNumber(params, 'quotaTokens', this.filters.quotaTokens);
    params = setNumber(params, 'activeRequests', this.filters.activeRequests);
    params = setList(params, 'updaterLoginIdList', this.filters.updaterLoginIdList);
    params = setDate(params, 'updatedAfter', this.filters.updatedAfter);
    params = setDate(params, 'updatedBefore', this.filters.updatedBefore);
    return encodedAiListQuery(params);
  }
}

function aiListParams(paramMap?: ParamMap): HttpParams {
  const encoded = paramMap?.get('q');
  return encoded ? new HttpParams({fromString: base64Decode(encoded)}) : new HttpParams();
}

function aiListPage(params: HttpParams): PageRequest {
  return new PageRequest(params.get('sortActive') || 'updatedOn',
    params.get('sortDirection') || 'desc', numberValue(params, 'pageIndex') ?? 0,
    numberValue(params, 'pageSize') ?? 10);
}

function aiListPageParams(page: PageRequest): HttpParams {
  return new HttpParams().set('sortActive', page.sortActive)
    .set('sortDirection', page.sortDirection).set('pageIndex', page.pageIndex)
    .set('pageSize', page.pageSize);
}

function stringList(params: HttpParams, name: string): string[] {
  return (params.get(name) || '').split(',').map(value => value.trim()).filter(Boolean);
}

function booleanList(params: HttpParams, name: string): boolean[] {
  return stringList(params, name).filter(value => value === 'true' || value === 'false')
    .map(value => value === 'true');
}

function numberValue(params: HttpParams, name: string): number | null {
  const value = params.get(name);
  if (value == null || value.trim() === '') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function dateValue(params: HttpParams, name: string): Date | null {
  const value = params.get(name);
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

function setText(params: HttpParams, name: string, value: string): HttpParams {
  return value?.trim() ? params.set(name, value.trim()) : params;
}

function setList(params: HttpParams, name: string,
                 values: Array<string | boolean>): HttpParams {
  return values?.length ? params.set(name, values.join(',')) : params;
}

function setNumber(params: HttpParams, name: string, value: number | null): HttpParams {
  return value == null ? params : params.set(name, value);
}

function setDate(params: HttpParams, name: string, value: Date | null): HttpParams {
  return value ? params.set(name, value.toISOString()) : params;
}

function encodedAiListQuery(params: HttpParams): string {
  return `q=${base64Encode(params.toString())}`;
}

export interface AiProviderApiKeyView {
  value: string;
  revealed: boolean;
}

export interface AiProviderConnectionTestResult {
  successful: boolean;
  message: string;
  statusCode: number | null;
}
