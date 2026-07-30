import {AiConversationRestoreCallbacks, AiConversationRestoreService} from './ai-conversation-restore.service';
import {AiChatMessage, AiChatSocketEvent} from './ai-chat-panel.model';

const TOKEN_1 = '00000000-0000-4000-8000-000000000001';
const TOKEN_2 = '00000000-0000-4000-8000-000000000002';
const TOKEN_3 = '00000000-0000-4000-8000-000000000003';

describe('AiConversationRestoreService', () => {
  let service: AiConversationRestoreService;
  let messages: AiChatMessage[];
  let callbacks: AiConversationRestoreCallbacks;
  let finished: ReturnType<typeof vi.fn>;
  let handle: (
    event: AiChatSocketEvent,
    callbacks: AiConversationRestoreCallbacks,
    token?: string,
    sequence?: number
  ) => void;

  beforeEach(() => {
    vi.useFakeTimers();
    service = new AiConversationRestoreService();
    messages = [];
    finished = vi.fn();
    callbacks = {
      setConversationId: vi.fn(),
      setModelName: vi.fn(),
      setReasoningEffort: vi.fn(),
      resetMessages: vi.fn(() => {
        messages = [];
      }),
      setRestoring: vi.fn(),
      setCurrentStatus: vi.fn(),
      clearStatus: vi.fn(),
      pushMessage: message => {
        messages.push(message);
        return messages.length - 1;
      },
      setMessage: (index, message) => messages[index] = message,
      hasMessage: index => index < messages.length,
      scrollTop: vi.fn(),
      updateScrollButton: vi.fn(),
      focusPrompt: vi.fn(),
      finish: finished
    };
    service.expectAttempt(TOKEN_1, 1);
    handle = (event, eventCallbacks, token = TOKEN_1, sequence = 1) => service.handleEvent({
      ...event,
      metadata: {...event.metadata, restoreToken: token, restoreSequence: sequence}
    }, eventCallbacks);
  });

  it('restores model settings from the admitted history metadata', () => {
    handle({
      requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1',
      metadata: {
        modelName: 'gpt-5.6-sol', reasoningEffort: 'high'
      }
    }, callbacks);

    expect(callbacks.setModelName).toHaveBeenCalledWith('gpt-5.6-sol');
    expect(callbacks.setReasoningEffort).toHaveBeenCalledWith('high');
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('buffers indexed restore messages and emits them in server order', async () => {
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'assistant', response: 'second', index: 1}, callbacks);
    handle({requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'user', response: 'first', index: 0}, callbacks);
    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks);

    await vi.runAllTimersAsync();

    expect(messages.map(message => message.content)).toEqual(['first', 'second']);
    expect(finished).toHaveBeenCalledOnce();
  });

  it('restores downloadable files on assistant history messages', async () => {
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'assistant',
      response: 'Download the generated report.', index: 0,
      files: [{
        fileId: 'file-history', format: 'pdf', filename: 'report.pdf',
        mediaType: 'application/pdf', size: 96, sha256: 'history-sha256',
        downloadUrl: '/api/ai/chat/conversations/c1/files/file-history'
      }]
    }, callbacks);
    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks);

    await vi.runAllTimersAsync();

    expect(messages).toEqual([{
      role: 'assistant', content: 'Download the generated report.',
      files: [expect.objectContaining({fileId: 'file-history', filename: 'report.pdf'})]
    }]);
  });

  it('restores only the canonical answer after legacy Workflow iterations', async () => {
    handle({requestId: 'restore', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'assistant',
      subtype: 'workflow_result', response: 'First synthesis.', index: 0
    }, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'assistant',
      subtype: 'workflow_result', response: 'Revised synthesis.', index: 1
    }, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'assistant',
      response: 'Canonical answer.', index: 2
    }, callbacks);
    handle({requestId: 'restore', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks);

    await vi.runAllTimersAsync();

    expect(messages).toEqual([{role: 'assistant', content: 'Canonical answer.'}]);
  });

  it('restores only the latest Workflow result when no canonical answer exists', async () => {
    handle({requestId: 'restore', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'assistant',
      subtype: 'workflow_result', response: 'First synthesis.', index: 0
    }, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'assistant',
      subtype: 'workflow_result', response: 'Revised synthesis.', index: 1
    }, callbacks);
    handle({requestId: 'restore', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks);

    await vi.runAllTimersAsync();

    expect(messages).toEqual([{
      role: 'assistant', content: 'Revised synthesis.',
      eventType: 'workflow_result', requestId: 'r1'
    }]);
  });

  it('coalesces legacy Workflow results in synchronous history projection', () => {
    const projected = service.projectStoredMessages([
      {index: 0, role: 'user', content: 'Run the checks.', requestId: 'r1'},
      {
        index: 1, role: 'assistant', content: 'First synthesis.',
        subtype: 'workflow_result', requestId: 'r1'
      },
      {
        index: 2, role: 'assistant', content: 'Revised synthesis.',
        subtype: 'workflow_result', requestId: 'r1'
      },
      {index: 3, role: 'assistant', content: 'Canonical answer.', requestId: 'r1'}
    ]);

    expect(projected).toEqual([
      {role: 'user', content: 'Run the checks.'},
      {role: 'assistant', content: 'Canonical answer.'}
    ]);
  });

  it('isolates reused workflow node ids by historical turn during WebSocket restore', async () => {
    const rootMetadata = {
      node_id: 'main:1:planned-workflow', parent_node_id: 'main',
      depth: 1, member_count: 1, status: 'started'
    };
    const workerMetadata = {
      node_id: 'main:1:planned-workflow:agent:count-accs',
      parent_node_id: 'main:1:planned-workflow', depth: 2,
      agent_name: 'Evidence researcher', status: 'started'
    };
    handle({requestId: 'restore-request', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({
      requestId: 'restore-request', type: 'HISTORY_MESSAGE', message: 'agent_event',
      turnId: 'turn-1', subtype: 'workflow_started', response: 'First workflow.',
      index: 0, metadata: rootMetadata
    }, callbacks);
    handle({
      requestId: 'restore-request', type: 'HISTORY_MESSAGE', message: 'agent_event',
      turnId: 'turn-1', subtype: 'subagent_started', response: 'First worker.',
      index: 1, metadata: workerMetadata
    }, callbacks);
    handle({
      requestId: 'restore-request', type: 'HISTORY_MESSAGE', message: 'agent_event',
      turnId: 'turn-2', subtype: 'workflow_started', response: 'Second workflow.',
      index: 2, metadata: rootMetadata
    }, callbacks);
    handle({
      requestId: 'restore-request', type: 'HISTORY_MESSAGE', message: 'agent_event',
      turnId: 'turn-2', subtype: 'subagent_started', response: 'Second worker.',
      index: 3, metadata: workerMetadata
    }, callbacks);
    handle({
      requestId: 'restore-request', type: 'HISTORY_MESSAGE', message: 'provider_event',
      turnId: 'turn-2', subtype: 'provider_error', response: 'Turn two overloaded.',
      index: 4, metadata: workerMetadata
    }, callbacks);
    handle({requestId: 'restore-request', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks);

    await vi.runAllTimersAsync();

    const groups = messages.filter(message => message.role === 'workflow_group');
    expect(groups).toHaveLength(2);
    expect(groups[0].activities?.flatMap(activity => activity.events))
      .not.toContainEqual(expect.objectContaining({content: 'Turn two overloaded.'}));
    expect(groups[1].activities?.flatMap(activity => activity.events))
      .toContainEqual(expect.objectContaining({
        status: 'provider_error', content: 'Turn two overloaded.'
      }));
  });

  it('does not expose persisted reasoning that was absent from the completed live transcript', async () => {
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'user',
      response: 'How many records?', index: 0
    }, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'debug',
      response: 'Reasoning: count the authoritative records.', index: 1
    }, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'assistant',
      response: 'There are 12 records.', index: 2
    }, callbacks);
    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks);

    await vi.runAllTimersAsync();

    expect(messages).toEqual([
      {role: 'user', content: 'How many records?'},
      {role: 'assistant', content: 'There are 12 records.'}
    ]);
  });

  it('ignores already drained duplicate indexes', async () => {
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'user',
      response: 'first', index: 0
    }, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'user',
      response: 'duplicate', index: 0
    }, callbacks);
    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks);

    await vi.runAllTimersAsync();

    expect(messages.map(message => message.content)).toEqual(['first']);
    expect(finished).toHaveBeenCalledOnce();
  });

  it('keeps the first frame when an undrained index is delivered twice', async () => {
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'assistant',
      response: 'canonical second', index: 1
    }, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'assistant',
      response: 'replacement second', index: 1
    }, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'user',
      response: 'first', index: 0
    }, callbacks);
    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks);

    await vi.runAllTimersAsync();

    expect(messages.map(message => message.content)).toEqual(['first', 'canonical second']);
    expect(finished).toHaveBeenCalledOnce();
  });

  it('atomically restarts a replay after a repeated history start', async () => {
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'user',
      response: 'stale prefix', index: 0
    }, callbacks);

    service.expectAttempt(TOKEN_2, 2);
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks, TOKEN_2, 2);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'user',
      response: 'replayed prefix', index: 0
    }, callbacks, TOKEN_2, 2);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'assistant',
      response: 'replayed answer', index: 1
    }, callbacks, TOKEN_2, 2);
    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks, TOKEN_2, 2);

    await vi.runAllTimersAsync();

    expect(messages.map(message => message.content)).toEqual(['replayed prefix', 'replayed answer']);
    expect(finished).toHaveBeenCalledOnce();
  });

  it('ignores an unseen older start after the expected replay has started', async () => {
    service.expectAttempt(TOKEN_2, 2);
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks, TOKEN_2, 2);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'user',
      response: 'new prefix', index: 0
    }, callbacks, TOKEN_2, 2);

    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks, TOKEN_1);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'assistant',
      response: 'late old answer', index: 1
    }, callbacks, TOKEN_1);
    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks, TOKEN_1);
    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks, TOKEN_2, 2);

    await vi.runAllTimersAsync();

    expect(messages.map(message => message.content)).toEqual(['new prefix']);
    expect(finished).toHaveBeenCalledOnce();
  });

  it('ignores an already-enqueued older error while the expected replay is active', () => {
    service.expectAttempt(TOKEN_2, 2);
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks, TOKEN_2, 2);

    handle({
      requestId: 'r1', type: 'system', subtype: 'error',
      content: 'Late failure from attempt A.'
    }, callbacks, TOKEN_1, 1);

    expect(messages).toEqual([]);
    expect(finished).not.toHaveBeenCalled();
    expect(callbacks.setCurrentStatus).toHaveBeenLastCalledWith('Restoring');
  });

  it('keeps client sequence ordering across a server process restart', async () => {
    service.reset();
    service.expectAttempt(TOKEN_3, 41);
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks, TOKEN_3, 41);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'user',
      response: 'pre-restart prefix', index: 0
    }, callbacks, TOKEN_3, 41);

    service.expectAttempt(TOKEN_1, 42);
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks, TOKEN_1, 42);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'user',
      response: 'post-restart prefix', index: 0
    }, callbacks, TOKEN_1, 42);
    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks, TOKEN_1, 42);

    await vi.runAllTimersAsync();

    expect(messages.map(message => message.content)).toEqual(['post-restart prefix']);
    expect(finished).toHaveBeenCalledOnce();
  });

  it('clears the expected attempt when the panel cancels a restore', () => {
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);

    service.cancel();
    service.expectAttempt(TOKEN_2, 2);

    handle({requestId: 'r2', type: 'HISTORY_START', conversationId: 'c2'}, callbacks, TOKEN_2, 2);

    expect(callbacks.resetMessages).toHaveBeenCalledTimes(2);
    expect(callbacks.setConversationId).toHaveBeenLastCalledWith('c2');
  });

  it('ignores duplicate restore identities', async () => {
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'user',
      response: 'canonical', index: 0
    }, callbacks);
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    expect(finished).not.toHaveBeenCalled();

    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks);
    await vi.runAllTimersAsync();

    expect(messages.map(message => message.content)).toEqual(['canonical']);
    expect(callbacks.resetMessages).toHaveBeenCalledTimes(1);
    expect(finished).toHaveBeenCalledOnce();
  });

  it.each([
    {requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'},
    {requestId: 'r1', type: 'system', subtype: 'accepted', content: 'Legacy accepted.'},
    {
      requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1',
      metadata: {restoreToken: TOKEN_1, restoreSequence: '1'}
    }
  ] as AiChatSocketEvent[])('fails once on an incompatible restore identity', event => {
    service.handleEvent(event, callbacks);
    service.handleEvent(event, callbacks);

    expect(messages).toEqual([{
      role: 'error',
      content: 'Conversation restore protocol is incompatible. Refresh after the server and web client are upgraded together.'
    }]);
    expect(callbacks.setCurrentStatus).toHaveBeenLastCalledWith('Error');
    expect(finished).toHaveBeenCalledOnce();
  });

  it('cleans up exactly once for the latest expected restore error', () => {
    service.expectAttempt(TOKEN_2, 2);
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks, TOKEN_2, 2);
    const errorEvent: AiChatSocketEvent = {
      requestId: 'r1', type: 'system', subtype: 'error',
      content: 'The latest restore failed.'
    };

    handle(errorEvent, callbacks, TOKEN_2, 2);
    handle(errorEvent, callbacks, TOKEN_2, 2);

    expect(messages).toEqual([{role: 'error', content: 'The latest restore failed.'}]);
    expect(callbacks.setRestoring).toHaveBeenLastCalledWith(false);
    expect(callbacks.setCurrentStatus).toHaveBeenLastCalledWith('Error');
    expect(finished).toHaveBeenCalledOnce();
  });

  it('requires a strictly increasing positive safe client sequence', () => {
    expect(() => service.expectAttempt(TOKEN_2, 1)).toThrow();
    expect(() => service.expectAttempt(TOKEN_2, 0)).toThrow();
    expect(() => service.expectAttempt(TOKEN_2, Number.MAX_SAFE_INTEGER + 1)).toThrow();

    service.expectAttempt(TOKEN_2, 2);

    expect(() => service.expectAttempt(TOKEN_3, 2)).toThrow();
  });

  it('restores only terminal tool rows from persisted internal updates', async () => {
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({
      requestId: 'r1',
      type: 'HISTORY_MESSAGE',
      message: 'assistant_update',
      response: 'Searching for business context tool',
      index: 0
    }, callbacks);
    handle({
      requestId: 'r1',
      type: 'HISTORY_MESSAGE',
      message: 'tool_group',
      response: 'Searched 1 time',
      index: 1
    }, callbacks);
    handle({
      requestId: 'r1',
      type: 'HISTORY_MESSAGE',
      message: 'tool_call',
      response: 'Searched tools',
      subtype: 'completed',
      groupId: 'discovery',
      toolCallId: 'call-1',
      metadata: {toolName: 'search', statusMessage: 'search completed.'},
      index: 2
    }, callbacks);
    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks);

    await vi.runAllTimersAsync();

    expect(messages).toEqual([{
      role: 'tool_call', content: 'search completed.', groupId: 'discovery',
      toolCallId: 'call-1', toolName: 'search', toolStatus: 'completed'
    }]);
  });

  it('projects durable audit tool detail to the same concise text as the live transcript', async () => {
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'user',
      response: 'How many business contexts are there?', index: 0
    }, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'progress',
      response: 'Processing the request.', index: 1
    }, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'debug',
      response: '{"analysis":"internal plan"}', index: 2
    }, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'tool_call',
      response: 'toolSearchTool\nArguments: {"arg0":"list business contexts"}\nResult: ["get_business_contexts"]',
      subtype: 'completed', groupId: 'request-1', toolCallId: 'call-1',
      metadata: {toolName: 'toolSearchTool', toolCallSeq: 0}, index: 3
    }, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'tool_call',
      response: 'get_business_contexts\nArguments: {"limit":1}\nResult: [{"total_items":0}]',
      subtype: 'completed', groupId: 'request-1', toolCallId: 'call-2',
      metadata: {toolName: 'get_business_contexts', toolCallSeq: 1}, index: 4
    }, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'assistant',
      response: 'There are currently 0 business contexts.', index: 5
    }, callbacks);
    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks);

    await vi.runAllTimersAsync();

    expect(messages).toEqual([
      {role: 'user', content: 'How many business contexts are there?'},
      {
        role: 'tool_call', content: 'tool_search_tool completed.',
        groupId: 'request-1', toolCallId: 'call-1', toolCallSeq: 0,
        toolName: 'toolSearchTool',
        toolDetail: 'tool_search_tool\nArguments: {"arg0":"list business contexts"}\nResult: ["get_business_contexts"]',
        toolStatus: 'completed'
      },
      {
        role: 'tool_call', content: 'get_business_contexts completed.',
        groupId: 'request-1', toolCallId: 'call-2', toolCallSeq: 1,
        toolName: 'get_business_contexts',
        toolDetail: 'get_business_contexts\nArguments: {"limit":1}\nResult: [{"total_items":0}]',
        toolStatus: 'completed'
      },
      {role: 'assistant', content: 'There are currently 0 business contexts.'}
    ]);
  });

  it('skips evidence-less legacy tool rows without blocking later indexes', async () => {
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'tool_call',
      response: 'Unverified model-authored result', index: 0
    }, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'assistant',
      content: 'Verified final answer from canonical content.', index: 1
    }, callbacks);
    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks);

    await vi.runAllTimersAsync();

    expect(messages).toEqual([
      {role: 'assistant', content: 'Verified final answer from canonical content.'}
    ]);
    expect(finished).toHaveBeenCalledOnce();
  });

  it('never restores model-authored textual tool markers as executed calls', () => {
    const projected = service.projectStoredMessages([
      {index: 0, role: 'user', content: 'add values'},
      {
        index: 1, role: 'assistant',
        content: 'I will look up the available values.\n\n[Tool call: contextScheme_search]'
      },
      {
        index: 2, role: 'assistant',
        content: '**[Tool: toolSearchTool]** → searching "context scheme values"'
      }
    ]);

    expect(projected).toEqual([
      {role: 'user', content: 'add values'},
      {role: 'assistant', content: 'I will look up the available values.'}
    ]);
    expect(projected.some(message => message.role === 'tool_call')).toBe(false);
  });

  it('restores failed tool evidence with conservative policy flags', async () => {
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'tool_call',
      content: 'Business context lookup failed.', subtype: 'failed',
      groupId: 'tool-calls-1-batch-1-connect-center-mcp', toolCallId: 'call-1',
      metadata: {
        toolName: 'get_business_contexts', recoverable: true,
        retryable: false, changeSafe: false
      }, index: 0
    }, callbacks);
    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks);

    await vi.runAllTimersAsync();

    expect(messages).toEqual([{
      role: 'tool_call', content: 'get_business_contexts failed.',
      groupId: 'tool-calls-1-batch-1-connect-center-mcp',
      toolCallId: 'call-1', toolName: 'get_business_contexts',
      toolStatus: 'failed', recoverable: true, retryable: false, changeSafe: false
    }]);
  });

  it('restores guard-intercepted tool rows with the same content as the live transcript', async () => {
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'tool_call',
      response: 'create_business_context\nArguments: {"name":"Example"}\nResult: {"error":"CHANGE_CONFIRMATION_REQUIRED"}',
      subtype: 'blocked', groupId: 'mcp', toolCallId: 'call-1',
      metadata: {toolName: 'create_business_context'}, index: 0
    }, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'tool_call',
      response: 'update_business_context\nArguments: {"id":2}\nResult: {"error":"REQUEST_STOPPING"}',
      subtype: 'cancelled', groupId: 'mcp', toolCallId: 'call-2',
      metadata: {toolName: 'update_business_context'}, index: 1
    }, callbacks);
    handle({
      requestId: 'r1', type: 'HISTORY_MESSAGE', message: 'tool_call',
      response: 'delete_business_context\nArguments: {"id":3}\nResult: {"error":"CHANGE_CONFIRMATION_DENIED"}',
      subtype: 'denied', groupId: 'mcp', toolCallId: 'call-3',
      metadata: {toolName: 'delete_business_context'}, index: 2
    }, callbacks);
    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks);

    await vi.runAllTimersAsync();

    expect(messages).toEqual([
      {
        role: 'tool_call', content: 'create_business_context is awaiting approval.',
        groupId: 'mcp', toolCallId: 'call-1', toolName: 'create_business_context',
        toolDetail: 'create_business_context\nArguments: {"name":"Example"}\nResult: {"error":"CHANGE_CONFIRMATION_REQUIRED"}',
        toolStatus: 'blocked'
      },
      {
        role: 'tool_call', content: 'update_business_context was stopped before execution.',
        groupId: 'mcp', toolCallId: 'call-2', toolName: 'update_business_context',
        toolDetail: 'update_business_context\nArguments: {"id":2}\nResult: {"error":"REQUEST_STOPPING"}',
        toolStatus: 'cancelled'
      },
      {
        role: 'tool_call', content: 'delete_business_context was denied before execution.',
        groupId: 'mcp', toolCallId: 'call-3', toolName: 'delete_business_context',
        toolDetail: 'delete_business_context\nArguments: {"id":3}\nResult: {"error":"CHANGE_CONFIRMATION_DENIED"}',
        toolStatus: 'denied'
      }
    ]);
  });

  it('restores error messages with the error role', async () => {
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({
      requestId: 'r1',
      type: 'HISTORY_MESSAGE',
      message: 'ERROR',
      response: 'The AI model service is temporarily unavailable.',
      index: 0
    }, callbacks);
    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks);

    await vi.runAllTimersAsync();

    expect(messages).toEqual([
      {role: 'error', content: 'The AI model service is temporarily unavailable.'}
    ]);
  });

  it('reconstructs guide text, dynamic agent verbs, and child tool activity', () => {
    const projected = service.projectStoredMessages([
      {index: 0, role: 'user', content: 'Compare two BODs.'},
      {index: 1, role: 'guide', content: 'I’ll review both BODs independently.'},
      {
        index: 2, role: 'agent_event', content: 'I’ll review both BODs independently.',
        requestId: 'request-1', subtype: 'parallel_workflow_started', metadata: {
          fanout_id: 'fanout-1', node_id: 'fanout-1-lead', agent_name: 'lead',
          agent_role: 'workflow orchestrator', status: 'started', agent_count: 1,
          workflow: 'parallel', execution_kind: 'parallel',
          active_verb: 'Reviewing', completed_verb: 'Reviewed'
        }
      },
      {
        index: 3, role: 'agent_event', content: 'I’ll review Sync Purchase Order.',
        requestId: 'request-1', subtype: 'parallel_task_started', metadata: {
          fanout_id: 'fanout-1', node_id: 'fanout-1-agent-01',
          parent_node_id: 'fanout-1-lead', agent_name: 'Evidence researcher',
          task_label: 'Sync Purchase Order', status: 'started',
          workflow: 'parallel', execution_kind: 'parallel', conversation_kind: 'PARALLEL',
          active_verb: 'Reviewing', completed_verb: 'Reviewed'
        }
      },
      {
        index: 4, role: 'tool_call', content: 'get_asccp\nArguments: {}\nResult: {}',
        requestId: 'request-1', groupId: 'request-1', toolCallId: 'call-1',
        toolStatus: 'completed', subtype: 'completed', metadata: {
          toolName: 'get_asccp', node_id: 'fanout-1-agent-01',
          parent_node_id: 'fanout-1-lead', toolCallSeq: 0
        }
      },
      {
        index: 5, role: 'agent_event', content: 'Reviewed Sync Purchase Order.',
        requestId: 'request-1', subtype: 'parallel_task_completed', metadata: {
          fanout_id: 'fanout-1', node_id: 'fanout-1-agent-01',
          parent_node_id: 'fanout-1-lead', agent_name: 'Evidence researcher',
          task_label: 'Sync Purchase Order', status: 'completed',
          workflow: 'parallel', execution_kind: 'parallel', conversation_kind: 'PARALLEL',
          active_verb: 'Reviewing', completed_verb: 'Reviewed'
        }
      },
      {
        index: 6, role: 'agent_event', content: 'Compared.', requestId: 'request-1',
        subtype: 'parallel_workflow_completed', metadata: {
          fanout_id: 'fanout-1', node_id: 'fanout-1-lead', agent_name: 'lead',
          agent_role: 'workflow orchestrator', status: 'completed',
          workflow: 'parallel', execution_kind: 'parallel',
          active_verb: 'Comparing', completed_verb: 'Compared'
        }
      },
      {index: 7, role: 'assistant', content: 'Comparison complete.'}
    ]);

    expect(projected.map(message => message.role))
      .toEqual(['user', 'guide', 'workflow_group', 'assistant']);
    const group = projected.find(message => message.role === 'workflow_group')!;
    expect(group.activities).toHaveLength(2);
    expect(group.activities?.find(activity => activity.isLead)).toEqual(expect.objectContaining({
      status: 'completed', activeVerb: 'Comparing', completedVerb: 'Compared',
      workflow: 'parallel', executionKind: 'parallel'
    }));
    expect(group.activities?.find(activity => !activity.isLead)?.events)
      .toContainEqual(expect.objectContaining({status: 'tool', content: 'get_asccp completed.'}));
  });

  it('keeps legacy composed-worker guides and tools out of the restored root chat', () => {
    const worker = {
      node_id: 'request-1:find-extenders', agent_name: 'Evidence researcher',
      conversation_kind: 'SUBAGENT', depth: 1
    };
    const projected = service.projectStoredMessages([
      {index: 0, role: 'user', content: 'Check every extender.'},
      {index: 1, role: 'guide', content: 'I’ll check the current structures.'},
      {
        index: 2, role: 'guide', content: 'Listing every extending ACC.',
        requestId: 'request-1', metadata: worker
      },
      {
        index: 3, role: 'agent_event', content: 'Listing every extending ACC.',
        requestId: 'request-1', subtype: 'subagent_started', metadata: {
          ...worker, status: 'started', active_verb: 'Searching', completed_verb: 'Searched'
        }
      },
      {
        index: 4, role: 'tool_call', content: 'get_acc\nArguments: {}\nResult: {}',
        requestId: 'request-1', groupId: 'request-1', toolCallId: 'call-1',
        toolStatus: 'completed', subtype: 'completed', metadata: {
          ...worker, toolName: 'get_acc', toolCallSeq: 0
        }
      },
      {
        index: 5, role: 'agent_event', content: 'Searched.',
        requestId: 'request-1', subtype: 'subagent_completed', metadata: {
          ...worker, status: 'completed', active_verb: 'Searching', completed_verb: 'Searched'
        }
      },
      {index: 6, role: 'assistant', content: 'The structures were checked.'}
    ]);

    expect(projected.map(message => message.role))
      .toEqual(['user', 'guide', 'agent_group', 'assistant']);
    expect(projected.some(message => message.content === 'Listing every extending ACC.'))
      .toBe(false);
    const specialist = projected.find(message => message.role === 'agent_group')
      ?.activities?.[0];
    expect(specialist).toMatchObject({
      agentId: 'request-1:find-extenders', status: 'completed'
    });
    expect(specialist?.events).toContainEqual(expect.objectContaining({
      status: 'tool', content: 'get_acc completed.'
    }));
  });

  it('restores a complete new-format composed workflow as one stable group', () => {
    const fanout = 'request-1:composed';
    const worker = `${fanout}:worker:research`;
    const projected = service.projectStoredMessages([
      {index: 0, role: 'user', content: 'Check the structures.'},
      {
        index: 1, role: 'agent_event', requestId: 'request-1',
        subtype: 'multi_agent_started', content: 'Checking both stages.', metadata: {
          fanout_id: fanout, node_id: `${fanout}:lead`, execution_scope: 'lead',
          agent_name: 'Lead agent', agent_count: 1, workflow: 'chain',
          active_verb: 'Checking', completed_verb: 'Checked'
        }
      },
      {
        index: 2, role: 'agent_event', requestId: 'request-1',
        subtype: 'subagent_planned', content: 'Research is queued.', metadata: {
          fanout_id: fanout, node_id: worker, parent_node_id: `${fanout}:lead`,
          execution_scope: 'worker', conversation_kind: 'SUBAGENT',
          agent_name: 'Evidence researcher', active_verb: 'Searching', completed_verb: 'Searched'
        }
      },
      {
        index: 3, role: 'agent_event', requestId: 'request-1',
        subtype: 'subagent_started', content: 'Searching current structures.', metadata: {
          fanout_id: fanout, node_id: worker, parent_node_id: `${fanout}:lead`,
          execution_scope: 'worker', conversation_kind: 'SUBAGENT',
          agent_name: 'Evidence researcher', active_verb: 'Searching', completed_verb: 'Searched'
        }
      },
      {
        index: 4, role: 'tool_call', requestId: 'request-1',
        groupId: 'request-1', toolCallId: 'call-1', subtype: 'completed',
        content: 'get_acc\nArguments: {}\nResult: {}', metadata: {
          fanout_id: fanout, node_id: worker, parent_node_id: `${fanout}:lead`,
          execution_scope: 'worker', conversation_kind: 'SUBAGENT',
          toolName: 'get_acc', toolCallSeq: 0
        }
      },
      {
        index: 5, role: 'provider_event', requestId: 'request-1',
        subtype: 'provider_error', content: 'Overloaded', metadata: {
          fanout_id: fanout, node_id: worker, parent_node_id: `${fanout}:lead`,
          execution_scope: 'worker', conversation_kind: 'SUBAGENT'
        }
      },
      {
        index: 6, role: 'provider_event', requestId: 'request-1',
        subtype: 'provider_retry',
        content: 'The model provider request failed; retrying (attempt 1 of 10).', metadata: {
          fanout_id: fanout, node_id: worker, parent_node_id: `${fanout}:lead`,
          execution_scope: 'worker', conversation_kind: 'SUBAGENT'
        }
      },
      {
        index: 7, role: 'agent_event', requestId: 'request-1',
        subtype: 'subagent_completed', content: 'Searched.', metadata: {
          fanout_id: fanout, node_id: worker, parent_node_id: `${fanout}:lead`,
          execution_scope: 'worker', conversation_kind: 'SUBAGENT',
          agent_name: 'Evidence researcher', active_verb: 'Searching', completed_verb: 'Searched'
        }
      },
      {
        index: 8, role: 'agent_event', requestId: 'request-1',
        subtype: 'multi_agent_completed', content: 'Checked.', metadata: {
          fanout_id: fanout, node_id: `${fanout}:lead`, execution_scope: 'lead',
          agent_name: 'Lead agent', agent_count: 1, workflow: 'chain',
          active_verb: 'Checking', completed_verb: 'Checked'
        }
      },
      {index: 9, role: 'assistant', content: 'Everything was checked.'}
    ]);

    expect(projected.map(message => message.role)).toEqual(['user', 'agent_group', 'assistant']);
    const activities = projected[1].activities || [];
    expect(activities).toHaveLength(2);
    expect(activities.find(activity => activity.isLead))
      .toMatchObject({status: 'completed', completedVerb: 'Checked', plannedAgentCount: 1});
    expect(activities.find(activity => !activity.isLead)?.events).toEqual(expect.arrayContaining([
      expect.objectContaining({status: 'tool', content: 'get_acc completed.'}),
      expect.objectContaining({status: 'provider_error', content: 'Overloaded'}),
      expect.objectContaining({
        status: 'provider_retry',
        content: 'The model provider request failed; retrying (attempt 1 of 10).'
      })
    ]));
  });

  it('keeps composed workflow iterations and requests in separate restored groups', () => {
    const projected = service.projectStoredMessages([
      {
        index: 0, role: 'agent_event', requestId: 'request-1',
        subtype: 'workflow_started', content: 'First workflow started.', metadata: {
          node_id: 'main:1:planned-workflow', parent_node_id: 'main', depth: 1,
          member_count: 3, status: 'started'
        }
      },
      {
        index: 1, role: 'agent_event', requestId: 'request-1',
        subtype: 'subagent_started', content: 'First worker started.', metadata: {
          node_id: 'main:1:planned-workflow:agent:count-accs',
          parent_node_id: 'main:1:planned-workflow', depth: 2,
          agent_name: 'Evidence researcher', status: 'started'
        }
      },
      {
        index: 2, role: 'agent_event', requestId: 'request-1',
        subtype: 'workflow_started', content: 'Nested workflow started.', metadata: {
          node_id: 'workflow:opaque-nested-node',
          parent_node_id: 'main:1:planned-workflow', depth: 2,
          member_count: 1, status: 'started'
        }
      },
      {
        index: 3, role: 'agent_event', requestId: 'request-1',
        subtype: 'subagent_started', content: 'Nested worker started.', metadata: {
          node_id: 'workflow:opaque-worker-node',
          parent_node_id: 'workflow:opaque-nested-node', depth: 3,
          agent_name: 'Critical reviewer', status: 'started'
        }
      },
      {
        index: 4, role: 'agent_event', requestId: 'request-1',
        subtype: 'workflow_started', content: 'Second workflow started.', metadata: {
          node_id: 'main:2:root-work', parent_node_id: 'main', depth: 1,
          member_count: 3, status: 'started'
        }
      },
      {
        index: 5, role: 'agent_event', requestId: 'request-1',
        subtype: 'subagent_started', content: 'Second worker started.', metadata: {
          node_id: 'main:2:root-work:agent:count-accs',
          parent_node_id: 'main:2:root-work', depth: 2,
          agent_name: 'Evidence researcher', status: 'started'
        }
      },
      {
        index: 6, role: 'agent_event', requestId: 'request-2',
        subtype: 'workflow_started', content: 'Next request workflow started.', metadata: {
          node_id: 'main:1:planned-workflow', parent_node_id: 'main', depth: 1,
          member_count: 1, status: 'started'
        }
      },
      {
        index: 7, role: 'agent_event', requestId: 'request-2',
        subtype: 'subagent_started', content: 'Next request worker started.', metadata: {
          node_id: 'main:1:planned-workflow:agent:count-accs',
          parent_node_id: 'main:1:planned-workflow', depth: 2,
          agent_name: 'Evidence researcher', status: 'started'
        }
      },
      {
        index: 8, role: 'provider_event', requestId: 'request-2',
        subtype: 'provider_error', content: 'Request two overloaded.', metadata: {
          node_id: 'main:1:planned-workflow:agent:count-accs',
          parent_node_id: 'main:1:planned-workflow', depth: 2
        }
      }
    ]);

    const rootWorkflows = projected.filter(message => message.role === 'workflow_group');
    expect(projected.filter(message => message.role === 'guide')).toHaveLength(3);
    expect(rootWorkflows).toHaveLength(3);
    expect(rootWorkflows.map(message => message.activities?.map(activity => activity.agentId)))
      .toEqual([
        ['main:1:planned-workflow:agent:count-accs'],
        ['main:2:root-work:agent:count-accs'],
        ['main:1:planned-workflow:agent:count-accs']
      ]);
    const nested = rootWorkflows[0].children
      ?.find(message => message.role === 'workflow_group');
    expect(nested?.activities?.map(activity => activity.agentId))
      .toEqual(['workflow:opaque-worker-node']);
    expect(rootWorkflows[0].activities?.flatMap(activity => activity.events))
      .not.toContainEqual(expect.objectContaining({content: 'Request two overloaded.'}));
    expect(rootWorkflows[2].activities?.flatMap(activity => activity.events))
      .toContainEqual(expect.objectContaining({
        status: 'provider_error', content: 'Request two overloaded.'
      }));
  });

  it.each([
    ['workflow_completed', 'completed'],
    ['workflow_cancelled', 'cancelled'],
    ['workflow_output_retry_handoff', 'cancelled'],
    ['workflow_failed', 'failed'],
    ['workflow_stalled', 'failed'],
    ['workflow_refused', 'failed']
  ] as const)('restores %s as a terminal Workflow', (subtype, expectedStatus) => {
    const projected = service.projectStoredMessages([{
      index: 0, role: 'agent_event', requestId: 'request-1',
      subtype: 'workflow_started', content: 'I’m checking the request.', metadata: {
        node_id: 'main:1:workflow', parent_node_id: 'main', depth: 1
      }
    }, {
      index: 1, role: 'agent_event', requestId: 'request-1',
      subtype, content: 'Workflow settled.', metadata: {
        node_id: 'main:1:workflow', parent_node_id: 'main', depth: 1
      }
    }]);

    expect(projected.find(message => message.role === 'workflow_group')?.workflowStatus)
      .toBe(expectedStatus);
  });

  it('restores a nested workflow failure as the owning Agent final error', () => {
    const root = 'main:1:root';
    const parent = root + ':agent:parent';
    const nested = parent + ':delegated';
    const projected = service.projectStoredMessages([
      {
        index: 0, role: 'agent_event', requestId: 'request-1',
        subtype: 'workflow_started', content: 'Starting root.', metadata: {
          node_id: root, parent_node_id: 'main', depth: 1
        }
      },
      {
        index: 1, role: 'agent_event', requestId: 'request-1',
        subtype: 'subagent_started', content: 'Starting parent.', metadata: {
          node_id: parent, parent_node_id: root, depth: 2,
          agent_id: 'parent', agent_name: 'Parent'
        }
      },
      {
        index: 2, role: 'agent_event', requestId: 'request-1',
        subtype: 'workflow_started', content: 'Starting nested.', metadata: {
          node_id: nested, parent_node_id: parent, depth: 3
        }
      },
      {
        index: 3, role: 'agent_event', requestId: 'request-1',
        subtype: 'workflow_failed', content: 'Nested workflow failed.', metadata: {
          node_id: nested, parent_node_id: parent, depth: 3
        }
      }
    ]);

    const owner = projected.find(message => message.role === 'workflow_group')
      ?.activities?.find(activity => activity.agentId === parent);
    expect(owner?.messages?.at(-1)).toMatchObject({
      role: 'error', content: 'Nested workflow failed.', eventType: 'workflow_failed'
    });
  });

  it('restores durable cancellation as terminal instead of running', () => {
    const fanout = 'request-1:composed';
    const worker = `${fanout}:worker:research`;
    const projected = service.projectStoredMessages([
      {index: 0, role: 'user', content: 'Check the structures.'},
      {
        index: 1, role: 'agent_event', requestId: 'request-1',
        subtype: 'multi_agent_started', content: 'Checking.', metadata: {
          fanout_id: fanout, node_id: `${fanout}:lead`, execution_scope: 'lead', agent_count: 1
        }
      },
      {
        index: 2, role: 'agent_event', requestId: 'request-1',
        subtype: 'subagent_planned', content: 'Research is queued.', metadata: {
          fanout_id: fanout, node_id: worker, parent_node_id: `${fanout}:lead`,
          execution_scope: 'worker', conversation_kind: 'SUBAGENT'
        }
      },
      {
        index: 3, role: 'agent_event', requestId: 'request-1',
        subtype: 'subagent_cancelled', content: 'Stopped research.', metadata: {
          fanout_id: fanout, node_id: worker, parent_node_id: `${fanout}:lead`,
          execution_scope: 'worker', conversation_kind: 'SUBAGENT'
        }
      },
      {
        index: 4, role: 'agent_event', requestId: 'request-1',
        subtype: 'multi_agent_cancelled', content: 'Workflow cancelled.', metadata: {
          fanout_id: fanout, node_id: `${fanout}:lead`, execution_scope: 'lead', agent_count: 1
        }
      }
    ]);

    const activities = projected.find(message => message.role === 'agent_group')?.activities || [];
    expect(activities).toHaveLength(2);
    expect(activities.every(activity => activity.status === 'cancelled')).toBe(true);
    expect(activities.every(activity => !activity.inProgress)).toBe(true);
  });

});
