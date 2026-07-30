import {
  AiChatHistoryMessage,
  AiChatSocketEvent,
  AiChangeApprovalBatchItem,
  AiChangeApprovalBatchNotice
} from './ai-chat-panel.model';

const MAX_ITEMS = 100;
const MAX_ID_LENGTH = 160;
const MAX_TOOL_LENGTH = 200;
const MAX_ARGUMENTS_LENGTH = 4000;

/** Strictly parses one root-scoped approval barrier from the active request. */
export function changeApprovalBatchNotice(
  event: AiChatSocketEvent,
  expectedRequestId: string,
  expectedConversationId?: string
): AiChangeApprovalBatchNotice | undefined {
  if (event.type !== 'system' || event.subtype !== 'change_approval_batch_required'
    || event.requestId !== expectedRequestId) {
    return undefined;
  }
  const conversationId = text(event.conversationId, MAX_ID_LENGTH);
  const batchId = text(event.metadata?.['batchId'], MAX_ID_LENGTH);
  const expiresAt = text(event.metadata?.['expiresAt'], 80);
  const parallel = event.metadata?.['parallel'];
  const rawItems = event.metadata?.['items'];
  const expires = expiresAt ? Date.parse(expiresAt) : Number.NaN;
  if (!conversationId || (expectedConversationId && conversationId !== expectedConversationId)
    || !batchId || typeof parallel !== 'boolean' || !expiresAt
    || !Number.isFinite(expires) || expires <= Date.now()
    || !Array.isArray(rawItems) || rawItems.length < 1 || rawItems.length > MAX_ITEMS) {
    return undefined;
  }
  const seen = new Set<string>();
  const items: AiChangeApprovalBatchItem[] = [];
  for (const rawItem of rawItems) {
    if (!rawItem || typeof rawItem !== 'object' || Array.isArray(rawItem)) return undefined;
    const item = rawItem as Record<string, unknown>;
    const confirmationRequestId = text(item['confirmationRequestId'], MAX_ID_LENGTH);
    const toolName = text(item['toolName'], MAX_TOOL_LENGTH);
    const argumentsSummary = text(item['argumentsSummary'], MAX_ARGUMENTS_LENGTH, true);
    const agentId = optionalText(item['agentId'], MAX_ID_LENGTH);
    const agentLabel = optionalText(item['agentLabel'], MAX_TOOL_LENGTH);
    if (!confirmationRequestId || !toolName || argumentsSummary === undefined
      || seen.has(confirmationRequestId)) {
      return undefined;
    }
    seen.add(confirmationRequestId);
    items.push({
      confirmationRequestId, toolName, argumentsSummary,
      ...(agentId ? {agentId} : {}),
      ...(agentLabel ? {agentLabel} : {})
    });
  }
  return {
    batchId, requestId: expectedRequestId, conversationId,
    parallel, expiresAt, items
  };
}

/** Reconstructs unresolved approval barriers from the durable request trajectory. */
export function pendingChangeApprovalBatches(
  messages: AiChatHistoryMessage[],
  expectedRequestId: string,
  expectedConversationId: string
): AiChangeApprovalBatchNotice[] {
  const pending = new Map<string, AiChangeApprovalBatchNotice>();
  [...messages].sort((left, right) => left.index - right.index).forEach(message => {
    if (message.requestId !== expectedRequestId) return;
    if (message.subtype === 'change_approval_batch_requested') {
      const notice = changeApprovalBatchNotice({
        requestId: expectedRequestId,
        conversationId: expectedConversationId,
        type: 'system',
        subtype: 'change_approval_batch_required',
        metadata: message.metadata
      }, expectedRequestId, expectedConversationId);
      if (notice) pending.set(notice.batchId, notice);
      return;
    }
    if (message.subtype === 'change_approval_decision') {
      const batchId = text(message.metadata?.['batchId'], MAX_ID_LENGTH);
      if (batchId) pending.delete(batchId);
    }
  });
  return [...pending.values()];
}

export function isUnexpiredChangeApprovalBatch(
  notice: AiChangeApprovalBatchNotice,
  now = Date.now()
): boolean {
  const expires = Date.parse(notice.expiresAt);
  return Number.isFinite(expires) && expires > now;
}

/** User-facing status for the currently displayed approval barrier. */
export function changeApprovalBatchStatus(
  notice: Pick<AiChangeApprovalBatchNotice, 'items'>
): string {
  return notice.items.length === 1
    ? 'Approval required'
    : `${notice.items.length} approvals required`;
}

function text(value: unknown, maximum: number, allowEmpty = false): string | undefined {
  if (typeof value !== 'string' || value.length > maximum
    || value.trim() !== value || (!allowEmpty && !value)) {
    return undefined;
  }
  return value;
}

function optionalText(value: unknown, maximum: number): string | undefined {
  return value === undefined ? undefined : text(value, maximum);
}
