import {
  AiAgentExecutionStatus,
  AiChatMessage,
  AiChatSocketEvent,
  AiChatStatusTone,
  AiWorkflowType
} from './ai-chat-panel.model';
import {displayToolName, displayToolText} from './ai-tool-presentation';
import {
  isWorkingStatusText,
  WORKING_STATUS_LABEL
} from './ai-chat-panel-display.constants';

const EXECUTION_ACTIVITY_SUBTYPES = new Set([
  'subagent_preparing',
  'subagent_planned',
  'subagent_started',
  'subagent_retry',
  'subagent_output_retry_handoff',
  'subagent_completed',
  'subagent_failed',
  'subagent_cancelled',
  'subagent_refused'
]);

export interface AiAgentActivityEvent {
  status: AiAgentExecutionStatus | 'tool' | 'provider_error' | 'provider_retry';
  content: string;
  key?: string;
  /** Stable identity of one tool invocation, shared by its started/terminal events. */
  toolKey?: string;
  toolStatus?: 'started' | 'completed' | 'failed' | 'blocked' | 'denied' | 'cancelled';
  detail?: string;
}

export interface AiAgentActivity {
  agentId: string;
  agentName: string;
  agentRole?: string;
  taskLabel?: string;
  plannedAgentCount?: number;
  activeVerb?: string;
  completedVerb?: string;
  workflowType?: AiWorkflowType;
  /** Assignment sent by the owning Agent to this child conversation. */
  assignment?: string;
  /** Final guarded output returned by this child conversation. */
  result?: string;
  status: AiAgentExecutionStatus;
  content: string;
  inProgress: boolean;
  isLead: boolean;
  firstSeenAt: number;
  lastUpdateAt: number;
  events: AiAgentActivityEvent[];
  /** The same conversation-message contract used by the root chat. */
  messages?: AiChatMessage[];
}

export type AiAgentActivityUpdate = Omit<AiAgentActivity,
  'firstSeenAt' | 'lastUpdateAt' | 'events' | 'messages'>;

export function agentActivityOwnerId(event: AiChatSocketEvent): string {
  return text(event.turnId) || event.requestId;
}

export function isExecutionActivityEvent(event: AiChatSocketEvent): boolean {
  return event.type === 'system' && !!event.subtype
    && EXECUTION_ACTIVITY_SUBTYPES.has(event.subtype);
}

export function agentActivityUpdate(event: AiChatSocketEvent): AiAgentActivityUpdate | undefined {
  if (!isExecutionActivityEvent(event)) return undefined;
  const metadata = event.metadata || {};
  const agentId = text(metadata['nodeId']) || text(metadata['node_id']) || text(metadata['agentId'])
    || undefined;
  if (!agentId) return undefined;
  const agentName = text(metadata['agentName']) || text(metadata['agent_name'])
    || 'Specialist';
  const taskLabel = text(metadata['taskLabel']) || text(metadata['task_label']);
  const content = text(event.content) || text(event.response) || text(event.message)
    || '';
  const status: AiAgentExecutionStatus = event.subtype === 'subagent_preparing'
    || event.subtype === 'subagent_planned' ? 'planned'
    : event.subtype === 'subagent_cancelled'
      || event.subtype === 'subagent_output_retry_handoff' ? 'cancelled'
      : event.subtype === 'subagent_failed' || event.subtype === 'subagent_refused' ? 'failed'
        : event.subtype === 'subagent_completed' ? 'completed' : 'started';
  return {
    agentId,
    agentName,
    agentRole: text(metadata['agentRole']) || text(metadata['agent_role']) || text(metadata['strategy']),
    taskLabel,
    plannedAgentCount: positiveInteger(metadata['agent_count']),
    activeVerb: text(metadata['activeVerb']) || text(metadata['active_verb']),
    completedVerb: text(metadata['completedVerb']) || text(metadata['completed_verb']),
    workflowType: knownWorkflowType(metadata['workflowType'] ?? metadata['workflow_type']),
    assignment: text(metadata['assignment']),
    result: text(metadata['result']),
    status,
    content,
    inProgress: status === 'started',
    isLead: false
  };
}

