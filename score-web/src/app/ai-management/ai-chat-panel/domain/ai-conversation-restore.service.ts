import {Injectable} from '@angular/core';
import {
  AiChatHistoryMessage,
  AiChatMessage,
  AiChatConversationDetails,
  AiChatSocketEvent,
  AiContextUsage
} from './ai-chat-panel.model';
import {
  contextUsageValue,
  withoutTextualToolCallPlaceholder
} from './ai-chat-event-semantics';
import {
  AiAgentActivity,
  isSpecialistActivityEvent,
  isSpecialistToolEvent,
  upsertAgentGuideEvent,
  upsertAgentProviderErrorEvent,
  upsertAgentRetryEvent,
  upsertAgentToolEvent
} from './ai-agent-activity';
import {
  AiExecutionComposite,
  appendWorkflowConversation,
  workflowTerminalStatus
} from './ai-execution-composite';

export interface AiConversationRestoreCallbacks {
  setConversationId(conversationId: string): void;
  setSettings?(settings: Pick<AiChatConversationDetails,
    'modelName' | 'reasoningEffort' | 'permissionMode'
    | 'activeWorkflow'>): void;
  setModelName?(modelName: string): void;
  setReasoningEffort?(reasoningEffort: string): void;
  setContextUsage?(contextUsage: AiContextUsage): void;
  resetMessages(): void;
  setRestoring(restoring: boolean): void;
  setCurrentStatus(status: string): void;
  clearStatus(): void;
  pushMessage(message: AiChatMessage): number;
  setMessage(index: number, message: AiChatMessage): void;
  hasMessage(index: number): boolean;
  scrollTop(): void;
  updateScrollButton(): void;
  focusPrompt?(): void;
  finish(): void;
}

interface AiConversationRestoreAttempt {
  token: string;
  sequence: number;
}

@Injectable()
export class AiConversationRestoreService {
  private static readonly RESTORE_TOKEN_PATTERN =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

  private restoreConversationTimeout?: number;
  private restoreConversationToken = 0;
  private restoreMessageQueue: AiChatMessage[] = [];
  private restoreMessageQueueRunning = false;
  private restoreMessageBuffer = new Map<number, AiChatSocketEvent>();
  private pendingRestoreWorkflowResults = new Map<string, AiChatSocketEvent>();
  private nextRestoreMessageIndex = 0;
  private restoreServerDone = false;
  private restoreFinished = false;
  private restoreStarted = false;
  private expectedRestoreAttempt?: AiConversationRestoreAttempt;
  private readonly restoredExecution = new AiExecutionComposite(true);

  projectStoredMessages(messages: AiChatHistoryMessage[]): AiChatMessage[] {
    this.resetProjectionState();
    return this.canonicalStoredMessages(messages)
      .flatMap(message => this.projectStoredMessagesFor(message));
  }

  isRestoreEvent(event: AiChatSocketEvent): boolean {
    return event.type === 'HISTORY_START' ||
      event.type === 'HISTORY_CONTEXT' ||
      event.type === 'HISTORY_MESSAGE' ||
      event.type === 'HISTORY_FINAL' ||
      (event.type === 'system' && event.subtype === 'accepted' &&
        !!event.metadata &&
        ('restoreToken' in event.metadata || 'restoreSequence' in event.metadata)) ||
      (event.type === 'system' && event.subtype === 'error' &&
        !!event.metadata &&
        ('restoreToken' in event.metadata || 'restoreSequence' in event.metadata));
  }

  isLegacyRestoreAdmission(event: AiChatSocketEvent): boolean {
    return event.type === 'system' && event.subtype === 'accepted' &&
      (!event.metadata ||
        (!('restoreToken' in event.metadata) && !('restoreSequence' in event.metadata)));
  }

  private projectStoredMessagesFor(message: AiChatHistoryMessage): AiChatMessage[] {
    return this.restoredMessages({
      requestId: message.requestId || 'stored',
      type: 'HISTORY_MESSAGE',
      message: message.role,
      response: message.content,
      turnId: message.turnId,
      groupId: message.groupId,
      toolCallId: message.toolCallId,
      subtype: message.subtype || message.toolStatus,
      artifacts: message.artifacts,
      metadata: {
        ...(message.metadata || {}),
        ...((message.toolCallSeq ?? message.toolCallSequence) !== undefined
          ? {toolCallSeq: message.toolCallSeq ?? message.toolCallSequence} : {})
      }
    });
  }

