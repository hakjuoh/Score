/**
 * Verifies queued restore rendering, callback ownership, draining, and reset cancellation.
 */

import {
  AiConversationRestoreRenderCallbacks,
  AiConversationRestoreRenderer
} from './ai-conversation-restore-renderer';
import {AiChatMessage} from './ai-chat-panel.model';

describe('AiConversationRestoreRenderer', () => {
  let renderer: AiConversationRestoreRenderer;
  let messages: AiChatMessage[];
  let callbacks: AiConversationRestoreRenderCallbacks;

  beforeEach(() => {
    vi.useFakeTimers();
    renderer = new AiConversationRestoreRenderer();
    messages = [];
    callbacks = {
      setRestoring: vi.fn(),
      clearStatus: vi.fn(),
      pushMessage: message => {
        messages.push(message);
        return messages.length - 1;
      },
      setMessage: (index, message) => messages[index] = message,
      hasMessage: index => !!messages[index],
      updateScrollButton: vi.fn()
    };
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders assistant paragraphs before draining later messages', () => {
    const idle = vi.fn();
    renderer.enqueue({role: 'assistant', content: 'First.\n\nSecond.'}, callbacks, idle);
    renderer.enqueue({role: 'user', content: 'Next.'}, callbacks, idle);

    expect(messages).toEqual([{role: 'assistant', content: 'First.'}]);
    vi.runAllTimers();

    expect(messages).toEqual([
      {role: 'assistant', content: 'First.\n\nSecond.'},
      {role: 'user', content: 'Next.'}
    ]);
    expect(renderer.pending).toBe(false);
    expect(idle).toHaveBeenCalledOnce();
  });

  it('cancels stale scheduled work on reset', () => {
    const idle = vi.fn();
    renderer.enqueue({role: 'user', content: 'Visible immediately.'}, callbacks, idle);

    renderer.reset();
    vi.runAllTimers();

    expect(renderer.pending).toBe(false);
    expect(idle).not.toHaveBeenCalled();
  });

  it('continues draining when the host removes an assistant placeholder', () => {
    const idle = vi.fn();
    callbacks.hasMessage = () => false;
    renderer.enqueue({role: 'assistant', content: 'Removed.'}, callbacks, idle);

    vi.runAllTimers();

    expect(renderer.pending).toBe(false);
    expect(idle).toHaveBeenCalledOnce();
  });

  it('uses the callbacks owned by each queued message', () => {
    const firstIdle = vi.fn();
    const secondIdle = vi.fn();
    const secondMessages: AiChatMessage[] = [];
    const secondCallbacks: AiConversationRestoreRenderCallbacks = {
      ...callbacks,
      pushMessage: message => {
        secondMessages.push(message);
        return secondMessages.length - 1;
      }
    };
    renderer.enqueue({role: 'assistant', content: 'First.'}, callbacks, firstIdle);
    renderer.enqueue({role: 'user', content: 'Second.'}, secondCallbacks, secondIdle);

    vi.runAllTimers();

    expect(secondMessages).toEqual([{role: 'user', content: 'Second.'}]);
    expect(firstIdle).not.toHaveBeenCalled();
    expect(secondIdle).toHaveBeenCalledOnce();
  });
});