export function isTerminalAgentStatus(status: AiAgentExecutionStatus): boolean {
  return status === 'completed' || status === 'failed' || status === 'cancelled';
}

/**
 * Applies one lifecycle update to the activity list in place, keyed by the
 * stable agent identity. A terminal status never regresses to a live one.
 * Returns false when the update was ignored as a stale regression.
 */
export function upsertAgentActivity(activities: AiAgentActivity[],
                                    update: AiAgentActivityUpdate,
                                    now = Date.now()): boolean {
  const existing = activities.find(activity => activity.agentId === update.agentId);
  if (!existing) {
    const activity: AiAgentActivity = {
      ...update,
      firstSeenAt: now,
      lastUpdateAt: now,
      events: [{status: update.status, content: update.content}],
      messages: []
    };
    activities.push(activity);
    applyAgentConversationLifecycle(activity, update);
    return true;
  }
  if (isTerminalAgentStatus(existing.status) && !isTerminalAgentStatus(update.status)) {
    return false;
  }
  existing.messages ||= [];
  if (existing.status === 'planned' && update.status === 'started') {
    // Queue time is not worker execution time.
    existing.firstSeenAt = now;
  }
  existing.agentName = update.agentName;
  existing.agentRole = update.agentRole || existing.agentRole;
  existing.taskLabel = update.taskLabel || existing.taskLabel;
  existing.plannedAgentCount = update.plannedAgentCount || existing.plannedAgentCount;
  existing.activeVerb = update.activeVerb || existing.activeVerb;
  existing.completedVerb = update.completedVerb || existing.completedVerb;
  existing.workflowType = update.workflowType || existing.workflowType;
  existing.assignment = update.assignment || existing.assignment;
  existing.result = update.result || existing.result;
  existing.status = update.status;
  existing.content = update.content;
  existing.inProgress = update.inProgress;
  existing.isLead = existing.isLead || update.isLead;
  existing.lastUpdateAt = now;
  const lastEvent = existing.events[existing.events.length - 1];
  if (!lastEvent || lastEvent.status !== update.status || lastEvent.content !== update.content) {
    existing.events.push({status: update.status, content: update.content});
  }
  applyAgentConversationLifecycle(existing, update);
  return true;
}

function applyAgentConversationLifecycle(activity: AiAgentActivity,
                                         update: AiAgentActivityUpdate): void {
  appendAgentAssignment(activity, update.assignment);
  if (update.status === 'planned') return;
  if (update.status === 'started' || update.status === 'synthesizing') {
    appendAgentGuide(activity, update.content);
    showAgentStatus(activity, WORKING_STATUS_LABEL, true);
    return;
  }
  clearAgentStatus(activity);
  if (update.status === 'completed' && update.result) {
    appendDistinctMessage(activity.messages ||= [], {
      role: 'assistant', content: update.result
    });
    return;
  }
  if (update.status === 'failed' || update.status === 'cancelled') {
    appendDistinctMessage(activity.messages ||= [], {
      role: 'error', content: update.content
    });
  }
}

function appendAgentAssignment(activity: AiAgentActivity,
                               assignment: string | undefined): void {
  const normalized = assignment?.trim();
  if (!normalized) return;
  const messages = activity.messages ||= [];
  if (messages.some(message => message.role === 'user' && message.content === normalized)) return;
  messages.unshift({role: 'user', content: normalized});
}

export function settleAgentConversation(activity: AiAgentActivity,
                                        status: 'completed' | 'failed' | 'cancelled',
                                        content: string): void {
  clearAgentStatus(activity);
  if (status !== 'completed') {
    appendDistinctMessage(activity.messages ||= [], {role: 'error', content});
  }
}

function appendAgentGuide(activity: AiAgentActivity, content: string): void {
  const normalized = content.trim();
  if (!normalized || isWorkingStatusText(normalized)) return;
  clearAgentStatus(activity);
  appendDistinctMessage(activity.messages ||= [], {role: 'guide', content: normalized});
}

