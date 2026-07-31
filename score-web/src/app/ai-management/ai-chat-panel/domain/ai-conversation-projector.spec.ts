import {AiConversationProjector} from './ai-conversation-projector';

describe('AiConversationProjector', () => {
  it('selects the canonical answer over workflow previews without a render queue', () => {
    const projector = new AiConversationProjector();

    expect(projector.projectStoredMessages([
      {index: 0, role: 'assistant', content: 'Old preview.', requestId: 'r1',
        subtype: 'workflow_result'},
      {index: 1, role: 'assistant', content: 'New preview.', requestId: 'r1',
        subtype: 'workflow_result'},
      {index: 2, role: 'assistant', content: 'Canonical answer.', requestId: 'r1'}
    ])).toEqual([{role: 'assistant', content: 'Canonical answer.'}]);
  });

  it('keeps a fallback preview before unrelated later-turn messages', () => {
    const projector = new AiConversationProjector();

    expect(projector.projectStoredMessages([
      {index: 0, role: 'assistant', content: 'Fallback A.', requestId: 'r1',
        subtype: 'workflow_result'},
      {index: 1, role: 'user', content: 'Question B.', requestId: 'r2'},
      {index: 2, role: 'assistant', content: 'Answer B.', requestId: 'r2'}
    ])).toEqual([
      {role: 'assistant', content: 'Fallback A.', eventType: 'workflow_result', requestId: 'r1'},
      {role: 'user', content: 'Question B.'},
      {role: 'assistant', content: 'Answer B.'}
    ]);
  });

  it('fails closed for a persisted tool row without durable execution identity', () => {
    const projector = new AiConversationProjector();

    expect(projector.createSession().acceptEvent({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'tool_call',
      subtype: 'completed', response: 'Pretend tool completed.'
    })).toEqual([]);
  });

  it('owns pending workflow preview state and discards it on reset', () => {
    const session = new AiConversationProjector().createSession();
    expect(session.acceptEvent({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'assistant',
      subtype: 'workflow_result', response: 'Preview.'
    })).toEqual([]);

    session.reset();

    expect(session.flushPendingEvents()).toEqual([]);
  });

  it('buffers later replay turns until an unresolved preview can keep its position', () => {
    const session = new AiConversationProjector().createSession();
    expect(session.acceptEvent({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'assistant',
      subtype: 'workflow_result', response: 'Fallback A.', index: 0
    })).toEqual([]);
    expect(session.acceptEvent({
      requestId: 'r2', type: 'HISTORY_MESSAGE', message: 'user',
      response: 'Question B.', index: 1
    })).toEqual([]);

    expect(session.flushPendingEvents()).toEqual([
      {role: 'assistant', content: 'Fallback A.', eventType: 'workflow_result', requestId: 'r1'},
      {role: 'user', content: 'Question B.'}
    ]);
  });
});
