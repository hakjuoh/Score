import {DestroyRef} from '@angular/core';
import {HttpErrorResponse, HttpHeaders} from '@angular/common/http';
import {TestBed} from '@angular/core/testing';
import {DomSanitizer} from '@angular/platform-browser';
import {MatDialog} from '@angular/material/dialog';
import {MatSnackBar} from '@angular/material/snack-bar';
import {NEVER, Subject, Subscription, of, throwError} from 'rxjs';
import {AuthService} from '../../authentication/auth.service';
import {WebPageInfoService} from '../../basis/basis.service';
import {ConfirmDialogService} from '../../common/confirm-dialog/confirm-dialog.service';
import {
  AI_CHAT_LAST_CONVERSATION_STORAGE_KEY_PREFIX,
  AI_CHAT_PANEL_DOCK_STORAGE_KEY_PREFIX,
  AI_CHAT_PANEL_VISIBILITY_STORAGE_KEY_PREFIX,
  AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY,
  AI_CHAT_WINDOW_MODE_STORAGE_KEY_PREFIX,
  AI_CHAT_WORKSPACE_STORAGE_KEY_PREFIX,
  AiChatPanelComponent
} from './ai-chat-panel.component';
import {AiContextBudgetDialogComponent} from './ai-context-budget-dialog.component';
import {AiChatApiService} from './domain/ai-chat-api.service';
import {AiActiveRequestRecoveryService} from './domain/ai-active-request-recovery.service';
import {AiChatAttachmentQueueService} from './domain/ai-chat-attachment-queue.service';
import {AiChatAttachmentService} from './domain/ai-chat-attachment.service';
import {AiChatCancellationService} from './domain/ai-chat-cancellation.service';
import {AiChatCommandService} from './domain/ai-chat-command.service';
import {AiChatContextService} from './domain/ai-chat-context.service';
import {AiConfirmedChangeRequestCoordinator} from './domain/ai-confirmed-change-request-coordinator';
import {AiConversationRestoreService} from './domain/ai-conversation-restore.service';
import {AiChatNavigationService} from './domain/ai-chat-navigation.service';
import {AiChatPanelLayoutService} from './domain/ai-chat-panel-layout.service';
import {AiChatMessageTrackerService} from './domain/ai-chat-message-tracker.service';
import {AiChatPanelViewportService} from './domain/ai-chat-panel-viewport.service';
import {AiChatSettingsService} from './domain/ai-chat-settings.service';
import {AiChatTransportService} from './domain/ai-chat-transport.service';
import {AiChatWindowCoordinatorService} from './domain/ai-chat-window-coordinator.service';
import {AiChangeInteractionService} from './domain/ai-change-interaction.service';
import {
  AiCancellationResponse,
  AiPublicExecutionRequestStatus
} from './domain/ai-chat-panel.model';

export {
  AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY,
  HttpErrorResponse,
  HttpHeaders,
  NEVER,
  Subject,
  Subscription,
  of,
  throwError,
  AiContextBudgetDialogComponent,
  AiChatCommandService,
  AiConversationRestoreService
};
export {
  CANCELLATION_ADMISSION_RETRY_MS,
  CANCELLATION_ACK_TIMEOUT_MS,
  CANCELLATION_TERMINAL_TIMEOUT_MS,
  ACTIVE_REQUEST_RECOVERY_RETRY_DELAY_MS,
  COMPLETED_PAYLOAD_WAIT_MS,
  CHANGE_CONFIRMATION_DECISION_TIMEOUT_MS,
  REQUEST_STATUS_WATCHDOG_MS
} from './domain/ai-chat-panel.constants';
export type {
  AiCancellationResponse,
  AiChatConversationDetails,
  AiChatConversationSummary,
  AiChatRestResponse,
  AiPublicExecutionRequestStatus
} from './domain/ai-chat-panel.model';

export interface AiChatPanelApiMock {
  sendChat: ReturnType<typeof vi.fn>;
  cancelRequest: ReturnType<typeof vi.fn>;
  getRequestStatus: ReturnType<typeof vi.fn>;
  getActiveRequest: ReturnType<typeof vi.fn>;
  getConversationHistory: ReturnType<typeof vi.fn>;
  getAvailableModels: ReturnType<typeof vi.fn>;
  getMcpStatus: ReturnType<typeof vi.fn>;
  updateConversationModel: ReturnType<typeof vi.fn>;
  getConversation: ReturnType<typeof vi.fn>;
  deleteConversation: ReturnType<typeof vi.fn>;
  decideChangeConfirmation: ReturnType<typeof vi.fn>;
}

