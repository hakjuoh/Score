import {Directive} from '@angular/core';
import {take, takeUntil} from 'rxjs/operators';
import {AiChatPanelRequestController} from './ai-chat-panel-request.controller';
import {AiChatAttachmentQueueCallbacks} from './domain/ai-chat-attachment-queue.service';
import {contextUsageValue} from './domain/ai-chat-event-semantics';
import {
  AiChatCommand,
  AiChatDock,
  AiChatPanelTab
} from './domain/ai-chat-panel.model';

@Directive()
export abstract class AiChatPanelUiController extends AiChatPanelRequestController {
  ngOnInit(): void {
    this.refreshBranding();
    this.loadAvailableModels();
    this.updateMainPanelInset();
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.destroyed$.next();
    this.destroyed$.complete();
    this.clearMutationRepeatDraft();
    this.mutationInteractions.destroy(this.state);
    this.state.elicitation = undefined;
    this.state.elicitationBusy = false;
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
    this.loadConversationHistory();
    this.recoverActiveRequest(() => this.restoreLastConversation());
    this.updateMainPanelInset();
    this.scrollToBottom(true);
    this.focusPrompt();
  }

  close(event?: MouseEvent): void {
    event?.stopPropagation();
    this.state.isOpen = false;
    this.state.showScrollToBottomButton = false;
    this.updateMainPanelInset();
  }

