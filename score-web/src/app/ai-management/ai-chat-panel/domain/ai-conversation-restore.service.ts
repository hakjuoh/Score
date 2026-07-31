import {Injectable} from '@angular/core';
import {
  AiChatMessage,
  AiChatConversationDetails,
  AiChatSocketEvent,
  AiContextUsage
} from './ai-chat-panel.model';
import {contextUsageValue} from './ai-chat-event-semantics';
import {
  AiConversationProjectionSession,
  AiConversationProjector
} from './ai-conversation-projector';
import {
  AiConversationRestoreRenderCallbacks,
  AiConversationRestoreRenderer
} from './ai-conversation-restore-renderer';

export interface AiConversationRestoreCallbacks extends AiConversationRestoreRenderCallbacks {
  setConversationId(conversationId: string): void;
  setSettings?(settings: Pick<AiChatConversationDetails,
    'modelName' | 'reasoningEffort' | 'permissionMode'
    | 'activeWorkflow'>): void;
  setModelName?(modelName: string): void;
  setReasoningEffort?(reasoningEffort: string): void;
  setContextUsage?(contextUsage: AiContextUsage): void;
  resetMessages(): void;
  setCurrentStatus(status: string): void;
  scrollTop(): void;
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

  private restoreMessageBuffer = new Map<number, AiChatSocketEvent>();
  private nextRestoreMessageIndex = 0;
  private restoreServerDone = false;
  private restoreFinished = false;
  private restoreStarted = false;
  private expectedRestoreAttempt?: AiConversationRestoreAttempt;
  private readonly projectionSession: AiConversationProjectionSession;

  constructor(
    projector: AiConversationProjector = new AiConversationProjector(),
    private readonly renderer: AiConversationRestoreRenderer = new AiConversationRestoreRenderer()
  ) {
    this.projectionSession = projector.createSession();
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

  expectAttempt(token: string, sequence: number): void {
    if (!this.validRestoreToken(token) || !this.validRestoreSequence(sequence)) {
      throw new Error('Invalid conversation restore identity.');
    }
    if (this.expectedRestoreAttempt &&
      sequence <= this.expectedRestoreAttempt.sequence) {
      throw new Error('Conversation restore sequence must increase.');
    }
    this.expectedRestoreAttempt = {token, sequence};
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

  cancel(): void {
    this.reset();
  }

  reset(): void {
    this.expectedRestoreAttempt = undefined;
    this.resetReplayState();
  }

  private resetReplayState(): void {
    this.renderer.reset();
    this.restoreMessageBuffer.clear();
    this.nextRestoreMessageIndex = 0;
    this.restoreServerDone = false;
    this.restoreFinished = false;
    this.restoreStarted = false;
    this.projectionSession.reset();
  }

  private enqueueRestoredMessage(message: AiChatMessage, callbacks: AiConversationRestoreCallbacks): void {
    this.renderer.enqueue(message, callbacks, () => this.finishIfComplete(callbacks));
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
    for (const message of this.projectionSession.acceptEvent(event)) {
      this.enqueueRestoredMessage(message, callbacks);
    }
  }

  private flushPendingRestoreWorkflowResults(
    callbacks: AiConversationRestoreCallbacks
  ): void {
    for (const message of this.projectionSession.flushPendingEvents()) {
      this.enqueueRestoredMessage(message, callbacks);
    }
  }

  private finishIfComplete(callbacks: AiConversationRestoreCallbacks): void {
    if (!this.restoreServerDone || this.renderer.pending || this.restoreMessageBuffer.size > 0 ||
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

  private nonBlankText(value: unknown): string | undefined {
    return typeof value === 'string' && value.trim() ? value.trim() : undefined;
  }

  private validRestoreSequence(sequence: number): boolean {
    return Number.isSafeInteger(sequence) && sequence > 0;
  }

  private failRestore(event: AiChatSocketEvent,
                      callbacks: AiConversationRestoreCallbacks): void {
    this.restoreFinished = true;
    this.renderer.reset();
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

}
