import {Injectable} from '@angular/core';
import {AiChatPanelState} from './ai-chat-panel-state';
import {AiChatSessionPersistenceService} from './ai-chat-session-persistence.service';

/** Owns change detection and async invalidation for persisted panel drafts. */
@Injectable()
export class AiChatWorkspacePersistenceCoordinator {
  private ready = false;
  private workspaceSignature = '';
  private attachmentSignature = '';
  private attachmentRestoreGeneration = 0;

  constructor(private readonly persistence: AiChatSessionPersistenceService) {}

  initialize(
    state: AiChatPanelState,
    options: {
      workspaceRestored: boolean;
      setRestoreChatScroll(pending: boolean): void;
      isDestroyed(): boolean;
    }
  ): void {
    options.setRestoreChatScroll(options.workspaceRestored);
    this.workspaceSignature = this.currentWorkspaceSignature(state);
    this.attachmentSignature = this.currentAttachmentSignature(state);
    this.ready = true;
    this.restoreDraftAttachments(state, options.isDestroyed);
  }

  restoreDraftAttachments(state: AiChatPanelState, isDestroyed: () => boolean): void {
    const generation = ++this.attachmentRestoreGeneration;
    void this.persistence.restoreDraftAttachments().then(attachments => {
      if (isDestroyed() || generation !== this.attachmentRestoreGeneration
        || state.attachments.length > 0) {
        return;
      }
      state.attachments = attachments;
    });
  }

  persistIfChanged(
    state: AiChatPanelState,
    owner: {destroyed: boolean; popoutMode: boolean}
  ): void {
    if (!this.ready || owner.destroyed || state.popoutActive && !owner.popoutMode) return;
    const workspaceSignature = this.currentWorkspaceSignature(state);
    if (workspaceSignature !== this.workspaceSignature) {
      this.workspaceSignature = workspaceSignature;
      this.persistence.persistWorkspace(state);
    }
    const attachmentSignature = this.currentAttachmentSignature(state);
    if (attachmentSignature !== this.attachmentSignature) {
      this.attachmentSignature = attachmentSignature;
      this.invalidateDraftAttachmentRestore();
      void this.persistence.persistDraftAttachments(state.attachments);
    }
  }

  flush(state: AiChatPanelState): void {
    this.persistence.persistWorkspace(state);
    this.workspaceSignature = this.currentWorkspaceSignature(state);
    this.attachmentSignature = this.currentAttachmentSignature(state);
    this.invalidateDraftAttachmentRestore();
    void this.persistence.persistDraftAttachments(state.attachments);
  }

  invalidateDraftAttachmentRestore(): void {
    this.attachmentRestoreGeneration += 1;
  }

  private currentWorkspaceSignature(state: AiChatPanelState): string {
    return JSON.stringify([
      state.activePanelTab, state.sideSize, state.horizontalSize, state.prompt,
      state.chatScrollTop, state.historyScrollTop
    ]);
  }

  private currentAttachmentSignature(state: AiChatPanelState): string {
    return JSON.stringify(state.attachments.map(attachment => [
      attachment.name, attachment.mediaType, attachment.size, attachment.data.length
    ]));
  }
}
