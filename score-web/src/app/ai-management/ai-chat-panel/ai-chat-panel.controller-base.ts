/**
 * Defines the shared state, dependencies, and lifecycle foundation for the chat controller hierarchy.
 */

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
import {AiChatApiService} from './domain/ai-chat-api.service';
import {AiActiveRequestRecoveryService} from './domain/ai-active-request-recovery.service';
import {AiChatAttachmentQueueService} from './domain/ai-chat-attachment-queue.service';
import {AiChatAttachmentService} from './domain/ai-chat-attachment.service';
import {
  AiChatCancellationCallbacks,
  AiChatCancellationService
} from './domain/ai-chat-cancellation.service';
import {AiChatCommandService, AiLocalCommand} from './domain/ai-chat-command.service';
import {AiChatContextService} from './domain/ai-chat-context.service';
import {AiConfirmedChangeRequestCoordinator} from './domain/ai-confirmed-change-request-coordinator';
import {
  AiChangeRepeatCoordinator,
  BoundChangeConfirmationNotice,
  ChangeRepeatDraft
} from './domain/ai-change-repeat-coordinator';
import {AiRequestDispatchCoordinator} from './domain/ai-request-dispatch-coordinator';
import {AiChangeApprovalBatchCoordinator} from './domain/ai-change-approval-batch-coordinator';
import {AiElicitationCoordinator} from './domain/ai-elicitation-coordinator';
import {
  AiRequestTerminalCoordinator,
  AiRequestTerminalTransition
} from './domain/ai-request-terminal-coordinator';
import {AiConversationRestoreService} from './domain/ai-conversation-restore.service';
import {AiConversationProjector} from './domain/ai-conversation-projector';
import {AiChatNavigationService} from './domain/ai-chat-navigation.service';
import {AiChatPanelLayoutService} from './domain/ai-chat-panel-layout.service';
import {AiChatMessageTrackerService} from './domain/ai-chat-message-tracker.service';
import {AiChatPanelState} from './domain/ai-chat-panel-state';
import {AiChatPanelViewportService} from './domain/ai-chat-panel-viewport.service';
import {AiChatSessionPersistenceService} from './domain/ai-chat-session-persistence.service';
import {AiPermissionSettingsService} from './domain/ai-permission-settings.service';
import {AiModelSettingsCoordinator} from './domain/ai-model-settings-coordinator';
import {AiChatTransportService} from './domain/ai-chat-transport.service';
import {AiChatWindowCoordinatorService} from './domain/ai-chat-window-coordinator.service';
import {AiChatWorkspacePersistenceCoordinator} from './domain/ai-chat-workspace-persistence-coordinator';
import {
  AiAgentActivity,
  isTerminalAgentStatus,
  settleAgentConversation
} from './domain/ai-agent-activity';
import {AiChangeInteractionService} from './domain/ai-change-interaction.service';
import {
  AiActiveRequestIdentity,
  AiAgentExecutionStatus,
  AiCancellationResponse,
  AiChatAttachment,
  AiChatCommand,
  AiChatContextUpdate,
  AiChatMessage,
  AiMcpStatus,
  AiChatPanelTab,
  AiChatSocketEvent,
  AiExecutionStatus,
  AiChangeInteraction,
  ResizeState,
  checkingAiMcpStatus,
  failedAiMcpStatusCheck,
  readyAiMcpStatus
} from './domain/ai-chat-panel.model';

@Directive()
export abstract class AiChatPanelControllerBase {

  protected api = inject(AiChatApiService);
  protected activeRequestRecovery = inject(AiActiveRequestRecoveryService);
  protected attachmentQueue = inject(AiChatAttachmentQueueService);
  protected attachmentService = inject(AiChatAttachmentService);
  protected cancellationService = inject(AiChatCancellationService);
  protected commandService = inject(AiChatCommandService);
  protected confirmedChangeRequests = inject(AiConfirmedChangeRequestCoordinator);
  protected changeRepeats = inject(AiChangeRepeatCoordinator);
  protected requestDispatch = inject(AiRequestDispatchCoordinator);
  protected changeApprovalBatches = inject(AiChangeApprovalBatchCoordinator);
  protected elicitationCoordinator = inject(AiElicitationCoordinator);
  protected requestTerminals = inject(AiRequestTerminalCoordinator);
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
  protected permissionSettings = inject(AiPermissionSettingsService);
  protected modelSettings = inject(AiModelSettingsCoordinator);
  protected transportService = inject(AiChatTransportService);
  protected windowCoordinator = inject(AiChatWindowCoordinatorService);
  protected workspacePersistence = inject(AiChatWorkspacePersistenceCoordinator);
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
  protected resizeState?: ResizeState;
  protected activeRequestPublished = false;
  protected activeRestoreRequestId?: string;
  protected restoreAttemptSequence = 0;
  protected assistantMessageIndexesByRequestId = new Map<string, number>();
  protected destroyed = false;
  protected readonly destroyed$ = new Subject<void>();
  private mcpStatusCheck?: Observable<AiMcpStatus>;
  protected restoreChatScrollPending = false;

  protected get changeRepeatDraft(): ChangeRepeatDraft | undefined {
    return this.changeRepeats.draft;
  }

