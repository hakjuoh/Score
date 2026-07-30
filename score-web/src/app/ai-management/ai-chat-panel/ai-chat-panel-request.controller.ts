import {HttpErrorResponse} from '@angular/common/http';
import {Message} from '@stomp/stompjs';
import {take, timeout} from 'rxjs/operators';
import {AiChatPanelControllerBase} from './ai-chat-panel.controller-base';
import {SAFE_ATTACHMENT_ERROR_PATTERNS} from './domain/ai-chat-panel-display.constants';
import {isBoundConfirmedChatResponse} from './domain/ai-change-confirmation';
import {CONFIRMED_CHANGE_RESPONSE_TIMEOUT_MS} from './domain/ai-chat-panel.constants';
import {
  AiChatAttachment,
  AiChatContextUpdate,
  AiChatSocketEvent,
  AiChangeConfirmationAuthorization
} from './domain/ai-chat-panel.model';
import {
  isExecutionActivityEvent,
  isSpecialistActivityEvent,
  isSpecialistToolEvent
} from './domain/ai-agent-activity';

const CHANGE_APPROVAL_EVENT_SUBTYPES = new Set([
  'change_approval_batch_required',
  'change_approval_decision_accepted',
  'change_approval_decision_rejected'
]);

function isChangeApprovalInteractionEvent(event: AiChatSocketEvent): boolean {
  return event.type === 'system' && !!event.subtype
    && CHANGE_APPROVAL_EVENT_SUBTYPES.has(event.subtype);
}

