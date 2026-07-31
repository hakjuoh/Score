/**
 * Validates change-confirmation notices, authorizations, denials, and replay boundaries.
 */

import {
  AiChatSocketEvent,
  AiChatRestResponse,
  AiChangeConfirmationDecisionResponse,
  AiChangeConfirmationNotice
} from './ai-chat-panel.model';

const NOTICE_EVENT_KEYS = new Set([
  'requestId', 'conversationId', 'type', 'subtype', 'visibility',
  'content', 'message', 'turnId', 'sequence', 'metadata',
  // Accepted only for compatibility with pre-minimal-envelope servers.
  'continuationRequired', 'progress', 'ids'
]);
const NOTICE_METADATA_KEYS = [
  'argumentsSummary', 'confirmationRequestId', 'expiresAt', 'status',
  'toolName'
];
const DECISION_RESPONSE_KEYS = new Set([
  'confirmationRequestId', 'conversationId', 'status', 'disposition',
  'expiresAt', 'approvedAt', 'deniedAt', 'expiredAt', 'consumedAt',
  'confirmationGrant'
]);
const CONFIRMED_CHAT_RESPONSE_KEYS = new Set([
  'agent', 'response', 'conversationId', 'continuationRequired', 'progress',
  'events', 'files'
]);
const CONFIRMED_CHAT_EVENT_KEYS = new Set([
  'requestId', 'conversationId', 'type', 'subtype', 'visibility', 'content',
  'turnId', 'sequence', 'groupId', 'toolCallId', 'metadata', 'message', 'agent',
  'response', 'continuationRequired', 'progress', 'resource', 'action',
  'targetPath', 'ids', 'index', 'files'
]);
const FILE_KEYS = new Set([
  'fileId', 'format', 'filename', 'mediaType', 'size', 'sha256',
  'createdAt', 'expiresAt', 'downloadUrl'
]);
const CONFIRMATION_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$/;
const RFC_3339_UTC_PATTERN =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;
const CANONICAL_256_BIT_BASE64URL_PATTERN =
  /^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/;
const GENERIC_NOTICE_CONTENT =
  'A change requires explicit approval.';

/**
 * Parse only owner-safe metadata emitted for an explicitly approved change.
 * Unknown fields invalidate the notice. The only displayed tool data is a
 * bounded, redacted change summary; hashes and bearer grants are never accepted.
 */
export function changeConfirmationNotice(
  event: AiChatSocketEvent,
  expectedRequestId: string,
  expectedConversationId: string | undefined,
  nowEpochMs = Date.now()
): AiChangeConfirmationNotice | undefined {
  if (event.type !== 'system'
    || event.subtype !== 'change_confirmation_required'
    || event.requestId !== expectedRequestId
    || !expectedConversationId
    || event.conversationId !== expectedConversationId
    || !onlyKeys(event as unknown as Record<string, unknown>, NOTICE_EVENT_KEYS)
    || !safeNoticeEnvelope(
      event as unknown as Record<string, unknown>, expectedRequestId
    )) {
    return undefined;
  }
  const metadata = plainRecord(event.metadata);
  if (!metadata
    || !sameKeys(metadata, NOTICE_METADATA_KEYS)
    || !validConfirmationId(metadata['confirmationRequestId'])
    || (metadata['status'] !== 'REQUESTED' && metadata['status'] !== 'APPROVED')
    || !unexpiredInstant(metadata['expiresAt'], nowEpochMs)
    || !safeToolName(metadata['toolName'])
    || !safeArgumentsSummary(metadata['argumentsSummary'])) {
    return undefined;
  }
  return {
    confirmationRequestId: metadata['confirmationRequestId'] as string,
    status: metadata['status'],
    expiresAt: metadata['expiresAt'] as string,
    toolName: metadata['toolName'] as string,
    argumentsSummary: metadata['argumentsSummary'] as string
  };
}

/** Exactly 32 bytes encoded as canonical, unpadded base64url. */
export function isCanonicalChangeConfirmationGrant(value: unknown): value is string {
  return typeof value === 'string'
    && CANONICAL_256_BIT_BASE64URL_PATTERN.test(value);
}

export function isBoundConfirmedChatResponse(
  value: unknown,
  expectedConversationId: string
): value is AiChatRestResponse {
  const response = plainRecord(value);
  return !!response
    && onlyKeys(response, CONFIRMED_CHAT_RESPONSE_KEYS)
    && response['conversationId'] === expectedConversationId
    && optionalString(response['agent'])
    && optionalString(response['response'])
    && (response['continuationRequired'] === undefined
      || typeof response['continuationRequired'] === 'boolean')
    && (response['progress'] === undefined
      || (Array.isArray(response['progress'])
        && response['progress'].every(item => typeof item === 'string')))
    && validFiles(response['files'], expectedConversationId)
    && (response['events'] === undefined
      || (Array.isArray(response['events'])
        && response['events'].every(item =>
          validConfirmedChatEvent(item, expectedConversationId))));
}

