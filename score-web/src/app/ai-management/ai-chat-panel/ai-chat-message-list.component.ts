import {Component, EventEmitter, Input, OnChanges, Output, SimpleChanges} from '@angular/core';
import {
  AiAgentActivity,
  AiAgentActivityEvent,
  agentActivityElapsedLabel,
  agentActivitySummary
} from './domain/ai-agent-activity';
import {
  AiChatAttachment,
  AiChatMessage,
  AiChatModelInfo,
  AiElicitationNotice,
  AiElicitationResponse,
  AiMutationApprovalBatchDecision,
  AiMutationApprovalBatchNotice,
  AiMutationInteraction,
  AiMutationPermissionMode,
  AiChatToolStatus,
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
    './ai-chat-panel-agents.css',
    './ai-chat-panel-composer.css'
  ]
})
export class AiChatMessageListComponent implements OnChanges {

  @Input() messages: AiChatMessage[] = [];
  @Input() attachments: AiChatAttachment[] = [];
  @Input() pending = false;
  @Input() modelSettingsOpen = false;
  @Input() permissionSettingsOpen = false;
  @Input() availableModels: AiChatModelInfo[] = [];
  @Input() modelDraftName = '';
  @Input() modelDraftReasoningEffort = '';
  @Input() selectedModelName = '';
  @Input() selectedReasoningEffort = '';
  @Input() permissionMode: AiMutationPermissionMode = 'ask';
  @Input() permissionDraft: AiMutationPermissionMode = 'ask';
  @Input() agentFocus?: AiAgentActivity;
  @Input() modelChangePending = false;
  @Input() mutationInteraction?: AiMutationInteraction;
  @Input() mutationApprovalBatch?: AiMutationApprovalBatchNotice;
  @Input() mutationApprovalBatchBusy = false;
  @Input() elicitation?: AiElicitationNotice;
  @Input() elicitationBusy = false;

  @Output() attachmentRemoved = new EventEmitter<number>();

