import {Component, EventEmitter, Input, Output, inject} from '@angular/core';
import {AiChatHistoryService} from './domain/ai-chat-history.service';
import {AiChatConversationSummary} from './domain/ai-chat-panel.model';

@Component({
  standalone: false,
  selector: 'score-ai-chat-history-list',
  templateUrl: './ai-chat-history-list.component.html',
  styleUrls: [
    './ai-chat-panel.component.css',
    './ai-chat-panel-history.css',
    './ai-chat-panel-messages.css',
    './ai-chat-panel-composer.css'
  ]
})
export class AiChatHistoryListComponent {

  private historyService = inject(AiChatHistoryService);

  @Input() conversations: AiChatConversationSummary[] = [];
  @Input() activeConversationId?: string;
  @Input() loading = false;
  @Input() loadFailed = false;

  @Output() conversationSelected = new EventEmitter<string>();
  @Output() conversationDeleted = new EventEmitter<string>();

  conversationAge(conversation: AiChatConversationSummary): string {
    return this.historyService.conversationAge(conversation);
  }

  deleteConversation(conversationId: string, event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    this.conversationDeleted.emit(conversationId);
  }
}
