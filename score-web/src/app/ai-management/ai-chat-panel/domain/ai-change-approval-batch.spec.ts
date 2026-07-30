import {describe, expect, it} from 'vitest';
import {AiChatSocketEvent} from './ai-chat-panel.model';
import {
  changeApprovalBatchNotice,
  pendingChangeApprovalBatches
} from './ai-change-approval-batch';

describe('changeApprovalBatchNotice', () => {
  const valid: AiChatSocketEvent = {
    requestId: 'request-1', conversationId: 'conversation-1',
    type: 'system', subtype: 'change_approval_batch_required',
    metadata: {
      batchId: 'batch-1', parallel: true,
      expiresAt: '2099-07-15T00:00:00Z',
      items: [
        {confirmationRequestId: 'confirmation-1', toolName: 'update_bbie',
          argumentsSummary: '{"id":1}', agentId: 'agent-a', agentLabel: 'Agent A'},
        {confirmationRequestId: 'confirmation-2', toolName: 'delete_bbie',
          argumentsSummary: '{"id":2}', agentId: 'agent-b', agentLabel: 'Agent B'}
      ]
    }
  };

  it('accepts a complete correlated parallel batch', () => {
    expect(changeApprovalBatchNotice(
      valid, 'request-1', 'conversation-1'
    )).toEqual({
      batchId: 'batch-1', requestId: 'request-1', conversationId: 'conversation-1',
      parallel: true, expiresAt: '2099-07-15T00:00:00Z',
      items: [
        {confirmationRequestId: 'confirmation-1', toolName: 'update_bbie',
          argumentsSummary: '{"id":1}', agentId: 'agent-a', agentLabel: 'Agent A'},
        {confirmationRequestId: 'confirmation-2', toolName: 'delete_bbie',
          argumentsSummary: '{"id":2}', agentId: 'agent-b', agentLabel: 'Agent B'}
      ]
    });
  });

  it.each([
    ['foreign request', {...valid, requestId: 'request-2'}],
    ['foreign conversation', {...valid, conversationId: 'conversation-2'}],
    ['expired', {...valid, metadata: {...valid.metadata, expiresAt: '2000-01-01T00:00:00Z'}}],
    ['duplicate item', {...valid, metadata: {...valid.metadata, items: [
      (valid.metadata?.['items'] as object[])[0],
      (valid.metadata?.['items'] as object[])[0]
    ]}}],
    ['missing parallel contract', {...valid, metadata: {...valid.metadata, parallel: 'true'}}]
  ])('fails closed for a %s batch', (_label, event) => {
    expect(changeApprovalBatchNotice(
      event as AiChatSocketEvent, 'request-1', 'conversation-1'
    )).toBeUndefined();
  });

  it('recovers only unresolved unexpired batches from durable request history', () => {
    const requested = (index: number, batchId: string) => ({
      index, role: 'guide', content: 'Approval requested.', requestId: 'request-1',
      subtype: 'change_approval_batch_requested', metadata: {
        batchId, parallel: false, expiresAt: '2099-07-15T00:00:00Z',
        items: [{confirmationRequestId: `confirmation-${batchId}`,
          toolName: 'update_bbie', argumentsSummary: '{}'}]
      }
    });
    const messages = [
      requested(1, 'resolved'),
      {index: 2, role: 'guide', content: 'Approved.', requestId: 'request-1',
        subtype: 'change_approval_decision', metadata: {batchId: 'resolved'}},
      requested(3, 'pending'),
      {...requested(4, 'foreign'), requestId: 'request-2'}
    ];

    expect(pendingChangeApprovalBatches(
      messages, 'request-1', 'conversation-1'
    )).toEqual([expect.objectContaining({batchId: 'pending'})]);
  });
});
