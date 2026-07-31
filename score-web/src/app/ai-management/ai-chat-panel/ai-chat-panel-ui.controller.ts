/**
 * Handles template-driven user actions, settings, attachments, and interaction responses.
 */

import {Directive} from '@angular/core';
import {AiChatPanelRequestController} from './ai-chat-panel-request.controller';
import {AiChatAttachmentQueueCallbacks} from './domain/ai-chat-attachment-queue.service';
import {
  AiChatCommand,
  AiChatDock,
  AiChatPanelTab
} from './domain/ai-chat-panel.model';

@Directive()
export abstract class AiChatPanelUiController extends AiChatPanelRequestController {
  private reattachFallbackTimeout?: number;

  ngOnInit(): void {
    this.refreshBranding();
    const workspaceRestored = this.sessionPersistence.restoreWorkspace(this.state);
    this.state.sideSize = this.layoutService.clamp(
      this.state.sideSize, 320, Math.max(320, window.innerWidth - 96)
    );
    this.state.horizontalSize = this.layoutService.clamp(
      this.state.horizontalSize, 240, Math.max(240, window.innerHeight - 96)
    );
    this.initializeWorkspacePersistence(workspaceRestored);
    this.loadAvailableModels();
    this.state.dock = this.sessionPersistence.restorePanelDock() || this.state.dock;
    this.windowCoordinator.connect({
      popoutReady: () => this.markPopoutReady(),
      reattachRequested: () => this.popoutMode
        ? this.reattachPopout() : this.restoreDockedWindow(),
      assistantClosed: () => this.closeDetachedAssistant(),
      popoutMissing: () => this.restoreDockedWindow(),
      popoutExpected: () => this.state.popoutActive
    });
    if (this.popoutMode) {
      document.title = 'connectCenter Assistant';
      this.state.popoutActive = false;
      this.sessionPersistence.persistPopoutActive(true);
      this.open();
      return;
    }
    if (this.sessionPersistence.restorePopoutActive()
      && this.sessionPersistence.restorePanelVisibility()) {
      this.state.isOpen = true;
      this.state.popoutActive = true;
      this.updateMainPanelInset();
      return;
    }
    if (this.sessionPersistence.restorePanelVisibility()) {
      this.open();
      return;
    }
    this.updateMainPanelInset();
  }

  protected loadAvailableModels(): void {
    this.modelSettings.load(this.state, this.destroyed$);
  }

  ngOnDestroy(): void {
    if (!this.state.popoutActive || this.popoutMode) this.flushWorkspacePersistence();
    this.windowCoordinator.destroy();
    if (this.reattachFallbackTimeout !== undefined) {
      window.clearTimeout(this.reattachFallbackTimeout);
      this.reattachFallbackTimeout = undefined;
    }
    this.destroyed = true;
    this.destroyed$.next();
    this.destroyed$.complete();
    this.clearChangeRepeatDraft();
    this.changeInteractions.destroy(this.state);
    this.elicitationCoordinator.clear(this.state);
    this.clearChangeApprovalBatch();
    this.invalidateAttachmentReads();
    this.requestSubscription?.unsubscribe();
    this.conversationHistorySubscription?.unsubscribe();
    this.lastConversationRestoreSubscription?.unsubscribe();
    this.clearCompletedPayloadRecovery();
    this.clearActiveRecovery();
    this.clearRequestStatusWatchdog();
    this.transportService.cancelReconnect();
    this.cancellationService.reset();
    this.clearTimers();
    this.clearToolCallTracking();
    this.viewport.destroy();
    this.cancelConversationRestore();
    this.layoutService.clearMainPanelInset();
  }

  open(event?: MouseEvent): void {
    event?.stopPropagation();
    this.refreshBranding();
    this.state.isOpen = true;
    if (this.state.mcpStatus.state === 'CHECKING') this.refreshMcpStatus();
    this.sessionPersistence.persistPanelVisibility(true);
    this.loadConversationHistory();
    this.recoverActiveRequest(() => this.restoreLastConversation());
    this.updateMainPanelInset();
    if (this.state.activePanelTab === 'chat') {
      if (!this.restoreChatScrollPending) this.scrollToBottom(true);
      this.focusPrompt();
    }
  }

