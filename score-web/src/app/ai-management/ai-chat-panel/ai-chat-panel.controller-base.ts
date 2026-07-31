import {DestroyRef, Directive, ElementRef, inject} from '@angular/core';
import {ConnectedPosition} from '@angular/cdk/overlay';
import {DomSanitizer} from '@angular/platform-browser';
import {MatDialog} from '@angular/material/dialog';
import {MatSnackBar} from '@angular/material/snack-bar';
import {Observable, Subject, Subscription, of} from 'rxjs';
import {catchError, finalize, map, shareReplay, take, takeUntil} from 'rxjs/operators';
import {AuthService} from '../../authentication/auth.service';
import {WebPageInfoService} from '../../basis/basis.service';
import {ConfirmDialogService} from '../../common/confirm-dialog/confirm-dialog.service';
import {AiChatComposerComponent} from './ai-chat-composer.component';
import {AiContextBudgetData} from './ai-context-budget-chart.model';
import {AiChatApiService} from './domain/ai-chat-api.service';
import {AiActiveRequestRecoveryService} from './domain/ai-active-request-recovery.service';
import {
  AiChatAttachmentQueueCallbacks,
  AiChatAttachmentQueueService
} from './domain/ai-chat-attachment-queue.service';
import {AiChatAttachmentService} from './domain/ai-chat-attachment.service';
import {
  AiChatCancellationCallbacks,
  AiChatCancellationService
} from './domain/ai-chat-cancellation.service';
import {AiChatCommandService, AiLocalCommand} from './domain/ai-chat-command.service';
import {AiChatContextService} from './domain/ai-chat-context.service';
import {AiConfirmedChangeRequestCoordinator} from './domain/ai-confirmed-change-request-coordinator';
import {
  AiConversationRestoreCallbacks,
  AiConversationRestoreService
} from './domain/ai-conversation-restore.service';
import {AiConversationProjector} from './domain/ai-conversation-projector';
import {AiChatNavigationService} from './domain/ai-chat-navigation.service';
import {AiChatPanelLayoutService} from './domain/ai-chat-panel-layout.service';
import {AiChatMessageTrackerService} from './domain/ai-chat-message-tracker.service';
import {AiChatPanelState} from './domain/ai-chat-panel-state';
import {AiChatPanelViewportService} from './domain/ai-chat-panel-viewport.service';
import {AiChatSessionPersistenceService} from './domain/ai-chat-session-persistence.service';
import {AiChatSettingsService} from './domain/ai-chat-settings.service';
import {AiChatTransportService} from './domain/ai-chat-transport.service';
import {AiChatWindowCoordinatorService} from './domain/ai-chat-window-coordinator.service';
import {
  AiAgentActivity,
  isTerminalAgentStatus,
  settleAgentConversation
} from './domain/ai-agent-activity';
import {
  AiChangeInteractionCallbacks,
  AiChangeInteractionService,
  ChangeRepeatDraft,
  ChangeRepeatOpportunity
} from './domain/ai-change-interaction.service';
import {AiTerminalRequestErrorStatus} from './domain/ai-chat-event-semantics';
import {
  AiActiveRequestIdentity,
  AiAgentExecutionStatus,
  AiCancellationResponse,
  AiChatAttachment,
  AiChatCommand,
  AiChatContextUpdate,
  AiChatConversationDetails,
  AiChatDock,
  AiChatMessage,
  AiMcpStatus,
  AiChatPanelTab,
  AiChatSocketEvent,
  AiElicitationResponse,
  AiExecutionStatus,
  AiChangeConfirmationAuthorization,
  AiChangeConfirmationNotice,
  AiChangeInteraction,
  AiPublicExecutionRequestStatus,
  ResizeState,
  checkingAiMcpStatus,
  failedAiMcpStatusCheck,
  readyAiMcpStatus
} from './domain/ai-chat-panel.model';

