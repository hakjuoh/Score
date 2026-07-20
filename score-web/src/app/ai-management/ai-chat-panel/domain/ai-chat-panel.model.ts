import type {AiAgentActivity} from './ai-agent-activity';

export type AiChatDock = 'right' | 'bottom' | 'left' | 'top';
export type AiChatMessageRole = 'user' | 'assistant' | 'guide' | 'progress'
  | 'agent_group' | 'workflow_group' | 'tool_group' | 'tool_call' | 'error' | 'debug';
export type AiChatToolStatus = 'completed' | 'failed';
export type AiChatPanelTab = 'chat' | 'history';
export type AiMutationPermissionMode = 'ask' | 'auto' | 'full_access';
export type AiAgentExecutionStatus = 'started' | 'completed' | 'failed' | 'cancelled' | 'synthesizing';

export interface AiChatMessage {
  role: AiChatMessageRole;
  content: string;
  inProgress?: boolean;
  formatting?: boolean;
  formattingPreviousContent?: string;
  eventType?: string;
  /** Owning request of a streamed assistant segment; identifies the live bubble
   *  without relying on array indexes that tool-row splices can shift. */
  requestId?: string;
  turnId?: string;
  groupId?: string;
  toolCallId?: string;
  toolCallSeq?: number;
  toolName?: string;
  toolDetail?: string;
  toolStatus?: AiChatToolStatus;
  recoverable?: boolean;
  retryable?: boolean;
  mutationSafe?: boolean;
  /**
   * Per-execution snapshot held BY REFERENCE on an agent/workflow group anchor.
   * Request starts replace (never mutate) the live state array, so settled
   * anchors keep rendering their own final statuses.
   */
  activities?: AiAgentActivity[];
}

export interface AiChatAttachment {
  name: string;
  mediaType: string;
  size: number;
  data: string;
}

export interface AiChatRequest {
  requestId: string;
  prompt: string;
  conversationId?: string;
  modelName?: string;
  reasoningEffort?: string;
  runtime?: string;
  runtimeOptions?: AiRuntimeOptions;
  permissionMode?: AiMutationPermissionMode;
  pageContext?: string;
  attachments: AiChatAttachment[];
  mutationConfirmation?: AiMutationConfirmationAuthorization;
}

export interface AiChatModelInfo {
  name: string;
  displayName: string;
  description: string;
  provider: string;
  defaultModel: boolean;
  defaultReasoningEffort: string;
  reasoningEfforts: AiReasoningEffortInfo[];
  defaultRuntime: string;
  runtimes: AiChatRuntimeInfo[];
  contextWindow?: number | null;
  outputReserveTokens?: number | null;
  autoCompactThresholdTokens?: number | null;
  emergencyHeadroomTokens?: number | null;
}

export interface AiContextUsage {
  modelName: string;
  currentInputTokens: number;
  contextWindow: number;
  safeInputLimit: number;
  remainingTokens: number;
  usedPercent: number;
  estimated: boolean;
  source: string;
}

export interface AiChatRuntimeInfo {
  name: string;
  displayName: string;
  description: string;
  settings: AiChatRuntimeSettingInfo[];
}

export type AiChatRuntimeSettingType = 'select' | 'number' | 'boolean';
export type AiRuntimeOptions = Record<string, unknown>;

export interface AiChatRuntimeSettingOptionInfo {
  value: string;
  displayName: string;
}

export interface AiChatRuntimeSettingInfo {
  name: string;
  displayName: string;
  description: string;
  type: AiChatRuntimeSettingType;
  defaultValue: unknown;
  options: AiChatRuntimeSettingOptionInfo[];
  minimum: number | null;
  maximum: number | null;
  step: number | null;
}

export interface AiReasoningEffortInfo {
  name: string;
  displayName: string;
  description: string;
}

export interface AiConversationModelResponse {
  conversationId: string;
  modelName: string;
  reasoningEffort: string;
  runtime: string;
  runtimeOptions: AiRuntimeOptions;
  contextCompacted?: boolean;
  contextUsage?: AiContextUsage;
}

export interface AiMutationConfirmationAuthorization {
  confirmationRequestId: string;
  confirmationGrant: string;
  toolName: string;
  arguments?: string;
  approvalMode?: 'EXACT' | 'REVISED';
  revisionPrompt?: string;
}

export type AiMutationConfirmationStatus =
  'REQUESTED' | 'APPROVED' | 'CONSUMED' | 'DENIED' | 'EXPIRED';

export type AiMutationConfirmationDisposition =
  'CREATED' | 'EXISTING' | 'APPROVED' | 'ALREADY_APPROVED'
  | 'DENIED' | 'ALREADY_DENIED' | 'EXPIRED' | 'CONSUMED'
  | 'CONFLICT' | 'NOT_FOUND';

export interface AiMutationConfirmationNotice {
  confirmationRequestId: string;
  status: 'REQUESTED' | 'APPROVED';
  expiresAt: string;
  toolName: string;
  argumentsSummary: string;
}

export interface AiMutationConfirmationDecisionResponse {
  confirmationRequestId: string;
  conversationId: string;
  status?: AiMutationConfirmationStatus;
  disposition: AiMutationConfirmationDisposition;
  expiresAt?: string;
  approvedAt?: string;
  deniedAt?: string;
  expiredAt?: string;
  consumedAt?: string;
  confirmationGrant?: string;
}

export interface AiElicitationNotice {
  elicitationId: string;
  requestId: string;
  conversationId: string;
  expiresAt: string;
  message: string;
  requestedSchema: Record<string, unknown>;
}

export type AiElicitationAction = 'ACCEPT' | 'DECLINE' | 'CANCEL';

