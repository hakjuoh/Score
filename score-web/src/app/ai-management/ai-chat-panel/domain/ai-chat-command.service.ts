/**
 * Parses local slash commands and derives context-aware command suggestions.
 */

import {Injectable} from '@angular/core';
import {AI_CHAT_COMMANDS} from './ai-chat-panel.constants';
import {AiChatCommand} from './ai-chat-panel.model';

export type AiLocalCommand = 'clear' | 'cancel' | 'debug' | 'mcp' | 'model' | 'permissions';

export interface AiChatCommandDecision {
  kind: 'local' | 'backend' | 'none';
  command?: AiLocalCommand;
}

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
    const normalized = this.normalize(prompt);
    return AI_CHAT_COMMANDS.some(command => command.name === normalized)
      || this.isCompactInvocation(normalized);
  }

  decide(prompt: string): AiChatCommandDecision {
    const normalized = this.normalize(prompt);
    const command = AI_CHAT_COMMANDS.find(item => item.name === normalized);
    if (!command && this.isCompactInvocation(normalized)) {
      return {kind: 'backend'};
    }
    if (!command) {
      return {kind: 'none'};
    }
    if (command.kind === 'backend') {
      return {kind: 'backend'};
    }
    if (normalized === '/clear') {
      return {kind: 'local', command: 'clear'};
    }
    if (normalized === '/cancel') {
      return {kind: 'local', command: 'cancel'};
    }
    if (normalized === '/debug') {
      return {kind: 'local', command: 'debug'};
    }
    if (normalized === '/mcp') {
      return {kind: 'local', command: 'mcp'};
    }
    if (normalized === '/model') {
      return {kind: 'local', command: 'model'};
    }
    if (normalized === '/permissions') {
      return {kind: 'local', command: 'permissions'};
    }
    return {kind: 'none'};
  }

  private normalize(prompt: string): string {
    return prompt.trim().toLowerCase();
  }

  private isCompactInvocation(prompt: string): boolean {
    return prompt.startsWith('/compact') && prompt.length > '/compact'.length
      && /\s/.test(prompt.charAt('/compact'.length));
  }
}
