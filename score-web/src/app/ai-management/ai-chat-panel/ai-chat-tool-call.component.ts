import {Component, Input} from '@angular/core';
import {AiChatToolStatus} from './domain/ai-chat-panel.model';
import {displayToolText} from './domain/ai-tool-presentation';

/**
 * Shared tool-call presentation for both the lead conversation and a focused
 * specialist conversation. The parent supplies the same persisted tool state
 * regardless of which conversation owns the ai_chat_step row.
 */
@Component({
  standalone: false,
  selector: 'score-ai-chat-tool-call',
  templateUrl: './ai-chat-tool-call.component.html',
  styleUrl: './ai-chat-tool-call.component.css'
})
export class AiChatToolCallComponent {

  @Input() content = '';
  @Input() detail?: string;
  @Input() status?: AiChatToolStatus;
  @Input() inProgress = false;

  get statusLabel(): string {
    if (this.status === 'failed') {
      return 'Tool failed';
    }
    if (this.status === 'blocked') {
      return 'Awaiting approval';
    }
    if (this.status === 'denied') {
      return 'Denied';
    }
    if (this.status === 'cancelled') {
      return 'Stopped';
    }
    return this.status === 'completed' ? 'Tool completed' : 'Tool result';
  }

  get hasDetail(): boolean {
    return typeof this.detail === 'string' && this.detail.trim().length > 0;
  }

  get visibleContent(): string {
    return displayToolText(this.content) || this.content;
  }

  get visibleDetail(): string | undefined {
    return displayToolText(this.detail);
  }
}
