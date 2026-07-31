import {
  contextUsageValue,
  exactOptionalText,
  isReconciliationRequired,
  legacyRecoverableToolName,
  primaryContent,
  terminalRequestErrorStatus,
  toolCallEventSemantics
} from './ai-chat-event-semantics';
import {AiChatSocketEvent} from './ai-chat-panel.model';

describe('AI chat event semantics', () => {
  it('preserves only transport text with exact whitespace', () => {
    expect(exactOptionalText('conversation-1')).toBe('conversation-1');
    expect(exactOptionalText(' conversation-1')).toBeUndefined();
    expect(exactOptionalText(null)).toBeUndefined();
  });

  it('uses content, response, then message as the canonical event text precedence', () => {
    const event: AiChatSocketEvent = {
      requestId: 'request-1', type: 'system', content: 'content',
      response: 'response', message: 'message'
    };
    expect(primaryContent(event)).toBe('content');
    expect(primaryContent({...event, content: ''})).toBe('response');
    expect(primaryContent({...event, content: '', response: ''})).toBe('message');
    expect(primaryContent({...event, content: '', response: '', message: ''})).toBe('');
  });

  it('accepts only internally consistent context usage snapshots', () => {
    const valid = {
      modelName: 'gpt-5_6-sol', currentInputTokens: 75000, contextWindow: 200000,
      safeInputLimit: 150000, remainingTokens: 75000, usedPercent: 50,
      estimated: false, source: 'provider'
    };
    expect(contextUsageValue(valid, 'gpt-5_6-sol')).toEqual(valid);
    expect(contextUsageValue({...valid, remainingTokens: 90000}, 'gpt-5_6-sol')).toBeUndefined();
    expect(contextUsageValue({...valid, usedPercent: Number.NaN}, 'gpt-5_6-sol')).toBeUndefined();
    expect(contextUsageValue(valid, 'claude-fable-5')).toBeUndefined();
  });

  it('classifies an explicitly recoverable tool failure without making it a request terminal', () => {
    const semantics = toolCallEventSemantics({
      requestId: 'request-1',
      type: 'tool_call',
      subtype: 'failed',
      groupId: 'mcp',
      toolCallId: 'call-1',
      content: 'GitHub search',
      metadata: {
        toolName: 'github_search',
        statusMessage: 'GitHub search failed.',
        terminal: false,
        recoverable: true,
        retryable: true,
        changeSafe: true
      }
    });

    expect(semantics).toEqual({
      key: 'mcp:call-1',
      groupId: 'mcp',
      toolCallId: 'call-1',
      toolName: 'github_search',
      content: 'github_search failed.',
      active: false,
      hidden: false,
      status: 'failed',
      recoverable: true,
      retryable: true,
      changeSafe: true
    });
  });

  it('classifies a guard-blocked tool call as terminal with its reported content', () => {
    expect(toolCallEventSemantics({
      requestId: 'request-1', type: 'tool_call', subtype: 'blocked',
      groupId: 'mcp', toolCallId: 'call-1',
      content: 'create_business_context is awaiting approval.',
      metadata: {toolName: 'create_business_context'}
    })).toEqual(expect.objectContaining({
      key: 'mcp:call-1', toolName: 'create_business_context',
      content: 'create_business_context is awaiting approval.',
      active: false, status: 'blocked'
    }));
  });

  it('classifies a stop-intercepted tool call as terminal with a default stopped content', () => {
    expect(toolCallEventSemantics({
      requestId: 'request-1', type: 'tool_call', subtype: 'cancelled',
      groupId: 'mcp', toolCallId: 'call-1',
      metadata: {toolName: 'create_business_context'}
    })).toEqual(expect.objectContaining({
      key: 'mcp:call-1', toolName: 'create_business_context',
      content: 'create_business_context was stopped before execution.',
      active: false, status: 'cancelled'
    }));
  });

  it('classifies a denied change retry as terminal and non-executed', () => {
    expect(toolCallEventSemantics({
      requestId: 'request-1', type: 'tool_call', subtype: 'denied',
      groupId: 'mcp', toolCallId: 'call-1',
      metadata: {toolName: 'delete_business_context'}
    })).toEqual(expect.objectContaining({
      key: 'mcp:call-1', toolName: 'delete_business_context',
      content: 'delete_business_context was denied before execution.',
      active: false, status: 'denied'
    }));
  });

  it('shows a correlated tool discovery lifecycle with its tool name', () => {
    expect(toolCallEventSemantics({
      requestId: 'request-1', type: 'tool_call', subtype: 'started',
      groupId: 'discovery', toolCallId: 'search-1',
      metadata: {toolName: 'toolSearchTool', toolDiscovery: true}
    })).toEqual(expect.objectContaining({
      key: 'discovery:search-1', toolName: 'toolSearchTool',
      active: true, hidden: false, content: 'Calling tool_search_tool.'
    }));
  });

  it('normalizes the discovery tool name in terminal content and detail only', () => {
    expect(toolCallEventSemantics({
      requestId: 'request-1', type: 'tool_call', subtype: 'completed',
      groupId: 'discovery', toolCallId: 'search-1',
      metadata: {
        toolName: 'toolSearchTool',
        toolDetail: 'toolSearchTool\nArguments: {"query":"contexts"}'
      }
    })).toEqual(expect.objectContaining({
      toolName: 'toolSearchTool',
      content: 'tool_search_tool completed.',
      toolDetail: 'tool_search_tool\nArguments: {"query":"contexts"}'
    }));
  });

  it('retains terminal tool detail separately from the status summary', () => {
    const semantics = toolCallEventSemantics({
      requestId: 'request-1', type: 'tool_call', subtype: 'completed',
      groupId: 'mcp', toolCallId: 'call-1', content: 'Lookup completed.',
      metadata: {
        statusMessage: 'Lookup completed.',
        toolDetail: 'lookup\nArguments: {"limit":1}\nResult: {"total":0}'
      }
    });

    expect(semantics).toEqual(expect.objectContaining({
      content: 'Executed',
      toolDetail: 'lookup\nArguments: {"limit":1}\nResult: {"total":0}'
    }));
  });

  it('accepts only a safe server tool-call sequence for live row ordering', () => {
    const event: AiChatSocketEvent = {
      requestId: 'request-1', turnId: 'turn-1', type: 'tool_call',
      subtype: 'completed', groupId: 'mcp', toolCallId: 'call-1',
      metadata: {toolCallSeq: 7}
    };

    expect(toolCallEventSemantics(event)).toEqual(expect.objectContaining({
      turnId: 'turn-1', toolCallSeq: 7
    }));
    expect(toolCallEventSemantics({
      ...event, metadata: {toolCallSeq: -1}
    })).not.toHaveProperty('toolCallSeq');
    expect(toolCallEventSemantics({
      ...event, metadata: {toolCallSeq: Number.MAX_SAFE_INTEGER + 1}
    })).not.toHaveProperty('toolCallSeq');
  });

  it.each<Partial<AiChatSocketEvent>>([
    {groupId: undefined},
    {toolCallId: undefined},
    {subtype: 'unknown'},
    {metadata: {terminal: true, recoverable: true}},
    {metadata: {terminal: false, recoverable: false}},
    {metadata: {terminal: 'false', recoverable: true}}
  ])('rejects malformed or contradictory tool failure event %#', override => {
    expect(toolCallEventSemantics({
      requestId: 'request-1',
      type: 'tool_call',
      subtype: 'failed',
      groupId: 'mcp',
      toolCallId: 'call-1',
      metadata: {terminal: false, recoverable: true},
      ...override
    })).toBeUndefined();
  });

  it('requires the complete terminal request error contract', () => {
    const terminal: AiChatSocketEvent = {
      requestId: 'request-1',
      type: 'system',
      subtype: 'request_error',
      sequence: 12,
      metadata: {terminal: true, recoverable: false, retryable: false, status: 'FAILED'}
    };

    expect(terminalRequestErrorStatus(terminal)).toBe('FAILED');
    expect(terminalRequestErrorStatus({...terminal, sequence: undefined})).toBeUndefined();
    expect(terminalRequestErrorStatus({
      ...terminal,
      metadata: {...terminal.metadata, terminal: false}
    })).toBeUndefined();
    expect(terminalRequestErrorStatus({
      ...terminal,
      metadata: {...terminal.metadata, recoverable: true}
    })).toBeUndefined();
    expect(terminalRequestErrorStatus({
      ...terminal,
      metadata: {...terminal.metadata, status: 'RUNNING'}
    })).toBeUndefined();
    expect(terminalRequestErrorStatus({
      ...terminal,
      metadata: {...terminal.metadata, status: 'TIMED_OUT'}
    })).toBe('TIMED_OUT');
    expect(terminalRequestErrorStatus({
      ...terminal,
      metadata: {...terminal.metadata, status: 'STEP_LIMIT_REACHED'}
    })).toBe('STEP_LIMIT_REACHED');
  });

  it('requires an exact reconciliation terminal barrier', () => {
    const reconciliation: AiChatSocketEvent = {
      requestId: 'request-1',
      type: 'system',
      subtype: 'reconciliation_required',
      sequence: 13,
      metadata: {
        terminal: true, recoverable: false, retryable: false,
        status: 'UNKNOWN_RECONCILIATION_REQUIRED', generation: 7
      }
    };

    expect(isReconciliationRequired(reconciliation)).toBe(true);
    expect(isReconciliationRequired({
      ...reconciliation,
      metadata: {...reconciliation.metadata, terminal: false}
    })).toBe(false);
  });

  it('recognizes only the narrow legacy tool error candidate shape', () => {
    const legacy: AiChatSocketEvent = {
      requestId: 'request-1',
      type: 'system',
      subtype: 'error',
      content: 'GitHub search failed: upstream returned 500',
      metadata: {toolName: 'github_search'}
    };

    expect(legacyRecoverableToolName(legacy)).toBe('github_search');
    expect(legacyRecoverableToolName({
      ...legacy,
      metadata: {...legacy.metadata, terminal: true}
    })).toBeUndefined();
    expect(legacyRecoverableToolName({
      ...legacy,
      metadata: {...legacy.metadata, agent: 'main-agent'}
    })).toBeUndefined();
    expect(legacyRecoverableToolName({...legacy, content: 'The request failed.'})).toBeUndefined();
  });
});
