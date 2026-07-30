import {
  AiAgentActivity,
  AiAgentActivityGroupResolver,
  AiAgentActivityUpdate,
  agentActivityOwnerId,
  agentActivityUpdate,
  upsertAgentActivity
} from './ai-agent-activity';
import {AiChatMessage, AiChatSocketEvent} from './ai-chat-panel.model';

const WORKFLOW_TERMINAL_STATUSES = new Map<string, AiChatMessage['workflowStatus']>([
  ['workflow_completed', 'completed'],
  ['workflow_cancelled', 'cancelled'],
  ['workflow_output_retry_handoff', 'cancelled'],
  ['workflow_failed', 'failed'],
  ['workflow_stalled', 'failed'],
  ['workflow_refused', 'failed']
]);

export function workflowTerminalStatus(subtype: string | undefined):
  AiChatMessage['workflowStatus'] | undefined {
  return subtype ? WORKFLOW_TERMINAL_STATUSES.get(subtype) : undefined;
}

export interface AiWorkflowPlacement {
  anchor: AiChatMessage;
  container: AiChatMessage[];
  root: boolean;
  created: boolean;
}

export interface AiAgentPlacement {
  activities: AiAgentActivity[];
  anchor?: AiChatMessage;
  createdRootAnchor: boolean;
  update: AiAgentActivityUpdate;
  rootGroup: boolean;
}

export function appendWorkflowConversation(container: AiChatMessage[], content: string,
                                           anchor: AiChatMessage,
                                           live: boolean): void {
  removeConversationStatuses(container);
  const guide = content.trim();
  if (guide) container.push({role: 'guide', content: guide});
  container.push(anchor);
  if (live) {
    container.push({
      role: 'progress', content: 'Working...', inProgress: true,
      eventType: 'composite_status'
    });
  }
}

/**
 * Rebuilds the execution graph as a Composite of conversation containers.
 * The root chat, every Agent, and every nested Workflow own the same
 * `AiChatMessage[]` contract, so arbitrary Agent -> Workflow -> Agent nesting
 * does not require a special rendering path.
 */
export class AiExecutionComposite {
  private readonly workflows = new Map<string, AiChatMessage>();
  private readonly workflowContainers = new Map<string, AiChatMessage[]>();
  private readonly rootWorkflows = new Set<string>();
  private readonly workflowOwners = new Map<string, AiAgentActivity>();
  private readonly agents = new Map<string, AiAgentActivity>();
  private readonly fallbackGroups = new Map<string, AiAgentActivity[]>();
  private readonly fallbackResolver = new AiAgentActivityGroupResolver();
  private activeOwnerId?: string;

  constructor(private readonly retainMultipleOwners = false) {}

  startWorkflow(event: AiChatSocketEvent,
                rootMessages: AiChatMessage[]): AiWorkflowPlacement | undefined {
    if (event.type !== 'system' || event.subtype !== 'workflow_started') return undefined;
    this.prepareOwner(event);
    const nodeId = metadataText(event, 'nodeId', 'node_id');
    const parentNodeId = metadataText(event, 'parentNodeId', 'parent_node_id');
    const depth = event.metadata?.['depth'];
    if (!nodeId || typeof depth !== 'number' || depth < 1) return undefined;

    const key = this.nodeKey(event, nodeId);
    const existing = this.workflows.get(key);
    if (existing) {
      const container = this.parentMessages(event, parentNodeId, rootMessages);
      return {anchor: existing, container, root: container === rootMessages, created: false};
    }

    const anchor: AiChatMessage = {
      role: 'workflow_group',
      content: 'Workflow',
      groupId: key,
      activities: [],
      children: [],
      workflowNodeId: nodeId,
      ...(parentNodeId ? {workflowParentNodeId: parentNodeId} : {}),
      workflowStatus: 'started'
    };
    this.workflows.set(key, anchor);
    const container = this.parentMessages(event, parentNodeId, rootMessages);
    this.workflowContainers.set(key, container);
    if (container === rootMessages) this.rootWorkflows.add(key);
    if (parentNodeId) {
      const owner = this.agents.get(this.nodeKey(event, parentNodeId));
      if (owner) {
        this.workflowOwners.set(key, owner);
        owner.status = 'started';
        owner.inProgress = true;
        owner.content = event.content || event.message || owner.content;
        owner.lastUpdateAt = Date.now();
      }
    }
    return {anchor, container, root: container === rootMessages, created: true};
  }

