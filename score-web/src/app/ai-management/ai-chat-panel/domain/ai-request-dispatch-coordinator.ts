/**
 * Initializes request state and dispatches normal, revised, and confirmed chat intents.
 */

import {Injectable} from '@angular/core';
import {AiChatAttachment} from './ai-chat-panel.model';
import {AiChatPanelState} from './ai-chat-panel-state';

export interface AiRequestDispatchCallbacks {
  activate(requestId: string): void;
  beginChangeRepeat(requestId: string, prompt: string, attachments: AiChatAttachment[]): void;
  clearToolTracking(): void;
  resizePrompt(): void;
  flushWorkspace(): void;
  userMessageContent(prompt: string, attachments: AiChatAttachment[]): string;
}

export interface AiRequestDispatchInput {
  requestId: string;
  prompt: string;
  attachments: AiChatAttachment[];
  intent: AiRequestDispatchIntent;
}

export type AiRequestDispatchIntent =
  | {kind: 'normal'}
  | {kind: 'approved-repeat'}
  | {kind: 'revised-repeat'};

@Injectable()
export class AiRequestDispatchCoordinator {
  prepare(state: AiChatPanelState, input: AiRequestDispatchInput,
          callbacks: AiRequestDispatchCallbacks): void {
    const normal = input.intent.kind === 'normal';
    const clearComposer = normal || input.intent.kind === 'revised-repeat';
    callbacks.beginChangeRepeat(input.requestId, input.prompt, input.attachments);
    callbacks.activate(input.requestId);
    callbacks.clearToolTracking();
    state.resetAgentActivity();
    state.activePanelTab = 'chat';
    state.pending = true;
    state.currentStatus = normal ? 'Sending request' : 'Sending approved change';
    if (clearComposer) {
      state.prompt = '';
      state.attachments = [];
    }
    if (normal) {
      callbacks.flushWorkspace();
    }
    if (clearComposer) {
      callbacks.resizePrompt();
    }
    state.messages.push({
      role: 'user',
      content: callbacks.userMessageContent(input.prompt, input.attachments)
    });
  }
}
