export const FORMATTER_META_RESPONSE_PATTERN =
  /\b(ui link formatter|ui formatter|already-correct assistant answer|already-correct assistant answers|do not execute tools|do not answer questions independently|please provide the final answer|provide the final answer text|#\s*UI Formatting Input|##\s*Final Answer|##\s*Resource Route Registry)\b/i;

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
