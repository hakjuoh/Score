import {Injectable} from '@angular/core';
import {
  AiChatHistoryMessage,
  AiChatMessage,
  AiChatSocketEvent
} from './ai-chat-panel.model';
import {withoutTextualToolCallPlaceholder} from './ai-chat-event-semantics';
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
  defaultToolStatusContent,
  displayToolName,
  displayToolText
} from './ai-tool-presentation';
import {
  AiExecutionComposite,
  appendWorkflowConversation,
  workflowTerminalStatus
} from './ai-execution-composite';

/** Creates isolated projection sessions and provides stateless REST projection. */
@Injectable()
export class AiConversationProjector {
  projectStoredMessages(messages: AiChatHistoryMessage[]): AiChatMessage[] {
    return this.createSession().projectStoredMessages(messages);
  }

  createSession(): AiConversationProjectionSession {
    return new AiConversationProjectionSession();
  }
}

/** Stateful, ordered projection session for one socket restore replay. */
export class AiConversationProjectionSession {
  private readonly restoredExecution = new AiExecutionComposite(true);
  private bufferedEvents: AiChatSocketEvent[] = [];

  projectStoredMessages(messages: AiChatHistoryMessage[]): AiChatMessage[] {
    this.reset();
    const events = messages.map(message => this.storedMessageEvent(message));
    return this.canonicalEvents(events).flatMap(event => this.projectEvent(event));
  }

  acceptEvent(event: AiChatSocketEvent): AiChatMessage[] {
    if (this.bufferedEvents.length > 0 || this.isWorkflowPreview(event)) {
      this.bufferedEvents.push(event);
      if (this.hasUnresolvedWorkflowPreview(this.bufferedEvents)) return [];
      const ready = this.canonicalEvents(this.bufferedEvents);
      this.bufferedEvents = [];
      return ready.flatMap(candidate => this.projectEvent(candidate));
    }
    return this.projectEvent(event);
  }

  flushPendingEvents(): AiChatMessage[] {
    const ready = this.canonicalEvents(this.bufferedEvents);
    this.bufferedEvents = [];
    return ready.flatMap(event => this.projectEvent(event));
  }

  reset(): void {
    this.bufferedEvents = [];
    this.restoredExecution.clear();
  }

  private projectEvent(event: AiChatSocketEvent): AiChatMessage[] {
    const message = this.restoredMessage(event);
    if (!message) return [];
    const content = this.nonBlankText(event.response)
      || this.nonBlankText(event.content);
    if (event.message === 'agent_event' && event.subtype === 'workflow_started'
      && message.role === 'workflow_group' && message.workflowNodeId && content) {
      return [{role: 'guide', content}, message];
    }
    if (event.files?.length) message.files = event.files;
    // Keep the Composite-owned object identity so later lifecycle frames update
    // the exact message already rendered by the restore coordinator.
    return [message];
  }

  private storedMessageEvent(message: AiChatHistoryMessage): AiChatSocketEvent {
    return {
      requestId: message.requestId || 'stored',
      type: 'HISTORY_MESSAGE',
      message: message.role,
      response: message.content,
      turnId: message.turnId,
      groupId: message.groupId,
      toolCallId: message.toolCallId,
      index: message.index,
      subtype: message.subtype || message.toolStatus,
      files: message.files,
      metadata: {
        ...(message.metadata || {}),
        ...((message.toolCallSeq ?? message.toolCallSequence) !== undefined
          ? {toolCallSeq: message.toolCallSeq ?? message.toolCallSequence} : {})
      }
    };
  }

