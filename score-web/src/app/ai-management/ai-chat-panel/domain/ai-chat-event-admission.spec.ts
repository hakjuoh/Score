/**
 * Verifies live, replay, interaction, and specialist event-admission decisions.
 */

import {describe, expect, it} from 'vitest';
import {
  admitsRestLiveSideChannel,
  interactionEventDisposition,
  requestEventAdmission,
  restReplayDisposition
} from './ai-chat-event-admission';
import {AiChatSocketEvent} from './ai-chat-panel.model';

const event = (type: AiChatSocketEvent['type'], subtype?: string): AiChatSocketEvent => ({
  requestId: 'request-1', type, subtype
});

describe('AI chat event admission', () => {
  it('classifies identity-sensitive request events without owning UI identity state', () => {
    expect(requestEventAdmission(event('system', 'accepted')))
      .toBe('requires-active-identity');
    expect(requestEventAdmission({
      ...event('system', 'request_error'),
      content: 'Failed',
      sequence: 1,
      metadata: {
        terminal: true, recoverable: false, retryable: false,
        status: 'FAILED', generation: 1
      }
    })).toBe('requires-terminal-identity');
    expect(requestEventAdmission(event('system', 'unknown'))).toBe('reject');
  });

  it('admits unknown debug system events for the optional debug transcript', () => {
    expect(requestEventAdmission({
      ...event('system', 'provider_trace'),
      visibility: 'debug',
      content: 'Internal provider trace'
    })).toBe('admit');
  });

  it('admits live Tool output truncation notices', () => {
    const notice = {
      ...event('system', 'tool_output_truncated'),
      content: 'get_acc exceeded the configured per-tool output limit.'
    };
    expect(requestEventAdmission(notice)).toBe('admit');
    expect(admitsRestLiveSideChannel(notice)).toBe(true);
  });

  it('requires visible content for assistant stream and final events', () => {
    expect(requestEventAdmission({...event('assistant_update'), content: 'part'})).toBe('admit');
    expect(requestEventAdmission({...event('assistant_update'), content: ''})).toBe('reject');
    expect(requestEventAdmission({
      ...event('assistant_final'), conversationId: 'conversation-1', content: 'done'
    })).toBe('admit');
  });

  it.each([
    ['change_approval_batch_required', 'change-approval-required', 'socket', true],
    ['change_approval_decision_accepted', 'change-approval-decision', 'socket', true],
    ['change_approval_decision_rejected', 'change-approval-decision', 'socket', true],
    ['change_confirmation_required', 'change-confirmation-required', 'confirmation', false],
    ['elicitation_required', 'elicitation-required', 'ignore', true],
    ['elicitation_decision_accepted', 'elicitation-decision', 'ignore', true],
    ['elicitation_decision_rejected', 'elicitation-decision', 'ignore', true]
  ] as const)('applies the %s interaction transport matrix',
    (subtype, disposition, replay, live) => {
      const value = event('system', subtype);
      expect(interactionEventDisposition(value)).toBe(disposition);
      expect(restReplayDisposition(value)).toBe(replay);
      expect(admitsRestLiveSideChannel(value)).toBe(live);
    });

  it('does not treat an interaction-looking non-system event as an interaction', () => {
    const value = event('assistant_update', 'elicitation_required');
    expect(interactionEventDisposition(value)).toBe('none');
    expect(admitsRestLiveSideChannel(value)).toBe(false);
  });

  it('routes replay-only system and tool events deliberately', () => {
    expect(restReplayDisposition(event('system', 'provider_retry'))).toBe('system');
    expect(restReplayDisposition({
      ...event('system', 'request_error'), sequence: 1,
      metadata: {terminal: true, recoverable: false, retryable: false, status: 'FAILED'}
    })).toBe('socket');
    expect(restReplayDisposition(event('system', 'request_error'))).toBe('ignore');
    expect(restReplayDisposition(event('tool_group', 'started'))).toBe('tool');
    expect(restReplayDisposition(event('assistant_final'))).toBe('ignore');
  });

  it.each(['context_usage', 'context_compacted'])(
    'replays %s without admitting a duplicate live side-channel event', subtype => {
      const value = event('system', subtype);
      expect(restReplayDisposition(value)).toBe('system');
      expect(admitsRestLiveSideChannel(value)).toBe(false);
    });

  it('admits specialist tools without leaking specialist narration to the root chat', () => {
    const narration = {
      ...event('assistant_update'), content: 'worker update',
      metadata: {nodeId: 'worker-1', executionScope: 'worker'}
    };
    const tool = {...narration, type: 'tool_call' as const};
    expect(admitsRestLiveSideChannel(narration)).toBe(false);
    expect(admitsRestLiveSideChannel(tool)).toBe(true);
  });

  it.each([
    {...event('assistant_final'), content: 'missing conversation'},
    {...event('assistant_final'), conversationId: 'conversation-1', content: ''},
    {...event('tool_group', 'unknown'), groupId: 'group-1'},
    {...event('system', 'request_error'), sequence: 0,
      metadata: {terminal: true, recoverable: false, retryable: false, status: 'FAILED'}}
  ])('rejects malformed request event %#', malformed => {
    expect(requestEventAdmission(malformed)).toBe('reject');
  });
});