  get showRequestPendingIndicator(): boolean {
    if (!this.pending || this.elicitation || this.mutationApprovalBatch) {
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
  @Output() permissionDraftChange = new EventEmitter<AiMutationPermissionMode>();
  @Output() permissionSettingsApplied = new EventEmitter<void>();
  @Output() permissionSettingsCancelled = new EventEmitter<void>();
  @Output() agentFocusRequested = new EventEmitter<string>();
  @Output() agentFocusClosed = new EventEmitter<void>();
  @Output() mutationApproved = new EventEmitter<void>();
  @Output() mutationDenied = new EventEmitter<void>();
  @Output() mutationChangeRequested = new EventEmitter<void>();
  @Output() mutationRevoked = new EventEmitter<void>();
  @Output() mutationDismissed = new EventEmitter<void>();
  @Output() mutationBatchApproved = new EventEmitter<void>();
  @Output() mutationBatchDenied = new EventEmitter<void>();
  @Output() mutationBatchDecided = new EventEmitter<AiMutationApprovalBatchDecision[]>();
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
      name: 'Ask only for risky actions',
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
        const workingMessages = turnMessages.slice(0, finalRelativeIndex);
        items.push({
          kind: 'turn',
          trackKey: `turn-${index}`,
          userIndex: index,
          userMessage: message,
          // Keep every execution event in its original position. In
          // particular, moving the agent group outside the folded history
          // makes a completed turn appear to have run tools before agents.
          historyMessages: workingMessages,
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

  agentToolMessage(event: AiAgentActivityEvent, agentInProgress: boolean): AiChatMessage {
    const toolStatus: AiChatToolStatus | undefined = event.toolStatus === 'completed'
      || event.toolStatus === 'failed' || event.toolStatus === 'blocked'
      || event.toolStatus === 'denied' || event.toolStatus === 'cancelled'
      ? event.toolStatus : undefined;
    return {
      role: 'tool_call',
      content: event.content,
      ...(event.detail ? {toolDetail: event.detail} : {}),
      ...(toolStatus ? {toolStatus} : {}),
      inProgress: event.toolStatus === 'started' && agentInProgress
    };
  }

  agentConversationMessage(event: AiAgentActivityEvent): AiChatMessage {
    return {role: 'guide', content: event.content};
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

  get selectedReasoningEffortDisplayName(): string {
    return this.selectedModel?.reasoningEfforts
      .find(effort => effort.name === this.selectedReasoningEffort)?.displayName
      || this.selectedReasoningEffort;
  }

  get permissionDisplayName(): string {
    return this.permissionOptions.find(option => option.value === this.permissionMode)?.name
      || this.permissionMode;
  }

  agentGroupSummary(message: AiChatMessage): string {
    return agentActivitySummary(message.activities || []);
  }

  agentGroupPhase(message: AiChatMessage): string {
    const activities = message.activities || [];
    const lead = activities.find(activity => activity.isLead);
    if (lead?.status === 'synthesizing') return this.activePhase(lead.activeVerb);
    if (lead?.status === 'completed') return lead.completedVerb || 'Completed';
    if (lead?.status === 'failed' || lead?.status === 'cancelled') {
      return `${lead.activeVerb || 'Workflow'} stopped`;
    }
    const specialists = activities.filter(activity => !activity.isLead);
    if (specialists.length > 0 && specialists.every(activity => !activity.inProgress)) {
      const completed = specialists.find(activity => activity.completedVerb)?.completedVerb || lead?.completedVerb;
      return specialists.some(activity => activity.status === 'failed' || activity.status === 'cancelled')
        ? `${completed || 'Completed'} with issues` : completed || 'Completed';
    }
    return this.activePhase(lead?.activeVerb
      || specialists.find(activity => activity.activeVerb)?.activeVerb);
  }

  agentGroupPlan(message: AiChatMessage): string {
    return message.activities?.find(activity => activity.isLead)?.content
      || (message.role === 'workflow_group'
        ? 'Running independent workflow tasks before synthesizing their results.'
        : this.agentGroupCount(message) === 1
          ? 'A specialist is gathering evidence for the lead.'
          : 'Specialists are gathering evidence for the lead.');
  }

  agentGroupActivities(message: AiChatMessage): AiAgentActivity[] {
    return (message.activities || []).filter(activity => !activity.isLead);
  }

  agentGroupCount(message: AiChatMessage): number {
    const activities = message.activities || [];
    const actual = activities.filter(activity => !activity.isLead).length;
    const planned = activities.find(activity => activity.isLead)?.plannedAgentCount || 0;
    return Math.max(actual, planned);
  }

  agentGroupWorkflow(message: AiChatMessage): string | undefined {
    const activities = message.activities || [];
    return activities.find(activity => activity.isLead)?.workflow
      || activities.find(activity => activity.workflow)?.workflow;
  }

  agentGroupExecutionKind(message: AiChatMessage): string | undefined {
    const activities = message.activities || [];
    return activities.find(activity => activity.isLead)?.executionKind
      || activities.find(activity => activity.executionKind)?.executionKind;
  }

  agentGroupWorkflowLabel(message: AiChatMessage): string | undefined {
    if (message.role === 'workflow_group') return 'Parallel workflow';
    const workflow = this.agentGroupWorkflow(message);
    if (workflow === 'chain') return 'Chain workflow';
    if (workflow === 'routing') return 'Routing workflow';
    if (workflow === 'orchestrator_workers') return 'Agent workflow';
    if (message.role === 'agent_group') {
      return this.agentGroupCount(message) === 1 ? 'Specialist workflow' : 'Multi-agent workflow';
    }
    return undefined;
  }

  agentDisplayName(activity: AiAgentActivity): string {
    return activity.taskLabel || activity.agentName;
  }

  agentDisplayRole(activity: AiAgentActivity): string | undefined {
    return activity.taskLabel ? activity.agentName : activity.agentRole;
  }

  agentElapsed(activity: AiAgentActivity): string {
    return agentActivityElapsedLabel(activity);
  }

  agentStatusLabel(activity: AiAgentActivity): string {
    return activity.status === 'started' ? 'running' : activity.status;
  }

  private activePhase(verb?: string): string {
    const value = verb?.trim() || 'Working';
    return /\.{3}$/.test(value) ? value : `${value}...`;
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