export let component: AiChatPanelComponent;
export let api: AiChatPanelApiMock;
export let cancellationService: AiChatCancellationService;
export let attachmentService: {
  userMessageContent: ReturnType<typeof vi.fn>;
  attachmentMediaType: ReturnType<typeof vi.fn>;
  isSupportedAttachment: ReturnType<typeof vi.fn>;
  readAttachment: ReturnType<typeof vi.fn>;
};
export let navigation: {handleDataChanged: ReturnType<typeof vi.fn>};
export let transport: {
  watch: ReturnType<typeof vi.fn>;
  publish: ReturnType<typeof vi.fn>;
  publishWhenConnected: ReturnType<typeof vi.fn>;
  reconnectOnce: ReturnType<typeof vi.fn>;
  cancelReconnect: ReturnType<typeof vi.fn>;
};
export let snackBar: {open: ReturnType<typeof vi.fn>};
export let dialog: {open: ReturnType<typeof vi.fn>};
export let confirmDialog: {
  newConfig: ReturnType<typeof vi.fn>;
  open: ReturnType<typeof vi.fn>;
};
export let windowCoordinator: {
  popoutMode: boolean;
  connect: ReturnType<typeof vi.fn>;
  openPopout: ReturnType<typeof vi.fn>;
  focusPopout: ReturnType<typeof vi.fn>;
  closePopoutForReattach: ReturnType<typeof vi.fn>;
  requestReattach: ReturnType<typeof vi.fn>;
  notifyAssistantClosed: ReturnType<typeof vi.fn>;
  destroy: ReturnType<typeof vi.fn>;
};

const defaultTestUsername = 'test_eu';
let currentUsername = defaultTestUsername;
let destroyCallbacks: Set<() => void>;

