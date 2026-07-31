import {describe, expect, it, vi} from 'vitest';
import {AiRequestDispatchCoordinator} from './ai-request-dispatch-coordinator';
import {AiChatPanelState} from './ai-chat-panel-state';

describe('AiRequestDispatchCoordinator', () => {
  it('prepares normal and confirmed requests through one state transition', () => {
    const coordinator = new AiRequestDispatchCoordinator();
    const state = new AiChatPanelState();
    state.prompt = 'draft';
    const calls: string[] = [];
    const callbacks = {
      activate: (id: string) => calls.push(`activate:${id}`),
      beginChangeRepeat: () => calls.push('repeat'),
      clearToolTracking: () => calls.push('tools'),
      resizePrompt: () => calls.push('resize'),
      flushWorkspace: () => calls.push('flush'),
      userMessageContent: (prompt: string) => `visible:${prompt}`
    };

    coordinator.prepare(state, {
      requestId: 'request-1', prompt: 'hello', attachments: [],
      intent: {kind: 'normal'}
    }, callbacks);

    expect(state.pending).toBe(true);
    expect(state.prompt).toBe('');
    expect(state.messages.at(-1)?.content).toBe('visible:hello');
    expect(calls).toEqual(['repeat', 'activate:request-1', 'tools', 'flush', 'resize']);
  });

  it('preserves the composer for an unchanged approved repeat', () => {
    const coordinator = new AiRequestDispatchCoordinator();
    const state = new AiChatPanelState();
    state.prompt = 'keep';
    const resizePrompt = vi.fn();
    coordinator.prepare(state, {
      requestId: 'request-2', prompt: 'repeat', attachments: [],
      intent: {kind: 'approved-repeat'}
    }, {
      activate: vi.fn(), beginChangeRepeat: vi.fn(), clearToolTracking: vi.fn(),
      resizePrompt, flushWorkspace: vi.fn(), userMessageContent: value => value
    });
    expect(state.prompt).toBe('keep');
    expect(resizePrompt).not.toHaveBeenCalled();
  });
});
