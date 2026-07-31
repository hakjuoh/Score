/**
 * Verifies AI Change Repeat Coordinator coordination, state transitions, and edge cases.
 */

import {describe, expect, it} from 'vitest';
import {AiChangeRepeatCoordinator} from './ai-change-repeat-coordinator';
import {AiChatSocketEvent} from './ai-chat-panel.model';

function notice(overrides: Partial<AiChatSocketEvent> = {}): AiChatSocketEvent {
  return {
    requestId: 'request-1', type: 'system', subtype: 'change_confirmation_required',
    conversationId: 'conversation-1', content: 'A change requires explicit approval.',
    metadata: {
      confirmationRequestId: 'confirmation-1', status: 'REQUESTED',
      expiresAt: '2099-07-15T00:00:00Z', toolName: 'changeTool',
      argumentsSummary: '{}'
    },
    ...overrides
  } as AiChatSocketEvent;
}

describe('AiChangeRepeatCoordinator', () => {
  it('binds the first valid notice and produces one repeat opportunity', () => {
    const coordinator = new AiChangeRepeatCoordinator();
    coordinator.begin('request-1', 'change it', []);
    expect(coordinator.acceptNotice(
      notice(), 'request-1', undefined, undefined
    )).toBe('accepted');
    expect(coordinator.acceptNotice(
      notice({metadata: {...notice().metadata, confirmationRequestId: 'other'}}),
      'request-1', undefined, undefined
    )).toBe('ignored');
    expect(coordinator.finish('request-1', 'conversation-1').kind).toBe('confirmation');
    expect(coordinator.finish('request-1', 'conversation-1').kind).toBe('none');
  });

  it('fails closed when identity binding conflicts', () => {
    const coordinator = new AiChangeRepeatCoordinator();
    coordinator.begin('request-1', 'change it', []);
    coordinator.acceptNotice(notice(), 'request-1', undefined, undefined);
    coordinator.reject('request-1');
    expect(coordinator.finish('request-1', 'conversation-1')).toEqual({kind: 'none'});
  });

  it('does not clear a newer draft from an older terminal request', () => {
    const coordinator = new AiChangeRepeatCoordinator();
    coordinator.begin('request-2', 'new', []);
    coordinator.clear('request-1');
    expect(coordinator.draft?.requestId).toBe('request-2');
  });
});
