/**
 * Resolves slash commands and derives context-aware command suggestions.
 */

import {Injectable} from '@angular/core';
import {AI_CHAT_COMMANDS, type AiChatCommandName} from './ai-chat-panel.constants';
import {AiChatCommand} from './ai-chat-panel.model';

@Injectable({
  providedIn: 'root'
})
export class AiChatCommandService {

  suggestions(prompt: string, requestActive = false): AiChatCommand[] {
    const value = prompt.trim().toLowerCase();
    if (!value.startsWith('/')) {
      return [];
    }
    return AI_CHAT_COMMANDS
      .filter(command => requestActive ? command.name === '/cancel' : command.name !== '/cancel')
      .filter(command => command.name.startsWith(value));
  }

  isKnownCommand(prompt: string): boolean {
    return this.resolveCommand(prompt) !== undefined;
  }

  resolveCommand(prompt: string): AiChatCommandName | undefined {
    const normalized = this.normalize(prompt);
    const command = AI_CHAT_COMMANDS.find(command => command.name === normalized);
    if (command) {
      return command.name;
    }
    return this.isCompactInvocation(normalized) ? '/compact' : undefined;
  }

  private normalize(prompt: string): string {
    return prompt.trim().toLowerCase();
  }

  private isCompactInvocation(prompt: string): boolean {
    return prompt.startsWith('/compact') && prompt.length > '/compact'.length
      && /\s/.test(prompt.charAt('/compact'.length));
  }
}
