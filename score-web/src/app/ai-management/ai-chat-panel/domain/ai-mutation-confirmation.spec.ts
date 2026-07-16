import {
  isCanonicalMutationConfirmationGrant,
  isBoundConfirmedChatResponse,
  mutationApprovalOutcome,
  mutationConfirmationNotice,
  mutationConflictOutcome,
  mutationDenialOutcome
} from './ai-mutation-confirmation';
import {AiChatSocketEvent, AiMutationConfirmationDecisionResponse} from './ai-chat-panel.model';

describe('AI mutation confirmation boundary', () => {
  const future = '2099-07-15T00:00:00Z';
  const now = Date.parse('2099-07-14T00:00:00Z');
  const canonicalGrant = 'AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA';

  it('accepts only the exact owner-safe notice envelope', () => {
    expect(mutationConfirmationNotice(
      noticeEvent(), 'request-1', 'conversation-1', now
    )).toEqual({
      confirmationRequestId: 'confirmation-1',
      status: 'REQUESTED',
      expiresAt: future,
      toolName: 'delete_business_context',
      argumentsSummary: '{"id":1}'
    });
  });

  it('rejects fields outside the stable mutation-notice envelope', () => {
    expect(mutationConfirmationNotice({
      ...noticeEvent(), resource: null as unknown as string
    }, 'request-1', 'conversation-1', now)).toBeUndefined();
  });

  it.each([
    ['foreign request', {...noticeEvent(), requestId: 'request-2'}],
    ['foreign conversation', {...noticeEvent(), conversationId: 'conversation-2'}],
    ['expired', noticeEvent({expiresAt: '2099-07-13T23:59:59Z'})],
    ['nonexistent calendar date', noticeEvent({expiresAt: '2099-02-31T00:00:00Z'})],
    ['non-UTC date', noticeEvent({expiresAt: '2099-07-15T01:00:00+01:00'})],
    ['unknown metadata', noticeEvent({unexpectedTool: 'delete_item'})],
    ['unsafe tool name', noticeEvent({toolName: '<script>'})],
    ['oversized arguments summary', noticeEvent({argumentsSummary: 'x'.repeat(2015)})],
    ['smuggled grant', noticeEvent({confirmationGrant: canonicalGrant})],
    ['unknown envelope field', {...noticeEvent(), response: 'hidden'}]
  ])('rejects a %s notice', (_label, event) => {
    expect(mutationConfirmationNotice(
      event as AiChatSocketEvent, 'request-1', 'conversation-1', now
    )).toBeUndefined();
  });

  it('validates canonical 32-byte unpadded base64url grants', () => {
    expect(isCanonicalMutationConfirmationGrant(canonicalGrant)).toBe(true);
    expect(isCanonicalMutationConfirmationGrant(canonicalGrant + '=')).toBe(false);
    expect(isCanonicalMutationConfirmationGrant('A'.repeat(42) + 'B')).toBe(false);
    expect(isCanonicalMutationConfirmationGrant('A'.repeat(42) + '+')).toBe(false);
    expect(isCanonicalMutationConfirmationGrant('A'.repeat(42))).toBe(false);
  });

  it('accepts only a safe confirmed chat response bound to the exact conversation', () => {
    const response = {
      conversationId: 'conversation-1',
      agent: 'score-agent',
      response: 'Completed.',
      continuationRequired: false,
      progress: ['Done.'],
      events: [noticeEvent()]
    };
    expect(isBoundConfirmedChatResponse(response, 'conversation-1')).toBe(true);
    expect(isBoundConfirmedChatResponse(
      {...response, conversationId: 'conversation-2'}, 'conversation-1'
    )).toBe(false);
    expect(isBoundConfirmedChatResponse(
      {...response, confirmationGrant: canonicalGrant}, 'conversation-1'
    )).toBe(false);
    expect(isBoundConfirmedChatResponse(
      {...response, progress: [1]}, 'conversation-1'
    )).toBe(false);
  });

  it('returns a grant only for the exact first approval transition', () => {
    expect(mutationApprovalOutcome({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'APPROVED',
      disposition: 'APPROVED',
      expiresAt: future,
      approvedAt: '2099-07-14T00:30:00Z',
      confirmationGrant: canonicalGrant
    }, 'conversation-1', 'confirmation-1', now)).toEqual({
      kind: 'GRANTED', confirmationGrant: canonicalGrant
    });
  });

  it.each([
    ['foreign conversation', {conversationId: 'conversation-2'}],
    ['foreign request', {confirmationRequestId: 'confirmation-2'}],
    ['expired approval', {expiresAt: '2099-07-13T23:59:59Z'}],
    ['noncanonical grant', {confirmationGrant: 'A'.repeat(42) + 'B'}],
    ['unknown field', {unexpected: 'value'}]
  ])('fails closed for a %s approval response', (_label, change) => {
    const response = {
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'APPROVED' as const,
      disposition: 'APPROVED' as const,
      expiresAt: future,
      approvedAt: '2099-07-14T00:30:00Z',
      confirmationGrant: canonicalGrant,
      ...change
    };
    expect(mutationApprovalOutcome(
      response, 'conversation-1', 'confirmation-1', now
    ).kind).not.toBe('GRANTED');
  });

  it('classifies an already-approved response without its lost grant', () => {
    expect(mutationApprovalOutcome({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'APPROVED',
      disposition: 'ALREADY_APPROVED',
      expiresAt: future,
      approvedAt: '2099-07-14T00:30:00Z'
    }, 'conversation-1', 'confirmation-1', now)).toEqual({kind: 'LOST'});
  });

  it('rejects an already-approved response with missing, expired, or invalid expiry', () => {
    for (const expiresAt of [
      undefined, '2099-07-13T23:59:59Z', '2099-02-31T00:00:00Z'
    ]) {
      expect(mutationApprovalOutcome({
        confirmationRequestId: 'confirmation-1',
        conversationId: 'conversation-1',
        status: 'APPROVED',
        disposition: 'ALREADY_APPROVED',
        expiresAt,
        approvedAt: '2099-07-14T00:30:00Z'
      }, 'conversation-1', 'confirmation-1', now)).toEqual({kind: 'INVALID'});
    }
  });

  it.each([
    ['missing approvedAt', {approvedAt: undefined}],
    ['invalid approvedAt', {approvedAt: '2099-02-31T00:00:00Z'}],
    ['denied timestamp', {deniedAt: '2099-07-14T01:00:00Z'}],
    ['expired timestamp', {expiredAt: '2099-07-14T01:00:00Z'}],
    ['consumed timestamp', {consumedAt: '2099-07-14T01:00:00Z'}]
  ])('rejects approved response with %s', (_label, conflict) => {
    expect(mutationApprovalOutcome({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'APPROVED',
      disposition: 'APPROVED',
      expiresAt: future,
      approvedAt: '2099-07-14T00:30:00Z',
      confirmationGrant: canonicalGrant,
      ...conflict
    }, 'conversation-1', 'confirmation-1', now)).toEqual({kind: 'INVALID'});
  });

  it.each(['DENIED', 'ALREADY_DENIED'] as const)(
    'validates an exact %s response', disposition => {
      expect(mutationDenialOutcome({
        confirmationRequestId: 'confirmation-1',
        conversationId: 'conversation-1',
        status: 'DENIED',
        disposition,
        expiresAt: future,
        deniedAt: '2099-07-14T01:00:00Z'
      }, 'conversation-1', 'confirmation-1')).toBe(disposition);
    }
  );

  it.each([
    ['foreign conversation', {conversationId: 'conversation-2'}],
    ['foreign request', {confirmationRequestId: 'confirmation-2'}],
    ['conflict disposition', {status: 'APPROVED', disposition: 'CONFLICT'}],
    ['consumed disposition', {status: 'CONSUMED', disposition: 'CONSUMED'}],
    ['missing status', {status: undefined}],
    ['missing denied time', {deniedAt: undefined}],
    ['invalid denied time', {deniedAt: '2099-02-31T00:00:00Z'}],
    ['smuggled grant', {confirmationGrant: canonicalGrant}],
    ['unknown field', {unexpected: true}]
  ])('rejects a %s denial response', (_label, change) => {
    expect(mutationDenialOutcome({
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'DENIED',
      disposition: 'DENIED',
      expiresAt: future,
      deniedAt: '2099-07-14T01:00:00Z',
      ...change
    } as AiMutationConfirmationDecisionResponse, 'conversation-1', 'confirmation-1')).toBe('INVALID');
  });

  it('accepts only an exact consumed conflict for reconciliation', () => {
    const consumed = {
      confirmationRequestId: 'confirmation-1',
      conversationId: 'conversation-1',
      status: 'CONSUMED' as const,
      disposition: 'CONSUMED' as const,
      expiresAt: future,
      approvedAt: '2099-07-14T00:30:00Z',
      consumedAt: '2099-07-14T01:00:00Z'
    };
    expect(mutationConflictOutcome(
      consumed, 'conversation-1', 'confirmation-1'
    )).toBe('CONSUMED');
    expect(mutationConflictOutcome(
      {...consumed, conversationId: 'conversation-2'},
      'conversation-1', 'confirmation-1'
    )).toBe('INVALID');
    expect(mutationConflictOutcome(
      {...consumed, approvedAt: undefined},
      'conversation-1', 'confirmation-1'
    )).toBe('INVALID');
    expect(mutationConflictOutcome({
      ...consumed, status: 'APPROVED', disposition: 'CONFLICT'
    }, 'conversation-1', 'confirmation-1')).toBe('INVALID');
  });

  function noticeEvent(metadata: Record<string, unknown> = {}): AiChatSocketEvent {
    return {
      requestId: 'request-1',
      conversationId: 'conversation-1',
      type: 'system',
      subtype: 'mutation_confirmation_required',
      message: 'A data-changing action requires explicit approval.',
      continuationRequired: false,
      progress: [],
      ids: [],
      turnId: 'request-1',
      sequence: 4,
      visibility: 'visible',
      content: 'A data-changing action requires explicit approval.',
      metadata: {
        confirmationRequestId: 'confirmation-1',
        status: 'REQUESTED',
        expiresAt: future,
        toolName: 'delete_business_context',
        argumentsSummary: '{"id":1}',
        ...metadata
      }
    };
  }
});