  expectAttempt(token: string, sequence: number): void {
    if (!this.validRestoreToken(token) || !this.validRestoreSequence(sequence)) {
      throw new Error('Invalid conversation restore identity.');
    }
    if (this.expectedRestoreAttempt &&
      sequence <= this.expectedRestoreAttempt.sequence) {
      throw new Error('Conversation restore sequence must increase.');
    }
    this.expectedRestoreAttempt = {token, sequence};
    this.restoreConversationToken += 1;
    this.clearConversationRestoreTimeout();
    this.resetReplayState();
  }

  handleEvent(event: AiChatSocketEvent, callbacks: AiConversationRestoreCallbacks): void {
    const restoreAttempt = this.restoreAttempt(event);
    if (!restoreAttempt) {
      if (this.expectedRestoreAttempt && !this.restoreFinished &&
        this.incompatibleRestoreFrame(event)) {
        this.failRestore({
          ...event,
          type: 'system',
          subtype: 'error',
          content: 'Conversation restore protocol is incompatible. Refresh after the server and web client are upgraded together.'
        }, callbacks);
      }
      return;
    }
    if (!this.matchesExpectedAttempt(restoreAttempt) || this.restoreFinished) {
      return;
    }

    if (event.type === 'system' && event.subtype === 'error') {
      this.failRestore(event, callbacks);
      return;
    }

    if (event.type === 'HISTORY_START') {
      if (this.restoreStarted) {
        return;
      }
      this.restoreConversationToken += 1;
      this.clearConversationRestoreTimeout();
      this.resetReplayState();
      this.restoreStarted = true;
      callbacks.resetMessages();
      if (event.conversationId) {
        callbacks.setConversationId(event.conversationId);
      }
      const modelName = this.nonBlankText(event.metadata?.['modelName']);
      const reasoningEffort = this.nonBlankText(event.metadata?.['reasoningEffort']);
      const permissionMode = this.permissionMode(event.metadata?.['permissionMode']);
      const activeWorkflow = this.nonBlankText(event.metadata?.['activeWorkflow']);
      if (callbacks.setSettings && (modelName || reasoningEffort
        || permissionMode || activeWorkflow)) {
        callbacks.setSettings({
          modelName, reasoningEffort, permissionMode, activeWorkflow
        });
      } else {
        if (modelName) callbacks.setModelName?.(modelName);
        if (reasoningEffort) callbacks.setReasoningEffort?.(reasoningEffort);
      }
      const contextUsage = contextUsageValue(event.metadata?.['contextUsage'], modelName);
      if (contextUsage) {
        callbacks.setContextUsage?.(contextUsage);
      }
      callbacks.setCurrentStatus('Restoring');
      callbacks.scrollTop();
      return;
    }

    if (!this.restoreStarted) {
      return;
    }

    if (event.type === 'HISTORY_CONTEXT') {
      return;
    }

    if (event.type === 'HISTORY_MESSAGE') {
      if (typeof event.index === 'number') {
        if (event.index < this.nextRestoreMessageIndex) {
          return;
        }
        // Delivery is at-least-once. Preserve the first frame for an index so
        // a duplicate cannot replace already buffered canonical history.
        if (!this.restoreMessageBuffer.has(event.index)) {
          this.restoreMessageBuffer.set(event.index, event);
        }
        this.drainBufferedRestoreMessages(callbacks);
        return;
      }
      this.enqueueRestoredEvent(event, callbacks);
      return;
    }

    if (event.type === 'HISTORY_FINAL') {
      if (event.conversationId) {
        callbacks.setConversationId(event.conversationId);
      }
      this.restoreServerDone = true;
      this.drainBufferedRestoreMessages(callbacks);
      this.flushPendingRestoreWorkflowResults(callbacks);
      this.finishIfComplete(callbacks);
    }
  }

