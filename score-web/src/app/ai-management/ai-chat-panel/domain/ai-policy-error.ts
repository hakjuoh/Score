/**
 * User-facing error messages for AI Policy violations.
 * Explains specific restriction reasons and instructs the user to contact their administrator.
 */

export const AI_POLICY_ERROR_MESSAGES: Record<string, string> = {
  AI_DISABLED_BY_POLICY:
    'AI Assistant access is disabled by your administrator. Please contact your administrator to request access.',
  AI_QUOTA_EXHAUSTED:
    'Your AI token quota has been exhausted. Please contact your administrator to request a quota increase.',
  AI_REQUEST_TOKEN_LIMIT_EXHAUSTED:
    'This request exceeded the maximum allowed token limit per request. Please shorten your prompt or contact your administrator.',
  AI_ACTIVE_REQUEST_LIMIT:
    'You have reached the maximum number of concurrent active AI requests. Please wait for existing requests to complete or contact your administrator.',
  AI_MODEL_NOT_ALLOWED:
    'The requested AI model is not permitted by your access policy. Please choose an allowed model or contact your administrator.',
  AI_REASONING_EFFORT_NOT_ALLOWED:
    'The requested reasoning effort is not permitted by your access policy. Please adjust your settings or contact your administrator.',
  AI_NO_ALLOWED_MODELS:
    'There are no AI models currently permitted for your account. Please contact your administrator.',
  AI_PROVIDER_NOT_CONFIGURED:
    'The AI provider is not configured on the server. Please contact your administrator.',
  AI_POLICY_VERSION_CONFLICT:
    'The AI policy was modified concurrently. Please refresh the page and try again, or contact your administrator.'
};

export function resolvePolicyErrorMessage(
  errorCode?: string | null,
  fallbackMessage?: string | null
): string {
  if (errorCode && AI_POLICY_ERROR_MESSAGES[errorCode]) {
    return AI_POLICY_ERROR_MESSAGES[errorCode];
  }
  if (fallbackMessage && fallbackMessage.trim()) {
    const trimmed = fallbackMessage.trim();
    if (trimmed.toLowerCase().includes('administrator')) {
      return trimmed;
    }
    return `${trimmed}. Please contact your administrator.`;
  }
  return 'The AI request cannot be completed due to policy restrictions. Please contact your administrator.';
}
