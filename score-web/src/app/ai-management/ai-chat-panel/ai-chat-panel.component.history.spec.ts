/**
 * Verifies the AI chat panel's streaming and conversation-history behavior.
 */

import {
  AiChatConversationSummary,
  Subject,
  api,
  component,
  confirmDialog,
  of,
  setupAiChatPanelSpec,
  teardownAiChatPanelSpec
} from './ai-chat-panel.component.spec-support';

describe('AiChatPanelComponent streaming and history management', () => {
  beforeEach(setupAiChatPanelSpec);
  afterEach(teardownAiChatPanelSpec);

  it('keeps a visible fallback notice when later assistant updates arrive', () => {
    component.state.prompt = 'Use a fallback model';
    component.send();
    (component as any).handleSocketEvent({
      requestId: 'request-1',
      type: 'system',
      subtype: 'model_fallback',
      content: 'Continuing with a fallback model.',
      metadata: {
        fromDeployment: 'claude-fable-5',
        toDeployment: 'claude-haiku-4-5'
      }
    });

    (component as any).handleSocketEvent({
      requestId: 'request-1',
      type: 'assistant_update',
      content: 'Working on the request.'
    });

    expect(component.state.messages).toContainEqual({
      role: 'progress',
      content: 'Continuing with a fallback model.',
      eventType: 'model_fallback',
      inProgress: false
    });
    expect(component.state.messages).toContainEqual({
      role: 'progress',
      content: 'Working on the request.',
      eventType: 'assistant_update',
      requestId: 'request-1',
      inProgress: true
    });
  });

  it('coalesces streamed content deltas and replaces them with the final response', () => {
    component.state.prompt = 'Stream the answer';
    component.send();

    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'assistant_update', content: 'Hello '
    });
    (component as any).handleSocketEvent({
      requestId: 'request-1', type: 'assistant_update', content: 'world'
    });

    expect(component.state.messages.filter(message => message.eventType === 'assistant_update'))
      .toEqual([expect.objectContaining({
        role: 'progress', content: 'Hello world', inProgress: true
      })]);

    (component as any).handleSocketEvent({
      requestId: 'request-1', conversationId: 'conversation-1',
      type: 'assistant_final', content: 'Hello world', response: 'Hello world'
    });

    expect(component.state.messages.filter(message => message.eventType === 'assistant_update'))
      .toEqual([]);
    expect(component.state.messages.filter(message => message.role === 'assistant'))
      .toEqual([expect.objectContaining({content: 'Hello world'})]);
  });

  it('keeps existing history visible after a failed refresh and clears the error after a successful reload', () => {
    const historyResponse = new Subject<AiChatConversationSummary[]>();
    const existing = {
      conversationId: 'conversation-1', title: 'Existing conversation',
      visibleMessageCount: 2, compacted: false
    };
    component.state.conversationHistory = [existing];
    api.getConversationHistory.mockReturnValueOnce(historyResponse);

    component.loadConversationHistory();

    expect(component.state.conversationHistoryLoading).toBe(true);
    expect(component.state.conversationHistoryLoadFailed).toBe(false);

    historyResponse.error(new Error('Network unavailable'));

    expect(component.state.conversationHistory).toEqual([existing]);
    expect(component.state.conversationHistoryLoading).toBe(false);
    expect(component.state.conversationHistoryLoadFailed).toBe(true);

    const retryResponse = new Subject<AiChatConversationSummary[]>();
    const refreshed = {
      conversationId: 'conversation-2', title: 'Refreshed conversation',
      visibleMessageCount: 1, compacted: false
    };
    api.getConversationHistory.mockReturnValueOnce(retryResponse);

    component.loadConversationHistory();

    expect(component.state.conversationHistoryLoading).toBe(true);
    expect(component.state.conversationHistoryLoadFailed).toBe(true);

    retryResponse.next([refreshed]);

    expect(component.state.conversationHistory).toEqual([refreshed]);
    expect(component.state.conversationHistoryLoading).toBe(false);
    expect(component.state.conversationHistoryLoadFailed).toBe(false);
  });

  it('does not delete a conversation when confirmation is cancelled', () => {
    component.state.conversationHistory = [{
      conversationId: 'conversation-1', title: 'Keep this conversation',
      visibleMessageCount: 2, compacted: false
    }];

    component.deleteConversation('conversation-1');

    expect(confirmDialog.open).toHaveBeenCalledOnce();
    expect(api.deleteConversation).not.toHaveBeenCalled();
    expect(component.state.conversationHistory).toHaveLength(1);
  });

  it('permanently deletes a conversation only after confirmation', () => {
    component.state.conversationHistory = [{
      conversationId: 'conversation-1', title: 'Delete this conversation',
      visibleMessageCount: 2, compacted: false
    }];
    confirmDialog.open.mockReturnValueOnce({afterClosed: () => of(true)});

    component.deleteConversation('conversation-1');

    expect(confirmDialog.newConfig.mock.results[0].value.data).toEqual({
      header: 'Delete conversation?',
      content: ['“Delete this conversation” will be permanently deleted.'],
      action: 'Delete'
    });
    expect(api.deleteConversation).toHaveBeenCalledWith('conversation-1');
    expect(component.state.conversationHistory).toEqual([]);
  });

});
