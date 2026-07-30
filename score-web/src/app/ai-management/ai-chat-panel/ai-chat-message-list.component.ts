import {
  AfterViewChecked,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  SimpleChanges
} from '@angular/core';
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
  AiReasoningEffortInfo,
  aiModelSessionLabel
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
export class AiChatMessageListComponent implements OnChanges, AfterViewChecked {

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
  @Input() agentFocusBackLabel = 'Back to conversation';
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
      description:
        'Automatically allow new data and changes to data you own; ask before changing' +
        " somebody else's data, and before any deletion, state change, or ownership transfer."
    },
    {
      value: 'full_access',
      name: 'Full access',
      description: 'Allow all requested data changes without asking. Exercise caution when using.'
    }
  ];

  private expandedHistoryUserIndexes = new Set<number>();
  private focusAgentBackButton = false;
  private focusAgentRowId?: string;

  constructor(private readonly host: ElementRef<HTMLElement>) {}

  artifactSize(bytes: number): string {
    if (!Number.isFinite(bytes) || bytes < 0) return '';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['messages']) {
      this.expandedHistoryUserIndexes.clear();
    }
    const focusChange = changes['agentFocus'];
    if (focusChange) {
      const current = focusChange.currentValue as AiAgentActivity | undefined;
      const previous = focusChange.previousValue as AiAgentActivity | undefined;
      if (current) {
        if (previous && this.activityContains(current, previous.agentId)) {
          this.focusAgentRowId = previous.agentId;
        } else {
          this.focusAgentBackButton = true;
        }
      } else if (previous) {
        this.focusAgentRowId = previous.agentId;
      }
    }
  }

  ngAfterViewChecked(): void {
    if (this.focusAgentBackButton) {
      this.focusAgentBackButton = false;
      this.host.nativeElement.querySelector<HTMLButtonElement>('.agent-focus-back')?.focus();
      return;
    }
    if (this.focusAgentRowId) {
      const agentId = this.focusAgentRowId;
      this.focusAgentRowId = undefined;
      const rows = this.host.nativeElement.querySelectorAll<HTMLButtonElement>(
        '.agent-group-row[data-agent-id]'
      );
      Array.from(rows).find(row => row.dataset['agentId'] === agentId)?.focus();
    }
  }

  private activityContains(activity: AiAgentActivity, agentId: string): boolean {
    const visit = (messages: AiChatMessage[]): boolean => messages.some(message =>
      (message.activities || []).some(candidate => candidate.agentId === agentId
        || this.activityContains(candidate, agentId))
      || visit(message.children || []));
    return visit(activity.messages || []);
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

  isLiveProgressStatus(message: AiChatMessage): boolean {
    return message.role === 'progress' && message.inProgress === true
      && message.eventType !== 'assistant_update';
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

  agentConversationMessage(event: AiAgentActivityEvent,
                           agentInProgress: boolean): AiChatMessage {
    if (event.status === 'provider_error') {
      return {role: 'error', content: event.content};
    }
    if (event.status === 'provider_retry') {
      return {role: 'progress', content: event.content, inProgress: agentInProgress};
    }
    return {role: 'guide', content: event.content};
  }

  agentConversationMessages(activity: AiAgentActivity): AiChatMessage[] {
    // An empty array is meaningful in the composite contract (for example,
    // planned lifecycle chatter is intentionally hidden). Only legacy
    // activities that predate `messages` should fall back to raw events.
    if (activity.messages !== undefined) return activity.messages;
    return activity.events.map(event => event.status === 'tool'
      ? this.agentToolMessage(event, activity.inProgress)
      : this.agentConversationMessage(event, activity.inProgress));
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

  get selectedModelSessionLabel(): string {
    return aiModelSessionLabel(this.selectedModel?.displayName || '', this.selectedReasoningEffort);
  }

  get permissionDisplayName(): string {
    return this.permissionOptions.find(option => option.value === this.permissionMode)?.name
      || this.permissionMode;
  }

  agentGroupSummary(message: AiChatMessage): string {
    if (!(message.activities?.length) && message.workflowStatus) {
      return message.workflowStatus === 'completed' ? 'completed'
        : message.workflowStatus === 'cancelled' ? 'stopped' : 'failed';
    }
    return agentActivitySummary(message.activities || []);
  }

  agentGroupPhase(message: AiChatMessage): string {
    if (message.workflowStatus === 'completed') return 'Completed';
    if (message.workflowStatus === 'cancelled') return 'Workflow stopped';
    if (message.workflowStatus === 'failed') return 'Workflow failed';
    const activities = message.activities || [];
    const lead = activities.find(activity => activity.isLead);
    if (lead?.status === 'synthesizing') return this.activePhase(lead.activeVerb);
    if (lead?.status === 'completed') return lead.completedVerb || 'Completed';
    if (lead?.status === 'failed' || lead?.status === 'cancelled') {
      return `${lead.activeVerb || 'Workflow'} stopped`;
    }
    // A composed chain can have a gap between one worker completing and the
    // next worker starting. The still-live lead owns the phase throughout.
    if (lead?.inProgress) return this.activePhase(lead.activeVerb);
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
        ? this.agentGroupExecutionKind(message) === 'parallel'
          ? 'Running independent workflow tasks before synthesizing their results.'
          : 'Running workflow tasks before synthesizing their results.'
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
    if (message.role === 'workflow_group') {
      return this.agentGroupExecutionKind(message) === 'parallel'
        ? 'Parallel workflow' : 'Workflow';
    }
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
    if (activity.status === 'planned') return 'queued';
    return agentActivityElapsedLabel(activity);
  }

  agentStatusLabel(activity: AiAgentActivity): string {
    return activity.status === 'planned' ? 'queued'
      : activity.status === 'started' ? 'running' : activity.status;
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
