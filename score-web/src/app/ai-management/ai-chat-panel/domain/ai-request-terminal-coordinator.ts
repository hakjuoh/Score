/**
 * Applies the invariant state cleanup shared by all active-request terminal paths.
 */

import {Injectable} from '@angular/core';
import {AiChatCancellationService} from './ai-chat-cancellation.service';
import {AiChangeApprovalBatchCoordinator} from './ai-change-approval-batch-coordinator';
import {AiChatMessageTrackerService} from './ai-chat-message-tracker.service';
import {AiChatPanelState} from './ai-chat-panel-state';
import {AiConfirmedChangeRequestCoordinator} from './ai-confirmed-change-request-coordinator';
import {AiElicitationCoordinator} from './ai-elicitation-coordinator';
import {AiAgentExecutionStatus} from './ai-chat-panel.model';

export interface AiRequestTerminalTransition {
  agentStatus: Extract<AiAgentExecutionStatus, 'completed' | 'failed' | 'cancelled'>;
  reconciliationRequired: boolean;
  cancellation: 'preserve' | 'reset';
  completedPayload: 'preserve' | 'clear';
  toolGroups: 'preserve' | 'complete';
  confirmedChange: {kind: 'preserve'} | {kind: 'cancel'; requestId: string};
  changeRepeat: {kind: 'preserve'} | {kind: 'clear-all'}
    | {kind: 'clear-request'; requestId: string};
}

export interface AiRequestTerminalCallbacks {
  beforeSettlement?(): void;
  settleAgent(status: AiRequestTerminalTransition['agentStatus']): void;
  clearTimers(): void;
  clearCompletedPayload(): void;
  clearChangeRepeat(requestId?: string): void;
  releaseRequest(): void;
}

/** Applies the invariant cleanup shared by every active-request terminal path. */
@Injectable()
export class AiRequestTerminalCoordinator {
  constructor(
    private readonly cancellation: AiChatCancellationService,
    private readonly messages: AiChatMessageTrackerService,
    private readonly elicitation: AiElicitationCoordinator,
    private readonly approvals: AiChangeApprovalBatchCoordinator,
    private readonly confirmedChanges: AiConfirmedChangeRequestCoordinator
  ) {}

  transition(
    state: AiChatPanelState,
    transition: AiRequestTerminalTransition,
    callbacks: AiRequestTerminalCallbacks
  ): void {
    if (transition.completedPayload === 'clear') callbacks.clearCompletedPayload();
    if (transition.cancellation === 'reset') this.cancellation.reset();
    this.messages.completeProgressMessages(state);
    if (transition.toolGroups === 'complete') this.messages.completeToolGroupMessages(state);
    let preparationError: unknown;
    try {
      callbacks.beforeSettlement?.();
    } catch (error) {
      preparationError = error;
    }
    callbacks.settleAgent(transition.agentStatus);
    callbacks.clearTimers();
    this.messages.clearStatusMessage(state);
    this.elicitation.clear(state);
    this.approvals.clear(state);
    if (transition.confirmedChange.kind === 'cancel') {
      this.confirmedChanges.cancel(transition.confirmedChange.requestId);
    }
    if (transition.changeRepeat.kind === 'clear-request') {
      callbacks.clearChangeRepeat(transition.changeRepeat.requestId);
    } else if (transition.changeRepeat.kind === 'clear-all') {
      callbacks.clearChangeRepeat();
    }
    callbacks.releaseRequest();
    this.messages.clearToolCallTracking();
    state.pending = false;
    state.reconciliationRequired = transition.reconciliationRequired;
    if (preparationError !== undefined) throw preparationError;
  }
}
