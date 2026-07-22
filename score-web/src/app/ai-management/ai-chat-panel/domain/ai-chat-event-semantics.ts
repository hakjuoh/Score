import {AiChatSocketEvent, AiChatToolStatus, AiContextUsage, AiExecutionStatus} from './ai-chat-panel.model';

const TEXTUAL_TOOL_CALL_PLACEHOLDER =
  /\*{0,2}\[\s*tool(?:[ -]call)?\s*:\s*[^\]\r\n]+]\*{0,2}(?:\s*(?:→|->).*?)?\s*$/is;

export type AiTerminalRequestErrorStatus = Extract<
  AiExecutionStatus, 'FAILED' | 'TIMED_OUT' | 'STEP_LIMIT_REACHED'
>;

export interface AiToolCallEventSemantics {
  key: string;
  turnId?: string;
  groupId: string;
  toolCallId: string;
  toolCallSeq?: number;
  toolName?: string;
  toolDetail?: string;
  content: string;
  active: boolean;
  hidden: boolean;
  status?: AiChatToolStatus;
  recoverable?: boolean;
  retryable?: boolean;
  mutationSafe?: boolean;
}

/**
 * Parses only correlation-safe tool lifecycle events. A malformed failure must
 * never unlock the composer or be mistaken for a request terminal.
 */
export function toolCallEventSemantics(event: AiChatSocketEvent): AiToolCallEventSemantics | undefined {
  if (event.type !== 'tool_call') {
    return undefined;
  }
  const groupId = nonBlank(event.groupId);
  const toolCallId = nonBlank(event.toolCallId);
  const subtype = nonBlank(event.subtype);
  if (!groupId || !toolCallId
    || (subtype !== 'started' && subtype !== 'progress'
      && subtype !== 'completed' && subtype !== 'failed'
      && subtype !== 'blocked' && subtype !== 'denied' && subtype !== 'cancelled')) {
    return undefined;
  }
  if (subtype === 'failed') {
    const terminal = event.metadata?.['terminal'];
    const recoverable = event.metadata?.['recoverable'];
    if ((terminal !== undefined && terminal !== false)
      || (recoverable !== undefined && recoverable !== true)) {
      return undefined;
    }
  }
  const active = subtype === 'started' || subtype === 'progress';
  const toolName = nonBlank(event.metadata?.['toolName']);
  const content = active
    ? (toolName ? `Calling ${toolName}.` : 'Executing...')
    : terminalToolCallContent(subtype, toolName, event);
  const turnId = nonBlank(event.turnId);
  const toolCallSeq = nonNegativeSequence(event.metadata?.['toolCallSeq']);
  const toolDetail = nonBlank(event.metadata?.['toolDetail']);
  return {
    key: `${groupId}:${toolCallId}`,
    ...(turnId ? {turnId} : {}),
    groupId,
    toolCallId,
    ...(toolCallSeq !== undefined ? {toolCallSeq} : {}),
    toolName,
    ...(toolDetail ? {toolDetail} : {}),
    content,
    active,
    hidden: false,
    status: active ? undefined : subtype,
    recoverable: subtype === 'failed' ? true : undefined,
    retryable: booleanValue(event.metadata?.['retryable']),
    mutationSafe: booleanValue(event.metadata?.['mutationSafe'])
  };
}

function terminalToolCallContent(subtype: 'completed' | 'failed' | 'blocked' | 'denied' | 'cancelled',
                                 toolName: string | undefined,
                                 event: AiChatSocketEvent): string {
  if (subtype === 'completed') {
    return toolName ? `${toolName} completed.` : 'Executed';
  }
  if (subtype === 'failed') {
    return toolName ? `${toolName} failed.` : 'Execution failed';
  }
  const content = nonBlank(primaryContent(event));
  if (content) {
    return content;
  }
  if (subtype === 'blocked') {
    return toolName ? `${toolName} is awaiting approval.` : 'Awaiting approval';
  }
  if (subtype === 'denied') {
    return toolName ? `${toolName} was denied before execution.` : 'Denied before execution';
  }
  return toolName ? `${toolName} was stopped before execution.` : 'Stopped before execution';
}

/** Returns a terminal status only when the complete explicit contract agrees. */
export function terminalRequestErrorStatus(event: AiChatSocketEvent): AiTerminalRequestErrorStatus | undefined {
  if (event.type !== 'system'
    || event.subtype !== 'request_error'
    || !positiveSequence(event.sequence)
    || event.metadata?.['terminal'] !== true
    || event.metadata?.['recoverable'] !== false
    || event.metadata?.['retryable'] !== false) {
    return undefined;
  }
  const status = event.metadata?.['status'];
  return status === 'FAILED' || status === 'TIMED_OUT' || status === 'STEP_LIMIT_REACHED'
    ? status : undefined;
}

