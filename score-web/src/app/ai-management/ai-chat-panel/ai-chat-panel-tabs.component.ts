import {Component, ElementRef, EventEmitter, Input, Output, ViewChild} from '@angular/core';
import {AiChatPanelTab} from './domain/ai-chat-panel.model';

@Component({
  standalone: false,
  selector: 'score-ai-chat-panel-tabs',
  templateUrl: './ai-chat-panel-tabs.component.html',
  styleUrls: [
    './ai-chat-panel.component.css',
    './ai-chat-panel-history.css',
    './ai-chat-panel-messages.css',
    './ai-chat-panel-composer.css'
  ]
})
export class AiChatPanelTabsComponent {

  @Input() idPrefix = 'score-ai-chat';
  @Input() activePanelTab: AiChatPanelTab = 'chat';
  @Input() historyLoading = false;

  @Output() panelTabSelected = new EventEmitter<AiChatPanelTab>();
  @Output() newChatRequested = new EventEmitter<Event>();
  @Output() historyReloadRequested = new EventEmitter<void>();

  @ViewChild('chatTab') chatTab?: ElementRef<HTMLButtonElement>;
  @ViewChild('historyTab') historyTab?: ElementRef<HTMLButtonElement>;

  selectTab(tab: AiChatPanelTab, event: Event): void {
    event.stopPropagation();
    this.panelTabSelected.emit(tab);
  }

  onTabKeydown(tab: AiChatPanelTab, event: KeyboardEvent): void {
    let nextTab: AiChatPanelTab | undefined;
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      nextTab = tab === 'chat' ? 'history' : 'chat';
    } else if (event.key === 'Home') {
      nextTab = 'chat';
    } else if (event.key === 'End') {
      nextTab = 'history';
    }
    if (!nextTab) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    this.panelTabSelected.emit(nextTab);
    (nextTab === 'chat' ? this.chatTab : this.historyTab)?.nativeElement.focus();
  }
}
