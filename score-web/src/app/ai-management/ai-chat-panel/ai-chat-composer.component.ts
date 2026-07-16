import {Component, ElementRef, EventEmitter, Input, Output, ViewChild} from '@angular/core';
import {AI_CHAT_ATTACHMENT_ACCEPT} from './domain/ai-chat-panel.constants';
import {AiChatCommand} from './domain/ai-chat-panel.model';

@Component({
  standalone: false,
  selector: 'score-ai-chat-composer',
  templateUrl: './ai-chat-composer.component.html',
  styleUrls: [
    './ai-chat-panel.component.css',
    './ai-chat-panel-history.css',
    './ai-chat-panel-messages.css',
    './ai-chat-panel-composer.css'
  ]
})
export class AiChatComposerComponent {

  readonly attachmentAccept = AI_CHAT_ATTACHMENT_ACCEPT;

  @Input() prompt = '';
  @Input() pending = false;
  @Input() blocked = false;
  @Input() cancellationInProgress = false;
  @Input() cancellationDelayed = false;
  @Input() isInitialPrompt = false;
  @Input() showCommandSuggestions = false;
  @Input() commandSuggestions: AiChatCommand[] = [];
  @Input() selectedCommandIndex = 0;
  @Input() placeholder = 'Ask connectCenter';

  @Output() promptChange = new EventEmitter<string>();
  @Output() keydownEvent = new EventEmitter<KeyboardEvent>();
  @Output() commandSuggestionSelected = new EventEmitter<AiChatCommand>();
  @Output() fileInputChanged = new EventEmitter<Event>();
  @Output() filePickerRequested = new EventEmitter<HTMLInputElement>();
  @Output() stopRequested = new EventEmitter<void>();
  @Output() cancellationRetryRequested = new EventEmitter<void>();
  @Output() forceSafeStopRequested = new EventEmitter<void>();

  @ViewChild('commandInput') commandInput?: ElementRef<HTMLTextAreaElement>;

  focus(): void {
    setTimeout(() => {
      this.resize();
      this.commandInput?.nativeElement.focus();
    });
  }

  resize(): void {
    setTimeout(() => {
      const input = this.commandInput?.nativeElement;
      if (!input) {
        return;
      }
      input.style.height = 'auto';
      input.style.height = Math.min(input.scrollHeight, 88) + 'px';
    });
  }

  updatePrompt(value: string): void {
    this.prompt = value;
    this.promptChange.emit(value);
    this.resize();
  }
}