  protected get pendingChangeConfirmation(): BoundChangeConfirmationNotice | undefined {
    return this.changeRepeats.pendingConfirmation;
  }

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
    this.changeApprovalBatches.clear(this.state);
  }

  protected transitionActiveRequest(
    transition: AiRequestTerminalTransition,
    beforeSettlement?: () => void
  ): void {
    this.requestTerminals.transition(this.state, transition, {
      beforeSettlement,
      settleAgent: status => this.settleAgentActivity(status),
      clearTimers: () => this.clearTimers(),
      clearCompletedPayload: () => this.clearCompletedPayloadRecovery(),
      clearChangeRepeat: requestId => this.clearChangeRepeatDraft(requestId),
      releaseRequest: () => {
        this.activeRequestId = undefined;
        this.requestSubscription?.unsubscribe();
        this.requestSubscription = undefined;
      }
    });
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
    this.workspacePersistence.initialize(
      this.state,
      {
        workspaceRestored,
        setRestoreChatScroll: pending => this.restoreChatScrollPending = pending,
        isDestroyed: () => this.destroyed
      }
    );
  }

  protected restorePersistedDraftAttachments(): void {
    this.workspacePersistence.restoreDraftAttachments(this.state, () => this.destroyed);
  }

  protected persistWorkspaceIfChanged(): void {
    this.workspacePersistence.persistIfChanged(this.state, {
      destroyed: this.destroyed, popoutMode: this.popoutMode
    });
  }

  protected flushWorkspacePersistence(): void {
    this.workspacePersistence.flush(this.state);
  }

  protected invalidateDraftAttachmentRestore(): void {
    this.workspacePersistence.invalidateDraftAttachmentRestore();
  }

  // Forward dependencies used before their owning controller layer is mixed
  // into the final component. Public template methods are intentionally not
  // repeated here; this contract contains only genuine cross-layer calls.
  protected abstract acceptedIdentity(event: AiChatSocketEvent): AiActiveRequestIdentity | undefined;
  protected abstract acknowledgeRecoveredRequestLiveEvent(requestId: string): void;
  protected abstract applyFormattedResponse(event: AiChatSocketEvent): void;
  protected abstract beginChangeRepeatDraft(
    requestId: string, prompt: string, attachments: AiChatAttachment[]
  ): void;
  protected abstract canRequestChangeRevision(): boolean;
  abstract cancelActiveRequest(): void;
  protected abstract cancelConversationRestore(): void;
  protected abstract cancellationCallbacks(): AiChatCancellationCallbacks;
  protected abstract captureAcceptedIdentity(event: AiChatSocketEvent): void;
  protected abstract captureTerminalIdentity(event: AiChatSocketEvent): boolean;
  protected abstract clearActiveRecovery(): void;
  protected abstract clearChangeRepeatDraft(requestId?: string): void;
  protected abstract clearCompletedPayloadRecovery(): void;
  protected abstract clearProviderRecoveryState(): void;
  protected abstract clearRequestStatusWatchdog(): void;
  protected abstract clearStatusMessage(eventType?: string): void;
  protected abstract clearTimers(): void;
  protected abstract clearToolCallTracking(): void;
  protected abstract commitAssistantMessage(
    requestId: string, content: string,
    files?: import('./domain/ai-chat-panel.model').AiChatFile[]
  ): number;
  protected abstract completeCancellationTerminal(
    status: AiExecutionStatus, response?: AiCancellationResponse
  ): void;
  protected abstract completeProgressMessages(): void;
  protected abstract createRequestId(): string;
  protected abstract createRestoreToken(): string;
  protected abstract divertSpecialistToolEvent(event: AiChatSocketEvent): boolean;
  protected abstract finishChangeRepeatOpportunity(
    requestId: string, terminalConversationId: string | undefined
  ): void;
  protected abstract finishConversationRestore(): void;
  abstract focusPrompt(): void;
  protected abstract handleChangeConfirmationNotice(event: AiChatSocketEvent): void;
  protected abstract handleConversationRestoreEvent(event: AiChatSocketEvent): void;
  protected abstract handleLocalCommand(localCommand: AiLocalCommand, commandText: string): void;
  protected abstract handleSocketEvent(event: AiChatSocketEvent): void;
  protected abstract handleSystemEvent(event: AiChatSocketEvent): void;
  protected abstract handleToolCallEvent(event: AiChatSocketEvent): void;
  protected abstract invalidateAttachmentReads(): void;
  protected abstract isRecognizedRequestEvent(event: AiChatSocketEvent): boolean;
  abstract loadConversationHistory(): void;
  protected abstract matchesActiveIdentity(identity: AiActiveRequestIdentity): boolean;
  protected abstract matchesTerminalIdentity(event: AiChatSocketEvent): boolean;
  protected abstract nextContextUpdate(): AiChatContextUpdate;
  protected abstract recoverActiveRequest(onIdle?: () => void): void;
  protected abstract refreshBranding(): void;
  protected abstract resizePromptInput(): void;
  protected abstract restoreChatScrollPosition(consumePending?: boolean): void;
  protected abstract restoreLastConversation(): void;
  protected abstract runDeferredNewChat(): boolean;
  protected abstract scheduleRequestStatusWatchdog(): void;
  protected abstract scrollChatPaneToTop(): void;
  protected abstract scrollToBottom(force?: boolean): void;
  protected abstract sendChangeRevisionRequest(
    prompt: string, attachments: AiChatAttachment[]
  ): void;
  protected abstract showStatus(
    content: string, inProgress?: boolean,
    options?: import('./domain/ai-chat-panel.model').AiChatStatusOptions
  ): void;
  protected abstract updateMainPanelInset(): void;
  protected abstract upsertToolGroup(event: AiChatSocketEvent): void;

}

let aiChatPanelInstanceSequence = 0;

function nextAiChatPanelInstanceSequence(): number {
  return ++aiChatPanelInstanceSequence;
}
