import {AiChatCommand} from './ai-chat-panel.model';

export const AI_CHAT_COMMANDS: AiChatCommand[] = [
  {name: '/clear', description: 'Start a new session with empty context.', kind: 'local'},
  {name: '/model', description: 'Change the model and reasoning effort for this session.', kind: 'local'},
  {name: '/runtime', description: 'Change the AI runtime for this session.', kind: 'local'},
  {name: '/permissions', description: 'Control approval for data-changing assistant actions.', kind: 'local'},
  {name: '/cancel', description: 'Cancel the active request.', kind: 'local'},
  {name: '/debug', description: 'Toggle detailed progress events for this chat.', kind: 'local'},
  {name: '/compact', description: 'Ask the backend to summarize the conversation context.', kind: 'backend'}
];

export const SUPPORTED_ATTACHMENT_TYPES = new Set([
  'text/plain',
  'text/csv',
  'text/tab-separated-values',
  'text/xml',
  'image/png',
  'image/jpeg',
  'image/gif',
  'image/webp',
  'application/pdf',
  'application/json',
  'application/xml',
]);

export const TEXT_ATTACHMENT_EXTENSIONS = [
  '.txt', '.md', '.markdown', '.csv', '.tsv', '.json', '.jsonl',
  '.xml', '.xsd', '.xsl', '.xslt', '.yaml', '.yml', '.html', '.htm',
  '.css', '.js', '.ts', '.sql', '.log', '.properties', '.ini', '.cfg'
];

/** Single source of truth for the browser picker and attachment validator. */
export const AI_CHAT_ATTACHMENT_ACCEPT = [
  ...TEXT_ATTACHMENT_EXTENSIONS,
  ...SUPPORTED_ATTACHMENT_TYPES
].join(',');

export const MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024;
export const MAX_TOTAL_ATTACHMENT_BYTES = 20 * 1024 * 1024;
export const MAX_ATTACHMENTS = 10;
export const MAX_STOMP_RECONNECT_ATTEMPTS = 3;
export const STOMP_RECONNECT_ATTEMPT_TIMEOUT_MS = 10000;
export const CANCELLATION_ACK_TIMEOUT_MS = 2000;
export const CANCELLATION_TERMINAL_TIMEOUT_MS = 5000;
export const CANCELLATION_ADMISSION_RETRY_MS = 250;
export const MAX_CANCELLATION_ADMISSION_RETRIES = 6;
export const COMPLETED_PAYLOAD_WAIT_MS = 1000;
export const MUTATION_CONFIRMATION_DECISION_TIMEOUT_MS = 10000;
export const CONFIRMED_MUTATION_RESPONSE_TIMEOUT_MS = 300000;
export const REQUEST_STATUS_WATCHDOG_MS = 10000;
