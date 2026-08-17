/**
 * Owns template-facing context, viewport, focus, and link-navigation behavior.
 */

import {AiChatPanelEventController} from './ai-chat-panel-event.controller';
import {AiContextBudgetDialogComponent} from './ai-context-budget-dialog.component';
import {AiContextBudgetData} from './ai-context-budget-chart.model';
import {contextUsageValue} from './domain/ai-chat-event-semantics';
import {AiChatSocketEvent} from './domain/ai-chat-panel.model';

/** Owns template-facing context, viewport, focus, and link-navigation behavior. */
export abstract class AiChatPanelViewController extends AiChatPanelEventController {
  requestManualCompact(): void {
    if (!this.state.conversationId || this.interactionBlocked) return;
    if (this.attachmentQueue.pending) {
      this.snackBar.open('Wait for attachments to finish loading.', 'Dismiss', {duration: 3000});
      return;
    }
    const draft = this.state.prompt;
    const attachments = [...this.state.attachments];
    this.startChatRequest('/compact', []);
    this.state.prompt = draft;
    this.state.attachments = attachments;
    this.resizePromptInput();
  }

  openContextBudgetDialog(): void {
    const data = this.contextBudgetData();
    if (!data) return;
    this.closeContextBudgetHover(true);
    this.dialog.open(AiContextBudgetDialogComponent, {
      data,
      width: '408px',
      maxWidth: 'calc(100vw - 24px)',
      autoFocus: false,
      restoreFocus: true,
      ariaLabel: 'Context budget details'
    });
  }

  contextBudgetData(): AiContextBudgetData | undefined {
    if (this.state.contextUsage && this.state.contextUsage.modelName !== this.state.selectedModelName) {
      this.state.resetContextUsageForSelectedModel();
    }
    const usage = this.state.contextUsage;
    if (!usage) return undefined;
    const model = this.state.selectedModel();
    const reservedTokens = Math.max(0, usage.contextWindow - usage.safeInputLimit);
    const emergencyHeadroomTokens = Math.max(0, model?.emergencyHeadroomTokens || 0);
    const outputReserveTokens = Math.max(0, model?.outputReserveTokens
      ?? reservedTokens - emergencyHeadroomTokens);
    return {usage, outputReserveTokens, emergencyHeadroomTokens};
  }

  showContextBudgetHover(): void {
    this.viewport.showContextBudgetHover(() => !!this.state.contextUsage);
  }

  keepContextBudgetHoverOpen(): void {
    this.viewport.keepContextBudgetHoverOpen();
  }

  closeContextBudgetHover(immediately = false): void {
    this.viewport.closeContextBudgetHover(immediately);
  }

  protected applyContextEvent(event: AiChatSocketEvent): void {
    const usage = contextUsageValue(event.metadata?.['contextUsage'], this.state.selectedModelName);
    this.state.setContextUsage(usage);
  }

  scrollChatToBottom(event?: MouseEvent): void {
    event?.preventDefault();
    event?.stopPropagation();
    this.scrollToBottom(true);
    this.focusPrompt();
  }

  onChatPaneScroll(): void {
    const element = this.chatTerminalPane?.nativeElement;
    if (element) this.state.chatScrollTop = element.scrollTop;
    this.viewport.onChatPaneScroll(this.state, element);
  }

  onHistoryScrollTopChange(scrollTop: number): void {
    this.state.historyScrollTop = scrollTop;
  }

  protected scrollToBottom(force = false): void {
    this.viewport.scrollToBottom(
      this.state, () => this.chatTerminalPane?.nativeElement, force
    );
  }

  protected restoreChatScrollPosition(consumePending = true): void {
    const scrollTop = this.state.chatScrollTop;
    window.setTimeout(() => {
      window.requestAnimationFrame(() => {
        const element = this.chatTerminalPane?.nativeElement;
        if (!element) return;
        element.scrollTop = Math.min(
          scrollTop, Math.max(0, element.scrollHeight - element.clientHeight)
        );
        this.state.chatScrollTop = element.scrollTop;
        this.state.shouldFollowChatScroll =
          element.scrollHeight - element.scrollTop - element.clientHeight <= 48;
        this.updateScrollToBottomButton();
      });
    });
    if (consumePending) this.restoreChatScrollPending = false;
  }

  protected updateScrollToBottomButton(): void {
    this.viewport.updateScrollButton(this.state, this.chatTerminalPane?.nativeElement);
  }

  focusPrompt(): void {
    this.composer?.focus();
  }

  focusPromptIfNoSelection(): void {
    setTimeout(() => {
      const selection = window.getSelection();
      if (selection && selection.toString()) return;
      this.composer?.focus();
    });
  }

  onPanelClick(event: MouseEvent): void {
    event.stopPropagation();
    this.focusPromptIfNoSelection();
  }

  protected resizePromptInput(): void {
    this.composer?.resize();
  }

  onMessageListClick(event: MouseEvent): void {
    event.stopPropagation();
    const target = event.target as HTMLElement | null;
    const link = target?.closest('a[href]') as HTMLAnchorElement | null;
    if (link && this.navigationService.navigateLink(link)) event.preventDefault();
  }
}