export function setupAiChatPanelSpec(): void {
  currentUsername = defaultTestUsername;
  localStorage.removeItem(AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY);
  localStorage.removeItem(lastConversationStorageKey());
  localStorage.removeItem(panelDockStorageKey());
  localStorage.removeItem(panelVisibilityStorageKey());
  localStorage.removeItem(windowModeStorageKey());
  localStorage.removeItem(workspaceStorageKey());
  api = {
    sendChat: vi.fn(() => NEVER),
    cancelRequest: vi.fn(() => NEVER),
    getRequestStatus: vi.fn(() => NEVER),
    getActiveRequest: vi.fn(() => of(null)),
    getConversationHistory: vi.fn(() => of([])),
    getAvailableModels: vi.fn(() => of([
      {name: 'claude-fable-5', displayName: 'Claude Fable 5', description: 'Claude model.',
        provider: 'azure-foundry', defaultModel: true,
        defaultReasoningEffort: 'high', reasoningEfforts: [
          {name: 'low', displayName: 'Low', description: 'Fast responses.'},
          {name: 'medium', displayName: 'Medium', description: 'Balanced reasoning.'},
          {name: 'high', displayName: 'High', description: 'Greater reasoning.'}
        ]},
      {name: 'gpt-5_6-sol', displayName: 'GPT-5.6 SOL', description: 'GPT model.',
        provider: 'azure-openai', defaultModel: false,
        defaultReasoningEffort: 'medium', reasoningEfforts: [
          {name: 'low', displayName: 'Low', description: 'Fast responses.'},
          {name: 'medium', displayName: 'Medium', description: 'Balanced reasoning.'},
          {name: 'high', displayName: 'High', description: 'Greater reasoning.'}
        ]}
    ])),
    getMcpStatus: vi.fn(() => of({
      servers: [{name: 'connect-center-mcp', status: 'CONNECTED', toolCount: 12}]
    })),
    updateConversationModel: vi.fn(() => NEVER),
    getConversation: vi.fn(() => NEVER),
    deleteConversation: vi.fn(() => of({deleted: true})),
    decideChangeConfirmation: vi.fn(() => NEVER)
  };
  snackBar = {open: vi.fn()};
  dialog = {open: vi.fn()};
  confirmDialog = {
    newConfig: vi.fn(() => ({data: {}})),
    open: vi.fn(() => ({afterClosed: () => of(false)}))
  };
  destroyCallbacks = new Set();
  transport = {
    watch: vi.fn(() => NEVER),
    publish: vi.fn(),
    publishWhenConnected: vi.fn(),
    reconnectOnce: vi.fn(() => NEVER),
    cancelReconnect: vi.fn()
  };
  navigation = {handleDataChanged: vi.fn()};
  windowCoordinator = {
    popoutMode: false,
    connect: vi.fn(),
    openPopout: vi.fn(() => true),
    focusPopout: vi.fn(),
    closePopoutForReattach: vi.fn(),
    requestReattach: vi.fn(),
    notifyAssistantClosed: vi.fn(),
    destroy: vi.fn()
  };
  attachmentService = {
    userMessageContent: vi.fn((prompt: string) => prompt),
    attachmentMediaType: vi.fn(() => 'text/plain'),
    isSupportedAttachment: vi.fn(() => true),
    readAttachment: vi.fn()
  };
  TestBed.configureTestingModule({
    providers: [
      {provide: AiChatApiService, useValue: api},
      AiActiveRequestRecoveryService,
      AiChatAttachmentQueueService,
      {provide: AiChatAttachmentService, useValue: attachmentService},
      {provide: AiChatCommandService, useValue: {decide: () => ({kind: 'none'}), suggestions: () => []}},
      AiConfirmedChangeRequestCoordinator,
      {provide: AiChatContextService, useValue: {
        nextContextUpdate: () => ({routeManifest: {schemaVersion: 1, routes: []}})
      }},
      {provide: AiConversationRestoreService, useValue: {
        reset: vi.fn(), cancel: vi.fn(), expectAttempt: vi.fn(),
        isRestoreEvent: () => false, isLegacyRestoreAdmission: () => false,
        projectStoredMessage: (message: any) => ({role: message.role, content: message.content}),
        projectStoredMessages: (messages: any[]) => messages.map(message => ({
          role: message.role, content: message.content
        }))
      }},
      {provide: AiChatNavigationService, useValue: navigation},
      {provide: AiChatPanelLayoutService, useValue: {
        clearMainPanelInset: vi.fn(), updateMainPanelInset: vi.fn(),
        clamp: (value: number, minimum: number, maximum: number) =>
          Math.min(maximum, Math.max(minimum, value))
      }},
      AiChatMessageTrackerService,
      AiChangeInteractionService,
      AiChatPanelViewportService,
      AiChatSettingsService,
      {provide: AiChatTransportService, useValue: transport},
      {provide: AiChatWindowCoordinatorService, useValue: windowCoordinator},
      {provide: DomSanitizer, useValue: {bypassSecurityTrustHtml: (value: string) => value}},
      {provide: WebPageInfoService, useValue: {brand: undefined}},
      {provide: AuthService, useValue: {getUserToken: () => ({username: currentUsername})}},
      {provide: DestroyRef, useValue: {
        onDestroy: (callback: () => void) => {
          destroyCallbacks.add(callback);
          return () => destroyCallbacks.delete(callback);
        }
      }},
      {provide: MatSnackBar, useValue: snackBar},
      {provide: MatDialog, useValue: dialog},
      {provide: ConfirmDialogService, useValue: confirmDialog}
    ]
  });
  component = TestBed.runInInjectionContext(() => new AiChatPanelComponent());
  (component as any).destroyRef = {
    onDestroy: (callback: () => void) => {
      destroyCallbacks.add(callback);
      return () => destroyCallbacks.delete(callback);
    }
  };
  cancellationService = TestBed.inject(AiChatCancellationService);
  vi.spyOn(cancellationService as any, 'createCancellationRequestId').mockReturnValue('cancel-1');
  vi.spyOn(component as any, 'createRequestId').mockReturnValue('request-1');
}

export function teardownAiChatPanelSpec(): void {
  destroyComponent();
  localStorage.removeItem(lastConversationStorageKey());
  localStorage.removeItem(lastConversationStorageKey(defaultTestUsername));
  localStorage.removeItem(panelDockStorageKey());
  localStorage.removeItem(panelDockStorageKey(defaultTestUsername));
  localStorage.removeItem(panelVisibilityStorageKey());
  localStorage.removeItem(panelVisibilityStorageKey(defaultTestUsername));
  localStorage.removeItem(windowModeStorageKey());
  localStorage.removeItem(windowModeStorageKey(defaultTestUsername));
  localStorage.removeItem(workspaceStorageKey());
  localStorage.removeItem(workspaceStorageKey(defaultTestUsername));
  vi.useRealTimers();
}

export function setCurrentUsername(username: string): void {
  currentUsername = username;
}

export function cancellationResponse(): AiCancellationResponse {
  return {
    requestId: 'request-1',
    conversationId: 'conversation-1',
    generation: 7,
    cancellationRequestId: 'cancel-1',
    effectiveCancellationRequestId: 'cancel-1',
    disposition: 'ACKNOWLEDGED',
    status: 'CANCELLING',
    acknowledged: true,
    terminal: false,
    lifecycleEventSequence: 3
  };
}