  private restoredRole(role?: string): AiChatMessage['role'] {
    if (role === 'error' || role === 'ERROR') {
      return 'error';
    }
    if (role === 'assistant_update') {
      return 'progress';
    }
    if (role === 'tool_group') {
      return 'tool_group';
    }
    if (role === 'tool_call') {
      return 'tool_call';
    }
    if (role === 'progress') {
      return 'progress';
    }
    if (role === 'debug') {
      return 'debug';
    }
    if (role === 'assistant') {
      return 'assistant';
    }
    if (role === 'guide') {
      return 'guide';
    }
    if (role === 'agent_event') {
      return 'agent_group';
    }
    return 'user';
  }

  private restoredMessage(event: AiChatSocketEvent): AiChatMessage | null {
    const role = this.restoredRole(event.message);
    const content = this.nonBlankText(event.response)
      || this.nonBlankText(event.content) || '';
    if (event.message === 'agent_event') {
      return this.restoredAgentEvent(event, content);
    }
    if (event.message === 'provider_event') {
      const providerEvent: AiChatSocketEvent = {...event, type: 'system', content};
      for (const activities of this.restoredGroupsForEvent(event)) {
        const applied = event.subtype === 'provider_error'
          ? upsertAgentProviderErrorEvent(activities, providerEvent)
          : event.subtype === 'provider_retry'
            ? upsertAgentRetryEvent(activities, providerEvent) : false;
        if (applied) return null;
      }
      return null;
    }
    if (event.message === 'guide') {
      const guideEvent: AiChatSocketEvent = {...event, type: 'system', subtype: 'guide', content};
      for (const activities of this.restoredGroupsForEvent(event)) {
        if (upsertAgentGuideEvent(activities, guideEvent)) return null;
      }
      // Pre-fix composed workers persisted their guide immediately before the
      // lifecycle that creates the specialist activity. Its lifecycle repeats
      // that status, so suppress the worker-owned guide instead of restoring it
      // as a root conversation message.
      if (isSpecialistActivityEvent(guideEvent)) return null;
    }
    // The durable trajectory contains audit-only progress, model reasoning,
    // and orchestration rows that are never retained in the completed live
    // transcript. Replaying them would make a restored request expose a
    // different (and much noisier) message history after refresh.
    if (role === 'progress' || role === 'tool_group' || role === 'debug') {
      return null;
    }
    if (role !== 'tool_call') {
      if (role === 'assistant') {
        const visibleContent = withoutTextualToolCallPlaceholder(content);
        return visibleContent ? {
          role, content: visibleContent,
          ...(event.subtype === 'workflow_result'
            ? {eventType: 'workflow_result', requestId: event.requestId} : {})
        } : null;
      }
      return {role, content};
    }

    const toolEvent: AiChatSocketEvent = {...event, type: 'tool_call'};
    if (isSpecialistToolEvent(toolEvent)) {
      for (const activities of this.restoredGroupsForEvent(event)) {
        if (upsertAgentToolEvent(activities, toolEvent)) break;
      }
      return null;
    }

    const groupId = this.nonBlankText(event.groupId);
    const toolCallId = this.nonBlankText(event.toolCallId);
    const toolName = this.nonBlankText(event.metadata?.['toolName']);
    const toolStatus = event.subtype === 'completed' || event.subtype === 'failed'
      || event.subtype === 'blocked' || event.subtype === 'denied'
      || event.subtype === 'cancelled'
      ? event.subtype : undefined;
    if (!groupId || !toolCallId || !toolName || !toolStatus) {
      // Old projected tool rows had no durable execution evidence. Skipping
      // them is safer than presenting model-authored text as an executed call.
      return null;
    }
    const turnId = this.nonBlankText(event.turnId);
    const toolCallSeq = this.nonNegativeSequence(event.metadata?.['toolCallSeq']);
    const toolDetail = this.restoredToolDetail(event, content);
    return {
      role,
      content: this.restoredToolContent(toolStatus, toolName),
      ...(turnId ? {turnId} : {}),
      groupId,
      toolCallId,
      ...(toolCallSeq !== undefined ? {toolCallSeq} : {}),
      toolName,
      ...(toolDetail ? {toolDetail} : {}),
      toolStatus,
      ...(toolStatus === 'failed' ? {
        recoverable: event.metadata?.['recoverable'] === true,
        retryable: event.metadata?.['retryable'] === true,
        mutationSafe: event.metadata?.['mutationSafe'] === true
      } : {})
    };
  }

