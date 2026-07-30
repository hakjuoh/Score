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
