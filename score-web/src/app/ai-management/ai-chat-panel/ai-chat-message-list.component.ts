import {Component, EventEmitter, Input, OnChanges, Output, SimpleChanges} from '@angular/core';
import {
  AiChatAttachment,
  AiChatMessage,
  AiChatModelInfo,
  AiElicitationNotice,
  AiElicitationResponse,
  AiMutationInteraction,
  AiMutationPermissionMode,
  AiChatRuntimeSettingInfo,
  AiReasoningEffortInfo
} from './domain/ai-chat-panel.model';

type AiChatMessageDisplayItem =
  | {
      kind: 'message';
      trackKey: string;
      message: AiChatMessage;
    }
  | {
      kind: 'turn';
      trackKey: string;
      userIndex: number;
      userMessage: AiChatMessage;
      historyMessages: AiChatMessage[];
      finalMessage: AiChatMessage;
      trailingMessages: AiChatMessage[];
    };

@Component({
  standalone: false,
  selector: 'score-ai-chat-message-list',
  templateUrl: './ai-chat-message-list.component.html',
  styleUrls: [
    './ai-chat-panel.component.css',
    './ai-chat-panel-history.css',
    './ai-chat-panel-messages.css',
    './ai-chat-panel-composer.css'
  ]
})
export class AiChatMessageListComponent implements OnChanges {

  @Input() messages: AiChatMessage[] = [];
  @Input() attachments: AiChatAttachment[] = [];
  @Input() pending = false;
  @Input() isInitialPrompt = false;
  @Input() modelSettingsOpen = false;
  @Input() runtimeSettingsOpen = false;
  @Input() permissionSettingsOpen = false;
  @Input() availableModels: AiChatModelInfo[] = [];
  @Input() modelDraftName = '';
  @Input() modelDraftReasoningEffort = '';
  @Input() runtimeDraft = '';
  @Input() runtimeDraftOptions: Record<string, unknown> = {};
  @Input() selectedModelName = '';
  @Input() selectedReasoningEffort = '';
  @Input() selectedRuntime = '';
  @Input() permissionMode: AiMutationPermissionMode = 'ask';
  @Input() permissionDraft: AiMutationPermissionMode = 'ask';
  @Input() modelChangePending = false;
  @Input() mutationInteraction?: AiMutationInteraction;
  @Input() elicitation?: AiElicitationNotice;
  @Input() elicitationBusy = false;

  @Output() attachmentRemoved = new EventEmitter<number>();

  get showRequestPendingIndicator(): boolean {
    if (!this.pending || this.elicitation) {
      return false;
    }
    let latestUserIndex = -1;
    for (let index = this.messages.length - 1; index >= 0; index--) {
      if (this.messages[index].role === 'user') {
        latestUserIndex = index;
        break;
      }
    }
    return !this.messages.slice(latestUserIndex + 1)
      .some(message => message.inProgress === true);
  }
  @Output() messageListClicked = new EventEmitter<MouseEvent>();
  @Output() modelDraftNameChange = new EventEmitter<string>();
  @Output() modelDraftReasoningEffortChange = new EventEmitter<string>();
  @Output() modelSettingsApplied = new EventEmitter<void>();
  @Output() modelSettingsCancelled = new EventEmitter<void>();
  @Output() runtimeDraftChange = new EventEmitter<string>();
  @Output() runtimeDraftOptionChange = new EventEmitter<{name: string; value: unknown}>();
  @Output() runtimeSettingsApplied = new EventEmitter<void>();
  @Output() runtimeSettingsCancelled = new EventEmitter<void>();
  @Output() permissionDraftChange = new EventEmitter<AiMutationPermissionMode>();
  @Output() permissionSettingsApplied = new EventEmitter<void>();
  @Output() permissionSettingsCancelled = new EventEmitter<void>();
  @Output() mutationApproved = new EventEmitter<void>();
  @Output() mutationDenied = new EventEmitter<void>();
  @Output() mutationChangeRequested = new EventEmitter<void>();
  @Output() mutationRevoked = new EventEmitter<void>();
  @Output() mutationDismissed = new EventEmitter<void>();
  @Output() elicitationResponded = new EventEmitter<AiElicitationResponse>();

  readonly permissionOptions: Array<{
    value: AiMutationPermissionMode;
    name: string;
    description: string;
  }> = [
    {
      value: 'ask',
      name: 'Ask for approval',
      description: 'Show every model-requested data change and wait for your choice.'
    },
    {
      value: 'auto',
      name: 'Approve for me',
      description: 'Automatically allow additive changes; ask before potentially unsafe actions.'
    },
    {
      value: 'full_access',
      name: 'Full access',
      description: 'Allow all requested data changes without asking. Exercise caution when using.'
    }
  ];

