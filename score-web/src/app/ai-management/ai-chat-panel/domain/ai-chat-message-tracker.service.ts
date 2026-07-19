import {Injectable} from '@angular/core';
import {
  AiToolCallEventSemantics,
  legacyRecoverableToolName,
  toolCallEventSemantics
} from './ai-chat-event-semantics';
import {AiChatPanelState} from './ai-chat-panel-state';
import {AiChatMessage, AiChatSocketEvent} from './ai-chat-panel.model';

@Injectable()
export class AiChatMessageTrackerService {
  private statusMessageIndex?: number;
  private toolCallMessageIndexesByToolCallId = new Map<string, number>();
  private activeToolCallsByKey = new Map<string, AiToolCallEventSemantics>();

  get activeToolCallCount(): number {
    return this.activeToolCallsByKey.size;
  }

  upsertToolGroup(state: AiChatPanelState, event: AiChatSocketEvent): void {
    const terminal = event.subtype === 'completed' || event.subtype === 'failed';
    const content = this.primaryContent(event) || 'Used tools';
    this.showStatus(state, terminal ? 'Working' : content, true);
    state.currentStatus = terminal ? 'Working' : content;
  }

  handleToolCall(state: AiChatPanelState, event: AiChatSocketEvent): void {
    const semantics = toolCallEventSemantics(event);
    if (!semantics) return;

    if (semantics.active) {
      const existing = this.activeToolCallsByKey.get(semantics.key);
      this.activeToolCallsByKey.set(semantics.key, {
        ...semantics,
        turnId: semantics.turnId || existing?.turnId,
        toolCallSeq: semantics.toolCallSeq ?? existing?.toolCallSeq,
        toolName: semantics.toolName || existing?.toolName,
        hidden: semantics.hidden || existing?.hidden === true
      });
      this.showStatus(state, semantics.hidden ? 'Working' : semantics.content, true);
      state.currentStatus = semantics.content;
      return;
    }

    const existing = this.activeToolCallsByKey.get(semantics.key);
    this.activeToolCallsByKey.delete(semantics.key);
    const terminalSemantics: AiToolCallEventSemantics = {
      ...semantics,
      turnId: semantics.turnId || existing?.turnId,
      toolCallSeq: semantics.toolCallSeq ?? existing?.toolCallSeq,
      toolName: semantics.toolName || existing?.toolName,
      hidden: semantics.hidden || existing?.hidden === true
    };
    if (terminalSemantics.hidden) {
      this.showStatus(state, 'Working', true);
      state.currentStatus = 'Working';
      return;
    }

    this.appendToolCall(state, terminalSemantics);
    const requestStatus = terminalSemantics.status === 'failed'
      ? 'Continuing after tool failure' : 'Working';
    this.showStatus(state, requestStatus, true);
    state.currentStatus = requestStatus;
  }

  handleLegacyRecoverableToolError(
    state: AiChatPanelState, event: AiChatSocketEvent, _content: string
  ): boolean {
    const toolName = legacyRecoverableToolName(event);
    if (!toolName) return false;

    const active = Array.from(this.activeToolCallsByKey.values()).reverse()
      .find(candidate => candidate.toolName === toolName);
    if (!active) return false;

    this.activeToolCallsByKey.delete(active.key);
    if (active.hidden) {
      this.showStatus(state, 'Working', true);
      state.currentStatus = 'Working';
      return true;
    }

    this.appendToolCall(state, {
      ...active,
      active: false,
      status: 'failed',
      content: 'Execution failed',
      recoverable: true
    });
    this.showStatus(state, 'Continuing after tool failure', true);
    state.currentStatus = 'Continuing after tool failure';
    return true;
  }

  hasStructuredToolRows(): boolean {
    return this.toolCallMessageIndexesByToolCallId.size > 0;
  }

  showStatus(state: AiChatPanelState, content: string, inProgress = false): void {
    if (this.statusMessageIndex !== undefined) {
      const existing = state.messages[this.statusMessageIndex];
      if (existing?.role === 'progress') {
        const status = {role: 'progress' as const, content, inProgress};
        if (this.statusMessageIndex === state.messages.length - 1) {
          state.messages[this.statusMessageIndex] = status;
        } else {
          this.removeMessageAt(state, this.statusMessageIndex);
          state.messages.push(status);
          this.statusMessageIndex = state.messages.length - 1;
        }
        return;
      }
      this.statusMessageIndex = undefined;
    }
    this.completeProgressMessages(state);
    state.messages.push({role: 'progress', content, inProgress});
    this.statusMessageIndex = state.messages.length - 1;
  }