  setDock(dock: AiChatDock): void {
    this.state.dock = dock;
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
    this.scrollToBottom(true);
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
      if (commandDecision.kind !== 'local' && this.canRequestMutationChange()) {
        this.sendMutationChangeRequest(prompt, attachments);
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

    if (this.canRequestMutationChange()) {
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
      this.state.attachments.splice(index, 1);
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
      added: () => this.focusPrompt(),
      rejected: message => this.snackBar.open(message, 'Dismiss', {duration: 3500})
    };
  }

  applyCommandSuggestion(command: AiChatCommand): void {
    this.state.prompt = command.name;
    this.resizePromptInput();
    this.focusPrompt();
  }

  changeModelDraft(modelName: string): void {
    this.settingsService.changeModelDraft(this.state, modelName);
  }

  applyModelSettings(): void {
    const model = this.state.availableModels.find(candidate => candidate.name === this.state.modelDraftName);
    const reasoningEffort = this.state.modelDraftReasoningEffort;
    const runtime = model && this.settingsService.modelRuntimes(model)
      .some(candidate => candidate.name === this.state.selectedRuntime)
      ? this.state.selectedRuntime : model?.defaultRuntime || 'default';
    const runtimeOptions = this.state.runtimeOptionsFor(runtime,
      runtime === this.state.selectedRuntime ? this.state.selectedRuntimeOptions : {}, model);
    if (!this.state.modelSettingsOpen || !model
      || !model.reasoningEfforts.some(effort => effort.name === reasoningEffort)
      || this.state.modelChangePending) {
      return;
    }
    const previousModelName = this.state.selectedModelName;
    const previousReasoningEffort = this.state.selectedReasoningEffort;
    const previousRuntime = this.state.selectedRuntime;
    const previousRuntimeOptions = {...this.state.selectedRuntimeOptions};
    if (!this.state.conversationId) {
      this.state.selectedModelName = model.name;
      this.state.selectedReasoningEffort = reasoningEffort;
      this.state.selectRuntime(runtime, runtimeOptions);
      this.state.resetContextUsageForSelectedModel();
      this.finishModelSettings(model.displayName, reasoningEffort);
      return;
    }
    this.state.modelChangePending = true;
    this.api.updateConversationModel(
      this.state.conversationId, model.name, reasoningEffort, runtime, runtimeOptions
    ).pipe(
      take(1), takeUntil(this.destroyed$)
    ).subscribe({
      next: response => {
        this.state.selectedModelName = response.modelName;
        this.state.selectedReasoningEffort = response.reasoningEffort;
        this.state.selectRuntime(response.runtime || runtime, response.runtimeOptions || runtimeOptions);
        this.state.resetContextUsageForSelectedModel();
        this.state.setContextUsage(contextUsageValue(response.contextUsage, response.modelName));
        this.state.modelChangePending = false;
        this.finishModelSettings(model.displayName, response.reasoningEffort);
        if (response.contextCompacted) {
          this.snackBar.open('The conversation context was compacted for the selected model.',
            'Dismiss', {duration: 3500});
        }
      },
      error: () => {
        this.state.selectedModelName = previousModelName;
        this.state.selectedReasoningEffort = previousReasoningEffort;
        this.state.selectRuntime(previousRuntime, previousRuntimeOptions);
        this.state.modelChangePending = false;
        this.snackBar.open('Could not change the assistant model.', 'Dismiss', {duration: 3500});
      }
    });
  }

  closeModelSettings(): void {
    if (this.settingsService.closeModel(this.state)) {
      this.focusPrompt();
    }
  }

  changeRuntimeDraft(runtime: string): void {
    this.settingsService.changeRuntimeDraft(this.state, runtime);
  }

  applyRuntimeSettings(): void {
    const model = this.state.selectedModel() || this.state.defaultModel();
    const runtime = this.state.runtimeDraft;
    const runtimeInfo = model ? this.settingsService.modelRuntimes(model)
      .find(candidate => candidate.name === runtime) : undefined;
    if (!this.state.runtimeSettingsOpen || !model || !runtimeInfo || this.state.modelChangePending) {
      return;
    }
    const previousRuntime = this.state.selectedRuntime;
    const previousRuntimeOptions = {...this.state.selectedRuntimeOptions};
    const runtimeOptions = this.state.runtimeOptionsFor(runtime, this.state.runtimeDraftOptions);
    if (!this.state.conversationId) {
      this.state.selectRuntime(runtime, runtimeOptions);
      this.finishRuntimeSettings(runtimeInfo.displayName);
      return;
    }
    this.state.modelChangePending = true;
    this.api.updateConversationModel(this.state.conversationId, model.name,
      this.state.selectedReasoningEffort, runtime, runtimeOptions).pipe(
      take(1), takeUntil(this.destroyed$)
    ).subscribe({
      next: response => {
        this.state.selectedModelName = response.modelName;
        this.state.selectedReasoningEffort = response.reasoningEffort;
        this.state.selectRuntime(response.runtime || runtime, response.runtimeOptions || runtimeOptions);
        this.state.setContextUsage(contextUsageValue(response.contextUsage, response.modelName));
        this.state.modelChangePending = false;
        this.finishRuntimeSettings(this.settingsService.modelRuntimes(model)
          .find(candidate => candidate.name === (response.runtime || runtime))?.displayName
          || response.runtime || runtime);
      },
      error: () => {
        this.state.selectRuntime(previousRuntime, previousRuntimeOptions);
        this.state.modelChangePending = false;
        this.snackBar.open('Could not change the assistant runtime.', 'Dismiss', {duration: 3500});
      }
    });
  }

  closeRuntimeSettings(): void {
    if (this.settingsService.closeRuntime(this.state)) {
      this.focusPrompt();
    }
  }

  startNewChat(event?: Event, activePanelTab: AiChatPanelTab = 'chat'): void {
    event?.stopPropagation();
    if (this.mutationDecisionOpen || this.mutationDecisionInFlight) {
      this.snackBar.open(
        'Finish the action approval decision before starting another chat.',
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
    this.clearMutationRepeatDraft();
    this.cancellationService.reset();
    this.clearTimers();
    this.cancelConversationRestore();
    this.requestSubscription?.unsubscribe();
    this.transportService.cancelReconnect();
    this.clearStatusMessage();
    this.settingsService.reset();
    this.state.resetForNewChat();
    this.sessionPersistence.clearLastConversation();
    this.sessionPersistence.restoreSelection(this.state);
    this.resizePromptInput();
    this.routeRegistrySent = false;
    this.lastPageContextPath = undefined;
    this.pendingContextUpdate = undefined;
    this.activeRequestId = undefined;
    this.assistantMessageIndexesByRequestId.clear();
    this.clearToolCallTracking();
    this.state.activePanelTab = activePanelTab;
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
    return this.layoutService.panelStyle(this.state.dock, this.state.sideSize, this.state.horizontalSize);
  }

}
