import {
  Component,
  DoCheck,
  ElementRef,
  HostListener,
  OnDestroy,
  OnInit,
  ViewChild
} from '@angular/core';
import {AiChatComposerComponent} from './ai-chat-composer.component';
import {AiChatPanelLifecycleController} from './ai-chat-panel-lifecycle.controller';
import {AiChatAttachmentQueueService} from './domain/ai-chat-attachment-queue.service';
import {AiActiveRequestRecoveryService} from './domain/ai-active-request-recovery.service';
import {AiConfirmedChangeRequestCoordinator} from './domain/ai-confirmed-change-request-coordinator';
import {AiChangeRepeatCoordinator} from './domain/ai-change-repeat-coordinator';
import {AiRequestDispatchCoordinator} from './domain/ai-request-dispatch-coordinator';
import {AiChangeApprovalBatchCoordinator} from './domain/ai-change-approval-batch-coordinator';
import {AiElicitationCoordinator} from './domain/ai-elicitation-coordinator';
import {AiRequestTerminalCoordinator} from './domain/ai-request-terminal-coordinator';
import {AiConversationRestoreService} from './domain/ai-conversation-restore.service';
import {AiConversationProjector} from './domain/ai-conversation-projector';
import {AiConversationRestoreRenderer} from './domain/ai-conversation-restore-renderer';
import {AiChatMessageTrackerService} from './domain/ai-chat-message-tracker.service';
import {AiChangeInteractionService} from './domain/ai-change-interaction.service';
import {AiChatPanelViewportService} from './domain/ai-chat-panel-viewport.service';
import {AiPermissionSettingsService} from './domain/ai-permission-settings.service';
import {AiModelSettingsCoordinator} from './domain/ai-model-settings-coordinator';
import {AiChatWindowCoordinatorService} from './domain/ai-chat-window-coordinator.service';
import {AiChatWorkspacePersistenceCoordinator} from './domain/ai-chat-workspace-persistence-coordinator';

export {
  AI_CHAT_LAST_CONVERSATION_STORAGE_KEY_PREFIX,
  AI_CHAT_PANEL_DOCK_STORAGE_KEY_PREFIX,
  AI_CHAT_PANEL_VISIBILITY_STORAGE_KEY_PREFIX,
  AI_CHAT_SELECTION_PREFERENCE_STORAGE_KEY,
  AI_CHAT_WINDOW_MODE_STORAGE_KEY_PREFIX,
  AI_CHAT_WORKSPACE_STORAGE_KEY_PREFIX
} from './domain/ai-chat-session-persistence.service';

@Component({
  standalone: false,
  selector: 'score-ai-chat-panel',
  templateUrl: './ai-chat-panel.component.html',
  styleUrls: [
    './ai-chat-shell.component.css',
    './ai-chat-agent-list.css',
    './ai-chat-agent-row.css',
    './ai-chat-progress-spinner.css',
    './ai-chat-overlay.css'
  ],
  providers: [
    AiActiveRequestRecoveryService,
    AiChatAttachmentQueueService,
    AiConversationProjector,
    AiConversationRestoreRenderer,
    AiConversationRestoreService,
    AiConfirmedChangeRequestCoordinator,
    AiChangeRepeatCoordinator,
    AiRequestDispatchCoordinator,
    AiChangeApprovalBatchCoordinator,
    AiElicitationCoordinator,
    AiRequestTerminalCoordinator,
    AiChatMessageTrackerService,
    AiChangeInteractionService,
    AiChatPanelViewportService,
    AiPermissionSettingsService,
    AiModelSettingsCoordinator,
    AiChatWindowCoordinatorService,
    AiChatWorkspacePersistenceCoordinator
  ]
})
export class AiChatPanelComponent extends AiChatPanelLifecycleController
  implements DoCheck, OnDestroy, OnInit {

  @ViewChild(AiChatComposerComponent) override composer?: AiChatComposerComponent;
  @ViewChild('chatTerminalPane') override chatTerminalPane?: ElementRef<HTMLDivElement>;

  @HostListener('document:mousemove', ['$event'])
  override onResizeMove(event: MouseEvent): void {
    super.onResizeMove(event);
  }

  @HostListener('document:mouseup')
  override stopResize(): void {
    super.stopResize();
  }

  ngDoCheck(): void {
    this.persistWorkspaceIfChanged();
  }
}
