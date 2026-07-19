import {HttpErrorResponse} from '@angular/common/http';
import {Message} from '@stomp/stompjs';
import {take, timeout} from 'rxjs/operators';
import {AiChatPanelControllerBase} from './ai-chat-panel.controller-base';
import {SAFE_ATTACHMENT_ERROR_PATTERNS} from './domain/ai-chat-panel-display.constants';
import {isBoundConfirmedChatResponse} from './domain/ai-mutation-confirmation';
import {CONFIRMED_MUTATION_RESPONSE_TIMEOUT_MS} from './domain/ai-chat-panel.constants';
import {
  AiChatAttachment,
  AiChatContextUpdate,
  AiChatSocketEvent,
  AiMutationConfirmationAuthorization
} from './domain/ai-chat-panel.model';
import {isExecutionActivityEvent, isSpecialistToolEvent} from './domain/ai-agent-activity';

export abstract class AiChatPanelRequestController extends AiChatPanelControllerBase {
  protected startChatRequest(prompt: string, attachments: AiChatAttachment[]): void {
    this.state.activePanelTab = 'chat';
    const requestId = this.createRequestId();
    this.beginMutationRepeatDraft(requestId, prompt, attachments);
    this.activeRequestId = requestId;
    this.activeRequestPublished = false;
    this.clearToolCallTracking();
    this.state.resetAgentActivity();
    const destination = '/user/queue/ai/chat/' + requestId;
    this.state.pending = true;
    this.state.currentStatus = 'Sending request';
    this.state.prompt = '';
    this.state.attachments = [];
    this.resizePromptInput();
    this.state.messages.push({role: 'user', content: this.attachmentService.userMessageContent(prompt, attachments)});

    if (attachments.length > 0) {
      this.sendHttpChat(prompt, attachments);
      return;
    }

    this.showStatus('Waiting for WebSocket connection.', true);
    this.scrollToBottom(true);

    this.requestSubscription?.unsubscribe();
    this.requestSubscription = this.transportService.watch(destination).subscribe((message: Message) => {
      this.handleSocketEvent(JSON.parse(message.body) as AiChatSocketEvent);
    });

    this.connectAndPublishWhenReady(requestId, prompt, attachments);
  }

