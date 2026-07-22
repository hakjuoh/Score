import {HttpErrorResponse} from '@angular/common/http';
import {forkJoin} from 'rxjs';
import {take} from 'rxjs/operators';
import {AiChatPanelCommandController} from './ai-chat-panel-command.controller';
import {pendingMutationApprovalBatches} from './domain/ai-mutation-approval-batch';
import {
  AiChatConversationDetails,
  AiChatMessage,
  AiExecutionStatus,
  AiPublicExecutionRequestStatus
} from './domain/ai-chat-panel.model';

export abstract class AiChatPanelConversationController extends AiChatPanelCommandController {
  loadConversationHistory(): void {
    this.conversationHistorySubscription?.unsubscribe();
    this.state.conversationHistoryLoading = true;
    this.conversationHistorySubscription = this.api.getConversationHistory().pipe(take(1))
      .subscribe({
        next: conversations => {
          this.state.conversationHistory = conversations || [];
          this.state.conversationHistoryLoading = false;
          this.state.conversationHistoryLoadFailed = false;
        },
        error: () => {
          this.state.conversationHistoryLoading = false;
          this.state.conversationHistoryLoadFailed = true;
        }
      });
  }

  protected recoverActiveRequest(onIdle?: () => void): void {
    if (this.activeRequestId || this.destroyed) {
      return;
    }
    this.activeRecoverySubscription?.unsubscribe();
    this.activeRecoverySubscription = this.api.getActiveRequest().pipe(take(1)).subscribe({
      next: status => {
        if (this.destroyed || this.activeRequestId) {
          return;
        }
        if (!status) {
          onIdle?.();
          return;
        }
        this.state.activeRequest = {
          requestId: status.requestId,
          conversationId: status.conversationId,
          generation: status.generation,
          deadline: status.deadline
        };
        this.state.conversationId = status.conversationId;
        this.sessionPersistence.rememberLastConversation(status.conversationId);
        this.state.pending = true;
        this.activeRequestPublished = true;
        this.state.currentStatus = 'Reconnected to running request';
        this.requestSubscription?.unsubscribe();
        this.requestSubscription = this.transportService.watch(
          '/user/queue/ai/chat/' + status.requestId
        ).subscribe(message => {
          this.handleSocketEvent(JSON.parse(message.body));
        });
        this.pollRecoveredRequest();
      },
      error: () => onIdle?.()
    });
  }

  protected restoreLastConversation(): void {
    if (this.destroyed || this.state.pending || this.state.conversationId
      || this.state.messages.length > 0) {
      return;
    }
    const conversationId = this.sessionPersistence.readLastConversation();
    if (!conversationId) {
      if (this.restoreChatScrollPending) this.restoreChatScrollPosition();
      return;
    }

    this.state.prepareConversationRestore(
      conversationId, this.state.activePanelTab, true
    );
    this.lastConversationRestoreSubscription?.unsubscribe();
    this.lastConversationRestoreSubscription = this.api.getConversation(conversationId)
      .pipe(take(1)).subscribe({
        next: details => {
          if (this.destroyed || this.activeRequestId
            || this.state.conversationId !== conversationId) {
            return;
          }
          this.state.messages = this.conversationRestoreService.projectStoredMessages(
            [...(details.messages || [])].sort((left, right) => left.index - right.index));
          this.state.agentActivities = [];
          this.state.conversationId = details.conversationId;
          this.state.restoreConversationSettings(details);
          if (details.contextUsage) this.state.setContextUsage(details.contextUsage);
          this.state.restoringConversation = false;
          this.state.currentStatus = 'Ready';
          this.state.shouldFollowChatScroll = true;
          this.sessionPersistence.rememberLastConversation(details.conversationId);
          this.restoreChatScrollPosition();
          if (this.state.activePanelTab === 'chat') this.focusPrompt();
        },
        error: error => {
          if (this.state.conversationId !== conversationId || this.activeRequestId) {
            return;
          }
          const draft = {
            prompt: this.state.prompt,
            attachments: this.state.attachments,
            activePanelTab: this.state.activePanelTab,
            chatScrollTop: this.state.chatScrollTop,
            historyScrollTop: this.state.historyScrollTop
          };
          this.state.resetForNewChat();
          this.state.prompt = draft.prompt;
          this.state.attachments = draft.attachments;
          this.state.activePanelTab = draft.activePanelTab;
          this.state.chatScrollTop = draft.chatScrollTop;
          this.state.historyScrollTop = draft.historyScrollTop;
          this.sessionPersistence.restoreSelection(this.state);
          this.restoreChatScrollPosition();
          if (error instanceof HttpErrorResponse && (error.status === 403 || error.status === 404)) {
            this.sessionPersistence.clearLastConversation();
          }
        }
      });
  }