  close(event?: MouseEvent): void {
    event?.stopPropagation();
    if (this.popoutMode) {
      this.sessionPersistence.persistPanelVisibility(false);
      this.sessionPersistence.persistPopoutActive(false);
      this.sessionPersistence.persistWorkspace(this.state);
      void this.sessionPersistence.persistDraftAttachments(this.state.attachments)
        .then(() => this.windowCoordinator.notifyAssistantClosed());
      return;
    }
    this.state.isOpen = false;
    this.sessionPersistence.persistPanelVisibility(false);
    this.state.showScrollToBottomButton = false;
    this.updateMainPanelInset();
  }

  openPopout(event?: Event): void {
    event?.stopPropagation();
    if (this.popoutMode || this.state.popoutActive) return;
    if (this.state.pending || this.changeDecisionOpen || this.changeDecisionInFlight
      || !!this.state.elicitation) {
      this.snackBar.open(
        'Finish the active assistant interaction before opening a separate window.',
        'Dismiss', {duration: 3500}
      );
      return;
    }
    this.flushWorkspacePersistence();
    if (!this.windowCoordinator.openPopout()) {
      this.snackBar.open(
        'The browser blocked the Assistant window. Allow pop-ups and try again.',
        'Dismiss', {duration: 4000}
      );
      return;
    }
    this.state.popoutActive = true;
    this.state.isOpen = true;
    this.sessionPersistence.persistPanelVisibility(true);
    this.sessionPersistence.persistPopoutActive(true);
    this.updateMainPanelInset();
  }

  focusPopout(event?: Event): void {
    event?.stopPropagation();
    this.windowCoordinator.focusPopout();
  }

  reattachPopout(event?: Event): void {
    event?.stopPropagation();
    if (!this.popoutMode) {
      this.windowCoordinator.closePopoutForReattach();
      if (this.reattachFallbackTimeout !== undefined) {
        window.clearTimeout(this.reattachFallbackTimeout);
      }
      this.reattachFallbackTimeout = window.setTimeout(() => {
        this.reattachFallbackTimeout = undefined;
        this.restoreDockedWindow();
      }, 5000);
      return;
    }
    this.sessionPersistence.persistWorkspace(this.state);
    this.sessionPersistence.persistPopoutActive(false);
    void this.sessionPersistence.persistDraftAttachments(this.state.attachments)
      .then(() => this.windowCoordinator.requestReattach());
  }

  setDock(dock: AiChatDock): void {
    this.state.dock = dock;
    this.sessionPersistence.persistPanelDock(dock);
    this.updateMainPanelInset();
    this.scrollToBottom(true);
    this.focusPrompt();
  }

  setPanelTab(tab: AiChatPanelTab, event?: Event): void {
    event?.stopPropagation();
    this.state.activePanelTab = tab;
    if (tab === 'history') {
      this.loadConversationHistory();
      return;
    }
    this.restoreChatScrollPosition(false);
    this.focusPrompt();
  }

  send(): void {
    if (this.attachmentQueue.pending) {
      this.snackBar.open('Wait for attachments to finish loading.', 'Dismiss', {duration: 3000});
      return;
    }
    const prompt = this.state.prompt.trim();
    if (!prompt && this.state.attachments.length === 0) {
      return;
    }

    const attachments = [...this.state.attachments];

    if (prompt) {
      const commandDecision = this.commandService.decide(prompt);
      if (commandDecision.kind === 'local' && commandDecision.command === 'cancel') {
        this.handleLocalCommand(commandDecision.command, prompt);
        return;
      }
      if (commandDecision.kind !== 'local' && this.canRequestChangeRevision()) {
        this.sendChangeRevisionRequest(prompt, attachments);
        return;
      }
      if (this.interactionBlocked) {
        return;
      }
      if (commandDecision.kind === 'local') {
        this.handleLocalCommand(commandDecision.command!, prompt);
        return;
      }
    }

    if (this.canRequestChangeRevision()) {
      this.snackBar.open('Describe the change to approve in Chat.', 'Dismiss', {duration: 3000});
      return;
    }

    if (this.interactionBlocked) {
      return;
    }

    this.startChatRequest(prompt, attachments);
  }

