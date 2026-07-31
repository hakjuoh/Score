/**
 * Verifies AI Provider Recovery Coordinator coordination, state transitions, and edge cases.
 */

import {AiChatSocketEvent} from './ai-chat-panel.model';
import {AiProviderRecoveryCoordinator} from './ai-provider-recovery-coordinator';

const providerEvent = (subtype: string, content = '',
                       metadata?: Record<string, unknown>): AiChatSocketEvent => ({
  requestId: 'request-1', conversationId: 'conversation-1', type: 'system',
  subtype, content, metadata
});

describe('AiProviderRecoveryCoordinator', () => {
  let coordinator: AiProviderRecoveryCoordinator;

  beforeEach(() => coordinator = new AiProviderRecoveryCoordinator());

  it('pairs a root provider failure with its retry narration', () => {
    expect(coordinator.handleError(
      providerEvent('provider_error', 'Rate limited'), {cancelling: false, specialist: false}
    )).toEqual({kind: 'present', content: 'Rate limited'});

    expect(coordinator.handleRetry(providerEvent('provider_retry', '', {
      attempt: 2, max_attempts: 3, delay_millis: 100
    }), {cancelling: false, specialist: false})).toEqual({
      kind: 'present', reason: 'Rate limited.',
      retryMessage: 'The model provider request failed; retrying (attempt 2 of 3).'
    });
  });

  it('uses the retry metadata reason when no paired failure exists', () => {
    expect(coordinator.handleRetry(providerEvent('provider_retry', 'Trying again', {
      attempt: 1, max_attempts: 3, delay_millis: 100, reason: 'Provider unavailable'
    }), {cancelling: false, specialist: false})).toEqual({
      kind: 'present', reason: 'Provider unavailable.', retryMessage: 'Trying again'
    });
  });

  it('keeps specialist and cancellation recovery out of the root presentation', () => {
    expect(coordinator.handleError(
      providerEvent('provider_error', 'Worker failed'), {cancelling: false, specialist: true}
    )).toEqual({kind: 'specialist'});
    expect(coordinator.handleRetry(providerEvent('provider_retry', '', {
      attempt: 2, max_attempts: 3, delay_millis: 100
    }), {cancelling: true, specialist: false})).toEqual({kind: 'ignored'});
  });

  it('rejects malformed provider events and clears stale failure state', () => {
    expect(coordinator.handleError(
      providerEvent('provider_error'), {cancelling: false, specialist: false}
    )).toEqual({kind: 'unhandled'});
    expect(coordinator.handleError(
      providerEvent('guide', 'Not a provider failure'), {cancelling: false, specialist: false}
    )).toEqual({kind: 'unhandled'});
    coordinator.handleError(
      providerEvent('provider_error', 'Old reason'), {cancelling: false, specialist: false}
    );
    coordinator.clear();
    expect(coordinator.handleRetry(providerEvent('provider_retry', 'Retrying', {
      attempt: 1, max_attempts: 2, delay_millis: 100
    }), {cancelling: false, specialist: false})).toEqual({
      kind: 'present', retryMessage: 'Retrying', reason: ''
    });
  });
});