  protected pollRecoveredRequest(): void {
    const active = this.state.activeRequest;
    if (!active?.conversationId || active.generation === undefined || this.destroyed) {
      return;
    }
    this.activeRecoverySubscription?.unsubscribe();
    this.activeRecoverySubscription = forkJoin({
      details: this.api.getConversation(active.conversationId),
      status: this.api.getRequestStatus(active.requestId, active.conversationId, active.generation)
    }).pipe(take(1)).subscribe({
      next: snapshot => {
        if (this.activeRequestId !== snapshot.status.requestId) {
          return;
        }
        this.applyRecoveredConversation(snapshot.details, snapshot.status);
        if (this.isTerminalExecutionStatus(snapshot.status.status)) {
          this.loadTerminalRecoveredConversation(snapshot.status);
          return;
        }
        this.scheduleRecoveredRequestPoll(1000);
      },
      error: () => {
        if (this.activeRequestId === active.requestId) {
          this.state.currentStatus = 'Reconnecting to running request';
          this.scheduleRecoveredRequestPoll(1500);
        }
      }
    });
  }

  protected loadTerminalRecoveredConversation(status: AiPublicExecutionRequestStatus): void {
    this.clearProviderRetryCountdown();
    this.activeRecoverySubscription?.unsubscribe();
    this.activeRecoverySubscription = this.api.getConversation(status.conversationId).pipe(take(1)).subscribe({
      next: details => {
        if (this.activeRequestId !== status.requestId) {
          return;
        }
        // Status becomes terminal only after the final trajectory write. Reading
        // details again avoids a forkJoin race that could otherwise render the
        // pre-terminal snapshot permanently after refresh.
        this.applyRecoveredConversation(details, status);
        this.finishRecoveredRequest(status);
      },
      error: () => {
        if (this.activeRequestId === status.requestId) {
          this.scheduleRecoveredRequestPoll(1500);
        }
      }
    });
  }

  protected applyRecoveredConversation(details: AiChatConversationDetails,
                                     status: AiPublicExecutionRequestStatus): void {
    const messages = this.conversationRestoreService.projectStoredMessages(
      [...(details.messages || [])].sort((left, right) => left.index - right.index));
    if (!this.isTerminalExecutionStatus(status.status)) {
      messages.push({
        role: 'progress',
        content: 'The request is still running. Progress is restored automatically.',
        inProgress: true,
        turnId: status.requestId
      });
    }
    this.state.messages = messages;
    const recoveredApprovals = this.isTerminalExecutionStatus(status.status)
      ? [] : pendingMutationApprovalBatches(
        details.messages || [], status.requestId, details.conversationId
      );
    const activeApproval = this.state.mutationApprovalBatch;
    if (activeApproval
      && recoveredApprovals.some(batch => batch.batchId === activeApproval.batchId)) {
      this.state.mutationApprovalBatchQueue = recoveredApprovals.filter(
        batch => batch.batchId !== activeApproval.batchId
      );
    } else {
      this.state.mutationApprovalBatch = recoveredApprovals.shift();
      this.state.mutationApprovalBatchQueue = recoveredApprovals;
      this.state.mutationApprovalBatchBusy = false;
      this.scheduleMutationApprovalExpiry();
    }
    this.state.agentActivities = [...messages].reverse()
      .find(message => (message.role === 'agent_group' || message.role === 'workflow_group')
        && message.activities?.some(activity => activity.inProgress))?.activities || [];
    this.state.conversationId = details.conversationId;
    this.sessionPersistence.rememberLastConversation(details.conversationId);
    this.state.restoreConversationSettings(details);
    this.state.currentStatus = this.state.mutationApprovalBatch
      ? (this.state.mutationApprovalBatch.items.length === 1
        ? 'Approval required'
        : `${this.state.mutationApprovalBatch.items.length} approvals required`)
      : this.isTerminalExecutionStatus(status.status)
        ? status.status : 'Request in progress';
    if (this.restoreChatScrollPending) {
      this.restoreChatScrollPosition();
    } else {
      this.scrollToBottom();
    }
  }

