import {AiAgentExecutionStatus, AiChatSocketEvent} from './ai-chat-panel.model';

const EXECUTION_ACTIVITY_SUBTYPES = new Set([
  'multi_agent_started',
  'subagent_started',
  'subagent_completed',
  'subagent_failed',
  'multi_agent_synthesizing',
  'multi_agent_completed',
  'multi_agent_failed',
  'parallel_workflow_started',
  'parallel_task_started',
  'parallel_task_completed',
  'parallel_task_failed',
  'parallel_workflow_synthesizing',
  'parallel_workflow_completed',
  'parallel_workflow_failed'
]);

export interface AiAgentActivityEvent {
  status: AiAgentExecutionStatus | 'tool';
  content: string;
  key?: string;
  /** Stable identity of one tool invocation, shared by its started/terminal events. */
  toolKey?: string;
  toolStatus?: 'started' | 'completed' | 'failed';
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
  workflow?: string;
  executionKind?: string;
  status: AiAgentExecutionStatus;
  content: string;
  inProgress: boolean;
  isLead: boolean;
  firstSeenAt: number;
  lastUpdateAt: number;
  events: AiAgentActivityEvent[];
}

export type AiAgentActivityUpdate = Omit<AiAgentActivity, 'firstSeenAt' | 'lastUpdateAt' | 'events'>;

export function isExecutionActivityEvent(event: AiChatSocketEvent): boolean {
  return event.type === 'system' && !!event.subtype
    && EXECUTION_ACTIVITY_SUBTYPES.has(event.subtype);
}

