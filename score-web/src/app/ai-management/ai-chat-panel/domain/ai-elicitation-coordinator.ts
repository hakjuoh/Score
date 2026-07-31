import {Injectable} from '@angular/core';
import {AiChatPanelState} from './ai-chat-panel-state';
import {AiChatTransportService} from './ai-chat-transport.service';
import {AiChatSocketEvent, AiElicitationResponse} from './ai-chat-panel.model';
import {elicitationNotice} from './ai-elicitation';
import {exactOptionalText, primaryContent} from './ai-chat-event-semantics';

export interface AiElicitationCallbacks {
  acknowledge(requestId: string): void;
  completeProgress(): void;
  clearStatus(): void;
  showStatus(content: string, inProgress: boolean): void;
  scrollToBottom(): void;
}

/** Coordinates one active elicitation without depending on the panel controller. */
@Injectable()
export class AiElicitationCoordinator {
  constructor(private readonly transport: AiChatTransportService) {}

  handleRequired(
    state: AiChatPanelState,
    event: AiChatSocketEvent,
    activeRequestId: string | undefined,
    callbacks: AiElicitationCallbacks
  ): void {
    if (!activeRequestId) return;
    const eventConversationId = exactOptionalText(event.conversationId);
    const expectedConversationId = state.activeRequest?.conversationId
      || state.conversationId || eventConversationId;
    const notice = elicitationNotice(
      event, activeRequestId, expectedConversationId, state.activeRequest?.generation
    );
    if (!notice || (state.elicitation
      && state.elicitation.elicitationId !== notice.elicitationId)) {
      return;
    }
    callbacks.acknowledge(event.requestId);
    callbacks.completeProgress();
    callbacks.clearStatus();
    state.elicitation = notice;
    state.elicitationBusy = false;
    state.currentStatus = 'Waiting for your input';
  }

  handleDecision(
    state: AiChatPanelState,
    event: AiChatSocketEvent,
    callbacks: AiElicitationCallbacks
  ): void {
    const active = state.elicitation;
    if (!active || event.requestId !== active.requestId
      || event.conversationId !== active.conversationId
      || event.metadata?.['elicitationId'] !== active.elicitationId) {
      return;
    }
    callbacks.acknowledge(event.requestId);
    if (event.subtype === 'elicitation_decision_accepted') {
      this.clear(state);
      state.currentStatus = 'Working';
      callbacks.showStatus('Response sent. Continuing.', true);
      return;
    }
    this.showResponseError(
      state,
      primaryContent(event).trim()
        || 'The assistant could not accept that response. Please try again.'
    );
  }

  respond(
    state: AiChatPanelState,
    response: AiElicitationResponse,
    activeRequestId: string | undefined,
    callbacks: AiElicitationCallbacks
  ): void {
    const active = state.elicitation;
    if (!active || state.elicitationBusy || !state.pending
      || activeRequestId !== active.requestId) {
      return;
    }
    state.elicitationBusy = true;
    state.currentStatus = 'Sending your response';
    try {
      this.transport.publish('/app/ai/chat/elicitation', {
        requestId: active.requestId,
        conversationId: active.conversationId,
        elicitationId: active.elicitationId,
        generation: active.generation,
        action: response.action,
        content: response.action === 'ACCEPT' ? response.content : {}
      });
    } catch {
      this.showResponseError(state, 'Could not send your response.');
      callbacks.scrollToBottom();
    }
  }

  clear(state: AiChatPanelState): void {
    state.elicitation = undefined;
    state.elicitationBusy = false;
  }

  private showResponseError(state: AiChatPanelState, content: string): void {
    state.elicitationBusy = false;
    state.currentStatus = 'Waiting for your input';
    state.messages.push({role: 'error', content});
  }

}
