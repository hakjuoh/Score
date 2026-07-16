import {Injectable, inject} from '@angular/core';
import {MatSnackBar} from '@angular/material/snack-bar';
import {Subject} from 'rxjs';
import {finalize, take, takeUntil, timeout} from 'rxjs/operators';
import {AiChatApiService} from './ai-chat-api.service';
import {MUTATION_CONFIRMATION_DECISION_TIMEOUT_MS} from './ai-chat-panel.constants';
import {AiChatPanelState} from './ai-chat-panel-state';
import {
  AiChatAttachment,
  AiMutationInteraction,
  AiMutationConfirmationNotice
} from './ai-chat-panel.model';
import {
  isUnexpiredMutationConfirmation,
  mutationApprovalOutcome,
  mutationConflictOutcome,
  mutationDenialOutcome
} from './ai-mutation-confirmation';

export interface MutationRepeatDraft {
  requestId: string;
  prompt: string;
  attachments: AiChatAttachment[];
}

export interface MutationRepeatOpportunity {
  conversationId: string;
  draft: MutationRepeatDraft;
  notice: AiMutationConfirmationNotice;
}

export interface AiMutationInteractionCallbacks {
  sendConfirmedMutationRepeat(
    opportunity: MutationRepeatOpportunity,
    confirmationGrant: string,
    revision?: {prompt: string; attachments: AiChatAttachment[]}
  ): boolean;
  focusPrompt(): void;
  scrollToBottom(): void;
}

@Injectable()
export class AiMutationInteractionService {
  private api = inject(AiChatApiService);
  private snackBar = inject(MatSnackBar);
  private opportunity?: MutationRepeatOpportunity;
  private mode: AiMutationInteraction['mode'] = 'confirm';
  private open = false;
  private inFlight = false;
  private destroyed = false;
  private readonly destroyed$ = new Subject<void>();

  get decisionOpen(): boolean {
    return this.open;
  }

  get decisionInFlight(): boolean {
    return this.inFlight;
  }

  get interactionMode(): AiMutationInteraction['mode'] {
    return this.mode;
  }

  get interaction(): AiMutationInteraction | undefined {
    return this.opportunity ? {
      toolName: this.opportunity.notice.toolName,
      argumentsSummary: this.opportunity.notice.argumentsSummary,
      mode: this.mode,
      busy: this.inFlight
    } : undefined;
  }

  canRequestChange(state: AiChatPanelState): boolean {
    return this.open && this.mode === 'confirm' && !!this.opportunity
      && !this.inFlight && !state.pending && !state.reconciliationRequired;
  }

  showConfirmation(state: AiChatPanelState, opportunity: MutationRepeatOpportunity,
                   callbacks: AiMutationInteractionCallbacks): void {
    if (this.destroyed || !isUnexpiredMutationConfirmation(opportunity.notice)) return;
    this.opportunity = opportunity;
    this.mode = 'confirm';
    this.open = true;
    state.currentStatus = 'Approval required';
    callbacks.scrollToBottom();
  }

  showLostGrant(state: AiChatPanelState, opportunity: MutationRepeatOpportunity,
                callbacks: AiMutationInteractionCallbacks): void {
    if (this.destroyed) return;
    this.opportunity = opportunity;
    this.mode = 'lost_grant';
    this.open = true;
    state.currentStatus = 'Approval unavailable';
    callbacks.scrollToBottom();
  }

  approve(state: AiChatPanelState, callbacks: AiMutationInteractionCallbacks): void {
    const opportunity = this.opportunity;
    if (!opportunity || this.mode !== 'confirm' || this.inFlight) return;
    if (!isUnexpiredMutationConfirmation(opportunity.notice)) {
      this.snackBar.open('The action approval expired.', 'Dismiss', {duration: 3500});
      return;
    }
    this.inFlight = true;
    this.api.decideMutationConfirmation(
      opportunity.conversationId,
      opportunity.notice.confirmationRequestId,
      'APPROVE'
    ).pipe(
      timeout(MUTATION_CONFIRMATION_DECISION_TIMEOUT_MS),
      take(1),
      takeUntil(this.destroyed$),
      finalize(() => this.finishDecision(state, callbacks))
    ).subscribe({
      next: response => {
        if (this.destroyed) return;
        const outcome = mutationApprovalOutcome(
          response,
          opportunity.conversationId,
          opportunity.notice.confirmationRequestId
        );
        if (outcome.kind === 'GRANTED') {
          this.close(state);
          if (!callbacks.sendConfirmedMutationRepeat(
            opportunity, outcome.confirmationGrant
          )) {
            this.showLostGrant(state, opportunity, callbacks);
          }
          return;
        }
        if (outcome.kind === 'LOST') {
          this.showLostGrant(state, opportunity, callbacks);
          return;
        }
        this.snackBar.open(
          'The action approval response was not valid.',
          'Dismiss', {duration: 3500}
        );
      },
      error: error => {
        if (this.handleConsumedDecision(state, error, opportunity)) return;
        if (this.approvalOutcomeUnknown(error)) {
          this.showLostGrant(state, opportunity, callbacks);
          return;
        }
        this.snackBar.open(
          'The action could not be approved.',
          'Dismiss', {duration: 3500}
        );
      }
    });
  }

