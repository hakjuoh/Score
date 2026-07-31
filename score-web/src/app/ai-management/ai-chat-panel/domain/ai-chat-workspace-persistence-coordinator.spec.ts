import {AiChatPanelState} from './ai-chat-panel-state';
import {AiChatAttachment} from './ai-chat-panel.model';
import {AiChatWorkspacePersistenceCoordinator} from './ai-chat-workspace-persistence-coordinator';

describe('AiChatWorkspacePersistenceCoordinator', () => {
  it('persists only changed workspace and attachment snapshots', () => {
    const persistence = {
      restoreDraftAttachments: vi.fn().mockResolvedValue([]),
      persistWorkspace: vi.fn(), persistDraftAttachments: vi.fn()
    };
    const coordinator = new AiChatWorkspacePersistenceCoordinator(persistence as any);
    const state = new AiChatPanelState();
    coordinator.initialize(state, {
      workspaceRestored: false, setRestoreChatScroll: vi.fn(), isDestroyed: () => false
    });

    coordinator.persistIfChanged(state, {destroyed: false, popoutMode: false});
    expect(persistence.persistWorkspace).not.toHaveBeenCalled();
    expect(persistence.persistDraftAttachments).not.toHaveBeenCalled();

    state.prompt = 'changed';
    state.attachments = [{
      name: 'a.txt', mediaType: 'text/plain', size: 1, data: 'YQ=='
    }];
    coordinator.persistIfChanged(state, {destroyed: false, popoutMode: false});
    expect(persistence.persistWorkspace).toHaveBeenCalledOnce();
    expect(persistence.persistDraftAttachments).toHaveBeenCalledWith(state.attachments);
  });

  it('rejects a stale asynchronous attachment restore after invalidation', async () => {
    let resolve!: (attachments: AiChatAttachment[]) => void;
    const persistence = {
      restoreDraftAttachments: vi.fn().mockReturnValue(
        new Promise<AiChatAttachment[]>(done => resolve = done)
      ),
      persistWorkspace: vi.fn(), persistDraftAttachments: vi.fn()
    };
    const coordinator = new AiChatWorkspacePersistenceCoordinator(persistence as any);
    const state = new AiChatPanelState();
    coordinator.restoreDraftAttachments(state, () => false);
    coordinator.invalidateDraftAttachmentRestore();
    resolve([{name: 'stale.txt', mediaType: 'text/plain', size: 1, data: 'YQ=='}]);
    await Promise.resolve();

    expect(state.attachments).toEqual([]);
  });

  it('restores draft attachments when the request remains current and the state is empty', async () => {
    const attachments: AiChatAttachment[] = [{
      name: 'restored.txt', mediaType: 'text/plain', size: 1, data: 'YQ=='
    }];
    const persistence = {
      restoreDraftAttachments: vi.fn().mockResolvedValue(attachments),
      persistWorkspace: vi.fn(), persistDraftAttachments: vi.fn()
    };
    const coordinator = new AiChatWorkspacePersistenceCoordinator(persistence as any);
    const state = new AiChatPanelState();
    coordinator.restoreDraftAttachments(state, () => false);
    await Promise.resolve();

    expect(state.attachments).toEqual(attachments);
  });

  it.each([
    ['destroyed owner', true, []],
    ['non-empty state', false, [{
      name: 'current.txt', mediaType: 'text/plain', size: 1, data: 'YQ=='
    }]]
  ] as const)('rejects a completed restore for a %s', async (_label, destroyed, current) => {
    const persistence = {
      restoreDraftAttachments: vi.fn().mockResolvedValue([{
        name: 'restored.txt', mediaType: 'text/plain', size: 1, data: 'YQ=='
      }]),
      persistWorkspace: vi.fn(), persistDraftAttachments: vi.fn()
    };
    const coordinator = new AiChatWorkspacePersistenceCoordinator(persistence as any);
    const state = new AiChatPanelState();
    state.attachments = [...current];
    coordinator.restoreDraftAttachments(state, () => destroyed);
    await Promise.resolve();

    expect(state.attachments).toEqual(current);
  });

  it('flushes both snapshots and invalidates an in-flight attachment restore', async () => {
    let resolve!: (attachments: AiChatAttachment[]) => void;
    const persistence = {
      restoreDraftAttachments: vi.fn().mockReturnValue(
        new Promise<AiChatAttachment[]>(done => resolve = done)
      ),
      persistWorkspace: vi.fn(), persistDraftAttachments: vi.fn()
    };
    const coordinator = new AiChatWorkspacePersistenceCoordinator(persistence as any);
    const state = new AiChatPanelState();
    coordinator.restoreDraftAttachments(state, () => false);
    coordinator.flush(state);
    resolve([{name: 'stale.txt', mediaType: 'text/plain', size: 1, data: 'YQ=='}]);
    await Promise.resolve();

    expect(persistence.persistWorkspace).toHaveBeenCalledWith(state);
    expect(persistence.persistDraftAttachments).toHaveBeenCalledWith([]);
    expect(state.attachments).toEqual([]);
  });

  it('does not persist a docked owner while its detached panel is active', () => {
    const persistence = {
      restoreDraftAttachments: vi.fn().mockResolvedValue([]),
      persistWorkspace: vi.fn(), persistDraftAttachments: vi.fn()
    };
    const coordinator = new AiChatWorkspacePersistenceCoordinator(persistence as any);
    const state = new AiChatPanelState();
    coordinator.initialize(state, {
      workspaceRestored: false, setRestoreChatScroll: vi.fn(), isDestroyed: () => false
    });
    state.popoutActive = true;
    state.prompt = 'owned by popout';
    coordinator.persistIfChanged(state, {destroyed: false, popoutMode: false});

    expect(persistence.persistWorkspace).not.toHaveBeenCalled();

    coordinator.persistIfChanged(state, {destroyed: false, popoutMode: true});
    expect(persistence.persistWorkspace).toHaveBeenCalledWith(state);
  });
});
