/**
 * Defines user-facing labels and display patterns shared by chat presentation logic.
 */

export const FORMATTER_META_RESPONSE_PATTERN =
  /\b(ui link formatter|ui formatter|already-correct assistant answer|already-correct assistant answers|do not execute tools|do not answer questions independently|please provide the final answer|provide the final answer text|#\s*UI Formatting Input|##\s*Final Answer|##\s*Resource Route Registry)\b/i;

export const WORKING_STATUS_LABEL = 'Working';

/** Accepts persisted/server values from before the Working label was normalized. */
export function isWorkingStatusText(value: string | undefined): boolean {
  return /^Working(?:\.{1,3})?$/.test(value?.trim() || '');
}

export const SAFE_ATTACHMENT_ERROR_PATTERNS = [
  /^Encoded attachment exceeds the 8 MB per-file limit: .+$/,
  /^Attachment is not valid Base64: .+$/,
  /^Attachment exceeds the 8 MB per-file limit: .+$/,
  /^Attachments exceed the 20 MB request limit\.$/,
  /^Unsupported AI attachment type: .+$/,
  /^Could not encode attachment data\.$/,
  /^A prompt or attachment is required\.$/,
  /^A maximum of 10 attachments is allowed per request\.$/
];