export interface BoundChangeConfirmationNotice {
  conversationId: string;
  notice: AiChangeConfirmationNotice;
}

@Directive()
export abstract class AiChatPanelControllerBase {

  protected api = inject(AiChatApiService);
  protected activeRequestRecovery = inject(AiActiveRequestRecoveryService);
  protected attachmentQueue = inject(AiChatAttachmentQueueService);
  protected attachmentService = inject(AiChatAttachmentService);
  protected cancellationService = inject(AiChatCancellationService);
  protected commandService = inject(AiChatCommandService);
  protected confirmedChangeRequests = inject(AiConfirmedChangeRequestCoordinator);
  protected readonly destroyRef = inject(DestroyRef);
  protected contextService = inject(AiChatContextService);
  protected conversationRestoreService = inject(AiConversationRestoreService);
  protected conversationProjector = inject(AiConversationProjector);
  protected navigationService = inject(AiChatNavigationService);
  protected layoutService = inject(AiChatPanelLayoutService);
  protected messageTracker = inject(AiChatMessageTrackerService);
  protected changeInteractions = inject(AiChangeInteractionService);
  protected viewport = inject(AiChatPanelViewportService);
  protected sessionPersistence = inject(AiChatSessionPersistenceService);
  protected settingsService = inject(AiChatSettingsService);
  protected transportService = inject(AiChatTransportService);
  protected windowCoordinator = inject(AiChatWindowCoordinatorService);
  protected sanitizer = inject(DomSanitizer);
  protected webPageInfo = inject(WebPageInfoService);
  protected auth = inject(AuthService);
  protected dialog = inject(MatDialog);
  protected confirmDialogService = inject(ConfirmDialogService);
  protected snackBar = inject(MatSnackBar);
  protected requestSubscription?: Subscription;
  protected conversationHistorySubscription?: Subscription;
  protected lastConversationRestoreSubscription?: Subscription;
  protected completedPayloadSubscription?: Subscription;
  protected completedPayloadTimeout?: number;
  protected completedPayloadRequestId?: string;
  protected activeRecoverySubscription?: Subscription;
  protected activeRecoveryTimeout?: number;
  protected requestStatusWatchdogTimeout?: number;
  protected requestStatusWatchdogSubscription?: Subscription;
  protected deferredNewChatTab?: AiChatPanelTab;
  protected responseTimeout?: number;
  protected acknowledgementTimeout?: number;
  protected changeApprovalExpiryTimeout?: number;
  protected changeApprovalAcknowledgementTimeout?: number;
  protected resizeState?: ResizeState;
  protected activeRequestPublished = false;
  protected activeRestoreRequestId?: string;
  protected restoreAttemptSequence = 0;
  protected assistantMessageIndexesByRequestId = new Map<string, number>();
  protected changeRepeatDraft?: ChangeRepeatDraft;
  protected pendingChangeConfirmation?: BoundChangeConfirmationNotice;
  protected rejectedChangeConfirmationRequestId?: string;
  protected destroyed = false;
  protected readonly destroyed$ = new Subject<void>();
  private mcpStatusCheck?: Observable<AiMcpStatus>;
  private workspacePersistenceReady = false;
  private workspacePersistenceSignature = '';
  private attachmentPersistenceSignature = '';
  private attachmentRestoreGeneration = 0;
  protected restoreChatScrollPending = false;

  abstract composer?: AiChatComposerComponent;
  abstract chatTerminalPane?: ElementRef<HTMLDivElement>;
  state = new AiChatPanelState();
  readonly navigationId = `score-ai-chat-${nextAiChatPanelInstanceSequence()}`;
  readonly contextBudgetHoverPositions: ConnectedPosition[] = [
    {originX: 'start', originY: 'top', overlayX: 'start', overlayY: 'bottom', offsetY: -6},
    {originX: 'end', originY: 'top', overlayX: 'end', overlayY: 'bottom', offsetY: -6},
    {originX: 'start', originY: 'bottom', overlayX: 'start', overlayY: 'top', offsetY: 6}
  ];

