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
import {AiConversationRestoreService} from './domain/ai-conversation-restore.service';
import {AiChatMessageTrackerService} from './domain/ai-chat-message-tracker.service';
import {AiChangeInteractionService} from './domain/ai-change-interaction.service';
import {AiChatPanelViewportService} from './domain/ai-chat-panel-viewport.service';
import {AiChatSettingsService} from './domain/ai-chat-settings.service';
import {AiChatWindowCoordinatorService} from './domain/ai-chat-window-coordinator.service';

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
    './ai-chat-panel.component.css',
    './ai-chat-panel-history.css',
    './ai-chat-panel-messages.css',
    './ai-chat-panel-agents.css',
    './ai-chat-panel-composer.css'
  ],
  providers: [
    AiActiveRequestRecoveryService,
    AiChatAttachmentQueueService,
    AiConversationRestoreService,
    AiConfirmedChangeRequestCoordinator,
    AiChatMessageTrackerService,
    AiChangeInteractionService,
    AiChatPanelViewportService,
    AiChatSettingsService,
    AiChatWindowCoordinatorService
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
