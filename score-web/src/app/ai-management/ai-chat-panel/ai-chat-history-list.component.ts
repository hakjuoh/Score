import {
  AfterViewInit,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  SimpleChanges,
  ViewChild,
  inject
} from '@angular/core';
import {AiChatHistoryService} from './domain/ai-chat-history.service';
import {AiChatConversationSummary} from './domain/ai-chat-panel.model';

@Component({
  standalone: false,
  selector: 'score-ai-chat-history-list',
  templateUrl: './ai-chat-history-list.component.html',
  styleUrl: './ai-chat-history.component.css'
})
export class AiChatHistoryListComponent implements AfterViewInit, OnChanges {

  private historyService = inject(AiChatHistoryService);

  @Input() conversations: AiChatConversationSummary[] = [];
  @Input() activeConversationId?: string;
  @Input() loading = false;
  @Input() loadFailed = false;
  @Input() scrollTop = 0;

  @Output() conversationSelected = new EventEmitter<string>();
  @Output() conversationDeleted = new EventEmitter<string>();
  @Output() scrollTopChange = new EventEmitter<number>();

  @ViewChild('historyPanel') historyPanel?: ElementRef<HTMLDivElement>;

  ngAfterViewInit(): void {
    this.restoreScrollTop();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['conversations'] || changes['scrollTop']) this.restoreScrollTop();
  }

  private restoreScrollTop(): void {
    window.setTimeout(() => {
      if (this.historyPanel) this.historyPanel.nativeElement.scrollTop = this.scrollTop;
    });
  }

  conversationAge(conversation: AiChatConversationSummary): string {
    return this.historyService.conversationAge(conversation);
  }

  deleteConversation(conversationId: string, event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    this.conversationDeleted.emit(conversationId);
  }

  onScroll(): void {
    this.scrollTopChange.emit(this.historyPanel?.nativeElement.scrollTop || 0);
  }
}
