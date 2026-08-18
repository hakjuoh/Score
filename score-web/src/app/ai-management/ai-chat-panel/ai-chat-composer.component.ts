/**
 * Collects prompts and attachments and emits submit, stop, recovery, and command-selection actions.
 */

import {Component, ElementRef, EventEmitter, Input, Output, ViewChild} from '@angular/core';
import {AI_CHAT_ATTACHMENT_ACCEPT} from './domain/ai-chat-panel.constants';
import {AiChatAttachment, AiChatCommand} from './domain/ai-chat-panel.model';

const MAX_COMPOSER_HEIGHT_PX = 72;

@Component({
  standalone: false,
  selector: 'score-ai-chat-composer',
  templateUrl: './ai-chat-composer.component.html',
  styleUrls: [
    './ai-chat-composer.component.css',
    './ai-chat-message-attachments.css'
  ]
})
export class AiChatComposerComponent {

  readonly attachmentAccept = AI_CHAT_ATTACHMENT_ACCEPT;

  @Input() prompt = '';
  @Input() attachments: AiChatAttachment[] = [];
  @Input() loadingAttachments: Array<{name: string}> = [];
  @Input() trajectoryUrl?: string;
  @Input() pending = false;
  @Input() blocked = false;
  @Input() cancellationInProgress = false;
  @Input() cancellationDelayed = false;
  @Input() showCommandSuggestions = false;
  @Input() commandSuggestions: AiChatCommand[] = [];
  @Input() selectedCommandIndex = 0;
  @Input() placeholder = 'Ask a question';

  @Output() promptChange = new EventEmitter<string>();
  @Output() keydownEvent = new EventEmitter<KeyboardEvent>();
  @Output() commandSuggestionSelected = new EventEmitter<AiChatCommand>();
  @Output() fileInputChanged = new EventEmitter<Event>();
  @Output() filePickerRequested = new EventEmitter<HTMLInputElement>();
  @Output() attachmentRemoved = new EventEmitter<number>();
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
      input.style.height = Math.min(input.scrollHeight, MAX_COMPOSER_HEIGHT_PX) + 'px';
    });
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Backspace' && !this.pending && this.attachments.length > 0) {
      const textarea = this.commandInput?.nativeElement;
      if (textarea && textarea.selectionStart === 0 && textarea.selectionEnd === 0) {
        event.preventDefault();
        this.attachmentRemoved.emit(this.attachments.length - 1);
        return;
      }
    }
    this.keydownEvent.emit(event);
  }

  updatePrompt(value: string): void {
    this.prompt = value;
    this.promptChange.emit(value);
    this.resize();
  }
}
