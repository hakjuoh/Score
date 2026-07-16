import {AiChatSocketEvent, AiElicitationNotice} from './ai-chat-panel.model';

const ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,254}$/;
const MAX_SCHEMA_LENGTH = 65_536;
const MAX_MESSAGE_LENGTH = 16_384;

export function elicitationNotice(
  event: AiChatSocketEvent,
  expectedRequestId: string,
  expectedConversationId: string | undefined,
  nowEpochMs = Date.now()
): AiElicitationNotice | undefined {
  if (event.type !== 'system' || event.subtype !== 'elicitation_required'
    || event.visibility !== 'visible' || event.requestId !== expectedRequestId
    || !expectedConversationId || event.conversationId !== expectedConversationId) {
    return undefined;
  }
  const metadata = record(event.metadata);
  if (!metadata || metadata['mode'] !== 'form'
    || !validId(metadata['elicitationId'])
    || !validFutureInstant(metadata['expiresAt'], nowEpochMs)
    || typeof metadata['message'] !== 'string'
    || metadata['message'].length === 0
    || metadata['message'].length > MAX_MESSAGE_LENGTH) {
    return undefined;
  }
  const schema = record(metadata['requestedSchema']);
  if (!schema || schema['type'] !== 'object' || !record(schema['properties'])
    || !safeSchemaProperties(schema, 0, {count: 0})) {
    return undefined;
  }
  try {
    if (JSON.stringify(schema).length > MAX_SCHEMA_LENGTH) {
      return undefined;
    }
  } catch {
    return undefined;
  }
  return {
    elicitationId: metadata['elicitationId'] as string,
    requestId: event.requestId,
    conversationId: expectedConversationId,
    expiresAt: metadata['expiresAt'] as string,
    message: metadata['message'],
    requestedSchema: schema
  };
}

function safeSchemaProperties(
  schema: Record<string, unknown>, depth: number, state: {count: number}
): boolean {
  if (depth > 5) {
    return false;
  }
  const properties = record(schema['properties']);
  if (!properties) {
    return true;
  }
  for (const [name, value] of Object.entries(properties)) {
    state.count++;
    if (state.count > 50 || !safePropertyName(name)) {
      return false;
    }
    const child = record(value);
    if (!child || !safeSchemaProperties(child, depth + 1, state)) {
      return false;
    }
  }
  return true;
}

function safePropertyName(name: string): boolean {
  return name.length > 0 && name.length <= 128
    && name !== '__proto__' && name !== 'prototype' && name !== 'constructor'
    && !/[\u0000-\u001f\u007f]/.test(name);
}

function validId(value: unknown): value is string {
  return typeof value === 'string' && ID_PATTERN.test(value);
}

function validFutureInstant(value: unknown, nowEpochMs: number): value is string {
  if (typeof value !== 'string') {
    return false;
  }
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) && timestamp > nowEpochMs;
}

export function record(value: unknown): Record<string, unknown> | undefined {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown> : undefined;
}