export type ChangeApprovalOutcome =
  | {kind: 'GRANTED'; confirmationGrant: string}
  | {kind: 'LOST'}
  | {kind: 'INVALID'};

export type ChangeDenialOutcome = 'DENIED' | 'ALREADY_DENIED' | 'INVALID';
export type ChangeConflictOutcome = 'CONSUMED' | 'INVALID';

/**
 * Validate an approval response without ever rendering or logging it. A grant
 * is usable only on the first APPROVED transition and only for the exact
 * conversation/request pair whose dialog the user approved.
 */
export function changeApprovalOutcome(
  response: AiChangeConfirmationDecisionResponse | null | undefined,
  expectedConversationId: string,
  expectedConfirmationRequestId: string,
  nowEpochMs = Date.now()
): ChangeApprovalOutcome {
  if (!response
    || !onlyKeys(response as unknown as Record<string, unknown>, DECISION_RESPONSE_KEYS)
    || response.conversationId !== expectedConversationId
    || response.confirmationRequestId !== expectedConfirmationRequestId
    || !validInstant(response.approvedAt)
    || response.deniedAt !== undefined
    || response.expiredAt !== undefined
    || response.consumedAt !== undefined) {
    return {kind: 'INVALID'};
  }
  if (response.status === 'APPROVED'
    && response.disposition === 'APPROVED'
    && unexpiredInstant(response.expiresAt, nowEpochMs)) {
    return isCanonicalChangeConfirmationGrant(response.confirmationGrant)
      ? {kind: 'GRANTED', confirmationGrant: response.confirmationGrant}
      : {kind: 'LOST'};
  }
  if (response.status === 'APPROVED'
    && response.disposition === 'ALREADY_APPROVED'
    && response.confirmationGrant === undefined
    && unexpiredInstant(response.expiresAt, nowEpochMs)) {
    return {kind: 'LOST'};
  }
  return {kind: 'INVALID'};
}

/** Validate a DENY or explicit revoke response for the exact owner binding. */
export function changeDenialOutcome(
  response: AiChangeConfirmationDecisionResponse | null | undefined,
  expectedConversationId: string,
  expectedConfirmationRequestId: string
): ChangeDenialOutcome {
  if (!response
    || !onlyKeys(response as unknown as Record<string, unknown>, DECISION_RESPONSE_KEYS)
    || response.conversationId !== expectedConversationId
    || response.confirmationRequestId !== expectedConfirmationRequestId
    || response.status !== 'DENIED'
    || (response.disposition !== 'DENIED'
      && response.disposition !== 'ALREADY_DENIED')
    || response.confirmationGrant !== undefined
    || !validInstant(response.expiresAt)
    || !validInstant(response.deniedAt)
    || !validOptionalInstant(response.approvedAt)
    || response.expiredAt !== undefined
    || response.consumedAt !== undefined) {
    return 'INVALID';
  }
  return response.disposition;
}

/** Validate only the exact 409 state proving this confirmation was consumed. */
export function changeConflictOutcome(
  response: AiChangeConfirmationDecisionResponse | null | undefined,
  expectedConversationId: string,
  expectedConfirmationRequestId: string
): ChangeConflictOutcome {
  if (!response
    || !onlyKeys(response as unknown as Record<string, unknown>, DECISION_RESPONSE_KEYS)
    || response.conversationId !== expectedConversationId
    || response.confirmationRequestId !== expectedConfirmationRequestId
    || response.status !== 'CONSUMED'
    || response.disposition !== 'CONSUMED'
    || response.confirmationGrant !== undefined
    || !validInstant(response.expiresAt)
    || !validInstant(response.consumedAt)
    || !validInstant(response.approvedAt)
    || response.deniedAt !== undefined
    || response.expiredAt !== undefined) {
    return 'INVALID';
  }
  return 'CONSUMED';
}

export function isUnexpiredChangeConfirmation(
  notice: AiChangeConfirmationNotice,
  nowEpochMs = Date.now()
): boolean {
  return unexpiredInstant(notice.expiresAt, nowEpochMs);
}

function validConfirmationId(value: unknown): value is string {
  return typeof value === 'string' && CONFIRMATION_ID_PATTERN.test(value);
}

function safeToolName(value: unknown): value is string {
  return typeof value === 'string'
    && /^[A-Za-z0-9_.:-]{1,240}$/.test(value);
}

function safeArgumentsSummary(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= 2014
    && !/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/.test(value);
}

function unexpiredInstant(value: unknown, nowEpochMs: number): boolean {
  if (!validInstant(value)) {
    return false;
  }
  const epochMs = Date.parse(value as string);
  return Number.isFinite(epochMs) && epochMs > nowEpochMs;
}

function validOptionalInstant(value: unknown): boolean {
  return value === undefined || validInstant(value);
}

