/**
 * Dispatches chat requests and reconciles their REST and socket response paths.
 */

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
import {AiRequestDispatchIntent} from './domain/ai-request-dispatch-coordinator';
import {
  admitsRestLiveSideChannel,
  restReplayDisposition
} from './domain/ai-chat-event-admission';

export abstract class AiChatPanelRequestController extends AiChatPanelControllerBase {
  protected startChatRequest(prompt: string, attachments: AiChatAttachment[]): void {
    this.invalidateDraftAttachmentRestore();
    this.restoreChatScrollPending = false;
    const requestId = this.createRequestId();
    this.prepareRequestDispatch(requestId, prompt, attachments, {
      kind: 'normal'
    });
    const destination = '/user/queue/ai/chat/' + requestId;

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

  protected prepareRequestDispatch(
    requestId: string, prompt: string, attachments: AiChatAttachment[],
    intent: AiRequestDispatchIntent
  ): void {
    this.requestDispatch.prepare(this.state, {
      requestId, prompt, attachments, intent
    }, {
      activate: activeRequestId => {
        this.activeRequestId = activeRequestId;
        this.activeRequestPublished = false;
      },
      beginChangeRepeat: (id, value, files) =>
        this.beginChangeRepeatDraft(id, value, files),
      clearToolTracking: () => this.clearToolCallTracking(),
      resizePrompt: () => this.resizePromptInput(),
      flushWorkspace: () => this.flushWorkspacePersistence(),
      userMessageContent: (value, files) =>
        this.attachmentService.userMessageContent(value, files)
    });
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
        const disposition = restReplayDisposition(event);
        if (disposition === 'socket') {
          this.handleSocketEvent(event);
        } else if (disposition === 'confirmation') {
          this.handleChangeConfirmationNotice(event);
        } else if (disposition === 'system') {
          this.handleSystemEvent(event);
        } else if (disposition === 'tool') {
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
      if (admitsRestLiveSideChannel(event)) {
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
        this.transitionActiveRequest({
          agentStatus: 'completed', reconciliationRequired: false,
          cancellation: 'reset', completedPayload: 'clear', toolGroups: 'preserve',
          confirmedChange: {kind: 'preserve'}, changeRepeat: {kind: 'preserve'}
        }, () => {
          this.state.conversationId = response.conversationId || this.state.conversationId;
          this.sessionPersistence.rememberLastConversation(this.state.conversationId);
          replayRestEvents(response.events || []);
          // Provider error/retry frames are transient recovery state. A successful
          // canonical response settles them even when replayed from REST.
          this.clearProviderRecoveryState();
        });
        if (response.progress?.length && this.state.debugEnabled) {
          response.progress.forEach(progress => this.state.messages.push({role: 'progress', content: progress}));
        }
        if (response.response) {
          this.commitAssistantMessage(requestId, response.response, response.files);
        }
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
        this.transitionActiveRequest({
          agentStatus: 'failed', reconciliationRequired: false,
          cancellation: 'preserve', completedPayload: 'preserve', toolGroups: 'preserve',
          confirmedChange: {kind: 'preserve'},
          changeRepeat: confirmationConversationId
            ? {kind: 'preserve'} : {kind: 'clear-request', requestId}
        });
        this.state.messages.push({
          role: 'error',
          content: this.attachmentFailureMessage(error)
        });
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
    this.transitionActiveRequest({
      agentStatus: 'failed', reconciliationRequired: true,
      cancellation: 'preserve', completedPayload: 'preserve', toolGroups: 'preserve',
      confirmedChange: {kind: 'cancel', requestId},
      changeRepeat: {kind: 'clear-request', requestId}
    });
    this.state.messages.push({
      role: 'error',
      content: 'The approved change outcome is unknown and will not be retried automatically.'
    });
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
    const requestId = this.activeRequestId;
    this.transitionActiveRequest({
      agentStatus: 'failed', reconciliationRequired: false,
      cancellation: 'preserve', completedPayload: 'preserve', toolGroups: 'preserve',
      confirmedChange: {kind: 'preserve'},
      changeRepeat: requestId
        ? {kind: 'clear-request', requestId} : {kind: 'clear-all'}
    });
    this.state.messages.push({
      role: 'error',
      content: 'Could not reconnect to WebSocket after 3 attempts. Check that score-http is running and the /ws proxy is active, then try again.'
    });
    this.state.currentStatus = 'Error';
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
        const activeRequestId = this.activeRequestId;
        this.transitionActiveRequest({
          agentStatus: 'failed', reconciliationRequired: false,
          cancellation: 'preserve', completedPayload: 'preserve', toolGroups: 'preserve',
          confirmedChange: {kind: 'preserve'},
          changeRepeat: activeRequestId
            ? {kind: 'clear-request', requestId: activeRequestId} : {kind: 'clear-all'}
        });
        this.state.messages.push({
          role: 'error',
          content: 'The request was sent, but the backend did not acknowledge it. If this included an attachment, the WebSocket message may be too large or the backend may need to be restarted.'
        });
        this.state.currentStatus = 'Error';
        this.transportService.cancelReconnect();
        this.focusPrompt();
        this.scrollToBottom();
      }
    }, 15000);
  }

  protected failChatPublish(): void {
    const requestId = this.activeRequestId;
    this.transitionActiveRequest({
      agentStatus: 'failed', reconciliationRequired: false,
      cancellation: 'preserve', completedPayload: 'preserve', toolGroups: 'preserve',
      confirmedChange: {kind: 'preserve'},
      changeRepeat: requestId
        ? {kind: 'clear-request', requestId} : {kind: 'clear-all'}
    });
    this.state.messages.push({role: 'error', content: 'Could not send the WebSocket chat request.'});
    this.focusPrompt();
    this.scrollToBottom();
  }

}
