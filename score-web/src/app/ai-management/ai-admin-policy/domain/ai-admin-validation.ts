import {AiModelUpdate, AiProviderUpdate} from './ai-admin-policy';

export function httpErrorMessage(error: unknown, fallback: string): string {
  if (!error || typeof error !== 'object') return fallback;
  const body = (error as {error?: unknown}).error;
  if (typeof body === 'string' && body.trim()) return body;
  if (body && typeof body === 'object') {
    const message = (body as {message?: unknown}).message;
    if (typeof message === 'string' && message.trim()) return message;
  }
  return fallback;
}

export function positiveSafeInteger(value: number | null): boolean {
  return value !== null && Number.isSafeInteger(value) && value > 0;
}

export function validProviderUpdate(form: AiProviderUpdate): boolean {
  const supportedTypes = new Set(['anthropic', 'openai']);
  return !!form.providerName.trim()
    && supportedTypes.has(form.providerType.trim().toLowerCase())
    && validHttpUrl(form.baseUrl)
    && validHttpUrl(form.messagesUrl)
    && (!form.enabled || !!form.baseUrl?.trim() || !!form.messagesUrl?.trim());
}

export function validModelUpdate(form: AiModelUpdate): boolean {
  const positiveOptional = (value: number | null) => value === null || positiveSafeInteger(value);
  if (!positiveSafeInteger(form.providerId) || !form.modelKey.trim()
    || !form.providerModelName.trim() || !form.displayName.trim() || form.description === null
    || !Number.isSafeInteger(form.sortOrder) || form.sortOrder < 0
    || !positiveOptional(form.maxTokens) || !positiveSafeInteger(form.contextWindow)
    || !positiveOptional(form.outputReserveTokens) || !positiveOptional(form.autoCompactThresholdTokens)
    || !Number.isSafeInteger(form.emergencyHeadroomTokens) || form.emergencyHeadroomTokens < 0
    || !positiveSafeInteger(form.toolOutputTokenLimit)
    || form.temperature !== null && !Number.isFinite(form.temperature)
    || !positiveOptional(form.thinkingBudgetTokens)) return false;

  const reserve = form.outputReserveTokens ?? form.maxTokens
    ?? Math.min(32768, Math.floor(form.contextWindow / 6));
  const safeInput = form.contextWindow - reserve - form.emergencyHeadroomTokens;
  const threshold = form.autoCompactThresholdTokens
    ?? Math.max(1, safeInput - Math.floor(safeInput / 5));
  if (reserve >= form.contextWindow || form.emergencyHeadroomTokens >= form.contextWindow - reserve
    || safeInput <= 0 || threshold > safeInput || form.toolOutputTokenLimit > safeInput) return false;

  if (new Set(form.thinkingModes).size !== form.thinkingModes.length
    || form.thinkingModes.some(mode => !mode.trim())
    || !!form.defaultThinking && !form.thinkingModes.includes(form.defaultThinking.trim())
    || form.defaultModel && !form.enabled) return false;

  const efforts = form.reasoningEfforts;
  const effortNames = efforts.map(effort => effort.name.trim().toLowerCase());
  return (efforts.length === 0 || efforts.filter(effort => effort.defaultEffort).length === 1)
    && new Set(effortNames).size === effortNames.length
    && efforts.every(effort => !!effort.name.trim() && !!effort.displayName.trim()
      && effort.description !== null && Number.isSafeInteger(effort.sortOrder) && effort.sortOrder >= 0);
}

function validHttpUrl(value: string | null): boolean {
  if (!value?.trim()) return true;
  try {
    const url = new URL(value);
    return (url.protocol === 'https:' || url.protocol === 'http:') && !!url.hostname;
  } catch {
    return false;
  }
}
