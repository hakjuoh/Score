import {AiChatSocketEvent, AiWorkflowType} from './ai-chat-panel.model';

export type AiWorkflowPresentation = 'hidden' | 'box' | 'message';

export function workflowType(event: AiChatSocketEvent): AiWorkflowType | undefined {
  const value = event.metadata?.['workflowType'] ?? event.metadata?.['workflow_type'];
  return value === 'direct' || value === 'sequential' || value === 'parallel'
    ? value : undefined;
}

export function workflowTypeValue(event: AiChatSocketEvent): string | undefined {
  const value = event.metadata?.['workflowType'] ?? event.metadata?.['workflow_type'];
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

export function workflowPresentation(type: AiWorkflowType | undefined): AiWorkflowPresentation {
  if (type === 'direct') return 'hidden';
  if (type === 'sequential' || type === 'parallel') return 'box';
  return 'message';
}
