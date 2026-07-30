import {AiConfirmedChangeRequestCoordinator} from './ai-confirmed-change-request-coordinator';

describe('AiConfirmedChangeRequestCoordinator', () => {
  it('isolates the same request ID across panel-scoped instances', () => {
    const firstPanel = new AiConfirmedChangeRequestCoordinator();
    const secondPanel = new AiConfirmedChangeRequestCoordinator();
    const firstCleanup = vi.fn();
    const secondCleanup = vi.fn();

    firstPanel.register('request-1', firstCleanup);
    secondPanel.register('request-1', secondCleanup);

    firstPanel.cancel('request-1');

    expect(firstCleanup).toHaveBeenCalledOnce();
    expect(secondCleanup).not.toHaveBeenCalled();

    secondPanel.cancel('request-1');
    expect(secondCleanup).toHaveBeenCalledOnce();
  });

  it('does not let a stale unregister remove a replacement cleanup', () => {
    const coordinator = new AiConfirmedChangeRequestCoordinator();
    const firstCleanup = vi.fn();
    const replacementCleanup = vi.fn();
    const unregisterFirst = coordinator.register('request-1', firstCleanup);

    coordinator.register('request-1', replacementCleanup);
    unregisterFirst();
    coordinator.cancel('request-1');

    expect(firstCleanup).toHaveBeenCalledOnce();
    expect(replacementCleanup).toHaveBeenCalledOnce();
  });
});