  private restoredMessages(event: AiChatSocketEvent): AiChatMessage[] {
    const message = this.restoredMessage(event);
    if (!message) return [];
    const content = this.nonBlankText(event.response)
      || this.nonBlankText(event.content);
    if (event.message === 'agent_event' && event.subtype === 'workflow_started'
      && message.workflowNodeId && content) {
      return [{role: 'guide', content}, message];
    }
    return [{...message, ...(event.artifacts?.length ? {artifacts: event.artifacts} : {})}];
  }

  private restoredToolContent(
    toolStatus: 'completed' | 'failed' | 'blocked' | 'denied' | 'cancelled', toolName: string
  ): string {
    if (toolStatus === 'blocked') {
      return `${toolName} is awaiting approval.`;
    }
    if (toolStatus === 'cancelled') {
      return `${toolName} was stopped before execution.`;
    }
    if (toolStatus === 'denied') {
      return `${toolName} was denied before execution.`;
    }
    return toolStatus === 'completed' ? `${toolName} completed.` : `${toolName} failed.`;
  }

  private restoredAgentEvent(event: AiChatSocketEvent, content: string): AiChatMessage | null {
    const lifecycleEvent: AiChatSocketEvent = {...event, type: 'system', content};
    if (event.subtype === 'workflow_started') {
      const placement = this.restoredExecution.startWorkflow(lifecycleEvent, []);
      if (!placement || !placement.created) return null;
      if (!placement.root) {
        appendWorkflowConversation(placement.container, content, placement.anchor, false);
        return null;
      }
      return placement.anchor;
    }
    if (workflowTerminalStatus(event.subtype)) {
      this.restoredExecution.finishWorkflow(lifecycleEvent);
      return null;
    }
    const placement = this.restoredExecution.placeAgent(lifecycleEvent);
    return placement?.createdRootAnchor ? placement.anchor || null : null;
  }

  private restoredGroupsForEvent(event: AiChatSocketEvent): AiAgentActivity[][] {
    const activities = this.restoredExecution.activitiesFor(event);
    return activities ? [activities] : [];
  }

  private restoredToolDetail(event: AiChatSocketEvent, content: string): string | undefined {
    return this.nonBlankText(event.metadata?.['toolDetail'])
      || (/\nArguments:\s*/.test(content) ? content : undefined);
  }

  private nonBlankText(value: unknown): string | undefined {
    return typeof value === 'string' && value.trim() ? value.trim() : undefined;
  }

  private nonNegativeSequence(value: unknown): number | undefined {
    return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
      ? value : undefined;
  }

  cancel(): void {
    this.reset();
    this.clearConversationRestoreTimeout();
  }

  reset(): void {
    this.restoreConversationToken += 1;
    this.expectedRestoreAttempt = undefined;
    this.resetReplayState();
  }

  private resetReplayState(): void {
    this.restoreMessageQueue = [];
    this.restoreMessageQueueRunning = false;
    this.restoreMessageBuffer.clear();
    this.pendingRestoreWorkflowResults.clear();
    this.nextRestoreMessageIndex = 0;
    this.restoreServerDone = false;
    this.restoreFinished = false;
    this.restoreStarted = false;
    this.resetProjectionState();
  }

  private resetProjectionState(): void {
    this.restoredExecution.clear();
  }

  private enqueueRestoredMessage(message: AiChatMessage, callbacks: AiConversationRestoreCallbacks): void {
    callbacks.setRestoring(false);
    callbacks.clearStatus();
    this.restoreMessageQueue.push(message);
    this.drainRestoreMessageQueue(callbacks);
  }

  private drainBufferedRestoreMessages(callbacks: AiConversationRestoreCallbacks): void {
    while (this.restoreMessageBuffer.has(this.nextRestoreMessageIndex)) {
      const event = this.restoreMessageBuffer.get(this.nextRestoreMessageIndex);
      this.restoreMessageBuffer.delete(this.nextRestoreMessageIndex);
      this.nextRestoreMessageIndex++;
      if (event) this.enqueueRestoredEvent(event, callbacks);
    }
  }

