import {AiRequestTerminalCoordinator} from './ai-request-terminal-coordinator';
import {AiChatPanelState} from './ai-chat-panel-state';

describe('AiRequestTerminalCoordinator', () => {
  it('applies terminal cleanup once in an explicit stable order', () => {
    const calls: string[] = [];
    const state = new AiChatPanelState();
    state.pending = true;
    state.reconciliationRequired = false;
    state.elicitation = {} as any;
    state.changeApprovalBatch = {} as any;
    const coordinator = new AiRequestTerminalCoordinator(
      {reset: () => calls.push('resetCancellation')} as any,
      {
        completeProgressMessages: () => calls.push('completeProgress'),
        completeToolGroupMessages: () => calls.push('completeTools'),
        clearStatusMessage: () => calls.push('clearStatus'),
        clearToolCallTracking: () => calls.push('clearTools')
      } as any,
      {clear: () => calls.push('clearElicitation')} as any,
      {clear: () => calls.push('clearApprovals')} as any,
      {cancel: () => calls.push('cancelConfirmed')} as any
    );

    coordinator.transition(state, {
      agentStatus: 'failed', reconciliationRequired: true,
      cancellation: 'reset', completedPayload: 'clear', toolGroups: 'complete',
      confirmedChange: {kind: 'cancel', requestId: 'request-1'},
      changeRepeat: {kind: 'clear-request', requestId: 'request-1'}
    }, {
      beforeSettlement: () => calls.push('beforeSettlement'),
      settleAgent: status => calls.push(`settle:${status}`),
      clearTimers: () => calls.push('clearTimers'),
      clearCompletedPayload: () => calls.push('clearCompleted'),
      clearChangeRepeat: () => calls.push('clearRepeat'),
      releaseRequest: () => calls.push('releaseRequest')
    });

    expect(calls).toEqual([
      'clearCompleted', 'resetCancellation', 'completeProgress', 'completeTools',
      'beforeSettlement', 'settle:failed', 'clearTimers', 'clearStatus', 'clearElicitation',
      'clearApprovals', 'cancelConfirmed', 'clearRepeat', 'releaseRequest', 'clearTools'
    ]);
    expect(state.pending).toBe(false);
    expect(state.reconciliationRequired).toBe(true);
  });

  it('leaves optional owners untouched for a minimal successful transition', () => {
    const cancellation = {reset: vi.fn()};
    const confirmed = {cancel: vi.fn()};
    const callbacks = {
      settleAgent: vi.fn(), clearTimers: vi.fn(), clearCompletedPayload: vi.fn(),
      clearChangeRepeat: vi.fn(), releaseRequest: vi.fn()
    };
    const coordinator = new AiRequestTerminalCoordinator(
      cancellation as any,
      {
        completeProgressMessages: vi.fn(), completeToolGroupMessages: vi.fn(),
        clearStatusMessage: vi.fn(), clearToolCallTracking: vi.fn()
      } as any,
      {clear: vi.fn()} as any, {clear: vi.fn()} as any, confirmed as any
    );

    const state = new AiChatPanelState();
    state.reconciliationRequired = true;
    coordinator.transition(state, {
      agentStatus: 'completed', reconciliationRequired: false,
      cancellation: 'preserve', completedPayload: 'preserve', toolGroups: 'preserve',
      confirmedChange: {kind: 'preserve'}, changeRepeat: {kind: 'preserve'}
    }, callbacks);

    expect(cancellation.reset).not.toHaveBeenCalled();
    expect(confirmed.cancel).not.toHaveBeenCalled();
    expect(callbacks.clearCompletedPayload).not.toHaveBeenCalled();
    expect(callbacks.clearChangeRepeat).not.toHaveBeenCalled();
    expect(callbacks.releaseRequest).toHaveBeenCalledOnce();
    expect(state.reconciliationRequired).toBe(false);
  });

  it('finishes invariant cleanup and then rethrows a settlement preparation error', () => {
    const state = new AiChatPanelState();
    state.pending = true;
    const clearStatusMessage = vi.fn();
    const clearToolCallTracking = vi.fn();
    const releaseRequest = vi.fn();
    const coordinator = new AiRequestTerminalCoordinator(
      {reset: vi.fn()} as any,
      {
        completeProgressMessages: vi.fn(), completeToolGroupMessages: vi.fn(),
        clearStatusMessage, clearToolCallTracking
      } as any,
      {clear: vi.fn()} as any, {clear: vi.fn()} as any, {cancel: vi.fn()} as any
    );
    const failure = new Error('replay failed');

    expect(() => coordinator.transition(state, {
      agentStatus: 'failed', reconciliationRequired: true,
      cancellation: 'preserve', completedPayload: 'preserve', toolGroups: 'preserve',
      confirmedChange: {kind: 'preserve'}, changeRepeat: {kind: 'clear-all'}
    }, {
      beforeSettlement: () => { throw failure; },
      settleAgent: vi.fn(), clearTimers: vi.fn(), clearCompletedPayload: vi.fn(),
      clearChangeRepeat: vi.fn(), releaseRequest
    })).toThrow(failure);

    expect(releaseRequest).toHaveBeenCalledOnce();
    expect(clearStatusMessage).toHaveBeenCalledOnce();
    expect(clearToolCallTracking).toHaveBeenCalledOnce();
    expect(state.pending).toBe(false);
    expect(state.reconciliationRequired).toBe(true);
  });
});
