import {HttpErrorResponse} from '@angular/common/http';
import {forkJoin, map, of, tap} from 'rxjs';
import {take} from 'rxjs/operators';
import {AiChatPanelCommandController} from './ai-chat-panel-command.controller';
import {pendingMutationApprovalBatches} from './domain/ai-mutation-approval-batch';
import {
  AiActiveRequestIdentity,
  AiChatConversationDetails,
  AiChatMessage,
  AiExecutionStatus,
  AiPublicExecutionRequestStatus
} from './domain/ai-chat-panel.model';

const RECOVERED_REQUEST_STATUS_EVENT = 'request_recovery';

export abstract class AiChatPanelConversationController extends AiChatPanelCommandController {
  private recoveredRequestSnapshotId?: string;

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
    const discoverySubscription = this.api.getActiveRequest().pipe(take(1)).subscribe({
      next: status => {
        if (this.destroyed || this.activeRequestId) {
          return;
        }
        if (!status) {
          this.state.reconciliationRequired = false;
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
        // Mark recovery before the asynchronous snapshot starts. A live event
        // can then cancel a delayed snapshot before it overwrites newer activity.
        this.recoveredRequestSnapshotId = status.requestId;
        this.sessionPersistence.rememberLastConversation(status.conversationId);
        this.state.pending = true;
        this.state.reconciliationRequired = false;
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
    // Synchronous sources used by tests can enter pollRecoveredRequest before
    // subscribe() returns. Do not overwrite the newer snapshot subscription
    // with an already-closed discovery subscription in that case.
    if (!discoverySubscription.closed) {
      this.activeRecoverySubscription = discoverySubscription;
    }
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
    const recoveryIdentity = {
      ...active,
      conversationId: active.conversationId,
      generation: active.generation
    };
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
          this.recoverActiveRequestConnection(
            recoveryIdentity, () => this.scheduleRecoveredRequestPoll(1000), true
          );
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
          const active = this.state.activeRequest;
          if (active?.conversationId && active.generation !== undefined) {
            this.recoverActiveRequestConnection(
              {...active, conversationId: active.conversationId, generation: active.generation},
              () => this.scheduleRequestStatusWatchdog()
            );
          }
        }
      }
    });
  }

  protected applyRecoveredConversation(details: AiChatConversationDetails,
                                     status: AiPublicExecutionRequestStatus): void {
    const messages = this.conversationRestoreService.projectStoredMessages(
      [...(details.messages || [])].sort((left, right) => left.index - right.index));
    this.clearStatusMessage();
    this.state.messages = messages;
    if (!this.isTerminalExecutionStatus(status.status)) {
      this.recoveredRequestSnapshotId = status.requestId;
      // Register this as the request's status row so the first admitted live
      // event can remove it instead of leaving a completed recovery notice in history.
      this.showStatus(
        'The request is still running. Progress is restored automatically.',
        true, undefined, RECOVERED_REQUEST_STATUS_EVENT
      );
    } else if (this.recoveredRequestSnapshotId === status.requestId) {
      this.recoveredRequestSnapshotId = undefined;
    }
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
    this.recoveredRequestSnapshotId = undefined;
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
    this.state.reconciliationRequired = status.status === 'UNKNOWN_RECONCILIATION_REQUIRED';
    this.appendBackendRestartMessage(status);
    this.state.currentStatus = status.status === 'COMPLETED'
      ? 'Ready' : this.state.reconciliationRequired ? 'Review needed' : status.status;
    this.loadConversationHistory();
    this.focusPrompt();
    this.scrollToBottom();
  }

  private appendBackendRestartMessage(status: AiPublicExecutionRequestStatus): void {
    if (status.statusReason !== 'WORKER_INSTANCE_SHUTDOWN') {
      return;
    }
    const content = status.status === 'UNKNOWN_RECONCILIATION_REQUIRED'
      ? 'The backend restarted while a data-changing action may have been running. '
        + 'Review the result before retrying.'
      : 'The assistant request stopped because the backend restarted.';
    if (!this.state.messages.some(message => message.role === 'error' && message.content === content)) {
      this.state.messages.push({role: 'error', content});
    }
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
    this.activeRequestRecovery.cancel();
  }

  protected acknowledgeRecoveredRequestLiveEvent(requestId: string): void {
    if (this.recoveredRequestSnapshotId !== requestId) {
      return;
    }
    this.recoveredRequestSnapshotId = undefined;
    this.clearActiveRecovery();
    this.clearStatusMessage(RECOVERED_REQUEST_STATUS_EVENT);
  }

  protected recoverActiveRequestConnection(
    active: AiActiveRequestIdentity & {conversationId: string; generation: number},
    resume: () => void,
    restoreConversationSnapshot = false
  ): void {
    let terminalStatus: AiPublicExecutionRequestStatus | undefined;
    this.activeRequestRecovery.recover(active, {
      onAttempt: (attempt, maxAttempts) => {
        if (!this.matchesRecoveryIdentity(active)) {
          this.activeRequestRecovery.cancel();
          return;
        }
        this.showStatus(`Reconnecting... (${attempt}/${maxAttempts})`, true);
        this.state.currentStatus = 'Reconnecting';
        this.scrollToBottom();
      },
      verify: status => {
        if (!this.matchesRecoveryIdentity(active)
          || status.requestId !== active.requestId
          || status.conversationId !== active.conversationId
          || status.generation !== active.generation) {
          throw new Error('Recovered request identity changed.');
        }
        terminalStatus = this.isTerminalExecutionStatus(status.status) ? status : undefined;
        if (!terminalStatus && !restoreConversationSnapshot) {
          return of(undefined);
        }
        return this.api.getConversation(status.conversationId).pipe(
          take(1),
          // Conversation verification is part of the same bounded attempt.
          // A successful status request alone must not reset the retry budget.
          tap(details => this.applyRecoveredConversation(details, status)),
          map(() => undefined)
        );
      },
      onRecovered: status => {
        if (!this.matchesRecoveryIdentity(active)
          || status.requestId !== active.requestId
          || status.conversationId !== active.conversationId
          || status.generation !== active.generation) {
          return;
        }
        this.activeRequestRecovery.cancel();
        if (this.isTerminalExecutionStatus(status.status)) {
          this.finishRecoveredRequest(status);
          return;
        }
        this.showStatus('Reconnected. Waiting for the assistant response.', true);
        this.state.currentStatus = 'Request in progress';
        resume();
      },
      onFailure: maxAttempts => {
        if (this.matchesRecoveryIdentity(active)) {
          if (terminalStatus) {
            this.completeTerminalConversationRecoveryFailure(terminalStatus);
          } else {
            this.completeActiveRequestRecoveryFailure(active.requestId, maxAttempts);
          }
        }
      }
    });
  }

  private completeTerminalConversationRecoveryFailure(
    status: AiPublicExecutionRequestStatus
  ): void {
    if (status.statusReason !== 'WORKER_INSTANCE_SHUTDOWN') {
      this.state.messages.push({
        role: 'error',
        content: `The request ended with status ${status.status}, but its saved conversation `
          + 'could not be restored after 3 attempts.'
      });
    }
    this.finishRecoveredRequest(status);
  }

  private matchesRecoveryIdentity(active: AiActiveRequestIdentity): boolean {
    const current = this.state.activeRequest;
    return !this.destroyed && this.state.pending
      && current?.requestId === active.requestId
      && current.conversationId === active.conversationId
      && current.generation === active.generation;
  }

  private completeActiveRequestRecoveryFailure(requestId: string, maxAttempts: number): void {
    this.recoveredRequestSnapshotId = undefined;
    this.activeRequestRecovery.cancel();
    this.completeProgressMessages();
    this.settleAgentActivity('failed');
    this.clearTimers();
    this.clearStatusMessage();
    this.state.elicitation = undefined;
    this.state.elicitationBusy = false;
    this.clearMutationApprovalBatch();
    this.clearMutationRepeatDraft(requestId);
    this.requestSubscription?.unsubscribe();
    this.requestSubscription = undefined;
    this.activeRequestPublished = false;
    this.activeRequestId = undefined;
    this.clearToolCallTracking();
    this.state.messages.push({
      role: 'error',
      content: `Could not reconnect to the assistant after ${maxAttempts} attempts. `
        + 'The request outcome is unknown. Reopen the assistant after the backend is available to reconcile it.'
    });
    this.state.pending = false;
    this.state.reconciliationRequired = true;
    this.state.currentStatus = 'Connection lost';
    this.scrollToBottom();
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