  deny(state: AiChatPanelState, callbacks: AiMutationInteractionCallbacks): void {
    const opportunity = this.opportunity;
    if (!opportunity || this.mode !== 'confirm' || this.inFlight) return;
    this.denyOpportunity(state, opportunity, callbacks);
  }

  revoke(state: AiChatPanelState, callbacks: AiMutationInteractionCallbacks): void {
    const opportunity = this.opportunity;
    if (!opportunity || this.mode !== 'lost_grant' || this.inFlight) return;
    this.denyOpportunity(state, opportunity, callbacks);
  }

  dismiss(state: AiChatPanelState): void {
    if (this.mode === 'lost_grant' && !this.inFlight) this.close(state);
  }

  sendChange(state: AiChatPanelState, prompt: string, attachments: AiChatAttachment[],
             callbacks: AiMutationInteractionCallbacks): void {
    const opportunity = this.opportunity;
    if (!opportunity || !this.canRequestChange(state)) return;
    state.currentStatus = 'Approving revised action';
    this.inFlight = true;
    this.api.decideMutationConfirmation(
      opportunity.conversationId,
      opportunity.notice.confirmationRequestId,
      'APPROVE',
      prompt
    ).pipe(
      timeout(MUTATION_CONFIRMATION_DECISION_TIMEOUT_MS),
      take(1),
      takeUntil(this.destroyed$),
      finalize(() => this.finishDecision(state, callbacks))
    ).subscribe({
      next: response => {
        if (this.destroyed) return;
        const outcome = mutationApprovalOutcome(
          response,
          opportunity.conversationId,
          opportunity.notice.confirmationRequestId
        );
        if (outcome.kind === 'GRANTED') {
          this.close(state);
          if (!callbacks.sendConfirmedMutationRepeat(
            opportunity, outcome.confirmationGrant, {prompt, attachments}
          )) {
            this.showLostGrant(state, opportunity, callbacks);
          }
          return;
        }
        if (outcome.kind === 'LOST') {
          this.showLostGrant(state, opportunity, callbacks);
          return;
        }
        state.currentStatus = 'Approval required';
        this.snackBar.open(
          'The revised action approval response was not valid.',
          'Dismiss', {duration: 3500}
        );
      },
      error: error => {
        if (this.handleConsumedDecision(state, error, opportunity)) return;
        if (this.approvalOutcomeUnknown(error)) {
          this.showLostGrant(state, opportunity, callbacks);
          return;
        }
        state.currentStatus = 'Approval required';
        this.snackBar.open(
          'The revised action could not be approved.',
          'Dismiss', {duration: 3500}
        );
      }
    });
  }

  close(state: AiChatPanelState): void {
    this.opportunity = undefined;
    this.open = false;
    if (!state.pending && !state.reconciliationRequired) {
      state.currentStatus = 'Ready';
    }
  }

  destroy(state: AiChatPanelState): void {
    this.destroyed = true;
    this.destroyed$.next();
    this.destroyed$.complete();
    this.close(state);
  }

  private denyOpportunity(state: AiChatPanelState, opportunity: MutationRepeatOpportunity,
                          callbacks: AiMutationInteractionCallbacks): void {
    this.inFlight = true;
    this.api.decideMutationConfirmation(
      opportunity.conversationId,
      opportunity.notice.confirmationRequestId,
      'DENY'
    ).pipe(
      timeout(MUTATION_CONFIRMATION_DECISION_TIMEOUT_MS),
      take(1),
      takeUntil(this.destroyed$),
      finalize(() => this.finishDecision(state, callbacks))
    ).subscribe({
      next: response => {
        const outcome = mutationDenialOutcome(
          response,
          opportunity.conversationId,
          opportunity.notice.confirmationRequestId
        );
        if (outcome === 'INVALID') {
          this.snackBar.open(
            'The action denial response was not valid.',
            'Dismiss', {duration: 3500}
          );
          return;
        }
        this.close(state);
      },
      error: error => {
        if (this.handleConsumedDecision(state, error, opportunity)) return;
        this.close(state);
        this.snackBar.open(
          'The denial outcome is unknown; the action was not sent.',
          'Dismiss', {duration: 3500}
        );
      }
    });
  }

  private approvalOutcomeUnknown(error: unknown): boolean {
    const status = (error as {status?: unknown} | null)?.status;
    return typeof status !== 'number' || status === 0 || status >= 500;
  }

  private handleConsumedDecision(
    state: AiChatPanelState,
    error: unknown,
    opportunity: MutationRepeatOpportunity
  ): boolean {
    const httpError = error as {status?: unknown; error?: unknown} | null;
    if (httpError?.status !== 409
      || mutationConflictOutcome(
        httpError.error as any,
        opportunity.conversationId,
        opportunity.notice.confirmationRequestId
      ) !== 'CONSUMED') {
      return false;
    }
    state.reconciliationRequired = true;
    state.currentStatus = 'Review needed';
    state.messages.push({
      role: 'error',
      content: 'The approved action may already have run and requires reconciliation.'
    });
    this.close(state);
    return true;
  }

  private finishDecision(state: AiChatPanelState,
                         callbacks: AiMutationInteractionCallbacks): void {
    this.inFlight = false;
    if (!this.destroyed && !this.open && !state.pending && !state.reconciliationRequired) {
      callbacks.focusPrompt();
    }
  }
}