  private enqueueRestoredEvent(event: AiChatSocketEvent,
                               callbacks: AiConversationRestoreCallbacks): void {
    const requestKey = this.restoreEventRequestKey(event);
    if (event.message === 'assistant' && event.subtype === 'workflow_result') {
      // A legacy Planner/Evaluator loop may have persisted one synthesis per
      // iteration. Hold only the latest safe fallback until history is complete.
      this.pendingRestoreWorkflowResults.delete(requestKey);
      this.pendingRestoreWorkflowResults.set(requestKey, event);
      return;
    }
    if (event.message === 'assistant') {
      // The canonical final answer supersedes every Workflow preview for its turn.
      this.pendingRestoreWorkflowResults.delete(requestKey);
    }
    for (const message of this.restoredMessages(event)) {
      this.enqueueRestoredMessage(message, callbacks);
    }
  }

  private flushPendingRestoreWorkflowResults(
    callbacks: AiConversationRestoreCallbacks
  ): void {
    const pending = [...this.pendingRestoreWorkflowResults.values()]
      .sort((left, right) => (left.index ?? 0) - (right.index ?? 0));
    this.pendingRestoreWorkflowResults.clear();
    for (const event of pending) {
      for (const message of this.restoredMessages(event)) {
        this.enqueueRestoredMessage(message, callbacks);
      }
    }
  }

  private canonicalStoredMessages(messages: AiChatHistoryMessage[]): AiChatHistoryMessage[] {
    const canonicalRequests = new Set(messages
      .filter(message => message.role === 'assistant'
        && message.subtype !== 'workflow_result')
      .map(message => this.storedMessageRequestKey(message)));
    const latestWorkflowResult = new Map<string, AiChatHistoryMessage>();
    for (const message of messages) {
      if (message.subtype === 'workflow_result') {
        latestWorkflowResult.set(this.storedMessageRequestKey(message), message);
      }
    }
    return messages.filter(message => {
      if (message.subtype !== 'workflow_result') return true;
      const requestKey = this.storedMessageRequestKey(message);
      return !canonicalRequests.has(requestKey)
        && latestWorkflowResult.get(requestKey) === message;
    });
  }

  private storedMessageRequestKey(message: AiChatHistoryMessage): string {
    return message.requestId || message.turnId || 'stored';
  }

  private restoreEventRequestKey(event: AiChatSocketEvent): string {
    return event.requestId || event.turnId || 'stored';
  }

  private drainRestoreMessageQueue(callbacks: AiConversationRestoreCallbacks): void {
    if (this.restoreMessageQueueRunning || this.restoreMessageQueue.length === 0) {
      this.finishIfComplete(callbacks);
      return;
    }
    const restoreToken = this.restoreConversationToken;
    const message = this.restoreMessageQueue.shift()!;
    this.restoreMessageQueueRunning = true;
    if (message.role === 'assistant') {
      this.restoreAssistantMessageParagraphs(message, callbacks, restoreToken, () => {
        this.restoreMessageQueueRunning = false;
        this.drainRestoreMessageQueue(callbacks);
      });
      return;
    }

    callbacks.pushMessage(message);
    callbacks.updateScrollButton();
    this.scheduleConversationRestore(() => {
      if (restoreToken !== this.restoreConversationToken) {
        return;
      }
      this.restoreMessageQueueRunning = false;
      this.drainRestoreMessageQueue(callbacks);
    });
  }

  private finishIfComplete(callbacks: AiConversationRestoreCallbacks): void {
    if (!this.restoreServerDone || this.restoreMessageQueueRunning ||
      this.restoreMessageQueue.length > 0 || this.restoreMessageBuffer.size > 0 ||
      this.restoreFinished) {
      return;
    }
    this.restoreFinished = true;
    callbacks.setRestoring(false);
    callbacks.setCurrentStatus('Restored');
    callbacks.updateScrollButton();
    callbacks.focusPrompt?.();
    callbacks.finish();
  }