  get contextBudgetHoverOpen(): boolean {
    return this.viewport.contextBudgetHoverOpen;
  }

  set contextBudgetHoverOpen(open: boolean) {
    this.viewport.contextBudgetHoverOpen = open;
  }

  protected get activeRequestId(): string | undefined {
    return this.state.activeRequest?.requestId;
  }

  protected set activeRequestId(requestId: string | undefined) {
    if (!requestId) {
      this.state.activeRequest = undefined;
      return;
    }
    if (this.state.activeRequest?.requestId !== requestId) {
      this.state.activeRequest = {requestId};
    }
  }

  protected refreshMcpStatus(onComplete?: (status: AiMcpStatus) => void): void {
    let check = this.mcpStatusCheck;
    if (!check) {
      this.state.mcpStatus = checkingAiMcpStatus();
      const created: Observable<AiMcpStatus> = this.api.getMcpStatus().pipe(
        map(readyAiMcpStatus),
        catchError(() => of(failedAiMcpStatusCheck())),
        take(1),
        finalize(() => {
          if (this.mcpStatusCheck === created) this.mcpStatusCheck = undefined;
        }),
        shareReplay({bufferSize: 1, refCount: true})
      );
      this.mcpStatusCheck = created;
      check = created;
    }
    check.pipe(takeUntil(this.destroyed$)).subscribe(status => {
      this.state.mcpStatus = status;
      onComplete?.(status);
    });
  }

  protected get changeDecisionOpen(): boolean {
    return this.changeInteractions.decisionOpen;
  }

  protected get changeDecisionInFlight(): boolean {
    return this.changeInteractions.decisionInFlight;
  }

  protected get changeInteractionMode(): AiChangeInteraction['mode'] {
    return this.changeInteractions.interactionMode;
  }

  get commandSuggestions(): AiChatCommand[] {
    return this.commandService.suggestions(
      this.state.prompt, this.state.pending && !!this.activeRequestId);
  }

  get showCommandSuggestions(): boolean {
    return !this.commandInputBlocked && this.commandSuggestions.length > 0;
  }

  get commandInputBlocked(): boolean {
    return this.state.reconciliationRequired
      || this.state.modelChangePending || this.state.modelSettingsOpen
      || this.state.permissionSettingsOpen
      || !!this.state.elicitation
      || !!this.state.changeApprovalBatch
      || this.changeDecisionInFlight
      || (this.changeDecisionOpen && this.changeInteractionMode !== 'confirm');
  }

  get composerPlaceholder(): string {
    return this.changeDecisionOpen && this.changeInteractionMode === 'confirm'
      ? 'Describe how to revise this change' : 'Ask a question';
  }

  get changeInteraction(): AiChangeInteraction | undefined {
    return this.changeInteractions.interaction;
  }

  get interactionBlocked(): boolean {
    return this.state.pending || this.commandInputBlocked;
  }

  get changeApprovalControlsBusy(): boolean {
    return this.state.changeApprovalBatchBusy
      || this.state.cancellation.phase !== 'idle';
  }

  protected clearChangeApprovalBatch(): void {
    if (this.changeApprovalExpiryTimeout !== undefined) {
      window.clearTimeout(this.changeApprovalExpiryTimeout);
      this.changeApprovalExpiryTimeout = undefined;
    }
    if (this.changeApprovalAcknowledgementTimeout !== undefined) {
      window.clearTimeout(this.changeApprovalAcknowledgementTimeout);
      this.changeApprovalAcknowledgementTimeout = undefined;
    }
    this.state.changeApprovalBatch = undefined;
    this.state.changeApprovalBatchQueue = [];
    this.state.changeApprovalBatchBusy = false;
  }