export interface AiProviderRetrySemantics {
  attempt: number;
  maxAttempts: number;
  delayMillis: number;
  reason?: string;
  statusCode?: number;
}

/** Parses only complete retry narrations. A malformed wait must never start a countdown. */
export function providerRetrySemantics(event: AiChatSocketEvent): AiProviderRetrySemantics | undefined {
  if (event.type !== 'system' || event.subtype !== 'provider_retry') {
    return undefined;
  }
  const attempt = positiveInteger(event.metadata?.['attempt']);
  const maxAttempts = positiveInteger(event.metadata?.['max_attempts']);
  const delayMillis = nonNegativeSequence(event.metadata?.['delay_millis']);
  if (attempt === undefined || maxAttempts === undefined || delayMillis === undefined) {
    return undefined;
  }
  const reason = nonBlank(event.metadata?.['reason']);
  const statusCode = positiveInteger(event.metadata?.['status_code']);
  return {
    attempt,
    maxAttempts,
    delayMillis,
    ...(reason ? {reason} : {}),
    ...(statusCode !== undefined ? {statusCode} : {})
  };
}

export function isReconciliationRequired(event: AiChatSocketEvent): boolean {
  return event.type === 'system'
    && event.subtype === 'reconciliation_required'
    && positiveSequence(event.sequence)
    && event.metadata?.['terminal'] === true
    && event.metadata?.['recoverable'] === false
    && event.metadata?.['retryable'] === false
    && event.metadata?.['status'] === 'UNKNOWN_RECONCILIATION_REQUIRED';
}

/**
 * Detects the narrow pre-RUN-03 tool error envelope. The caller must still
 * match the returned name to an active structured tool call before treating it
 * as recoverable; metadata alone is deliberately insufficient.
 */
export function legacyRecoverableToolName(event: AiChatSocketEvent): string | undefined {
  const terminal = event.metadata?.['terminal'];
  const recoverable = event.metadata?.['recoverable'];
  if (event.type !== 'system' || event.subtype !== 'error'
    || (terminal !== undefined && terminal !== false)
    || (recoverable !== undefined && recoverable !== true)
    || event.metadata?.['agent'] !== undefined
    || event.metadata?.['model'] !== undefined
    || !/\bfailed:\s*\S/i.test(primaryContent(event))) {
    return undefined;
  }
  return nonBlank(event.metadata?.['toolName']);
}

export function primaryContent(event: AiChatSocketEvent): string {
  return event.content || event.response || event.message || '';
}

/**
 * Removes a model-authored imitation of a tool call from visible assistant
 * text. Only correlated tool_call lifecycle events are execution evidence and
 * may be rendered as expandable tool rows.
 */
export function withoutTextualToolCallPlaceholder(content: string): string {
  return content.replace(TEXTUAL_TOOL_CALL_PLACEHOLDER, '').trim();
}

/** Accepts only internally consistent usage snapshots from untrusted socket/restore metadata. */
export function contextUsageValue(value: unknown, expectedModel?: string): AiContextUsage | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const raw = value as Record<string, unknown>;
  const modelName = nonBlank(raw['modelName']);
  const source = nonBlank(raw['source']);
  const currentInputTokens = safeInteger(raw['currentInputTokens'], 0);
  const contextWindow = safeInteger(raw['contextWindow'], 1);
  const safeInputLimit = safeInteger(raw['safeInputLimit'], 1);
  const remainingTokens = safeInteger(raw['remainingTokens'], 0);
  const usedPercent = raw['usedPercent'];
  if (!modelName || (expectedModel && modelName !== expectedModel) || !source || source.length > 64
    || currentInputTokens === undefined || contextWindow === undefined
    || safeInputLimit === undefined || safeInputLimit > contextWindow
    || remainingTokens === undefined || remainingTokens !== Math.max(0, safeInputLimit - currentInputTokens)
    || typeof usedPercent !== 'number' || !Number.isFinite(usedPercent)
    || usedPercent < 0 || usedPercent > 100 || typeof raw['estimated'] !== 'boolean') {
    return undefined;
  }
  const expectedPercent = Math.min(100, currentInputTokens * 100 / safeInputLimit);
  if (Math.abs(usedPercent - expectedPercent) > 0.11) return undefined;
  return {
    modelName, currentInputTokens, contextWindow, safeInputLimit, remainingTokens,
    usedPercent, estimated: raw['estimated'], source
  };
}

function nonBlank(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function booleanValue(value: unknown): boolean | undefined {
  return typeof value === 'boolean' ? value : undefined;
}

function positiveSequence(value: unknown): boolean {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
}

function positiveInteger(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
    ? value : undefined;
}

function nonNegativeSequence(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
    ? value : undefined;
}

function safeInteger(value: unknown, minimum: number): number | undefined {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum ? value : undefined;
}