  private expandedHistoryUserIndexes = new Set<number>();

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['messages']) {
      this.expandedHistoryUserIndexes.clear();
    }
  }

  get displayItems(): AiChatMessageDisplayItem[] {
    const items: AiChatMessageDisplayItem[] = [];
    let index = 0;

    while (index < this.messages.length) {
      const message = this.messages[index];
      if (message.role !== 'user') {
        items.push({
          kind: 'message',
          trackKey: `message-${index}`,
          message
        });
        index++;
        continue;
      }

      const nextUserIndex = this.nextUserIndex(index + 1);
      const turnMessages = this.messages.slice(index + 1, nextUserIndex);
      const finalRelativeIndex = this.finalMessageIndex(turnMessages);

      if (finalRelativeIndex > 0) {
        items.push({
          kind: 'turn',
          trackKey: `turn-${index}`,
          userIndex: index,
          userMessage: message,
          historyMessages: turnMessages.slice(0, finalRelativeIndex),
          finalMessage: turnMessages[finalRelativeIndex],
          trailingMessages: turnMessages.slice(finalRelativeIndex + 1)
        });
      } else {
        for (let messageIndex = index; messageIndex < nextUserIndex; messageIndex++) {
          items.push({
            kind: 'message',
            trackKey: `message-${messageIndex}`,
            message: this.messages[messageIndex]
          });
        }
      }

      index = nextUserIndex;
    }

    return items;
  }

  isHistoryExpanded(userIndex: number): boolean {
    return this.expandedHistoryUserIndexes.has(userIndex);
  }

  toggleHistory(userIndex: number, event: MouseEvent): void {
    event.preventDefault();
    event.stopPropagation();
    if (this.isHistoryExpanded(userIndex)) {
      this.expandedHistoryUserIndexes.delete(userIndex);
      return;
    }
    this.expandedHistoryUserIndexes.add(userIndex);
  }

  toolCallStatusLabel(message: AiChatMessage): string {
    if (message.toolStatus === 'failed') {
      return 'Tool failed';
    }
    return message.toolStatus === 'completed' ? 'Tool completed' : 'Tool result';
  }

  hasToolDetail(message: AiChatMessage): boolean {
    return typeof message.toolDetail === 'string' && message.toolDetail.trim().length > 0;
  }

  get modelDraftReasoningEfforts(): AiReasoningEffortInfo[] {
    return (this.availableModels.find(model => model.name === this.modelDraftName)?.reasoningEfforts || [])
      .filter(effort => effort.name !== 'default');
  }

  get modelDraft(): AiChatModelInfo | undefined {
    return this.availableModels.find(model => model.name === this.modelDraftName);
  }

  get selectedModel(): AiChatModelInfo | undefined {
    return this.availableModels.find(model => model.name === this.selectedModelName);
  }

  get runtimeOptions() {
    return this.selectedModel?.runtimes?.length ? this.selectedModel.runtimes : [{
      name: 'default', displayName: 'Default', description: 'Uses the Default runtime.', settings: []
    }];
  }

  get runtimeDraftSettings(): AiChatRuntimeSettingInfo[] {
    return this.runtimeOptions.find(runtime => runtime.name === this.runtimeDraft)?.settings || [];
  }

  runtimeDraftOption(name: string): unknown {
    return this.runtimeDraftOptions[name];
  }

  runtimeDraftTextOption(name: string): string {
    const value = this.runtimeDraftOption(name);
    return value == null ? '' : String(value);
  }

  runtimeDraftBooleanOption(name: string): boolean {
    return this.runtimeDraftOption(name) === true;
  }

  changeRuntimeDraftOption(setting: AiChatRuntimeSettingInfo, event: Event): void {
    const control = event.target as HTMLInputElement | HTMLSelectElement;
    let value: unknown = control.value;
    if (setting.type === 'boolean') {
      value = (control as HTMLInputElement).checked;
    } else if (setting.type === 'number') {
      const numberValue = (control as HTMLInputElement).valueAsNumber;
      if (!Number.isFinite(numberValue)) {
        return;
      }
      value = numberValue;
    }
    this.runtimeDraftOptionChange.emit({name: setting.name, value});
  }

  private nextUserIndex(startIndex: number): number {
    const nextIndex = this.messages.findIndex((message, index) => index >= startIndex && message.role === 'user');
    return nextIndex === -1 ? this.messages.length : nextIndex;
  }

  private finalMessageIndex(turnMessages: AiChatMessage[]): number {
    for (let index = turnMessages.length - 1; index >= 0; index--) {
      if (turnMessages[index].role === 'assistant' && !turnMessages[index].inProgress) {
        return index;
      }
    }
    return -1;
  }
}