  private restoreAttempt(event: AiChatSocketEvent): AiConversationRestoreAttempt | undefined {
    const restoreToken = event.metadata?.['restoreToken'];
    const restoreSequence = event.metadata?.['restoreSequence'];
    return typeof restoreToken === 'string' && this.validRestoreToken(restoreToken) &&
      typeof restoreSequence === 'number' && this.validRestoreSequence(restoreSequence)
      ? {token: restoreToken, sequence: restoreSequence} : undefined;
  }

  private matchesExpectedAttempt(attempt: AiConversationRestoreAttempt): boolean {
    const expected = this.expectedRestoreAttempt;
    return !!expected && attempt.token === expected.token &&
      attempt.sequence === expected.sequence;
  }

  private incompatibleRestoreFrame(event: AiChatSocketEvent): boolean {
    return event.type === 'HISTORY_START' ||
      event.type === 'HISTORY_CONTEXT' ||
      event.type === 'HISTORY_MESSAGE' ||
      event.type === 'HISTORY_FINAL' ||
      (event.type === 'system' && event.subtype === 'accepted');
  }

  private validRestoreToken(token: string): boolean {
    return AiConversationRestoreService.RESTORE_TOKEN_PATTERN.test(token);
  }

  private permissionMode(value: unknown) {
    return value === 'ask' || value === 'auto' || value === 'full_access'
      ? value : undefined;
  }

  private validRestoreSequence(sequence: number): boolean {
    return Number.isSafeInteger(sequence) && sequence > 0;
  }

  private failRestore(event: AiChatSocketEvent,
                      callbacks: AiConversationRestoreCallbacks): void {
    this.restoreFinished = true;
    this.restoreConversationToken += 1;
    this.clearConversationRestoreTimeout();
    this.restoreMessageQueue = [];
    this.restoreMessageQueueRunning = false;
    this.restoreMessageBuffer.clear();
    this.restoreServerDone = false;
    callbacks.setRestoring(false);
    callbacks.clearStatus();
    callbacks.setCurrentStatus('Error');
    callbacks.pushMessage({
      role: 'error',
      content: this.nonBlankText(event.content) ||
        this.nonBlankText(event.message) || 'Conversation restore failed.'
    });
    callbacks.updateScrollButton();
    callbacks.focusPrompt?.();
    callbacks.finish();
  }

  private restoreAssistantMessageParagraphs(
    message: AiChatMessage,
    callbacks: AiConversationRestoreCallbacks,
    restoreToken: number,
    done: () => void,
    blockIndex = 0,
    targetIndex?: number,
    blocks = this.restoreMarkdownBlocks(message.content)): void {
    if (restoreToken !== this.restoreConversationToken) {
      return;
    }
    if (targetIndex === undefined) {
      targetIndex = callbacks.pushMessage({...message, content: ''});
    }
    if (!callbacks.hasMessage(targetIndex)) {
      return;
    }
    if (blockIndex >= blocks.length) {
      this.scheduleConversationRestore(done);
      return;
    }

    callbacks.setMessage(targetIndex, {
      ...message,
      content: blocks.slice(0, blockIndex + 1).join('\n\n')
    });
    callbacks.updateScrollButton();
    this.scheduleConversationRestore(() => {
      this.restoreAssistantMessageParagraphs(message, callbacks, restoreToken, done, blockIndex + 1, targetIndex, blocks);
    });
  }

  private restoreMarkdownBlocks(content: string): string[] {
    const blocks = content
      .split(/\n\s*\n/)
      .map(block => block.trim())
      .filter(block => block.length > 0);
    return blocks.length > 0 ? blocks : [content];
  }

  private scheduleConversationRestore(callback: () => void): void {
    this.clearConversationRestoreTimeout();
    this.restoreConversationTimeout = window.setTimeout(() => {
      this.restoreConversationTimeout = undefined;
      callback();
    });
  }

  private clearConversationRestoreTimeout(): void {
    if (this.restoreConversationTimeout) {
      window.clearTimeout(this.restoreConversationTimeout);
      this.restoreConversationTimeout = undefined;
    }
  }
}
