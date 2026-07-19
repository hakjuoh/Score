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
      setRuntime: vi.fn(),
      setRuntimeOptions: vi.fn(),
      resetMessages: vi.fn(() => {
        messages = [];
      }),
      resetRouteContext: vi.fn(),
      markRouteRegistryRestored: vi.fn(),
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
        modelName: 'gpt-5.6-sol', reasoningEffort: 'high', runtime: 'openai',
        runtimeOptions: {maxTurns: 40, verbose: true}
      }
    }, callbacks);

    expect(callbacks.setModelName).toHaveBeenCalledWith('gpt-5.6-sol');
    expect(callbacks.setReasoningEffort).toHaveBeenCalledWith('high');
    expect(callbacks.setRuntime).toHaveBeenCalledWith('openai');
    expect(callbacks.setRuntimeOptions).toHaveBeenCalledWith({maxTurns: 40, verbose: true});
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
        role: 'tool_call', content: 'toolSearchTool completed.',
        groupId: 'request-1', toolCallId: 'call-1', toolCallSeq: 0,
        toolName: 'toolSearchTool',
        toolDetail: 'toolSearchTool\nArguments: {"arg0":"list business contexts"}\nResult: ["get_business_contexts"]',
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
        retryable: false, mutationSafe: false
      }, index: 0
    }, callbacks);
    handle({requestId: 'r1', type: 'HISTORY_FINAL', conversationId: 'c1'}, callbacks);

    await vi.runAllTimersAsync();

    expect(messages).toEqual([{
      role: 'tool_call', content: 'get_business_contexts failed.',
      groupId: 'tool-calls-1-batch-1-connect-center-mcp',
      toolCallId: 'call-1', toolName: 'get_business_contexts',
      toolStatus: 'failed', recoverable: true, retryable: false, mutationSafe: false
    }]);
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

  it('restores route registry context markers', () => {
    handle({requestId: 'r1', type: 'HISTORY_START', conversationId: 'c1'}, callbacks);
    handle({requestId: 'r1', type: 'HISTORY_CONTEXT', message: 'route-registry', agent: 'system', response: 'routes'}, callbacks);

    expect(callbacks.markRouteRegistryRestored).toHaveBeenCalledOnce();
  });
});