export function agentActivityUpdate(event: AiChatSocketEvent): AiAgentActivityUpdate | undefined {
  if (!isExecutionActivityEvent(event)) return undefined;
  const content = event.content || event.response || event.message || '';
  const metadata = event.metadata || {};
  const isLead = !!event.subtype?.startsWith('multi_agent')
    || !!event.subtype?.startsWith('parallel_workflow');
  const agentId = text(metadata['agentId']) || text(metadata['nodeId']) || text(metadata['node_id'])
    || (isLead ? `${event.requestId}:lead` : undefined);
  if (!agentId || !content) return undefined;
  const status: AiAgentExecutionStatus = event.subtype === 'subagent_failed'
    || event.subtype === 'multi_agent_failed' || event.subtype === 'parallel_task_failed'
    || event.subtype === 'parallel_workflow_failed' ? 'failed'
    : event.subtype === 'subagent_completed' || event.subtype === 'multi_agent_completed'
      || event.subtype === 'parallel_task_completed'
      || event.subtype === 'parallel_workflow_completed' ? 'completed'
      : event.subtype === 'multi_agent_synthesizing'
        || event.subtype === 'parallel_workflow_synthesizing' ? 'synthesizing' : 'started';
  return {
    agentId,
    agentName: text(metadata['agentName']) || text(metadata['agent_name'])
      || (isLead ? 'Lead agent' : 'Specialist'),
    agentRole: text(metadata['agentRole']) || text(metadata['agent_role']) || text(metadata['strategy']),
    taskLabel: text(metadata['taskLabel']) || text(metadata['task_label']),
    plannedAgentCount: positiveInteger(metadata['agent_count']),
    activeVerb: text(metadata['activeVerb']) || text(metadata['active_verb']),
    completedVerb: text(metadata['completedVerb']) || text(metadata['completed_verb']),
    workflow: text(metadata['workflow']),
    executionKind: text(metadata['executionKind']) || text(metadata['execution_kind'])
      || (event.subtype?.startsWith('parallel_') ? 'parallel' : 'multi_agent'),
    status,
    content,
    inProgress: status === 'started' || status === 'synthesizing',
    isLead
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
    activities.push({
      ...update,
      firstSeenAt: now,
      lastUpdateAt: now,
      events: [{status: update.status, content: update.content}]
    });
    return true;
  }
  if (isTerminalAgentStatus(existing.status) && !isTerminalAgentStatus(update.status)) {
    return false;
  }
  existing.agentName = update.agentName;
  existing.agentRole = update.agentRole || existing.agentRole;
  existing.taskLabel = update.taskLabel || existing.taskLabel;
  existing.plannedAgentCount = update.plannedAgentCount || existing.plannedAgentCount;
  existing.activeVerb = update.activeVerb || existing.activeVerb;
  existing.completedVerb = update.completedVerb || existing.completedVerb;
  existing.workflow = update.workflow || existing.workflow;
  existing.executionKind = update.executionKind || existing.executionKind;
  existing.status = update.status;
  existing.content = update.content;
  existing.inProgress = update.inProgress;
  existing.isLead = existing.isLead || update.isLead;
  existing.lastUpdateAt = now;
  const lastEvent = existing.events[existing.events.length - 1];
  if (!lastEvent || lastEvent.status !== update.status || lastEvent.content !== update.content) {
    existing.events.push({status: update.status, content: update.content});
  }
  return true;
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
 * Detects a tool lifecycle event recorded by a fan-out SPECIALIST. These must
 * never render as main-chat tool rows; they belong to the agent's timeline.
 */
export function isSpecialistToolEvent(event: AiChatSocketEvent): boolean {
  if (event.type !== 'tool_call' && event.type !== 'tool_group') {
    return false;
  }
  const metadata = event.metadata || {};
  const parentNodeId = text(metadata['parentNodeId']) || text(metadata['parent_node_id']);
  if (parentNodeId) {
    return true;
  }
  const agentId = text(metadata['agentId']) || text(metadata['nodeId']) || text(metadata['node_id']);
  return !!agentId && SPECIALIST_AGENT_ID_PATTERN.test(agentId);
}

export function specialistToolAgentId(event: AiChatSocketEvent): string | undefined {
  if (!isSpecialistToolEvent(event)) {
    return undefined;
  }
  const metadata = event.metadata || {};
  return text(metadata['agentId']) || text(metadata['nodeId']) || text(metadata['node_id']);
}

export function upsertAgentGuideEvent(activities: AiAgentActivity[],
                                      event: AiChatSocketEvent,
                                      now = Date.now()): boolean {
  const metadata = event.metadata || {};
  const parentNodeId = text(metadata['parentNodeId']) || text(metadata['parent_node_id']);
  const agentId = text(metadata['agentId']) || text(metadata['nodeId']) || text(metadata['node_id']);
  if (!parentNodeId || !agentId) return false;
  const activity = activities.find(candidate => candidate.agentId === agentId);
  const content = event.content || event.response || event.message || '';
  if (!activity || !content.trim()) return false;
  const last = activity.events[activity.events.length - 1];
  if (!last || last.content !== content) {
    activity.events.push({status: activity.status, content});
  }
  if (activity.inProgress) activity.content = content;
  activity.lastUpdateAt = now;
  return true;
}

export function agentToolEventContent(event: AiChatSocketEvent): string {
  const subtype = text(event.subtype) || 'update';
  const metadata = event.metadata || {};
  const toolName = text(metadata['toolName']) || text(metadata['tool_name']);
  if (subtype === 'started' || subtype === 'progress') {
    return toolName ? `Calling ${toolName}.` : 'Executing...';
  }
  if (subtype === 'completed') return toolName ? `${toolName} completed.` : 'Executed';
  if (subtype === 'failed') return toolName ? `${toolName} failed.` : 'Execution failed';
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

export function agentToolEventStatus(event: AiChatSocketEvent): 'started' | 'completed' | 'failed' | undefined {
  if (event.type !== 'tool_call' && event.type !== 'tool_group') {
    return undefined;
  }
  if (event.subtype === 'started' || event.subtype === 'progress') {
    return 'started';
  }
  return event.subtype === 'completed' || event.subtype === 'failed' ? event.subtype : undefined;
}

export function agentToolEventDetail(event: AiChatSocketEvent): string | undefined {
  const detail = event.metadata?.['toolDetail'];
  if (typeof detail === 'string' && detail.trim()) {
    return detail;
  }
  const content = event.content || event.response || event.message;
  return typeof content === 'string' && /\nArguments:\s*/.test(content)
    ? content.trim() : undefined;
}

/** Applies a specialist tool frame to the timeline shared by live and restored chat. */
export function upsertAgentToolEvent(activities: AiAgentActivity[],
                                     event: AiChatSocketEvent,
                                     now = Date.now()): boolean {
  const agentId = specialistToolAgentId(event);
  const activity = agentId
    ? activities.find(candidate => candidate.agentId === agentId) : undefined;
  if (!activity) return false;
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
