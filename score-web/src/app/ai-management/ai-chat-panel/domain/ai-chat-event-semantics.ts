import {AiChatSocketEvent, AiChatToolStatus, AiContextUsage, AiExecutionStatus} from './ai-chat-panel.model';

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
      && subtype !== 'completed' && subtype !== 'failed')) {
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
  const statusMessage = nonBlank(event.metadata?.['statusMessage']);
  const content = statusMessage || primaryContent(event) || 'Used tool';
  const active = subtype === 'started' || subtype === 'progress';
  const turnId = nonBlank(event.turnId);
  const toolCallSeq = nonNegativeSequence(event.metadata?.['toolCallSeq']);
  const toolDetail = nonBlank(event.metadata?.['toolDetail']);
  return {
    key: `${groupId}:${toolCallId}`,
    ...(turnId ? {turnId} : {}),
    groupId,
    toolCallId,
    ...(toolCallSeq !== undefined ? {toolCallSeq} : {}),
    toolName: nonBlank(event.metadata?.['toolName']),
    ...(toolDetail ? {toolDetail} : {}),
    content,
    active,
    hidden: event.metadata?.['toolDiscovery'] === true,
    status: active ? undefined : subtype,
    recoverable: subtype === 'failed' ? true : undefined,
    retryable: booleanValue(event.metadata?.['retryable']),
    mutationSafe: booleanValue(event.metadata?.['mutationSafe'])
  };
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

function nonNegativeSequence(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
    ? value : undefined;
}

function safeInteger(value: unknown, minimum: number): number | undefined {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= minimum ? value : undefined;
}