  completeProgressMessages(state: AiChatPanelState): void {
    state.messages.forEach(message => {
      if (message.role === 'progress' && message.inProgress) {
        message.inProgress = false;
      }
    });
  }

  completeToolGroupMessages(state: AiChatPanelState): void {
    state.messages.forEach(message => {
      if ((message.role === 'tool_group' || message.role === 'tool_call') && message.inProgress) {
        message.inProgress = false;
      }
    });
  }

  clearToolCallTracking(): void {
    this.toolCallMessageIndexesByToolCallId.clear();
    this.activeToolCallsByKey.clear();
  }

  clearStatusMessage(state: AiChatPanelState): void {
    if (this.statusMessageIndex === undefined) return;
    if (state.messages[this.statusMessageIndex]) {
      this.removeMessageAt(state, this.statusMessageIndex);
    }
    this.statusMessageIndex = undefined;
  }

  private appendToolCall(state: AiChatPanelState, semantics: AiToolCallEventSemantics): void {
    const index = this.toolCallMessageIndexesByToolCallId.get(semantics.key);
    const message: AiChatMessage = {
      role: 'tool_call',
      content: semantics.content,
      inProgress: false,
      eventType: 'tool_call',
      ...(semantics.turnId ? {turnId: semantics.turnId} : {}),
      groupId: semantics.groupId,
      toolCallId: semantics.toolCallId,
      ...(semantics.toolCallSeq !== undefined ? {toolCallSeq: semantics.toolCallSeq} : {}),
      toolName: semantics.toolName,
      ...(semantics.toolDetail ? {toolDetail: semantics.toolDetail} : {}),
      toolStatus: semantics.status,
      recoverable: semantics.recoverable,
      retryable: semantics.retryable,
      mutationSafe: semantics.mutationSafe
    };
    if (index !== undefined && state.messages[index]) {
      state.messages[index] = message;
      return;
    }

    const insertionIndex = this.toolCallInsertionIndex(state, message);
    state.messages.splice(insertionIndex, 0, message);
    this.shiftMessageIndexesAfterInsertion(insertionIndex);
    this.toolCallMessageIndexesByToolCallId.set(semantics.key, insertionIndex);
    if (this.statusMessageIndex !== undefined && this.statusMessageIndex >= insertionIndex) {
      this.statusMessageIndex += 1;
    }
  }

  private toolCallInsertionIndex(state: AiChatPanelState, message: AiChatMessage): number {
    if (message.toolCallSeq === undefined) return state.messages.length;
    const scope = message.turnId || message.groupId;
    for (let index = 0; index < state.messages.length; index++) {
      const candidate = state.messages[index];
      if (candidate.role !== 'tool_call'
        || (candidate.turnId || candidate.groupId) !== scope
        || candidate.toolCallSeq === undefined) {
        continue;
      }
      if (candidate.toolCallSeq > message.toolCallSeq) return index;
    }
    return state.messages.length;
  }

  private removeMessageAt(state: AiChatPanelState, index: number): void {
    state.messages.splice(index, 1);
    Array.from(this.toolCallMessageIndexesByToolCallId.entries()).forEach(([key, value]) => {
      if (value === index) {
        this.toolCallMessageIndexesByToolCallId.delete(key);
      } else if (value > index) {
        this.toolCallMessageIndexesByToolCallId.set(key, value - 1);
      }
    });
    if (this.statusMessageIndex !== undefined && this.statusMessageIndex > index) {
      this.statusMessageIndex -= 1;
    }
  }

  private shiftMessageIndexesAfterInsertion(insertedIndex: number): void {
    Array.from(this.toolCallMessageIndexesByToolCallId.entries()).forEach(([key, index]) => {
      if (index >= insertedIndex) {
        this.toolCallMessageIndexesByToolCallId.set(key, index + 1);
      }
    });
  }

  private primaryContent(event: AiChatSocketEvent): string {
    return event.content || event.response || event.message || '';
  }
}