export interface AiElicitationResponse {
  action: AiElicitationAction;
  content: Record<string, unknown>;
}

export interface AiMutationInteraction {
  toolName: string;
  argumentsSummary: string;
  mode: 'confirm' | 'lost_grant';
  busy: boolean;
}

export interface AiChatSocketEvent {
  requestId: string;
  conversationId?: string;
  type: string;
  subtype?: string;
  visibility?: string;
  content?: string;
  turnId?: string;
  sequence?: number;
  groupId?: string;
  toolCallId?: string;
  metadata?: {[key: string]: unknown};
  message?: string;
  agent?: string;
  response?: string;
  continuationRequired?: boolean;
  progress?: string[];
  resource?: string;
  action?: string;
  targetPath?: string;
  ids?: string[];
  index?: number;
}

export interface AiChatRestResponse {
  agent?: string;
  response?: string;
  conversationId?: string;
  continuationRequired?: boolean;
  progress?: string[];
  events?: AiChatSocketEvent[];
  runtimeOptions?: AiRuntimeOptions;
}

export type AiExecutionStatus =
  'REGISTERED'
  | 'RUNNING'
  | 'CANCELLING'
  | 'COMPLETED'
  | 'STEP_LIMIT_REACHED'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMED_OUT'
  | 'UNKNOWN_RECONCILIATION_REQUIRED';

export type AiCancellationDisposition =
  'ACKNOWLEDGED'
  | 'CANCELLED'
  | 'ALREADY_CANCELLING'
  | 'ALREADY_TERMINAL'
  | 'STALE_GENERATION';

export interface AiActiveRequestIdentity {
  requestId: string;
  conversationId?: string;
  generation?: number;
  deadline?: string;
}

export interface AiCancellationCommand {
  cancellationRequestId: string;
  conversationId?: string;
  expectedGeneration?: number;
}

export interface AiCancellationResponse {
  requestId: string;
  conversationId?: string | null;
  generation?: number | null;
  cancellationRequestId: string;
  effectiveCancellationRequestId?: string | null;
  disposition: AiCancellationDisposition;
  status?: AiExecutionStatus | null;
  acknowledged: boolean;
  terminal: boolean;
  lifecycleEventSequence: number;
  cancellationRequestedAt?: string | null;
  cancellationAcknowledgedAt?: string | null;
  cancellationDeadline?: string | null;
  terminalAt?: string | null;
}

export interface AiPublicExecutionRequestStatus {
  conversationId: string;
  requestId: string;
  generation: number;
  agentName?: string | null;
  status: AiExecutionStatus;
  statusReason?: string | null;
  deadline: string;
  retryCount: number;
  nextRetryAt?: string | null;
  lastHeartbeatAt?: string | null;
  createdAt: string;
  updatedAt: string;
  startedAt?: string | null;
  terminalAt?: string | null;
  cancellationRequestId?: string | null;
  cancellationRequestedAt?: string | null;
  cancellationDeadline?: string | null;
  cancellationAcknowledgedAt?: string | null;
  lastEventSequence: number;
  version: number;
}

export type AiCancellationPhase = 'idle' | 'requesting' | 'acknowledged' | 'delayed';
export type AiCancellationDelayReason =
  'ack_timeout' | 'terminal_timeout' | 'transport_unknown' | 'rejected' | 'force_safe_stop';

export interface AiCancellationUiState {
  phase: AiCancellationPhase;
  requestId?: string;
  cancellationRequestId?: string;
  effectiveCancellationRequestId?: string;
  acknowledged: boolean;
  lifecycleEventSequence: number;
  delayReason?: AiCancellationDelayReason;
}

export interface AiChatConversationSummary {
  conversationId: string;
  title: string;
  visibleMessageCount: number;
  compacted: boolean;
  createdAt?: string | number;
  updatedAt?: string | number;
}

export interface AiChatHistoryMessage {
  index: number;
  role: string;
  content: string;
  requestId?: string;
  turnId?: string;
  groupId?: string;
  toolCallId?: string;
  toolCallSeq?: number;
  toolStatus?: AiChatToolStatus;
  toolCallSequence?: number;
  subtype?: string;
  visibility?: string;
  metadata?: {[key: string]: unknown};
}

export interface AiChatConversationDetails {
  conversationId: string;
  title: string;
  modelName?: string;
  reasoningEffort?: string;
  runtime?: string;
  runtimeOptions?: AiRuntimeOptions;
  permissionMode?: AiMutationPermissionMode;
  contextUsage?: AiContextUsage;
  updatedAt?: string | number;
  messages: AiChatHistoryMessage[];
  contextMessages?: unknown[];
}

export interface ResizeState {
  startX: number;
  startY: number;
  startSideSize: number;
  startHorizontalSize: number;
}

export interface AiResourceRoute {
  resource: string;
  listPath: string;
  listQuery?: AiResourceListQuery;
  detailPrefixes?: string[];
  detailPattern?: string;
  detailPatterns?: {[key: string]: string};
  idFields: string[];
  linkableFields: string[];
  label: string;
  roles: string[];
}

export interface AiResourceListQuery {
  finalFormat: string;
  encoding?: string;
  formatterRule?: string;
  defaultParams?: {[key: string]: string};
  allowedPlainParams?: {[key: string]: string};
  toolParamAliases?: {[key: string]: string};
  dateRangeParamAliases?: {[key: string]: string};
}

export interface AiChatCommand {
  name: string;
  description: string;
  kind: 'local' | 'backend';
}

export interface AiChatContextUpdate {
  pageContext?: string;
  includesRouteRegistry: boolean;
  pagePath?: string;
}
