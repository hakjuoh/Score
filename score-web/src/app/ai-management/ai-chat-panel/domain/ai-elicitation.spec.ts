/**
 * Verifies strict parsing of bound, unexpired JSON Schema elicitation notices.
 */

import {describe, expect, it} from 'vitest';
import {elicitationNotice} from './ai-elicitation';

describe('AI elicitation event parsing', () => {
  it('accepts a bound, unexpired JSON Schema form', () => {
    expect(elicitationNotice(event(), 'request-1', 'conversation-1', 7,
      Date.parse('2026-07-18T12:00:00Z')))
      .toEqual(expect.objectContaining({
        elicitationId: 'elicitation-1',
        generation: 7,
        message: 'Choose how to continue.',
        requestedSchema: expect.objectContaining({type: 'object'})
      }));
  });

  it.each([
    ['foreign request', {...event(), requestId: 'request-2'}],
    ['foreign conversation', {...event(), conversationId: 'conversation-2'}],
    ['stale generation', {...event(), metadata: {...event().metadata, generation: 6}}],
    ['expired', {...event(), metadata: {...event().metadata, expiresAt: '2020-01-01T00:00:00Z'}}],
    ['non-object schema', {...event(), metadata: {...event().metadata,
      requestedSchema: {type: 'array', items: {type: 'string'}}}}],
    ['prototype-polluting schema', {...event(), metadata: {...event().metadata,
      requestedSchema: {type: 'object', properties: JSON.parse(
        '{"__proto__":{"type":"string"}}'
      )}}}]
  ])('rejects a %s event', (_label, candidate) => {
    expect(elicitationNotice(
      candidate as any, 'request-1', 'conversation-1', 7,
      Date.parse('2026-07-18T12:00:00Z')
    )).toBeUndefined();
  });

  function event() {
    return {
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'system', subtype: 'elicitation_required', visibility: 'visible',
      content: 'The assistant needs your input before it can continue.',
      metadata: {
        elicitationId: 'elicitation-1', generation: 7, mode: 'form',
        expiresAt: '2026-07-18T12:10:00Z',
        message: 'Choose how to continue.',
        requestedSchema: {
          type: 'object', properties: {
            strategy: {type: 'string', enum: ['merge', 'replace']}
          }, required: ['strategy']
        }
      }
    };
  }
});
