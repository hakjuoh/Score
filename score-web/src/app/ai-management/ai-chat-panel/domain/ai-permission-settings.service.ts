/**
 * Coordinates editing and applying the chat panel's change-permission mode.
 */

import {Injectable, inject} from '@angular/core';
import {AiChatPanelState} from './ai-chat-panel-state';
import {AiChatSessionPersistenceService} from './ai-chat-session-persistence.service';

@Injectable()
export class AiPermissionSettingsService {
  private persistence = inject(AiChatSessionPersistenceService);
  private commandMessageIndex?: number;

  reset(): void {
    this.commandMessageIndex = undefined;
  }

  open(state: AiChatPanelState, commandText: string): void {
    state.prompt = '';
    state.messages.push({role: 'user', content: commandText});
    this.commandMessageIndex = state.messages.length - 1;
    state.permissionDraft = state.permissionMode;
    state.permissionSettingsOpen = true;
  }

  apply(state: AiChatPanelState): boolean {
    if (!state.permissionSettingsOpen) return false;
    state.permissionMode = state.permissionDraft;
    this.commandMessageIndex = undefined;
    state.permissionSettingsOpen = false;
    this.persistence.persistSelection(state);
    const label = state.permissionMode === 'ask' ? 'Ask for approval'
      : state.permissionMode === 'auto' ? 'Ask only for risky changes' : 'Full access';
    state.messages.push({
      role: 'debug', content: `Change permissions changed to ${label}.`
    });
    return true;
  }

  close(state: AiChatPanelState): void {
    const message = this.commandMessageIndex === undefined
      ? undefined : state.messages[this.commandMessageIndex];
    if (message?.role === 'user'
      && message.content.trim().toLowerCase() === '/permissions') {
      state.messages.splice(this.commandMessageIndex!, 1);
    }
    this.commandMessageIndex = undefined;
    state.permissionSettingsOpen = false;
    state.permissionDraft = state.permissionMode;
  }
}