  protected sendHttpChat(
    prompt: string,
    attachments: AiChatAttachment[],
    mutationConfirmation?: AiMutationConfirmationAuthorization
  ): void {
    const requestId = this.activeRequestId || this.createRequestId();
    const contextUpdate = this.nextContextUpdate();
    this.pendingContextUpdate = contextUpdate;
    const confirmedMutation = mutationConfirmation !== undefined;
    const confirmedConversationId = confirmedMutation
      ? this.state.conversationId : undefined;
    const liveInteractionSubscription = this.transportService.watch(
      '/user/queue/ai/chat/' + requestId
    ).subscribe((message: Message) => {
      const event = JSON.parse(message.body) as AiChatSocketEvent;
      if (isExecutionActivityEvent(event)
        || isSpecialistToolEvent(event)
        || event.type === 'system' && event.subtype === 'guide'
        || event.type === 'system' && (event.subtype === 'elicitation_required'
        || event.subtype === 'elicitation_decision_accepted'
        || event.subtype === 'elicitation_decision_rejected')) {
        this.handleSocketEvent(event);
      }
    });
    this.showStatus(confirmedMutation
      ? 'Sending approved action request.' : 'Uploading attachment request.', true);
    this.scrollToBottom(true);

    this.requestSubscription?.unsubscribe();
    // HttpClient starts the request on subscription, so every later Stop must
    // reach the backend instead of being treated as a not-yet-published chat.
    this.activeRequestPublished = true;
    const response = this.api.sendChat({
      requestId,
      prompt,
      conversationId: this.state.conversationId,
      modelName: this.state.selectedModelName,
      reasoningEffort: this.state.selectedReasoningEffort,
      runtime: this.state.selectedRuntime,
      runtimeOptions: {...this.state.selectedRuntimeOptions},
      permissionMode: this.state.permissionMode,
      pageContext: contextUpdate.pageContext,
      attachments,
      ...(mutationConfirmation ? {mutationConfirmation} : {})
    });
    const boundedResponse = confirmedMutation
      ? response.pipe(
        timeout(CONFIRMED_MUTATION_RESPONSE_TIMEOUT_MS),
        take(1)
      )
      : response;
    const subscription = boundedResponse.subscribe({
      next: response => {
        if (this.destroyed || this.activeRequestId !== requestId) {
          return;
        }
        if (confirmedMutation
          && (!confirmedConversationId
            || !isBoundConfirmedChatResponse(
              response, confirmedConversationId
            ))) {
          this.completeUnknownConfirmedRequest(requestId);
          return;
        }
        if (this.cancellationService.isActive(requestId)
          && this.state.cancellation.acknowledged) {
          this.showStatus('Waiting for the canonical cancellation result.', true);
          this.state.currentStatus = 'Cancelling';
          return;
        }
        this.clearCompletedPayloadRecovery();
        this.cancellationService.reset();
        this.completeProgressMessages();
        this.clearTimers();
        this.clearStatusMessage();
        this.state.conversationId = response.conversationId || this.state.conversationId;
        this.sessionPersistence.rememberLastConversation(this.state.conversationId);
        (response.events || []).forEach(event => {
          if (event.type === 'system'
            && event.subtype === 'mutation_confirmation_required') {
            this.handleMutationConfirmationNotice(event);
          } else if (event.type === 'system'
            && (event.subtype === 'context_usage' || event.subtype === 'context_compacted')) {
            this.handleSystemEvent(event);
          } else if (isExecutionActivityEvent(event)) {
            this.handleSystemEvent(event);
          } else if (event.type === 'system' && event.subtype === 'guide') {
            this.handleSystemEvent(event);
          } else if (event.type === 'tool_call' || event.type === 'tool_group') {
            // Replayed specialist tool activity lands in the agent timeline;
            // lead tool rows are not replayed here (unchanged behavior).
            this.divertSpecialistToolEvent(event);
          }
        });
        this.settleAgentActivity('completed');
        this.confirmContextUpdate();
        this.state.elicitation = undefined;
        this.state.elicitationBusy = false;
        this.activeRequestId = undefined;
        this.clearToolCallTracking();
        if (response.progress?.length && this.state.debugEnabled) {
          response.progress.forEach(progress => this.state.messages.push({role: 'progress', content: progress}));
        }
        if (response.response) {
          this.state.messages.push({role: 'assistant', content: response.response});
        }
        this.state.pending = false;
        this.state.reconciliationRequired = false;
        this.state.currentStatus = response.continuationRequired ? 'More processing is needed' : 'Ready';
        if (this.runDeferredNewChat()) {
          return;
        }
        this.finishMutationRepeatOpportunity(
          requestId, response.conversationId || this.state.conversationId
        );
        this.loadConversationHistory();
        if (!this.mutationDecisionOpen) {
          this.focusPrompt();
        }
        this.scrollToBottom();
      },
      error: error => {
        if (this.destroyed || this.activeRequestId !== requestId) {
          return;
        }
        if (this.cancellationService.isActive(requestId)) {
          this.showStatus('Waiting for cancellation confirmation.', true);
          this.state.currentStatus = 'Cancelling';
          return;
        }
        if (confirmedMutation) {
          this.completeUnknownConfirmedRequest(requestId);
          return;
        }
        const confirmationConversationId = this.captureRestErrorMutationNotice(
          error, requestId
        );
        this.completeProgressMessages();
        this.settleAgentActivity('failed');
        this.clearTimers();
        this.clearStatusMessage();
        if (!confirmationConversationId) {
          this.clearMutationRepeatDraft(requestId);
        }
        this.pendingContextUpdate = undefined;
        this.state.elicitation = undefined;
        this.state.elicitationBusy = false;
        this.activeRequestId = undefined;
        this.clearToolCallTracking();
        this.state.messages.push({
          role: 'error',
          content: this.attachmentFailureMessage(error)
        });
        this.state.pending = false;
        this.state.reconciliationRequired = false;
        this.state.currentStatus = 'Error';
        if (this.runDeferredNewChat()) {
          return;
        }
        this.finishMutationRepeatOpportunity(
          requestId, confirmationConversationId
        );
        if (!this.mutationDecisionOpen) {
          this.focusPrompt();
        }
        this.scrollToBottom();
      }
    });
    subscription.add(liveInteractionSubscription);
    // Do not retain a confirmed request subscription on the component: the
    // HTTP pipeline owns the callback-local grant until this single attempt
    // ends. Cancellation still uses its durable request identity.
    if (!confirmedMutation) {
      this.requestSubscription = subscription;
    } else {
      const unregisterRequest = this.confirmedMutationRequests.register(
        requestId, () => subscription.unsubscribe()
      );
      const unregister = this.destroyRef.onDestroy(
        () => this.confirmedMutationRequests.cancel(requestId)
      );
      subscription.add(() => {
        unregisterRequest();
        unregister();
      });
    }
  }