  protected finishRecoveredRequest(status: AiPublicExecutionRequestStatus): void {
    this.clearActiveRecovery();
    this.requestSubscription?.unsubscribe();
    this.requestSubscription = undefined;
    this.clearTimers();
    this.clearMutationApprovalBatch();
    this.completeProgressMessages();
    this.settleAgentActivity(status.status === 'COMPLETED' ? 'completed'
      : status.status === 'CANCELLED' ? 'cancelled' : 'failed');
    this.state.pending = false;
    this.activeRequestPublished = false;
    this.state.activeRequest = undefined;
    this.state.currentStatus = status.status === 'COMPLETED' ? 'Ready' : status.status;
    this.loadConversationHistory();
    this.focusPrompt();
  }

  protected scheduleRecoveredRequestPoll(delay: number): void {
    if (this.activeRecoveryTimeout !== undefined) {
      window.clearTimeout(this.activeRecoveryTimeout);
    }
    this.activeRecoveryTimeout = window.setTimeout(() => {
      this.activeRecoveryTimeout = undefined;
      this.pollRecoveredRequest();
    }, delay);
  }

  protected clearActiveRecovery(): void {
    if (this.activeRecoveryTimeout !== undefined) {
      window.clearTimeout(this.activeRecoveryTimeout);
      this.activeRecoveryTimeout = undefined;
    }
    this.activeRecoverySubscription?.unsubscribe();
    this.activeRecoverySubscription = undefined;
  }

  protected isTerminalExecutionStatus(status: AiExecutionStatus): boolean {
    return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED'
      || status === 'TIMED_OUT' || status === 'STEP_LIMIT_REACHED'
      || status === 'UNKNOWN_RECONCILIATION_REQUIRED';
  }

  deleteConversation(conversationId: string, event?: Event): void {
    event?.preventDefault();
    event?.stopPropagation();
    if (this.mutationDecisionOpen || this.mutationDecisionInFlight) {
      this.snackBar.open(
        'Finish the action approval decision before deleting chat history.',
        'Dismiss', {duration: 3500}
      );
      return;
    }
    if (this.state.reconciliationRequired) {
      this.snackBar.open(
        'Resolve the request outcome before deleting chat history.',
        'Dismiss', {duration: 3500}
      );
      return;
    }
    if (this.state.pending) {
      this.snackBar.open('Stop the active request before deleting a chat history item.', 'Dismiss', {duration: 3000});
      return;
    }
    const conversation = this.state.conversationHistory
      .find(item => item.conversationId === conversationId);
    const dialogConfig = this.confirmDialogService.newConfig();
    dialogConfig.data.header = 'Delete conversation?';
    dialogConfig.data.content = [
      conversation?.title
        ? `“${conversation.title}” will be permanently deleted.`
        : 'This conversation will be permanently deleted.'
    ];
    dialogConfig.data.action = 'Delete';
    this.confirmDialogService.open(dialogConfig).afterClosed().pipe(take(1))
      .subscribe(confirmed => {
        if (confirmed) {
          this.deleteConversationNow(conversationId);
        }
      });
  }

  protected deleteConversationNow(conversationId: string): void {
    this.api.deleteConversation(conversationId)
      .subscribe({
        next: response => {
          if (response?.deleted === false) {
            this.snackBar.open('Could not delete the selected AI conversation.', 'Dismiss', {duration: 3500});
            return;
          }
          this.state.conversationHistory = this.state.conversationHistory
            .filter(conversation => conversation.conversationId !== conversationId);
          if (this.state.conversationId === conversationId) {
            this.startNewChat(undefined, 'history');
          }
        },
        error: () => this.snackBar.open('Could not delete the selected AI conversation.', 'Dismiss', {duration: 3500})
      });
  }

}
