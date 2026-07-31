/**
 * Verifies AI Elicitation Coordinator coordination, state transitions, and edge cases.
 */

import {AiElicitationCoordinator} from './ai-elicitation-coordinator';
import {AiChatPanelState} from './ai-chat-panel-state';
import {AiChatSocketEvent} from './ai-chat-panel.model';

describe('AiElicitationCoordinator', () => {
  let state: AiChatPanelState;
  let publish: ReturnType<typeof vi.fn>;
  let coordinator: AiElicitationCoordinator;
  const callbacks = {
    acknowledge: vi.fn(), completeProgress: vi.fn(), clearStatus: vi.fn(),
    showStatus: vi.fn(), scrollToBottom: vi.fn()
  };

  beforeEach(() => {
    state = new AiChatPanelState();
    state.pending = true;
    state.conversationId = 'conversation-1';
    state.activeRequest = {
      requestId: 'request-1', conversationId: 'conversation-1', generation: 7
    };
    publish = vi.fn();
    coordinator = new AiElicitationCoordinator({publish} as any);
    Object.values(callbacks).forEach(callback => callback.mockClear());
  });

  it('admits a correlated notice and rejects a conflicting interaction', () => {
    coordinator.handleRequired(state, required('elicitation-1'), 'request-1', callbacks);
    coordinator.handleRequired(state, required('elicitation-2'), 'request-1', callbacks);

    expect(state.elicitation?.elicitationId).toBe('elicitation-1');
    expect(state.currentStatus).toBe('Waiting for your input');
    expect(callbacks.acknowledge).toHaveBeenCalledOnce();
  });

  it('publishes only the accepted form content', () => {
    coordinator.handleRequired(state, required('elicitation-1'), 'request-1', callbacks);
    coordinator.respond(
      state, {action: 'ACCEPT', content: {strategy: 'merge'}}, 'request-1', callbacks
    );

    expect(publish).toHaveBeenCalledWith('/app/ai/chat/elicitation', {
      requestId: 'request-1', conversationId: 'conversation-1',
      elicitationId: 'elicitation-1', generation: 7, action: 'ACCEPT',
      content: {strategy: 'merge'}
    });
    expect(state.elicitationBusy).toBe(true);
  });

  it('keeps a rejected response retryable and clears an accepted response', () => {
    coordinator.handleRequired(state, required('elicitation-1'), 'request-1', callbacks);
    coordinator.handleDecision(state, decision('elicitation_decision_rejected'), callbacks);
    expect(state.elicitation?.elicitationId).toBe('elicitation-1');
    expect(state.elicitationBusy).toBe(false);

    coordinator.handleDecision(state, decision('elicitation_decision_accepted'), callbacks);
    expect(state.elicitation).toBeUndefined();
    expect(state.currentStatus).toBe('Working');
    expect(callbacks.showStatus).toHaveBeenCalledWith('Response sent. Continuing.', true);
  });

  it('turns a synchronous transport failure into a retryable chat error', () => {
    publish.mockImplementation(() => { throw new Error('offline'); });
    coordinator.handleRequired(state, required('elicitation-1'), 'request-1', callbacks);
    coordinator.respond(state, {action: 'DECLINE', content: {}}, 'request-1', callbacks);

    expect(state.elicitationBusy).toBe(false);
    expect(state.currentStatus).toBe('Waiting for your input');
    expect(state.messages.at(-1)).toEqual({role: 'error', content: 'Could not send your response.'});
    expect(callbacks.scrollToBottom).toHaveBeenCalledOnce();
  });

  function required(elicitationId: string): AiChatSocketEvent {
    return {
      requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
      subtype: 'elicitation_required', visibility: 'visible', metadata: {
        elicitationId, generation: 7, mode: 'form', expiresAt: '2099-07-15T00:00:00Z',
        message: 'Choose how to continue.', requestedSchema: {
          type: 'object', properties: {strategy: {type: 'string'}}
        }
      }
    };
  }

  function decision(subtype: string): AiChatSocketEvent {
    return {
      requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
      subtype, metadata: {elicitationId: 'elicitation-1'}
    };
  }
});
