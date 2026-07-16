import {Injectable} from '@angular/core';
import {AiChatConversationSummary} from './ai-chat-panel.model';

@Injectable({
  providedIn: 'root'
})
export class AiChatHistoryService {

  conversationAge(conversation: AiChatConversationSummary): string {
    const timestamp = this.conversationTimestamp(conversation.createdAt ?? conversation.updatedAt);
    if (!Number.isFinite(timestamp)) {
      return '';
    }
    const seconds = Math.max(0, Math.floor((Date.now() - timestamp) / 1000));
    if (seconds < 60) {
      return seconds <= 5 ? 'now' : seconds + 's';
    }
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) {
      return minutes + 'm';
    }
    const hours = Math.floor(minutes / 60);
    if (hours < 24) {
      return hours + 'h';
    }
    const days = Math.floor(hours / 24);
    if (days < 7) {
      return days + 'd';
    }
    const weeks = Math.floor(days / 7);
    return weeks + 'w';
  }

  private conversationTimestamp(value?: string | number): number {
    if (value === undefined || value === null || value === '') {
      return NaN;
    }
    if (typeof value === 'number') {
      return value < 1000000000000 ? value * 1000 : value;
    }
    const trimmed = value.trim();
    if (!trimmed) {
      return NaN;
    }
    if (/^\d+(\.\d+)?$/.test(trimmed)) {
      const numeric = Number(trimmed);
      return numeric < 1000000000000 ? numeric * 1000 : numeric;
    }
    return new Date(trimmed).getTime();
  }
}