export abstract class AiChatPanelRequestController extends AiChatPanelControllerBase {
  protected startChatRequest(prompt: string, attachments: AiChatAttachment[]): void {
    this.invalidateDraftAttachmentRestore();
    this.restoreChatScrollPending = false;
    this.state.activePanelTab = 'chat';
    const requestId = this.createRequestId();
    this.beginChangeRepeatDraft(requestId, prompt, attachments);
    this.activeRequestId = requestId;
    this.activeRequestPublished = false;
    this.clearToolCallTracking();
    this.state.resetAgentActivity();
    const destination = '/user/queue/ai/chat/' + requestId;
    this.state.pending = true;
    this.state.currentStatus = 'Sending request';
    this.state.prompt = '';
    this.state.attachments = [];
    this.flushWorkspacePersistence();
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
    changeConfirmation?: AiChangeConfirmationAuthorization
  ): void {
    const requestId = this.activeRequestId || this.createRequestId();
    const contextUpdate = this.nextContextUpdate();
    const confirmedChange = changeConfirmation !== undefined;
    const confirmedConversationId = confirmedChange
      ? this.state.conversationId : undefined;
    const liveRestEventKeys = new Set<string>();
    const restEventKey = (event: AiChatSocketEvent): string | undefined =>
      Number.isSafeInteger(event.sequence) && (event.sequence || 0) > 0
        ? `${event.requestId}:${event.sequence}:${event.type}:${event.subtype || ''}`
        : undefined;
    const replayRestEvents = (events: AiChatSocketEvent[]): void => {
      events.forEach(event => {
        const key = restEventKey(event);
        if (key && liveRestEventKeys.has(key)) {
          return;
        }
        if (isChangeApprovalInteractionEvent(event)) {
          this.handleSocketEvent(event);
        } else if (event.type === 'system'
          && event.subtype === 'change_confirmation_required') {
          this.handleChangeConfirmationNotice(event);
        } else if (event.type === 'system'
          && (event.subtype === 'context_usage' || event.subtype === 'context_compacted')) {
          this.handleSystemEvent(event);
        } else if (isExecutionActivityEvent(event)) {
          this.handleSystemEvent(event);
        } else if (event.type === 'system' && event.subtype === 'guide') {
          this.handleSystemEvent(event);
        } else if (event.type === 'system' && event.subtype === 'workflow_result') {
          this.handleSystemEvent(event);
        } else if (event.type === 'system' && event.subtype === 'provider_error') {
          this.handleSystemEvent(event);
        } else if (event.type === 'system' && event.subtype === 'provider_retry') {
          this.handleSystemEvent(event);
        } else if (event.type === 'tool_call' || event.type === 'tool_group') {
          // Replayed specialist activity belongs in its agent timeline;
          // lead activity uses the same structured row path as WebSocket chat.
          if (!this.divertSpecialistToolEvent(event)) {
            this.handleSocketEvent(event);
          }
        }
      });
    };
    const errorEvents = (error: unknown): AiChatSocketEvent[] => {
      if (!(error instanceof HttpErrorResponse)
        || typeof error.error !== 'object' || error.error === null) {
        return [];
      }
      const events = (error.error as {events?: unknown}).events;
      return Array.isArray(events)
        ? events.filter((event): event is AiChatSocketEvent =>
          typeof event === 'object' && event !== null)
        : [];
    };
    const liveInteractionSubscription = this.transportService.watch(
      '/user/queue/ai/chat/' + requestId
    ).subscribe((message: Message) => {
      const event = JSON.parse(message.body) as AiChatSocketEvent;
      if (isExecutionActivityEvent(event)
        || isSpecialistToolEvent(event)
        || event.type === 'tool_call' || event.type === 'tool_group'
        || event.type === 'system' && event.subtype === 'guide'
        || event.type === 'system' && event.subtype === 'workflow_result'
        || event.type === 'system' && (event.subtype === 'provider_error'
          || event.subtype === 'provider_retry')
        || isChangeApprovalInteractionEvent(event)
        || event.type === 'system' && (event.subtype === 'elicitation_required'
        || event.subtype === 'elicitation_decision_accepted'
        || event.subtype === 'elicitation_decision_rejected')) {
        const key = restEventKey(event);
        if (key) liveRestEventKeys.add(key);
        this.handleSocketEvent(event);
      }
    });
    this.showStatus(confirmedChange
      ? 'Sending approved change request.' : 'Uploading attachment request.', true);
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
      permissionMode: this.state.permissionMode,
      pageContext: contextUpdate.pageContext,
      routeManifest: contextUpdate.routeManifest,
      attachments,
      ...(changeConfirmation ? {changeConfirmation} : {})
    });
    const boundedResponse = confirmedChange
      ? response.pipe(
        timeout(CONFIRMED_CHANGE_RESPONSE_TIMEOUT_MS),
        take(1)
      )
      : response;
    const subscription = boundedResponse.subscribe({
      next: response => {
        if (this.destroyed || this.activeRequestId !== requestId) {
          return;
        }
        if (confirmedChange
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
        replayRestEvents(response.events || []);
        // Provider error/retry frames are transient recovery state. A successful
        // canonical response settles them even when they were replayed from REST.
        this.clearProviderRecoveryState();
        this.settleAgentActivity('completed');
        this.state.elicitation = undefined;
        this.state.elicitationBusy = false;
        this.clearChangeApprovalBatch();
        this.activeRequestId = undefined;
        this.clearToolCallTracking();
        if (response.progress?.length && this.state.debugEnabled) {
          response.progress.forEach(progress => this.state.messages.push({role: 'progress', content: progress}));
        }
        if (response.response) {
          this.commitAssistantMessage(requestId, response.response, response.files);
        }
        this.state.pending = false;
        this.state.reconciliationRequired = false;
        this.state.currentStatus = response.continuationRequired ? 'More processing is needed' : 'Ready';
        if (this.runDeferredNewChat()) {
          return;
        }
        this.finishChangeRepeatOpportunity(
          requestId, response.conversationId || this.state.conversationId
        );
        this.loadConversationHistory();
        if (!this.changeDecisionOpen) {
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
        if (confirmedChange) {
          this.completeUnknownConfirmedRequest(requestId);
          return;
        }
        replayRestEvents(errorEvents(error));
        const confirmationConversationId =
          this.pendingChangeConfirmation?.conversationId;
        this.completeProgressMessages();
        this.settleAgentActivity('failed');
        this.clearTimers();
        this.clearStatusMessage();
        if (!confirmationConversationId) {
          this.clearChangeRepeatDraft(requestId);
        }
        this.state.elicitation = undefined;
        this.state.elicitationBusy = false;
        this.clearChangeApprovalBatch();
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
        this.finishChangeRepeatOpportunity(
          requestId, confirmationConversationId
        );
        if (!this.changeDecisionOpen) {
          this.focusPrompt();
        }
        this.scrollToBottom();
      }
    });
    subscription.add(liveInteractionSubscription);
    // Do not retain a confirmed request subscription on the component: the
    // HTTP pipeline owns the callback-local grant until this single attempt
    // ends. Cancellation still uses its durable request identity.
    if (!confirmedChange) {
      this.requestSubscription = subscription;
    } else {
      const unregisterRequest = this.confirmedChangeRequests.register(
        requestId, () => subscription.unsubscribe()
      );
      const unregister = this.destroyRef.onDestroy(
        () => this.confirmedChangeRequests.cancel(requestId)
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

  protected completeUnknownConfirmedRequest(requestId: string): void {
    if (this.destroyed || this.activeRequestId !== requestId) {
      return;
    }
    this.completeProgressMessages();
    this.settleAgentActivity('failed');
    this.clearTimers();
    this.clearStatusMessage();
    this.state.elicitation = undefined;
    this.state.elicitationBusy = false;
    this.clearChangeApprovalBatch();
    this.clearChangeRepeatDraft(requestId);
    this.confirmedChangeRequests.cancel(requestId);
    this.activeRequestId = undefined;
    this.clearToolCallTracking();
    this.state.messages.push({
      role: 'error',
      content: 'The approved change outcome is unknown and will not be retried automatically.'
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
    changeConfirmation?: AiChangeConfirmationAuthorization
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
        requestId, prompt, attachments, changeConfirmation
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
    this.clearChangeRepeatDraft(this.activeRequestId);
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
    changeConfirmation?: AiChangeConfirmationAuthorization
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
      permissionMode: this.state.permissionMode,
      pageContext: contextUpdate.pageContext,
      routeManifest: contextUpdate.routeManifest,
      attachments,
      ...(changeConfirmation ? {changeConfirmation} : {})
    });
    this.activeRequestPublished = true;
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
        this.clearChangeRepeatDraft(this.activeRequestId);
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
    this.clearChangeRepeatDraft(this.activeRequestId);
    this.activeRequestId = undefined;
    this.clearToolCallTracking();
    this.requestSubscription?.unsubscribe();
    this.focusPrompt();
    this.scrollToBottom();
  }

}
