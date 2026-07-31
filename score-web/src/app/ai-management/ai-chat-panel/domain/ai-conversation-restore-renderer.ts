import {Injectable} from '@angular/core';
import {AiChatMessage} from './ai-chat-panel.model';

export interface AiConversationRestoreRenderCallbacks {
  setRestoring(restoring: boolean): void;
  clearStatus(): void;
  pushMessage(message: AiChatMessage): number;
  setMessage(index: number, message: AiChatMessage): void;
  hasMessage(index: number): boolean;
  updateScrollButton(): void;
}

interface RestoreRenderQueueItem {
  message: AiChatMessage;
  callbacks: AiConversationRestoreRenderCallbacks;
  onIdle: () => void;
}

/** Owns only the cancellable, paragraph-at-a-time restore presentation queue. */
@Injectable()
export class AiConversationRestoreRenderer {
  private timeout?: number;
  private generation = 0;
  private queue: RestoreRenderQueueItem[] = [];
  private running = false;

  get pending(): boolean {
    return this.running || this.queue.length > 0;
  }

  enqueue(
    message: AiChatMessage,
    callbacks: AiConversationRestoreRenderCallbacks,
    onIdle: () => void
  ): void {
    callbacks.setRestoring(false);
    callbacks.clearStatus();
    this.queue.push({message, callbacks, onIdle});
    this.drain();
  }

  reset(): void {
    this.generation += 1;
    this.queue = [];
    this.running = false;
    this.clearTimeout();
  }

  private drain(): void {
    if (this.running) return;
    if (this.queue.length === 0) return;
    const generation = this.generation;
    const {message, callbacks, onIdle} = this.queue.shift()!;
    this.running = true;
    const complete = (): void => {
      if (generation !== this.generation) return;
      this.running = false;
      if (this.queue.length === 0) {
        onIdle();
      } else {
        this.drain();
      }
    };
    if (message.role === 'assistant') {
      this.renderAssistantParagraphs(message, callbacks, generation, complete);
      return;
    }

    callbacks.pushMessage(message);
    callbacks.updateScrollButton();
    this.schedule(() => {
      complete();
    });
  }

  private renderAssistantParagraphs(
    message: AiChatMessage,
    callbacks: AiConversationRestoreRenderCallbacks,
    generation: number,
    done: () => void,
    blockIndex = 0,
    targetIndex?: number,
    blocks = this.markdownBlocks(message.content)
  ): void {
    if (generation !== this.generation) return;
    if (targetIndex === undefined) {
      targetIndex = callbacks.pushMessage({...message, content: ''});
    }
    if (!callbacks.hasMessage(targetIndex)) {
      this.schedule(done);
      return;
    }
    if (blockIndex >= blocks.length) {
      this.schedule(done);
      return;
    }

    callbacks.setMessage(targetIndex, {
      ...message,
      content: blocks.slice(0, blockIndex + 1).join('\n\n')
    });
    callbacks.updateScrollButton();
    this.schedule(() => {
      this.renderAssistantParagraphs(
        message, callbacks, generation, done, blockIndex + 1, targetIndex, blocks
      );
    });
  }

  private markdownBlocks(content: string): string[] {
    const blocks = content
      .split(/\n\s*\n/)
      .map(block => block.trim())
      .filter(block => block.length > 0);
    return blocks.length > 0 ? blocks : [content];
  }

  private schedule(callback: () => void): void {
    this.clearTimeout();
    this.timeout = window.setTimeout(() => {
      this.timeout = undefined;
      callback();
    });
  }

  private clearTimeout(): void {
    if (this.timeout !== undefined) {
      window.clearTimeout(this.timeout);
      this.timeout = undefined;
    }
  }
}