function showAgentStatus(activity: AiAgentActivity, content: string,
                         inProgress: boolean, tone: AiChatStatusTone = 'neutral'): void {
  clearAgentStatus(activity);
  (activity.messages ||= []).push({
    role: 'progress', content, inProgress, eventType: 'agent_status',
    ...(inProgress && isWorkingStatusText(content)
      ? {statusStartedAt: activity.firstSeenAt} : {}),
    ...(tone !== 'neutral' ? {statusTone: tone} : {})
  });
}

function clearAgentStatus(activity: AiAgentActivity): void {
  const messages = activity.messages ||= [];
  const index = messages.findIndex(message =>
    message.role === 'progress' && message.eventType === 'agent_status');
  if (index >= 0) messages.splice(index, 1);
}

function appendDistinctMessage(messages: AiChatMessage[], message: AiChatMessage): void {
  const last = messages[messages.length - 1];
  if (last?.role === message.role && last.content === message.content
    && last.eventType === message.eventType) {
    return;
  }
  messages.push(message);
}

export function agentActivitySummary(activities: AiAgentActivity[]): string {
  const specialists = activities.filter(activity => !activity.isLead);
  const done = specialists.filter(activity => activity.status === 'completed').length;
  const failed = specialists.filter(activity => activity.status === 'failed').length;
  const stopped = specialists.filter(activity => activity.status === 'cancelled').length;
  const running = specialists.filter(activity => activity.inProgress).length;
  const parts: string[] = [];
  if (done > 0) parts.push(`${done} done`);
  if (failed > 0) parts.push(`${failed} failed`);
  if (stopped > 0) parts.push(`${stopped} stopped`);
  if (activities.some(activity => activity.isLead && activity.status === 'synthesizing')) {
    parts.push('synthesizing');
  } else if (running > 0) {
    parts.push(`${running} running`);
  }
  return parts.join(' · ');
}

const SPECIALIST_AGENT_ID_PATTERN = /-agent-\d\d$/;

/**
 * Resolves the stable execution-node identity for a specialist event. New
 * servers declare the worker scope explicitly; the conversation kind,
 * parent-node link, and legacy fan-out id pattern keep persisted and rolling-
 * upgrade events compatible.
 */
export function specialistActivityAgentId(event: AiChatSocketEvent): string | undefined {
  const metadata = event.metadata || {};
  const agentId = text(metadata['nodeId']) || text(metadata['node_id']) || text(metadata['agentId']);
  if (!agentId) return undefined;
  const executionScope = (text(metadata['executionScope'])
    || text(metadata['execution_scope']))?.toLowerCase();
  const conversationKind = (text(metadata['conversationKind'])
    || text(metadata['conversation_kind']))?.toUpperCase();
  const parentNodeId = text(metadata['parentNodeId']) || text(metadata['parent_node_id']);
  const specialistOwned = executionScope === 'worker'
    || conversationKind === 'SUBAGENT' || conversationKind === 'PARALLEL'
    || !!parentNodeId || SPECIALIST_AGENT_ID_PATTERN.test(agentId);
  return specialistOwned ? agentId : undefined;
}

export function isSpecialistActivityEvent(event: AiChatSocketEvent): boolean {
  return !!specialistActivityAgentId(event);
}

/** Specialist tools belong to the owning agent timeline, never the main chat. */
export function isSpecialistToolEvent(event: AiChatSocketEvent): boolean {
  return (event.type === 'tool_call' || event.type === 'tool_group')
    && isSpecialistActivityEvent(event);
}

export function specialistToolAgentId(event: AiChatSocketEvent): string | undefined {
  if (!isSpecialistToolEvent(event)) {
    return undefined;
  }
  return specialistActivityAgentId(event);
}

