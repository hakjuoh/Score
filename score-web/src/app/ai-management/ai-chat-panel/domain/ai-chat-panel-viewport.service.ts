/**
 * Manages chat scrolling, scroll controls, and context-budget hover timing.
 */

import {Injectable} from '@angular/core';
import {AiChatPanelState} from './ai-chat-panel-state';

@Injectable()
export class AiChatPanelViewportService {
  private static readonly SCROLL_TO_BOTTOM_THRESHOLD_PX = 48;
  private scrollToBottomTimeout?: number;
  private contextBudgetHoverOpenTimeout?: number;
  private contextBudgetHoverCloseTimeout?: number;
  contextBudgetHoverOpen = false;

  destroy(): void {
    this.clearScrollToBottom();
    this.closeContextBudgetHover(true);
  }

  showContextBudgetHover(hasUsage: () => boolean): void {
    this.clearContextBudgetHoverClose();
    if (this.contextBudgetHoverOpen || this.contextBudgetHoverOpenTimeout !== undefined) return;
    this.contextBudgetHoverOpenTimeout = window.setTimeout(() => {
      this.contextBudgetHoverOpenTimeout = undefined;
      this.contextBudgetHoverOpen = hasUsage();
    }, 180);
  }

  keepContextBudgetHoverOpen(): void {
    this.clearContextBudgetHoverClose();
  }

  closeContextBudgetHover(immediately = false): void {
    if (this.contextBudgetHoverOpenTimeout !== undefined) {
      window.clearTimeout(this.contextBudgetHoverOpenTimeout);
      this.contextBudgetHoverOpenTimeout = undefined;
    }
    this.clearContextBudgetHoverClose();
    if (immediately) {
      this.contextBudgetHoverOpen = false;
      return;
    }
    this.contextBudgetHoverCloseTimeout = window.setTimeout(() => {
      this.contextBudgetHoverCloseTimeout = undefined;
      this.contextBudgetHoverOpen = false;
    }, 120);
  }

  onChatPaneScroll(state: AiChatPanelState, element?: HTMLElement): void {
    const nearBottom = this.isNearBottom(element);
    state.shouldFollowChatScroll = nearBottom;
    state.showScrollToBottomButton = !nearBottom;
  }

  scrollToBottom(state: AiChatPanelState, element: () => HTMLElement | undefined,
                 force = false): void {
    if (!force && !state.shouldFollowChatScroll) {
      this.updateScrollButton(state, element());
      return;
    }
    this.clearScrollToBottom();
    this.scrollToBottomTimeout = window.setTimeout(() => {
      const target = element();
      if (target) this.scrollElementToBottom(state, target);
      state.shouldFollowChatScroll = true;
      this.scrollToBottomTimeout = undefined;
    });
  }

  updateScrollButton(state: AiChatPanelState, element?: HTMLElement): void {
    setTimeout(() => state.showScrollToBottomButton = !this.isNearBottom(element));
  }

  scrollToTop(element: () => HTMLElement | undefined): void {
    window.setTimeout(() => {
      const target = element();
      if (target) target.scrollTop = 0;
    });
  }

  private scrollElementToBottom(state: AiChatPanelState, element: HTMLElement): void {
    element.scrollTop = element.scrollHeight;
    window.requestAnimationFrame(() => {
      element.scrollTop = element.scrollHeight;
      state.shouldFollowChatScroll = true;
      state.showScrollToBottomButton = !this.isNearBottom(element);
    });
  }

  private isNearBottom(element?: HTMLElement): boolean {
    if (!element) return true;
    const distanceFromBottom = element.scrollHeight - element.scrollTop - element.clientHeight;
    return distanceFromBottom <= AiChatPanelViewportService.SCROLL_TO_BOTTOM_THRESHOLD_PX;
  }

  private clearScrollToBottom(): void {
    if (this.scrollToBottomTimeout !== undefined) {
      window.clearTimeout(this.scrollToBottomTimeout);
      this.scrollToBottomTimeout = undefined;
    }
  }

  private clearContextBudgetHoverClose(): void {
    if (this.contextBudgetHoverCloseTimeout !== undefined) {
      window.clearTimeout(this.contextBudgetHoverCloseTimeout);
      this.contextBudgetHoverCloseTimeout = undefined;
    }
  }
}