export function startChangeConfirmation(status: 'REQUESTED' | 'APPROVED' = 'REQUESTED'): void {
  component.state.conversationId = 'conversation-1';
  component.state.prompt = 'Create the same item again';
  component.send();
  transport.publishWhenConnected.mock.calls[0][0].publish();
  sendChangeConfirmationNotice({metadata: {
    confirmationRequestId: 'confirmation-1',
    status,
    expiresAt: '2099-07-15T00:00:00Z',
    toolName: 'create_business_context',
    argumentsSummary: '{"name":"Example"}'
  }});
}

export function sendChangeConfirmationNotice(override: Record<string, unknown> = {}): void {
  (component as any).handleSocketEvent({
    ...changeConfirmationEvent('request-1'),
    ...override
  });
}

export function changeConfirmationEvent(requestId: string): any {
  return {
    requestId,
    conversationId: 'conversation-1',
    type: 'system',
    subtype: 'change_confirmation_required',
    message: 'A change requires explicit approval.',
    continuationRequired: false,
    progress: [],
    ids: [],
    turnId: requestId,
    sequence: 4,
    visibility: 'visible',
    content: 'A change requires explicit approval.',
    metadata: {
      confirmationRequestId: 'confirmation-1',
      status: 'REQUESTED',
      expiresAt: '2099-07-15T00:00:00Z',
      toolName: 'create_business_context',
      argumentsSummary: '{"name":"Example"}'
    }
  };
}

export function finishChangeConfirmationRequest(): void {
  (component as any).handleSocketEvent({
    requestId: 'request-1',
    conversationId: 'conversation-1',
    type: 'assistant_final',
    content: 'The original change already completed.'
  });
}

export function storageText(storage: Storage): string {
  const values: string[] = [];
  for (let index = 0; index < storage.length; index++) {
    const key = storage.key(index);
    if (key) {
      values.push(key, storage.getItem(key) || '');
    }
  }
  return values.join('\n');
}

export function lastConversationStorageKey(username = currentUsername): string {
  return `${AI_CHAT_LAST_CONVERSATION_STORAGE_KEY_PREFIX}:${encodeURIComponent(username)}`;
}

export function panelVisibilityStorageKey(username = currentUsername): string {
  return `${AI_CHAT_PANEL_VISIBILITY_STORAGE_KEY_PREFIX}:${encodeURIComponent(username)}`;
}

export function panelDockStorageKey(username = currentUsername): string {
  return `${AI_CHAT_PANEL_DOCK_STORAGE_KEY_PREFIX}:${encodeURIComponent(username)}`;
}

export function windowModeStorageKey(username = currentUsername): string {
  return `${AI_CHAT_WINDOW_MODE_STORAGE_KEY_PREFIX}:${encodeURIComponent(username)}`;
}

export function workspaceStorageKey(username = currentUsername): string {
  return `${AI_CHAT_WORKSPACE_STORAGE_KEY_PREFIX}:${encodeURIComponent(username)}`;
}

export function consumedDecisionResponse() {
  return {
    confirmationRequestId: 'confirmation-1',
    conversationId: 'conversation-1',
    status: 'CONSUMED',
    disposition: 'CONSUMED',
    expiresAt: '2099-07-15T00:00:00Z',
    approvedAt: '2099-07-14T00:30:00Z',
    consumedAt: '2099-07-14T01:00:00Z'
  };
}

export function destroyComponent(): void {
  component.ngOnDestroy();
  const callbacks = [...destroyCallbacks];
  destroyCallbacks.clear();
  callbacks.forEach(callback => callback());
}

export function completedCancellationResponse(): AiCancellationResponse {
  return {
    ...cancellationResponse(),
    disposition: 'ALREADY_TERMINAL',
    status: 'COMPLETED',
    acknowledged: false,
    terminal: true,
    lifecycleEventSequence: 4,
    terminalAt: '2026-07-14T13:00:04Z'
  };
}

export function publicStatus(
  status: AiPublicExecutionRequestStatus['status']
): AiPublicExecutionRequestStatus {
  return {
    conversationId: 'conversation-1',
    requestId: 'request-1',
    generation: 7,
    status,
    deadline: '2026-07-14T13:05:00Z',
    retryCount: 0,
    createdAt: '2026-07-14T13:00:00Z',
    updatedAt: '2026-07-14T13:00:05Z',
    cancellationRequestId: 'cancel-1',
    cancellationAcknowledgedAt: '2026-07-14T13:00:01Z',
    lastEventSequence: 4,
    version: 2
  };
}
