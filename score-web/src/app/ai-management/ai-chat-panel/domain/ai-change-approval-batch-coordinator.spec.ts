import {AiChangeApprovalBatchCoordinator} from './ai-change-approval-batch-coordinator';
import {AiChatPanelState} from './ai-chat-panel-state';
import {AiChatSocketEvent} from './ai-chat-panel.model';

describe('AiChangeApprovalBatchCoordinator', () => {
  let state: AiChatPanelState;
  let publish: ReturnType<typeof vi.fn>;
  let coordinator: AiChangeApprovalBatchCoordinator;
  const callbacks = {
    acknowledge: vi.fn(), completeProgress: vi.fn(), clearStatus: vi.fn(),
    scrollToBottom: vi.fn()
  };

  beforeEach(() => {
    vi.useFakeTimers();
    state = new AiChatPanelState();
    state.pending = true;
    state.conversationId = 'conversation-1';
    state.activeRequest = {requestId: 'request-1', conversationId: 'conversation-1'};
    publish = vi.fn();
    coordinator = new AiChangeApprovalBatchCoordinator({publish} as any);
    Object.values(callbacks).forEach(callback => callback.mockClear());
  });

  afterEach(() => vi.useRealTimers());

  it('admits one active batch and queues distinct later batches', () => {
    coordinator.handleRequired(state, event('batch-1'), 'request-1', callbacks);
    coordinator.handleRequired(state, event('batch-2'), 'request-1', callbacks);
    coordinator.handleRequired(state, event('batch-2'), 'request-1', callbacks);

    expect(state.changeApprovalBatch?.batchId).toBe('batch-1');
    expect(state.changeApprovalBatchQueue.map(batch => batch.batchId)).toEqual(['batch-2']);
    expect(state.messages.filter(message => message.role === 'guide')).toHaveLength(2);
    expect(callbacks.completeProgress).toHaveBeenCalledOnce();
  });

  it('publishes expanded decisions and returns to retryable state without an acknowledgement', () => {
    coordinator.handleRequired(state, event('batch-1'), 'request-1', callbacks);
    coordinator.decide(state, 'APPROVE', 'request-1', callbacks);

    expect(publish).toHaveBeenCalledWith('/app/ai/chat/change-approval', {
      requestId: 'request-1', conversationId: 'conversation-1', batchId: 'batch-1',
      decisions: [{confirmationRequestId: 'confirmation-batch-1', decision: 'APPROVE'}]
    });
    vi.advanceTimersByTime(15_000);
    expect(state.changeApprovalBatchBusy).toBe(false);
    expect(state.messages.at(-1)?.content).toContain('No acknowledgement');
  });

  it('advances to the next unexpired batch after an accepted decision', () => {
    coordinator.handleRequired(state, event('batch-1'), 'request-1', callbacks);
    coordinator.handleRequired(state, event('batch-2'), 'request-1', callbacks);
    coordinator.handleDecision(state, {
      requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
      subtype: 'change_approval_decision_accepted', metadata: {batchId: 'batch-1'}
    }, callbacks);

    expect(state.changeApprovalBatch?.batchId).toBe('batch-2');
    expect(state.changeApprovalBatchQueue).toEqual([]);
    expect(state.currentStatus).toBe('Approval required');
  });

  it('clears timers and all approval state atomically', () => {
    coordinator.handleRequired(state, event('batch-1'), 'request-1', callbacks);
    coordinator.decide(state, 'DENY', 'request-1', callbacks);
    coordinator.clear(state);
    vi.runAllTimers();

    expect(state.changeApprovalBatch).toBeUndefined();
    expect(state.changeApprovalBatchQueue).toEqual([]);
    expect(state.changeApprovalBatchBusy).toBe(false);
    expect(callbacks.scrollToBottom).not.toHaveBeenCalled();
  });

  it('cancels owned timers when its component scope is destroyed', () => {
    coordinator.handleRequired(state, event('batch-1'), 'request-1', callbacks);
    coordinator.decide(state, 'DENY', 'request-1', callbacks);
    coordinator.ngOnDestroy();
    vi.runAllTimers();

    expect(state.changeApprovalBatchBusy).toBe(true);
    expect(state.messages.some(message => message.content.includes('No acknowledgement')))
      .toBe(false);
  });

  function event(batchId: string): AiChatSocketEvent {
    return {
      requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
      subtype: 'change_approval_batch_required', metadata: {
        batchId, parallel: false, expiresAt: '2099-07-15T00:00:00Z', items: [{
          confirmationRequestId: `confirmation-${batchId}`,
          toolName: 'update_bbie', argumentsSummary: '{}'
        }]
      }
    };
  }
});
