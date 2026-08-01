export interface AiPolicyUserSummary {
  userId: string;
  loginId: string;
  name: string;
  organization: string;
  inherited: boolean;
  enabled: boolean;
  multiAgentEnabled: boolean;
  allowedModelCount: number;
  quotaLimitTokens: number | null;
  quotaConsumedTokens: number;
  quotaReservedTokens: number;
  quotaRemainingTokens: number | null;
  activeRequests: number;
  lastPolicyChange: string | null;
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
  catalogVersion: number;
  reasoningEfforts: AiReasoningEffort[];
  modelOptions: Record<string, AiModelOptionValue>;
}

export interface AiModelUpdate {
  expectedVersion: number | null;
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
  expectedVersion: number | null;
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
  recentCalls: Array<{callId: string; modelKey: string; executionKind: string; agentId: string | null;
    reservedTokens: number; chargedTokens: number; usageComplete: boolean; status: string;
    failureType: string | null; reservedAt: string; settledAt: string | null}>;
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
  anthropicVersion: string | null;
  apiVersion: string | null;
  enabled: boolean;
  apiKeyConfigured: boolean;
  catalogVersion: number;
}

export interface AiProviderUpdate {
  expectedVersion: number | null;
  providerName: string;
  providerType: string;
  baseUrl: string | null;
  messagesUrl: string | null;
  anthropicVersion: string | null;
  apiVersion: string | null;
  enabled: boolean;
  apiKey?: string;
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