  protected attachmentFailureMessage(error: unknown): string {
    const fallback = 'The attachment request could not be completed. Check the backend log for details.';
    if (!(error instanceof HttpErrorResponse)) {
      return fallback;
    }
    const message = error.headers?.get('x-error-message')?.trim();
    if (error.status !== 400 || !message || message.length > 500
      || /[\r\n\u0000-\u001f\u007f]/.test(message)
      || !SAFE_ATTACHMENT_ERROR_PATTERNS.some(
        pattern => pattern.test(message)
      )) {
      return fallback;
    }
    return message;
  }

  protected captureRestErrorMutationNotice(
    error: unknown,
    requestId: string
  ): string | undefined {
    if (!(error instanceof HttpErrorResponse)
      || typeof error.error !== 'object' || error.error === null) {
      return undefined;
    }
    const events = (error.error as {events?: unknown}).events;
    if (!Array.isArray(events)) {
      return undefined;
    }
    events.forEach(event => {
      if (typeof event === 'object' && event !== null
        && (event as AiChatSocketEvent).type === 'system'
        && (event as AiChatSocketEvent).subtype === 'mutation_confirmation_required') {
        this.handleMutationConfirmationNotice(event as AiChatSocketEvent);
      }
    });
    return this.pendingMutationConfirmation?.conversationId;
  }

  protected completeUnknownConfirmedRequest(requestId: string): void {
    if (this.destroyed || this.activeRequestId !== requestId) {
      return;
    }
    this.completeProgressMessages();
    this.settleAgentActivity('failed');
    this.clearTimers();
    this.clearStatusMessage();
    this.clearMutationRepeatDraft(requestId);
    this.pendingContextUpdate = undefined;
    this.confirmedMutationRequests.cancel(requestId);
    this.activeRequestId = undefined;
    this.clearToolCallTracking();
    this.state.messages.push({
      role: 'error',
      content: 'The approved action outcome is unknown and will not be retried automatically.'
    });
    this.state.pending = false;
    this.state.reconciliationRequired = true;
    this.state.currentStatus = 'Review needed';
    this.focusPrompt();
    this.scrollToBottom();
  }

  protected connectAndPublishWhenReady(
    requestId: string,
    prompt: string,
    attachments: AiChatAttachment[],
    mutationConfirmation?: AiMutationConfirmationAuthorization
  ): void {
    this.transportService.publishWhenConnected({
      active: () => this.state.pending && this.activeRequestId === requestId,
      onReconnectStatus: (attempt, maxAttempts) => {
        this.showStatus(`Reconnecting... (${attempt}/${maxAttempts})`, true);
        this.scrollToBottom();
      },
      onConnected: () => {
        this.showStatus('Connected. Sending request.', true);
        this.scrollToBottom();
      },
      publish: () => this.publishChatRequest(
        requestId, prompt, attachments, mutationConfirmation
      ),
      onReconnectFailure: () => this.failStompReconnect(),
      onPublishError: () => this.failChatPublish()
    });
  }