  finishWorkflow(event: AiChatSocketEvent): boolean {
    const terminalStatus = event.type === 'system'
      ? workflowTerminalStatus(event.subtype) : undefined;
    if (!terminalStatus) return false;
    this.prepareOwner(event);
    const nodeId = metadataText(event, 'nodeId', 'node_id');
    const workflow = nodeId ? this.workflows.get(this.nodeKey(event, nodeId)) : undefined;
    if (!workflow) return false;
    workflow.workflowStatus = terminalStatus;
    const key = this.nodeKey(event, nodeId);
    if (!this.rootWorkflows.has(key)) {
      const container = this.workflowContainers.get(key);
      if (container) removeConversationStatuses(container);
    }
    const owner = this.workflowOwners.get(key);
    if (owner) {
      owner.status = workflow.workflowStatus || 'completed';
      owner.inProgress = false;
      owner.content = event.content || event.message || owner.content;
      owner.lastUpdateAt = Date.now();
      if (terminalStatus !== 'completed') {
        const content = event.content || event.message || 'The workflow did not complete.';
        const duplicate = owner.messages?.some(message =>
          message.role === 'error' && message.eventType === event.subtype
          && message.workflowNodeId === nodeId && message.content === content);
        if (!duplicate) {
          (owner.messages ||= []).push({
            role: 'error', content, inProgress: false, eventType: event.subtype,
            workflowNodeId: nodeId
          });
        }
      }
    }
    return true;
  }

  placeAgent(event: AiChatSocketEvent,
             rootMessages: AiChatMessage[] = []): AiAgentPlacement | undefined {
    const update = agentActivityUpdate(event);
    if (!update) return undefined;
    this.prepareOwner(event);
    const parentNodeId = metadataText(event, 'parentNodeId', 'parent_node_id');
    const workflow = parentNodeId
      ? this.workflows.get(this.nodeKey(event, parentNodeId))
        || this.restoreWorkflowReference(event, parentNodeId, rootMessages)
      : undefined;
    let activities: AiAgentActivity[];
    let anchor: AiChatMessage | undefined;
    let createdRootAnchor = false;

    if (workflow) {
      activities = workflow.activities!;
      anchor = workflow;
    } else {
      const groupId = this.fallbackResolver.resolve(event);
      activities = this.fallbackGroups.get(groupId) || [];
      createdRootAnchor = !this.fallbackGroups.has(groupId);
      if (createdRootAnchor) this.fallbackGroups.set(groupId, activities);
      anchor = createdRootAnchor ? {
        role: update.executionKind === 'parallel' ? 'workflow_group' : 'agent_group',
        content: update.executionKind === 'parallel' ? 'Parallel workflow' : 'Delegated workflow',
        groupId,
        activities,
        children: []
      } : undefined;
    }

    if (!upsertAgentActivity(activities, update)) return undefined;
    const activity = activities.find(candidate => candidate.agentId === update.agentId);
    if (activity) this.agents.set(this.nodeKey(event, update.agentId), activity);
    const rootGroup = workflow && parentNodeId
      ? this.rootWorkflows.has(this.nodeKey(event, parentNodeId))
      : true;
    return {activities, anchor, createdRootAnchor, update, rootGroup};
  }

  activitiesFor(event: AiChatSocketEvent): AiAgentActivity[] | undefined {
    this.prepareOwner(event);
    const nodeId = metadataText(event, 'nodeId', 'node_id', 'agentId');
    const activity = nodeId ? this.agents.get(this.nodeKey(event, nodeId)) : undefined;
    if (activity) return [activity];
    const parentNodeId = metadataText(event, 'parentNodeId', 'parent_node_id');
    return parentNodeId
      ? this.workflows.get(this.nodeKey(event, parentNodeId))?.activities
      : undefined;
  }