  openFilePicker(input: HTMLInputElement): void {
    if (!this.interactionBlocked) {
      input.click();
    }
  }

  onFileInputChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.addFiles(input.files);
    input.value = '';
  }

  onDragOver(event: DragEvent): void {
    if (this.interactionBlocked) {
      return;
    }
    event.preventDefault();
    this.state.dragActive = true;
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.state.dragActive = false;
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.state.dragActive = false;
    if (!this.interactionBlocked) {
      this.addFiles(event.dataTransfer?.files);
    }
  }

  removeAttachment(index: number): void {
    if (!this.state.pending) {
      this.invalidateDraftAttachmentRestore();
      this.state.attachments.splice(index, 1);
      this.flushWorkspacePersistence();
    }
  }

  onComposerKeydown(event: KeyboardEvent): void {
    if (this.showCommandSuggestions) {
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        this.state.selectedCommandIndex = (this.state.selectedCommandIndex + 1) % this.commandSuggestions.length;
        return;
      }
      if (event.key === 'ArrowUp') {
        event.preventDefault();
        this.state.selectedCommandIndex =
          (this.state.selectedCommandIndex + this.commandSuggestions.length - 1) % this.commandSuggestions.length;
        return;
      }
      if (event.key === 'Tab') {
        event.preventDefault();
        this.applyCommandSuggestion(this.commandSuggestions[this.state.selectedCommandIndex]);
        return;
      }
    }

    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      if (this.showCommandSuggestions && !this.commandService.isKnownCommand(this.state.prompt)) {
        this.applyCommandSuggestion(this.commandSuggestions[this.state.selectedCommandIndex]);
        return;
      }
      this.send();
    }
  }

  onPromptChange(): void {
    this.state.selectedCommandIndex = 0;
    this.resizePromptInput();
  }

  protected addFiles(fileList?: FileList | null): void {
    this.attachmentQueue.addFiles(
      fileList, this.state.attachments, this.attachmentQueueCallbacks()
    );
  }

  protected addFile(file: File): void {
    this.attachmentQueue.addFile(
      file, this.state.attachments, this.attachmentQueueCallbacks()
    );
  }

  protected attachmentQueueCallbacks(): AiChatAttachmentQueueCallbacks {
    return {
      active: () => !this.destroyed,
      added: () => {
        this.invalidateDraftAttachmentRestore();
        this.flushWorkspacePersistence();
        this.focusPrompt();
      },
      rejected: message => this.snackBar.open(message, 'Dismiss', {duration: 3500})
    };
  }

  applyCommandSuggestion(command: AiChatCommand): void {
    this.state.prompt = command.name;
    this.resizePromptInput();
    this.focusPrompt();
  }

  changeModelDraft(modelName: string): void {
    this.modelSettings.changeDraft(this.state, modelName);
  }

  applyModelSettings(): void {
    this.modelSettings.apply(this.state, this.destroyed$, {
      completed: () => {
        this.scrollToBottom(true);
        this.focusPrompt();
      },
      compacted: () => this.snackBar.open(
        'The conversation context was compacted for the selected model.',
        'Dismiss', {duration: 3500}
      ),
      failed: () => this.snackBar.open(
        'Could not change the assistant model.', 'Dismiss', {duration: 3500}
      )
    });
  }

  closeModelSettings(): void {
    if (this.modelSettings.close(this.state)) {
      this.focusPrompt();
    }
  }

  startNewChat(event?: Event, activePanelTab: AiChatPanelTab = 'chat'): void {
    event?.stopPropagation();
    if (this.changeDecisionOpen || this.changeDecisionInFlight) {
      this.snackBar.open(
        'Finish the change approval decision before starting another chat.',
        'Dismiss', {duration: 3500}
      );
      return;
    }
    if (this.state.reconciliationRequired) {
      this.snackBar.open(
        'Resolve the request outcome before starting another chat.',
        'Dismiss', {duration: 3500}
      );
      return;
    }
    if (this.state.pending) {
      this.deferredNewChatTab = activePanelTab;
      this.cancelActiveRequest();
      if (this.state.pending) {
        return;
      }
    }
    this.deferredNewChatTab = undefined;
    this.clearCompletedPayloadRecovery();
    this.invalidateAttachmentReads();
    this.invalidateDraftAttachmentRestore();
    this.clearChangeRepeatDraft();
    this.cancellationService.reset();
    this.clearTimers();
    this.cancelConversationRestore();
    this.requestSubscription?.unsubscribe();
    this.transportService.cancelReconnect();
    this.clearStatusMessage();
    this.permissionSettings.reset();
    this.modelSettings.reset();
    this.state.resetForNewChat();
    this.sessionPersistence.clearLastConversation();
    this.sessionPersistence.restoreSelection(this.state);
    this.resizePromptInput();
    this.activeRequestId = undefined;
    this.assistantMessageIndexesByRequestId.clear();
    this.clearToolCallTracking();
    this.state.activePanelTab = activePanelTab;
    this.state.chatScrollTop = 0;
    this.state.historyScrollTop = 0;
    this.flushWorkspacePersistence();
    if (activePanelTab === 'chat') {
      this.scrollToBottom(true);
      this.focusPrompt();
    }
  }

  startResize(event: MouseEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.resizeState = {
      startX: event.clientX,
      startY: event.clientY,
      startSideSize: this.state.sideSize,
      startHorizontalSize: this.state.horizontalSize
    };
  }

  onResizeMove(event: MouseEvent): void {
    if (!this.resizeState) {
      return;
    }
    event.preventDefault();
    const dx = event.clientX - this.resizeState.startX;
    const dy = event.clientY - this.resizeState.startY;

    if (this.state.dock === 'right') {
      this.state.sideSize = this.layoutService.clamp(this.resizeState.startSideSize - dx, 320, window.innerWidth - 96);
    } else if (this.state.dock === 'left') {
      this.state.sideSize = this.layoutService.clamp(this.resizeState.startSideSize + dx, 320, window.innerWidth - 96);
    } else if (this.state.dock === 'bottom') {
      this.state.horizontalSize = this.layoutService.clamp(this.resizeState.startHorizontalSize - dy, 240, window.innerHeight - 96);
    } else {
      this.state.horizontalSize = this.layoutService.clamp(this.resizeState.startHorizontalSize + dy, 240, window.innerHeight - 96);
    }
    this.updateMainPanelInset();
  }

  stopResize(): void {
    this.resizeState = undefined;
  }

  get panelStyle(): {[key: string]: string} {
    if (this.popoutMode) {
      return {top: '0', right: '0', bottom: '0', left: '0', width: 'auto', height: 'auto'};
    }
    return this.layoutService.panelStyle(this.state.dock, this.state.sideSize, this.state.horizontalSize);
  }

  private markPopoutReady(): void {
    if (this.popoutMode || !this.sessionPersistence.restorePopoutActive()) return;
    this.state.popoutActive = true;
    this.state.isOpen = true;
    this.sessionPersistence.persistPanelVisibility(true);
    this.sessionPersistence.persistPopoutActive(true);
    this.updateMainPanelInset();
  }

  private restoreDockedWindow(): void {
    if (this.popoutMode || !this.state.popoutActive) return;
    if (this.reattachFallbackTimeout !== undefined) {
      window.clearTimeout(this.reattachFallbackTimeout);
      this.reattachFallbackTimeout = undefined;
    }
    this.state.popoutActive = false;
    this.sessionPersistence.persistPopoutActive(false);
    this.sessionPersistence.restoreWorkspace(this.state);
    this.invalidateDraftAttachmentRestore();
    this.state.attachments = [];
    this.restorePersistedDraftAttachments();
    this.state.messages = [];
    this.state.conversationId = undefined;
    this.state.pending = false;
    this.state.activeRequest = undefined;
    this.loadConversationHistory();
    this.recoverActiveRequest(() => this.restoreLastConversation());
    this.updateMainPanelInset();
    if (this.state.activePanelTab === 'chat') this.focusPrompt();
  }

  private closeDetachedAssistant(): void {
    if (this.popoutMode) return;
    this.state.popoutActive = false;
    this.state.isOpen = false;
    this.sessionPersistence.persistPopoutActive(false);
    this.sessionPersistence.persistPanelVisibility(false);
    this.updateMainPanelInset();
  }

}
