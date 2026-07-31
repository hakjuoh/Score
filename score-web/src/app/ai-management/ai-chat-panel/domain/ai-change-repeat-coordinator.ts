/**
 * Tracks revised change requests and binds their repeat execution to the matching confirmation.
 */

import {Injectable} from '@angular/core';
import {changeConfirmationNotice, isUnexpiredChangeConfirmation} from './ai-change-confirmation';
import {AiChatAttachment, AiChatSocketEvent, AiChangeConfirmationNotice} from './ai-chat-panel.model';

export interface ChangeRepeatDraft {
  requestId: string;
  prompt: string;
  attachments: AiChatAttachment[];
}

export interface ChangeRepeatOpportunity {
  conversationId: string;
  draft: ChangeRepeatDraft;
  notice: AiChangeConfirmationNotice;
}

export interface BoundChangeConfirmationNotice {
  conversationId: string;
  notice: AiChangeConfirmationNotice;
}

export type ChangeRepeatNoticeResult = 'accepted' | 'ignored' | 'rejected';
export type ChangeRepeatCompletion =
  | {kind: 'none'}
  | {kind: 'confirmation'; opportunity: ChangeRepeatOpportunity}
  | {kind: 'lost-grant'; opportunity: ChangeRepeatOpportunity};

@Injectable()
export class AiChangeRepeatCoordinator {
  #draft?: ChangeRepeatDraft;
  #pendingConfirmation?: BoundChangeConfirmationNotice;
  #rejectedRequestId?: string;

  get draft(): ChangeRepeatDraft | undefined {
    return this.#draft;
  }

  get pendingConfirmation(): BoundChangeConfirmationNotice | undefined {
    return this.#pendingConfirmation;
  }

  begin(requestId: string, prompt: string, attachments: AiChatAttachment[]): void {
    this.clear();
    this.#draft = {
      requestId,
      prompt,
      attachments: attachments.map(attachment => ({...attachment}))
    };
  }

  acceptNotice(event: AiChatSocketEvent, activeRequestId: string | undefined,
               activeConversationId: string | undefined,
               conversationId: string | undefined): ChangeRepeatNoticeResult {
    if (!activeRequestId || this.#rejectedRequestId === activeRequestId
      || this.#pendingConfirmation) {
      return 'ignored';
    }
    const eventConversationId = typeof event.conversationId === 'string'
      && event.conversationId.trim() === event.conversationId
      && event.conversationId.length > 0
      ? event.conversationId : undefined;
    const expectedConversationId = activeConversationId || conversationId || eventConversationId;
    const notice = changeConfirmationNotice(event, activeRequestId, expectedConversationId);
    if (!this.#draft || this.#draft.requestId !== activeRequestId || !notice) {
      this.#rejectedRequestId = activeRequestId;
      return 'rejected';
    }
    this.#pendingConfirmation = {
      conversationId: expectedConversationId!,
      notice
    };
    return 'accepted';
  }

  finish(requestId: string,
         terminalConversationId: string | undefined): ChangeRepeatCompletion {
    const draft = this.#draft;
    const boundNotice = this.#pendingConfirmation;
    const rejected = this.#rejectedRequestId === requestId;
    this.clear(requestId);
    if (rejected || !draft || draft.requestId !== requestId || !boundNotice
      || terminalConversationId !== boundNotice.conversationId
      || !isUnexpiredChangeConfirmation(boundNotice.notice)) {
      return {kind: 'none'};
    }
    const opportunity = {
      conversationId: boundNotice.conversationId,
      draft,
      notice: boundNotice.notice
    };
    return boundNotice.notice.status === 'APPROVED'
      ? {kind: 'lost-grant', opportunity}
      : {kind: 'confirmation', opportunity};
  }

  discardConfirmation(): void {
    this.#pendingConfirmation = undefined;
  }

  reject(requestId: string): void {
    this.#pendingConfirmation = undefined;
    this.#rejectedRequestId = requestId;
  }

  clear(requestId?: string): void {
    if (requestId && this.#draft && this.#draft.requestId !== requestId) {
      return;
    }
    this.#draft = undefined;
    this.#pendingConfirmation = undefined;
    this.#rejectedRequestId = undefined;
  }
}
