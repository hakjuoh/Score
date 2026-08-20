/**
 * Decides which live, replayed, and side-channel events each request path may process.
 */

import {
  isExecutionActivityEvent,
  isSpecialistToolEvent
} from './ai-agent-activity';
import {
  isReconciliationRequired,
  primaryContent,
  terminalRequestErrorStatus,
  toolCallEventSemantics
} from './ai-chat-event-semantics';
import {AiChatSocketEvent} from './ai-chat-panel.model';
import {isWorkflowLifecycleEvent, workflowTerminalStatus} from './ai-execution-composite';

const REPLAY_SYSTEM_SUBTYPES = new Set([
  'context_usage', 'context_compacted', 'guide', 'policy_notice', 'workflow_result',
  'provider_error', 'provider_retry'
]);
const LIVE_SYSTEM_SUBTYPES = new Set([
  'guide', 'policy_notice', 'workflow_result', 'provider_error', 'provider_retry',
  'tool_output_truncated'
]);
const RECOGNIZED_SYSTEM_SUBTYPES = new Set([
  'data_changed', 'data_change_rejected', 'audit_failed', 'cancelled',
  'authentication_failed', 'error', 'model_fallback', 'provider_error',
  'provider_retry', 'workflow_result', 'context_usage', 'context_compacted',
  'tool_output_truncated',
  'guide', 'policy_notice', 'workflow_started', 'progress', 'starting', 'started',
  'completed', 'agent_lifecycle', 'fanout_usage', 'agent_status'
]);

export type RequestEventAdmission =
  | 'admit'
  | 'reject'
  | 'requires-active-identity'
  | 'requires-terminal-identity';

export type RestReplayDisposition =
  | 'ignore'
  | 'socket'
  | 'confirmation'
  | 'system'
  | 'tool';

export type InteractionEventDisposition =
  | 'none'
  | 'change-approval-required'
  | 'change-approval-decision'
  | 'change-confirmation-required'
  | 'elicitation-required'
  | 'elicitation-decision';

interface InteractionPolicy {
  disposition: Exclude<InteractionEventDisposition, 'none'>;
  replay: RestReplayDisposition;
  live: boolean;
}

const INTERACTION_POLICIES = new Map<string, InteractionPolicy>([
  ['change_approval_batch_required',
    {disposition: 'change-approval-required', replay: 'socket', live: true}],
  ['change_approval_decision_accepted',
    {disposition: 'change-approval-decision', replay: 'socket', live: true}],
  ['change_approval_decision_rejected',
    {disposition: 'change-approval-decision', replay: 'socket', live: true}],
  ['change_confirmation_required',
    {disposition: 'change-confirmation-required', replay: 'confirmation', live: false}],
  ['elicitation_required',
    {disposition: 'elicitation-required', replay: 'ignore', live: true}],
  ['elicitation_decision_accepted',
    {disposition: 'elicitation-decision', replay: 'ignore', live: true}],
  ['elicitation_decision_rejected',
    {disposition: 'elicitation-decision', replay: 'ignore', live: true}]
]);

export function interactionEventDisposition(
  event: AiChatSocketEvent
): InteractionEventDisposition {
  return event.type === 'system'
    ? INTERACTION_POLICIES.get(event.subtype || '')?.disposition || 'none'
    : 'none';
}

export function requestEventAdmission(event: AiChatSocketEvent): RequestEventAdmission {
  if (event.type === 'assistant_update') {
    return primaryContent(event) ? 'admit' : 'reject';
  }
  if (event.type === 'assistant_final') {
    return typeof event.conversationId === 'string' && !!event.conversationId.trim()
      && !!primaryContent(event).trim() ? 'admit' : 'reject';
  }
  if (event.type === 'tool_call') {
    return toolCallEventSemantics(event) ? 'admit' : 'reject';
  }
  if (event.type === 'tool_group') {
    return event.groupId && (event.subtype === 'started' || event.subtype === 'progress'
      || event.subtype === 'completed' || event.subtype === 'failed') ? 'admit' : 'reject';
  }
  if (event.type === 'system') {
    if (event.subtype === 'request_error') {
      return terminalRequestErrorStatus(event) ? 'requires-terminal-identity' : 'reject';
    }
    if (event.subtype === 'reconciliation_required') {
      return isReconciliationRequired(event) ? 'requires-terminal-identity' : 'reject';
    }
    if (event.subtype === 'accepted') {
      return 'requires-active-identity';
    }
    return RECOGNIZED_SYSTEM_SUBTYPES.has(event.subtype || '')
      || !!workflowTerminalStatus(event.subtype)
      || isExecutionActivityEvent(event)
      || event.visibility === 'debug'
      || event.metadata?.['inProgress'] === true
      || (!!event.content && event.visibility === 'visible') ? 'admit' : 'reject';
  }
  return event.type === 'UI_FORMATTED' && !!event.response ? 'admit' : 'reject';
}

export function restReplayDisposition(event: AiChatSocketEvent): RestReplayDisposition {
  if (event.type === 'system') {
    if (event.subtype === 'request_error') {
      return terminalRequestErrorStatus(event) ? 'socket' : 'ignore';
    }
    const interaction = INTERACTION_POLICIES.get(event.subtype || '');
    if (interaction) return interaction.replay;
  }
  if ((event.type === 'system' && REPLAY_SYSTEM_SUBTYPES.has(event.subtype || ''))
    || isWorkflowLifecycleEvent(event) || isExecutionActivityEvent(event)) {
    return 'system';
  }
  return event.type === 'tool_call' || event.type === 'tool_group' ? 'tool' : 'ignore';
}

export function admitsRestLiveSideChannel(event: AiChatSocketEvent): boolean {
  return isWorkflowLifecycleEvent(event)
    || isExecutionActivityEvent(event)
    || isSpecialistToolEvent(event)
    || event.type === 'tool_call'
    || event.type === 'tool_group'
    || event.type === 'system' && (LIVE_SYSTEM_SUBTYPES.has(event.subtype || '')
      || INTERACTION_POLICIES.get(event.subtype || '')?.live === true);
}