function upsertAgentProviderEvent(activities: AiAgentActivity[],
                                  event: AiChatSocketEvent,
                                  status: 'provider_error' | 'provider_retry',
                                  now: number): boolean {
  const agentId = specialistActivityAgentId(event);
  if (!agentId) return false;
  const activity = activities.find(candidate => candidate.agentId === agentId);
  const content = event.content || event.response || event.message || '';
  if (!activity || !content.trim()) return false;
  if (!activity.inProgress) return true;
  const last = activity.events[activity.events.length - 1];
  if (!last || last.status !== status || last.content !== content) {
    activity.events.push({status, content});
  }
  showAgentStatus(activity, content, true, 'error');
  if (activity.inProgress) activity.content = content;
  activity.lastUpdateAt = now;
  return true;
}

/** Reflects a worker's provider error on its own agent timeline. */
export function upsertAgentProviderErrorEvent(activities: AiAgentActivity[],
                                               event: AiChatSocketEvent,
                                               now = Date.now()): boolean {
  return upsertAgentProviderEvent(activities, event, 'provider_error', now);
}

/** Reflects a worker's provider retry after the corresponding error. */
export function upsertAgentRetryEvent(activities: AiAgentActivity[],
                                      event: AiChatSocketEvent,
                                      now = Date.now()): boolean {
  return upsertAgentProviderEvent(activities, event, 'provider_retry', now);
}

export function upsertAgentGuideEvent(activities: AiAgentActivity[],
                                      event: AiChatSocketEvent,
                                      now = Date.now()): boolean {
  const agentId = specialistActivityAgentId(event);
  if (!agentId) return false;
  const activity = activities.find(candidate => candidate.agentId === agentId);
  const content = event.content || event.response || event.message || '';
  if (!activity || !content.trim()) return false;
  if (!activity.inProgress) return true;
  const last = activity.events[activity.events.length - 1];
  if (!last || last.content !== content) {
    activity.events.push({status: activity.status, content});
  }
  appendAgentGuide(activity, content);
  if (activity.inProgress) showAgentStatus(activity, WORKING_STATUS_LABEL, true);
  if (activity.inProgress) activity.content = content;
  activity.lastUpdateAt = now;
  return true;
}

export function agentToolEventContent(event: AiChatSocketEvent): string {
  const subtype = text(event.subtype) || 'update';
  const metadata = event.metadata || {};
  const toolName = displayToolName(text(metadata['toolName']) || text(metadata['tool_name']));
  if (subtype === 'started' || subtype === 'progress') {
    return toolName ? `Calling ${toolName}.` : 'Executing...';
  }
  if (subtype === 'completed') return toolName ? `${toolName} completed.` : 'Executed';
  if (subtype === 'failed') return toolName ? `${toolName} failed.` : 'Execution failed';
  if (subtype === 'blocked') {
    return toolName ? `${toolName} is awaiting approval.` : 'Awaiting approval';
  }
  if (subtype === 'denied') {
    return toolName ? `${toolName} was denied before execution.` : 'Denied before execution';
  }
  if (subtype === 'cancelled') {
    return toolName ? `${toolName} was stopped before execution.` : 'Stopped before execution';
  }
  return 'Executing...';
}

export function agentToolEventKey(event: AiChatSocketEvent): string | undefined {
  if (!event.groupId && !event.toolCallId) {
    return undefined;
  }
  return `${event.type}:${event.groupId || ''}:${event.toolCallId || ''}:${event.subtype || ''}`;
}

/** Identity of one tool invocation across its lifecycle, without the subtype. */
export function agentToolInvocationKey(event: AiChatSocketEvent): string | undefined {
  if (event.type !== 'tool_call' || !event.groupId || !event.toolCallId) {
    return undefined;
  }
  return `${event.groupId}:${event.toolCallId}`;
}

export function agentToolEventStatus(
  event: AiChatSocketEvent
): 'started' | 'completed' | 'failed' | 'blocked' | 'denied' | 'cancelled' | undefined {
  if (event.type !== 'tool_call' && event.type !== 'tool_group') {
    return undefined;
  }
  if (event.subtype === 'started' || event.subtype === 'progress') {
    return 'started';
  }
  return event.subtype === 'completed' || event.subtype === 'failed'
    || event.subtype === 'blocked' || event.subtype === 'denied'
    || event.subtype === 'cancelled'
    ? event.subtype : undefined;
}