  protected settleAgentActivity(status: Extract<AiAgentExecutionStatus,
    'completed' | 'failed' | 'cancelled'>): void {
    const now = Date.now();
    const visited = new Set<AiAgentActivity>();
    const settleMessages = (messages: AiChatMessage[]): void => {
      for (let index = messages.length - 1; index >= 0; index--) {
        const message = messages[index];
        if (message.role === 'progress'
          && (message.eventType === 'agent_status'
            || message.eventType === 'composite_status')) {
          messages.splice(index, 1);
          continue;
        }
        if ((message.role === 'workflow_group' || message.role === 'agent_group')
          && message.workflowStatus === 'started') {
          message.workflowStatus = status;
        }
        settleActivities(message.activities || []);
        settleMessages(message.children || []);
      }
    };
    const settleActivities = (activities: AiAgentActivity[]): void => {
      for (const activity of activities) {
        if (visited.has(activity)) continue;
        visited.add(activity);
        if (!isTerminalAgentStatus(activity.status)) {
          const name = activity.agentName || 'Agent';
          const content = status === 'completed'
            ? `${name} finished.`
            : status === 'cancelled'
              ? `${name} stopped when the request was cancelled.`
              : `${name} stopped before completing.`;
          activity.status = status;
          activity.content = content;
          activity.inProgress = false;
          activity.lastUpdateAt = now;
          activity.events.push({status, content});
          settleAgentConversation(activity, status, content);
        }
        settleMessages(activity.messages || []);
      }
    };
    settleActivities(this.state.agentActivities);
    settleMessages(this.state.messages);
  }

  get cancellationInProgress(): boolean {
    return this.state.cancellation.phase !== 'idle' || !!this.completedPayloadRequestId;
  }

  get trajectoryUrl(): string | undefined {
    return this.state.conversationId
      ? `/api/ai/chat/conversations/${encodeURIComponent(this.state.conversationId)}/trajectory`
      : undefined;
  }

  get cancellationDelayed(): boolean {
    return this.state.cancellation.phase === 'delayed';
  }

  get popoutMode(): boolean {
    return this.windowCoordinator.popoutMode;
  }

  protected initializeWorkspacePersistence(workspaceRestored: boolean): void {
    this.restoreChatScrollPending = workspaceRestored;
    this.workspacePersistenceSignature = this.currentWorkspacePersistenceSignature();
    this.attachmentPersistenceSignature = this.currentAttachmentPersistenceSignature();
    this.workspacePersistenceReady = true;
    this.restorePersistedDraftAttachments();
  }

  protected restorePersistedDraftAttachments(): void {
    const generation = ++this.attachmentRestoreGeneration;
    void this.sessionPersistence.restoreDraftAttachments().then(attachments => {
      if (this.destroyed || generation !== this.attachmentRestoreGeneration
        || this.state.attachments.length > 0) {
        return;
      }
      this.state.attachments = attachments;
    });
  }

  protected persistWorkspaceIfChanged(): void {
    if (!this.workspacePersistenceReady || this.destroyed
      || this.state.popoutActive && !this.popoutMode) return;
    const workspaceSignature = this.currentWorkspacePersistenceSignature();
    if (workspaceSignature !== this.workspacePersistenceSignature) {
      this.workspacePersistenceSignature = workspaceSignature;
      this.sessionPersistence.persistWorkspace(this.state);
    }
    const attachmentSignature = this.currentAttachmentPersistenceSignature();
    if (attachmentSignature !== this.attachmentPersistenceSignature) {
      this.attachmentPersistenceSignature = attachmentSignature;
      this.attachmentRestoreGeneration += 1;
      void this.sessionPersistence.persistDraftAttachments(this.state.attachments);
    }
  }

  protected flushWorkspacePersistence(): void {
    this.sessionPersistence.persistWorkspace(this.state);
    this.workspacePersistenceSignature = this.currentWorkspacePersistenceSignature();
    this.attachmentPersistenceSignature = this.currentAttachmentPersistenceSignature();
    this.attachmentRestoreGeneration += 1;
    void this.sessionPersistence.persistDraftAttachments(this.state.attachments);
  }