  protected clearResponseTimeout(): void {
    if (this.responseTimeout !== undefined) {
      window.clearTimeout(this.responseTimeout);
      this.responseTimeout = undefined;
    }
  }

  protected failStompReconnect(): void {
    if (!this.state.pending) {
      return;
    }
    this.completeProgressMessages();
    this.settleAgentActivity('failed');
    this.clearStatusMessage();
    this.state.messages.push({
      role: 'error',
      content: 'Could not reconnect to WebSocket after 3 attempts. Check that score-http is running and the /ws proxy is active, then try again.'
    });
    this.state.pending = false;
    this.pendingContextUpdate = undefined;
    this.clearMutationRepeatDraft(this.activeRequestId);
    this.activeRequestId = undefined;
    this.clearToolCallTracking();
    this.state.currentStatus = 'Error';
    this.requestSubscription?.unsubscribe();
    this.transportService.cancelReconnect();
    this.focusPrompt();
    this.scrollToBottom();
  }

  protected publishChatRequest(
    requestId: string,
    prompt: string,
    attachments: AiChatAttachment[],
    mutationConfirmation?: AiMutationConfirmationAuthorization
  ): void {
    if (!this.state.pending || this.activeRequestId !== requestId) {
      return;
    }
    const contextUpdate = this.nextContextUpdate();
    this.transportService.publish('/app/ai/chat', {
      requestId,
      prompt,
      conversationId: this.state.conversationId,
      modelName: this.state.selectedModelName,
      reasoningEffort: this.state.selectedReasoningEffort,
      runtime: this.state.selectedRuntime,
      runtimeOptions: {...this.state.selectedRuntimeOptions},
      permissionMode: this.state.permissionMode,
      pageContext: contextUpdate.pageContext,
      attachments,
      ...(mutationConfirmation ? {mutationConfirmation} : {})
    });
    this.activeRequestPublished = true;
    this.pendingContextUpdate = contextUpdate;
    this.responseTimeout = window.setTimeout(() => {
      if (this.state.pending) {
        this.showStatus('Request sent. Waiting for the assistant response.', true);
        this.scrollToBottom();
      }
    }, 5000);
    this.acknowledgementTimeout = window.setTimeout(() => {
      if (this.state.pending) {
        this.completeProgressMessages();
        this.settleAgentActivity('failed');
        this.clearStatusMessage();
        this.state.messages.push({
          role: 'error',
          content: 'The request was sent, but the backend did not acknowledge it. If this included an attachment, the WebSocket message may be too large or the backend may need to be restarted.'
        });
        this.state.pending = false;
        this.pendingContextUpdate = undefined;
        this.clearMutationRepeatDraft(this.activeRequestId);
        this.activeRequestId = undefined;
        this.clearToolCallTracking();
        this.state.currentStatus = 'Error';
        this.requestSubscription?.unsubscribe();
        this.transportService.cancelReconnect();
        this.focusPrompt();
        this.scrollToBottom();
      }
    }, 15000);
  }

  protected failChatPublish(): void {
    this.completeProgressMessages();
    this.settleAgentActivity('failed');
    this.clearStatusMessage();
    this.state.messages.push({role: 'error', content: 'Could not send the WebSocket chat request.'});
    this.state.pending = false;
    this.pendingContextUpdate = undefined;
    this.clearMutationRepeatDraft(this.activeRequestId);
    this.activeRequestId = undefined;
    this.clearToolCallTracking();
    this.requestSubscription?.unsubscribe();
    this.focusPrompt();
    this.scrollToBottom();
  }

}