  clear(): void {
    this.workflows.clear();
    this.workflowContainers.clear();
    this.rootWorkflows.clear();
    this.workflowOwners.clear();
    this.agents.clear();
    this.fallbackGroups.clear();
    this.fallbackResolver.clear();
    this.activeOwnerId = undefined;
  }

  private parentMessages(event: AiChatSocketEvent, parentNodeId: string | undefined,
                         rootMessages: AiChatMessage[]): AiChatMessage[] {
    if (!parentNodeId) return rootMessages;
    const key = this.nodeKey(event, parentNodeId);
    const agent = this.agents.get(key);
    if (agent) return agent.messages ||= [];
    const workflow = this.workflows.get(key);
    return workflow?.children || rootMessages;
  }

  private restoreWorkflowReference(event: AiChatSocketEvent, nodeId: string,
                                   messages: AiChatMessage[]): AiChatMessage | undefined {
    const key = this.nodeKey(event, nodeId);
    const workflow = findWorkflow(messages, nodeId, key);
    if (!workflow) return undefined;
    workflow.activities ||= [];
    workflow.children ||= [];
    workflow.workflowNodeId ||= nodeId;
    this.workflows.set(key, workflow);
    this.registerWorkflowDescendants(event, workflow);
    return workflow;
  }

  private registerWorkflowDescendants(event: AiChatSocketEvent,
                                      workflow: AiChatMessage): void {
    for (const activity of workflow.activities || []) {
      activity.messages ||= [];
      this.agents.set(this.nodeKey(event, activity.agentId), activity);
      this.registerMessageWorkflows(event, activity.messages);
    }
    this.registerMessageWorkflows(event, workflow.children || []);
  }

  private registerMessageWorkflows(event: AiChatSocketEvent,
                                   messages: AiChatMessage[]): void {
    for (const message of messages) {
      if ((message.role === 'workflow_group' || message.role === 'agent_group')
        && message.workflowNodeId) {
        const key = this.nodeKey(event, message.workflowNodeId);
        message.activities ||= [];
        message.children ||= [];
        this.workflows.set(key, message);
        this.registerWorkflowDescendants(event, message);
      } else {
        this.registerMessageWorkflows(event, message.children || []);
      }
    }
  }

  private prepareOwner(event: AiChatSocketEvent): void {
    if (this.retainMultipleOwners) return;
    const ownerId = agentActivityOwnerId(event);
    if (this.activeOwnerId && this.activeOwnerId !== ownerId) this.clear();
    this.activeOwnerId = ownerId;
  }

  private nodeKey(event: AiChatSocketEvent, nodeId: string): string {
    return `${agentActivityOwnerId(event)}\u0000${nodeId}`;
  }
}

function removeConversationStatuses(messages: AiChatMessage[]): void {
  for (let index = messages.length - 1; index >= 0; index--) {
    const message = messages[index];
    if (message.role === 'progress'
      && (message.eventType === 'agent_status'
        || message.eventType === 'composite_status')) {
      messages.splice(index, 1);
    }
  }
}

function findWorkflow(messages: AiChatMessage[], nodeId: string,
                      groupKey: string): AiChatMessage | undefined {
  for (let index = messages.length - 1; index >= 0; index--) {
    const message = messages[index];
    if ((message.role === 'workflow_group' || message.role === 'agent_group')
      && (message.workflowNodeId === nodeId || message.groupId === groupKey)) {
      return message;
    }
    for (const activity of message.activities || []) {
      const nested = findWorkflow(activity.messages || [], nodeId, groupKey);
      if (nested) return nested;
    }
    const nested = findWorkflow(message.children || [], nodeId, groupKey);
    if (nested) return nested;
  }
  return undefined;
}

function metadataText(event: AiChatSocketEvent, ...keys: string[]): string | undefined {
  for (const key of keys) {
    const value = event.metadata?.[key];
    if (typeof value === 'string' && value.trim()) return value.trim();
  }
  return undefined;
}