  private restoredRole(role?: string): AiChatMessage['role'] {
    if (role === 'error' || role === 'ERROR') return 'error';
    if (role === 'assistant_update') return 'progress';
    if (role === 'tool_group') return 'tool_group';
    if (role === 'tool_call') return 'tool_call';
    if (role === 'progress') return 'progress';
    if (role === 'debug') return 'debug';
    if (role === 'assistant') return 'assistant';
    if (role === 'guide') return 'guide';
    if (role === 'agent_event') return 'agent_group';
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
      // Older composed workers persisted this immediately before their
      // lifecycle row; suppress it instead of duplicating specialist chatter.
      if (isSpecialistActivityEvent(guideEvent)
        && !this.restoredExecution.isPlainEvent(guideEvent)) return null;
    }
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
    if (isSpecialistToolEvent(toolEvent)
      && !this.restoredExecution.isPlainEvent(toolEvent)) {
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
    if (!groupId || !toolCallId || !toolName || !toolStatus) return null;
    const turnId = this.nonBlankText(event.turnId);
    const toolCallSeq = this.nonNegativeSequence(event.metadata?.['toolCallSeq']);
    const toolDetail = this.restoredToolDetail(event, content);
    return {
      role,
      content: defaultToolStatusContent(toolStatus, displayToolName(toolName) || toolName),
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
        changeSafe: event.metadata?.['changeSafe'] === true
      } : {})
    };
  }

  private restoredAgentEvent(event: AiChatSocketEvent, content: string): AiChatMessage | null {
    const lifecycleEvent: AiChatSocketEvent = {...event, type: 'system', content};
    if (event.subtype === 'workflow_started') {
      const placement = this.restoredExecution.startWorkflow(lifecycleEvent, []);
      if (!placement || !placement.created || placement.presentation === 'hidden') return null;
      if (placement.presentation === 'message') {
        if (!placement.anchor) return null;
        if (!placement.root) {
          placement.container.push(placement.anchor);
          return null;
        }
        return placement.anchor;
      }
      if (!placement.anchor) return null;
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
    if (placement?.createdRootAnchor) return placement.anchor || null;
    const plain = this.restoredExecution.upsertPlainActivity(lifecycleEvent, []);
    if (!plain || !plain.created) return null;
    if (plain.root) return plain.message;
    plain.container.push(plain.message);
    return null;
  }

  private restoredGroupsForEvent(event: AiChatSocketEvent): AiAgentActivity[][] {
    const activities = this.restoredExecution.activitiesFor(event);
    return activities ? [activities] : [];
  }

  private restoredToolDetail(event: AiChatSocketEvent, content: string): string | undefined {
    const detail = this.nonBlankText(event.metadata?.['toolDetail'])
      || (/\nArguments:\s*/.test(content) ? content : undefined);
    return displayToolText(detail);
  }

  private eventRequestKey(event: AiChatSocketEvent): string {
    return event.requestId || event.turnId || 'stored';
  }

  private canonicalEvents(events: AiChatSocketEvent[]): AiChatSocketEvent[] {
    const canonicalRequests = this.canonicalRequestKeys(events);
    const latestWorkflowResult = new Map<string, AiChatSocketEvent>();
    for (const event of events) {
      if (this.isWorkflowPreview(event)) {
        latestWorkflowResult.set(this.eventRequestKey(event), event);
      }
    }
    return events.filter(event => {
      if (!this.isWorkflowPreview(event)) return true;
      const requestKey = this.eventRequestKey(event);
      return !canonicalRequests.has(requestKey)
        && latestWorkflowResult.get(requestKey) === event;
    });
  }

  private hasUnresolvedWorkflowPreview(events: AiChatSocketEvent[]): boolean {
    const canonicalRequests = this.canonicalRequestKeys(events);
    return events.some(event => this.isWorkflowPreview(event)
      && !canonicalRequests.has(this.eventRequestKey(event)));
  }

  private canonicalRequestKeys(events: AiChatSocketEvent[]): Set<string> {
    return new Set(events
      .filter(event => this.isCanonicalAssistant(event))
      .map(event => this.eventRequestKey(event)));
  }

  private isWorkflowPreview(event: AiChatSocketEvent): boolean {
    return event.message === 'assistant' && event.subtype === 'workflow_result';
  }

  private isCanonicalAssistant(event: AiChatSocketEvent): boolean {
    return event.message === 'assistant' && event.subtype !== 'workflow_result';
  }

  private nonBlankText(value: unknown): string | undefined {
    return typeof value === 'string' && value.trim() ? value.trim() : undefined;
  }

  private nonNegativeSequence(value: unknown): number | undefined {
    return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
      ? value : undefined;
  }
}
