import {Component, EventEmitter, Input, Output} from '@angular/core';
import {SafeHtml} from '@angular/platform-browser';
import {AiChatDock} from './domain/ai-chat-panel.model';

@Component({
  standalone: false,
  selector: 'score-ai-chat-panel-header',
  templateUrl: './ai-chat-panel-header.component.html',
  styleUrls: [
    './ai-chat-panel.component.css',
    './ai-chat-panel-history.css',
    './ai-chat-panel-messages.css',
    './ai-chat-panel-composer.css'
  ]
})
export class AiChatPanelHeaderComponent {

  @Input() assistantBrand?: SafeHtml;
  @Input() dock: AiChatDock = 'right';

  @Output() dockChanged = new EventEmitter<AiChatDock>();
  @Output() closeRequested = new EventEmitter<MouseEvent>();

  selectDock(dock: AiChatDock): void {
    this.dockChanged.emit(dock);
  }
}
