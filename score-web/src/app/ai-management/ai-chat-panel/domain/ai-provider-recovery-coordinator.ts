import {primaryContent, providerRetrySemantics} from './ai-chat-event-semantics';
import {AiChatSocketEvent} from './ai-chat-panel.model';

export type AiProviderErrorAction =
  | {kind: 'unhandled'}
  | {kind: 'ignored'}
  | {kind: 'specialist'}
  | {kind: 'present'; content: string};

export type AiProviderRetryAction =
  | {kind: 'unhandled'}
  | {kind: 'ignored'}
  | {kind: 'specialist'}
  | {kind: 'present'; retryMessage: string; reason: string};

export interface AiProviderRecoveryContext {
  cancelling: boolean;
  specialist: boolean;
}

/** Owns the short-lived provider failure/retry pairing for the active root request. */
export class AiProviderRecoveryCoordinator {
  private pendingError?: {requestId: string; content: string};

  clear(): void {
    this.pendingError = undefined;
  }

  handleError(event: AiChatSocketEvent,
              context: AiProviderRecoveryContext): AiProviderErrorAction {
    if (event.type !== 'system' || event.subtype !== 'provider_error') {
      return {kind: 'unhandled'};
    }
    const content = primaryContent(event).trim();
    if (!content) return {kind: 'unhandled'};
    if (context.cancelling) return {kind: 'ignored'};
    if (context.specialist) return {kind: 'specialist'};
    this.pendingError = {requestId: event.requestId, content};
    return {kind: 'present', content};
  }

  handleRetry(event: AiChatSocketEvent,
              context: AiProviderRecoveryContext): AiProviderRetryAction {
    const retry = providerRetrySemantics(event);
    if (!retry) return {kind: 'unhandled'};
    if (context.cancelling) return {kind: 'ignored'};
    if (context.specialist) return {kind: 'specialist'};
    const pendingReason = this.pendingError?.requestId === event.requestId
      ? this.pendingError.content : '';
    this.pendingError = undefined;
    const retryMessage = primaryContent(event).trim()
      || `The model provider request failed; retrying (attempt ${retry.attempt} of ${retry.maxAttempts}).`;
    const metadataReason = typeof event.metadata?.['reason'] === 'string'
      ? event.metadata['reason'].trim() : '';
    const rawReason = pendingReason || metadataReason;
    const reason = rawReason && !/[.!?]$/.test(rawReason) ? `${rawReason}.` : rawReason;
    return {kind: 'present', retryMessage, reason};
  }
}
