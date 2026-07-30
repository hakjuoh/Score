import type {AiChatToolStatus} from './ai-chat-panel.model';

const TOOL_SEARCH_TOOL_NAME = 'toolSearchTool';
const TOOL_SEARCH_TOOL_DISPLAY_NAME = 'tool_search_tool';
const TOOL_SEARCH_TOOL_NAME_PATTERN = /\btoolSearchTool\b/g;

/**
 * Keeps Spring AI's protocol-level tool identifier stable while presenting all
 * tools with the snake-case naming convention used by connectCenter tools.
 */
export function displayToolName(toolName: string | undefined): string | undefined {
  return toolName === TOOL_SEARCH_TOOL_NAME ? TOOL_SEARCH_TOOL_DISPLAY_NAME : toolName;
}

/** Normalizes tool names embedded in user-visible lifecycle or detail text. */
export function displayToolText(text: string | undefined): string | undefined {
  return text?.replace(TOOL_SEARCH_TOOL_NAME_PATTERN, TOOL_SEARCH_TOOL_DISPLAY_NAME);
}

/** Canonical fallback copy for one terminal tool lifecycle status. */
export function defaultToolStatusContent(
  status: AiChatToolStatus,
  toolName: string | undefined
): string {
  switch (status) {
    case 'completed':
      return toolName ? `${toolName} completed.` : 'Executed';
    case 'failed':
      return toolName ? `${toolName} failed.` : 'Execution failed';
    case 'blocked':
      return toolName ? `${toolName} is awaiting approval.` : 'Awaiting approval';
    case 'denied':
      return toolName ? `${toolName} was denied before execution.` : 'Denied before execution';
    case 'cancelled':
      return toolName ? `${toolName} was stopped before execution.` : 'Stopped before execution';
  }
  const unsupportedStatus: never = status;
  throw new Error(`Unsupported tool status: ${unsupportedStatus}`);
}