function validInstant(value: unknown): value is string {
  if (typeof value !== 'string') {
    return false;
  }
  const match = RFC_3339_UTC_PATTERN.exec(value);
  if (!match) {
    return false;
  }
  const dateParts = value.match(
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/
  );
  if (!dateParts) {
    return false;
  }
  const [, yearText, monthText, dayText, hourText,
    minuteText, secondText, fractionText = ''] = dateParts;
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const hour = Number(hourText);
  const minute = Number(minuteText);
  const second = Number(secondText);
  const millisecond = Number((fractionText + '000').slice(0, 3));
  const date = new Date(0);
  date.setUTCFullYear(year, month - 1, day);
  date.setUTCHours(hour, minute, second, millisecond);
  return Number.isFinite(date.getTime())
    && date.getUTCFullYear() === year
    && date.getUTCMonth() === month - 1
    && date.getUTCDate() === day
    && date.getUTCHours() === hour
    && date.getUTCMinutes() === minute
    && date.getUTCSeconds() === second
    && date.getUTCMilliseconds() === millisecond;
}

function plainRecord(value: unknown): Record<string, unknown> | undefined {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return undefined;
  }
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null
    ? value as Record<string, unknown> : undefined;
}

function onlyKeys(record: Record<string, unknown>, allowed: Set<string>): boolean {
  return Object.keys(record).every(key => allowed.has(key));
}

function sameKeys(record: Record<string, unknown>, expected: string[]): boolean {
  const keys = Object.keys(record).sort();
  return keys.length === expected.length
    && keys.every((key, index) => key === expected[index]);
}

function safeNoticeEnvelope(
  event: Record<string, unknown>,
  expectedRequestId: string
): boolean {
  return optionalExact(event['visibility'], 'visible')
    && optionalExact(event['turnId'], expectedRequestId)
    && optionalGenericContent(event['content'])
    && optionalGenericContent(event['message'])
    && optionalNonNegativeInteger(event['sequence'])
    && optionalFalse(event['continuationRequired'])
    && optionalEmptyArray(event['progress'])
    && optionalEmptyArray(event['ids'])
    && absent(event['agent'])
    && absent(event['response'])
    && absent(event['resource'])
    && absent(event['action'])
    && absent(event['targetPath'])
    && absent(event['index'])
    && absent(event['groupId'])
    && absent(event['toolCallId']);
}

function absent(value: unknown): boolean {
  return value === undefined || value === null;
}

function optionalString(value: unknown): boolean {
  return value === undefined || typeof value === 'string';
}

function validFiles(value: unknown, expectedConversationId: string): boolean {
  return value === undefined || (Array.isArray(value) && value.every(item => {
    const file = plainRecord(item);
    if (!file || !onlyKeys(file, FILE_KEYS)) return false;
    const fileId = file['fileId'];
    return typeof fileId === 'string'
      && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$/.test(fileId)
      && typeof file['format'] === 'string' && file['format'].length > 0
      && typeof file['filename'] === 'string' && file['filename'].length > 0
      && typeof file['mediaType'] === 'string' && file['mediaType'].length > 0
      && typeof file['size'] === 'number' && Number.isSafeInteger(file['size'])
      && file['size'] >= 0
      && typeof file['sha256'] === 'string' && /^[a-fA-F0-9]{64}$/.test(file['sha256'])
      && validOptionalInstant(file['createdAt'])
      && validOptionalInstant(file['expiresAt'])
      && file['downloadUrl'] === `/api/ai/chat/conversations/${expectedConversationId}/files/${fileId}`;
  }));
}

function validConfirmedChatEvent(value: unknown, expectedConversationId: string): boolean {
  const event = plainRecord(value);
  return !!event
    && onlyKeys(event, CONFIRMED_CHAT_EVENT_KEYS)
    && typeof event['requestId'] === 'string' && event['requestId'].length > 0
    && typeof event['type'] === 'string' && event['type'].length > 0
    && (event['conversationId'] === undefined
      || event['conversationId'] === expectedConversationId)
    && [
      'subtype', 'visibility', 'content', 'turnId', 'groupId', 'toolCallId',
      'message', 'agent', 'response', 'resource', 'action', 'targetPath'
    ].every(key => optionalString(event[key]))
    && optionalNonNegativeInteger(event['sequence'])
    && optionalNonNegativeInteger(event['index'])
    && (event['continuationRequired'] === undefined
      || typeof event['continuationRequired'] === 'boolean')
    && optionalStringArray(event['progress'])
    && optionalStringArray(event['ids'])
    && (event['metadata'] === undefined || !!plainRecord(event['metadata']))
    && validFiles(event['files'], expectedConversationId);
}

function optionalStringArray(value: unknown): boolean {
  return value === undefined || (Array.isArray(value)
    && value.every(item => typeof item === 'string'));
}

function optionalExact(value: unknown, expected: string): boolean {
  return absent(value) || value === expected;
}

function optionalGenericContent(value: unknown): boolean {
  return absent(value) || value === GENERIC_NOTICE_CONTENT;
}

function optionalFalse(value: unknown): boolean {
  return absent(value) || value === false;
}

function optionalEmptyArray(value: unknown): boolean {
  return absent(value) || (Array.isArray(value) && value.length === 0);
}

function optionalNonNegativeInteger(value: unknown): boolean {
  return absent(value) || (typeof value === 'number'
    && Number.isSafeInteger(value) && value >= 0);
}