export function agentToolEventDetail(event: AiChatSocketEvent): string | undefined {
  const detail = event.metadata?.['toolDetail'];
  if (typeof detail === 'string' && detail.trim()) {
    return displayToolText(detail);
  }
  const content = event.content || event.response || event.message;
  return typeof content === 'string' && /\nArguments:\s*/.test(content)
    ? displayToolText(content.trim()) : undefined;
}

/** Applies a specialist tool frame to the timeline shared by live and restored chat. */
export function upsertAgentToolEvent(activities: AiAgentActivity[],
                                     event: AiChatSocketEvent,
                                     now = Date.now()): boolean {
  const agentId = specialistToolAgentId(event);
  const activity = agentId
    ? activities.find(candidate => candidate.agentId === agentId) : undefined;
  if (!activity) return false;
  if (!activity.inProgress) return true;
  const content = agentToolEventContent(event);
  const key = agentToolEventKey(event);
  const toolKey = agentToolInvocationKey(event);
  const toolStatus = agentToolEventStatus(event);
  const detail = agentToolEventDetail(event);
  const invocation = toolKey
    ? activity.events.find(entry => entry.status === 'tool' && entry.toolKey === toolKey)
    : undefined;
  if (invocation) {
    invocation.content = content;
    if (key) invocation.key = key;
    if (toolStatus) invocation.toolStatus = toolStatus;
    if (detail) invocation.detail = detail;
  } else {
    const duplicate = key
      ? activity.events.some(entry => entry.key === key && entry.content === content)
      : activity.events[activity.events.length - 1]?.status === 'tool'
        && activity.events[activity.events.length - 1]?.content === content;
    if (duplicate) return true;
    activity.events.push({
      status: 'tool', content,
      ...(key ? {key} : {}),
      ...(toolKey ? {toolKey} : {}),
      ...(toolStatus ? {toolStatus} : {}),
      ...(detail ? {detail} : {})
    });
  }
  if (toolStatus === 'started') {
    showAgentStatus(activity, content, true);
  } else if (toolStatus) {
    clearAgentStatus(activity);
    const existingMessage = event.toolCallId
      ? activity.messages?.find(message => message.role === 'tool_call'
        && message.groupId === event.groupId
        && message.toolCallId === event.toolCallId) : undefined;
    const toolMessage: AiChatMessage = {
      role: 'tool_call', content,
      inProgress: false,
      eventType: 'tool_call',
      ...(event.groupId ? {groupId: event.groupId} : {}),
      ...(event.toolCallId ? {toolCallId: event.toolCallId} : {}),
      ...(text(event.metadata?.['toolName']) || text(event.metadata?.['tool_name'])
        ? {toolName: text(event.metadata?.['toolName']) || text(event.metadata?.['tool_name'])}
        : {}),
      ...(detail ? {toolDetail: detail} : {}),
      toolStatus
    };
    if (existingMessage) Object.assign(existingMessage, toolMessage);
    else (activity.messages ||= []).push(toolMessage);
    if (activity.inProgress) showAgentStatus(activity, WORKING_STATUS_LABEL, true);
  }
  if (activity.inProgress) activity.content = content;
  activity.lastUpdateAt = now;
  return true;
}

export function agentActivityElapsedLabel(activity: AiAgentActivity, now = Date.now()): string {
  const end = activity.inProgress ? now : activity.lastUpdateAt;
  const seconds = Math.max(0, Math.round((end - activity.firstSeenAt) / 1000));
  if (seconds < 60) {
    return `${seconds}s`;
  }
  const minutes = Math.floor(seconds / 60);
  return `${minutes}m ${String(seconds % 60).padStart(2, '0')}s`;
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function positiveInteger(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
    ? value : undefined;
}

function knownWorkflowType(value: unknown): AiWorkflowType | undefined {
  return value === 'direct' || value === 'sequential' || value === 'parallel'
    ? value : undefined;
}