  protected invalidateDraftAttachmentRestore(): void {
    this.attachmentRestoreGeneration += 1;
  }

  private currentWorkspacePersistenceSignature(): string {
    return JSON.stringify([
      this.state.activePanelTab,
      this.state.sideSize,
      this.state.horizontalSize,
      this.state.prompt,
      this.state.chatScrollTop,
      this.state.historyScrollTop
    ]);
  }

  private currentAttachmentPersistenceSignature(): string {
    return JSON.stringify(this.state.attachments.map(attachment => [
      attachment.name, attachment.mediaType, attachment.size, attachment.data.length
    ]));
  }

  abstract get panelStyle(): {[key: string]: string};

  abstract ngOnInit(): void;
  abstract ngOnDestroy(): void;
  abstract open(event?: MouseEvent): void;
  abstract close(event?: MouseEvent): void;
  abstract openPopout(event?: Event): void;
  abstract focusPopout(event?: Event): void;
  abstract reattachPopout(event?: Event): void;
  abstract setDock(dock: AiChatDock): void;
  abstract setPanelTab(tab: AiChatPanelTab, event?: Event): void;
  abstract send(): void;
  protected abstract startChatRequest(prompt: string, attachments: AiChatAttachment[]): void;
  protected abstract sendHttpChat(prompt: string, attachments: AiChatAttachment[],
                                  changeConfirmation?: AiChangeConfirmationAuthorization): void;
  protected abstract attachmentFailureMessage(error: unknown): string;
  protected abstract completeUnknownConfirmedRequest(requestId: string): void;
  protected abstract connectAndPublishWhenReady(requestId: string, prompt: string,
                                                attachments: AiChatAttachment[],
                                                changeConfirmation?: AiChangeConfirmationAuthorization): void;
  protected abstract failStompReconnect(): void;
  protected abstract publishChatRequest(requestId: string, prompt: string,
                                        attachments: AiChatAttachment[],
                                        changeConfirmation?: AiChangeConfirmationAuthorization): void;
  protected abstract failChatPublish(): void;
  abstract openFilePicker(input: HTMLInputElement): void;
  abstract onFileInputChange(event: Event): void;
  abstract onDragOver(event: DragEvent): void;
  abstract onDragLeave(event: DragEvent): void;
  abstract onDrop(event: DragEvent): void;
  abstract removeAttachment(index: number): void;
  abstract onComposerKeydown(event: KeyboardEvent): void;
  abstract onPromptChange(): void;
  protected abstract addFiles(fileList?: FileList | null): void;
  protected abstract addFile(file: File): void;
  protected abstract attachmentQueueCallbacks(): AiChatAttachmentQueueCallbacks;
  abstract applyCommandSuggestion(command: AiChatCommand): void;
  abstract changeModelDraft(modelName: string): void;
  abstract applyModelSettings(): void;
  abstract closeModelSettings(): void;
  abstract startNewChat(event?: Event, activePanelTab?: AiChatPanelTab): void;
  abstract startResize(event: MouseEvent): void;
  abstract onResizeMove(event: MouseEvent): void;
  abstract stopResize(): void;
  protected abstract handleSocketEvent(event: AiChatSocketEvent): void;
  protected abstract handleElicitationRequired(event: AiChatSocketEvent): void;
  protected abstract handleElicitationDecisionEvent(event: AiChatSocketEvent): void;
  abstract respondToElicitation(response: AiElicitationResponse): void;
  protected abstract completeFinalEvent(event: AiChatSocketEvent): void;
  protected abstract commitAssistantMessage(requestId: string, content: string,
                                            files?: import('./domain/ai-chat-panel.model').AiChatFile[]): number;
  protected abstract beginChangeRepeatDraft(requestId: string, prompt: string,
                                              attachments: AiChatAttachment[]): void;
  protected abstract handleChangeConfirmationNotice(event: AiChatSocketEvent): void;
  protected abstract completeConflictingChangeConfirmationFinal(
    requestId: string, confirmationConversationId: string
  ): void;
  protected abstract finishChangeRepeatOpportunity(
    requestId: string, terminalConversationId: string | undefined
  ): void;
  protected abstract showChangeRepeatInteraction(opportunity: ChangeRepeatOpportunity): void;
  abstract approveChangeInteraction(): void;
  abstract denyChangeInteraction(): void;
  abstract requestChangeRevision(): void;
  abstract revokeChangeInteraction(): void;
  abstract dismissChangeInteraction(): void;
  protected abstract canRequestChangeRevision(): boolean;
  protected abstract sendChangeRevisionRequest(prompt: string, attachments: AiChatAttachment[]): void;
  protected abstract showLostGrantInteraction(opportunity: ChangeRepeatOpportunity): void;
  protected abstract changeInteractionCallbacks(): AiChangeInteractionCallbacks;
  protected abstract sendConfirmedChangeRepeat(
    opportunity: ChangeRepeatOpportunity, confirmationGrant: string,
    revision?: {prompt: string; attachments: AiChatAttachment[]}
  ): boolean;
  protected abstract clearChangeRepeatDraft(requestId?: string): void;
  protected abstract handleSystemEvent(event: AiChatSocketEvent): void;
  protected abstract handleProviderRetryEvent(event: AiChatSocketEvent): boolean;
  protected abstract clearProviderRecoveryState(): void;
  protected abstract completeCancelledRequest(content?: string): void;
  protected abstract completeAuthenticationFailure(content?: string): void;
  protected abstract completeFailedRequest(content?: string,
                                           status?: AiTerminalRequestErrorStatus): void;
  protected abstract upsertToolGroup(event: AiChatSocketEvent): void;
  protected abstract handleToolCallEvent(event: AiChatSocketEvent): void;
  protected abstract divertSpecialistToolEvent(event: AiChatSocketEvent): boolean;
  protected abstract handleLegacyRecoverableToolError(event: AiChatSocketEvent, content: string): boolean;
  protected abstract isToolDiscoveryName(value: unknown): boolean;
  protected abstract isRecognizedRequestEvent(event: AiChatSocketEvent): boolean;
  protected abstract hasActiveStructuredToolRows(): boolean;
  protected abstract handleConversationRestoreEvent(event: AiChatSocketEvent): void;
  protected abstract conversationRestoreCallbacks(): AiConversationRestoreCallbacks;
  abstract requestManualCompact(): void;
  abstract openContextBudgetDialog(): void;
  abstract contextBudgetData(): AiContextBudgetData | undefined;
  abstract showContextBudgetHover(): void;
  abstract keepContextBudgetHoverOpen(): void;
  abstract closeContextBudgetHover(immediately?: boolean): void;
  protected abstract applyContextEvent(event: AiChatSocketEvent): void;
  protected abstract loadAvailableModels(): void;
  protected abstract applyFormattedResponse(event: AiChatSocketEvent): void;
  protected abstract normalizedDisplayText(value?: string): string;
  abstract scrollChatToBottom(event?: MouseEvent): void;
  abstract onChatPaneScroll(): void;
  abstract onHistoryScrollTopChange(scrollTop: number): void;
  protected abstract scrollToBottom(force?: boolean): void;
  protected abstract restoreChatScrollPosition(consumePending?: boolean): void;
  protected abstract updateScrollToBottomButton(): void;
  abstract focusPrompt(): void;
  abstract focusPromptIfNoSelection(): void;
  abstract onPanelClick(event: MouseEvent): void;
  protected abstract resizePromptInput(): void;
  abstract onMessageListClick(event: MouseEvent): void;
  protected abstract handleLocalCommand(localCommand: AiLocalCommand, commandText: string): void;
  protected abstract openModelSettings(commandText: string): void;
  protected abstract finishModelSettings(displayName: string, reasoningEffort: string): void;
  protected abstract openPermissionSettings(commandText: string): void;
  abstract applyPermissionSettings(): void;
  abstract closePermissionSettings(): void;
  abstract cancelActiveRequest(): void;
  abstract retryCancellation(): void;
  abstract forceSafeStop(): void;
  abstract loadConversation(conversationId: string): void;
  protected abstract publishConversationRestoreWhenReady(requestId: string, conversationId: string): void;
  protected abstract publishConversationRestoreAttempt(requestId: string, conversationId: string): void;
  protected abstract failConversationRestoreReconnect(requestId: string): void;
  protected abstract finishConversationRestore(): void;
  protected abstract cancelConversationRestore(): void;
  protected abstract scrollChatPaneToTop(): void;
  abstract loadConversationHistory(): void;
  protected abstract recoverActiveRequest(onIdle?: () => void): void;
  protected abstract restoreLastConversation(): void;
  protected abstract pollRecoveredRequest(): void;
  protected abstract loadTerminalRecoveredConversation(status: AiPublicExecutionRequestStatus): void;
  protected abstract applyRecoveredConversation(details: AiChatConversationDetails,
                                                 status: AiPublicExecutionRequestStatus): void;
  protected abstract finishRecoveredRequest(status: AiPublicExecutionRequestStatus): void;
  protected abstract scheduleRecoveredRequestPoll(delay: number): void;
  protected abstract clearActiveRecovery(): void;
  protected abstract acknowledgeRecoveredRequestLiveEvent(requestId: string): void;
  protected abstract isTerminalExecutionStatus(status: AiExecutionStatus): boolean;
  abstract deleteConversation(conversationId: string, event?: Event): void;
  protected abstract deleteConversationNow(conversationId: string): void;
  protected abstract showStatus(content: string, inProgress?: boolean,
                                options?: import('./domain/ai-chat-panel.model').AiChatStatusOptions): void;
  protected abstract completeProgressMessages(): void;
  protected abstract completeToolGroupMessages(): void;
  protected abstract clearToolCallTracking(): void;
  protected abstract clearStatusMessage(eventType?: string): void;
  protected abstract refreshBranding(): void;
  protected abstract clearTimers(): void;
  protected abstract cancellationCallbacks(): AiChatCancellationCallbacks;
  protected abstract updateActiveIdentity(identity: AiActiveRequestIdentity): void;
  protected abstract captureAcceptedIdentity(event: AiChatSocketEvent): void;
  protected abstract scheduleRequestStatusWatchdog(): void;
  protected abstract clearRequestStatusWatchdog(): void;
  protected abstract acceptedIdentity(event: AiChatSocketEvent): AiActiveRequestIdentity | undefined;
  protected abstract captureTerminalIdentity(event: AiChatSocketEvent): boolean;
  protected abstract matchesTerminalIdentity(event: AiChatSocketEvent): boolean;
  protected abstract matchesActiveIdentity(identity: AiActiveRequestIdentity): boolean;
  protected abstract terminalIdentity(event: AiChatSocketEvent): AiActiveRequestIdentity | undefined;
  protected abstract completeCancellationTerminal(status: AiExecutionStatus,
                                                  response?: AiCancellationResponse): void;
  protected abstract awaitCompletedPayload(response?: AiCancellationResponse): void;
  protected abstract finishCompletedPayload(requestId: string, conversationId?: string,
                                            content?: string): void;
  protected abstract clearCompletedPayloadRecovery(): void;
  protected abstract runDeferredNewChat(): boolean;
  protected abstract createRequestId(): string;
  protected abstract createRestoreToken(): string;
  protected abstract nextContextUpdate(): AiChatContextUpdate;
  protected abstract invalidateAttachmentReads(): void;
  protected abstract updateMainPanelInset(): void;
}

let aiChatPanelInstanceSequence = 0;

function nextAiChatPanelInstanceSequence(): number {
  return ++aiChatPanelInstanceSequence;
}
