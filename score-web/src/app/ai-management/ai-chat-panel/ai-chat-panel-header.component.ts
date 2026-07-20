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
  @Input() popoutMode = false;

  @Output() dockChanged = new EventEmitter<AiChatDock>();
  @Output() popoutRequested = new EventEmitter<Event>();
  @Output() reattachRequested = new EventEmitter<Event>();
  @Output() closeRequested = new EventEmitter<MouseEvent>();

  get reattachIcon(): string {
    if (this.dock === 'left') return 'align_horizontal_left';
    if (this.dock === 'top') return 'vertical_align_top';
    if (this.dock === 'bottom') return 'vertical_align_bottom';
    return 'align_horizontal_right';
  }

  selectDock(dock: AiChatDock): void {
    this.dockChanged.emit(dock);
  }
}
