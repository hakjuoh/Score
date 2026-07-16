import {Injectable} from '@angular/core';
import {AiChatDock} from './ai-chat-panel.model';

@Injectable({
  providedIn: 'root'
})
export class AiChatPanelLayoutService {

  panelStyle(dock: AiChatDock, sideSize: number, horizontalSize: number): {[key: string]: string} {
    if (dock === 'right') {
      return {top: '0', right: '0', bottom: '0', width: sideSize + 'px'};
    }
    if (dock === 'left') {
      return {top: '0', left: '0', bottom: '0', width: sideSize + 'px'};
    }
    if (dock === 'bottom') {
      return {left: '0', right: '0', bottom: '0', height: horizontalSize + 'px'};
    }
    return {left: '0', right: '0', top: '0', height: horizontalSize + 'px'};
  }

  updateMainPanelInset(isOpen: boolean, dock: AiChatDock, sideSize: number, horizontalSize: number): void {
    this.clearMainPanelInset();
    if (!isOpen) {
      return;
    }

    const root = document.documentElement;
    if (dock === 'right') {
      root.style.setProperty('--score-ai-chat-right', sideSize + 'px');
      root.style.setProperty('--score-ai-chat-overlay-x', -(sideSize / 2) + 'px');
    } else if (dock === 'left') {
      root.style.setProperty('--score-ai-chat-left', sideSize + 'px');
      root.style.setProperty('--score-ai-chat-overlay-x', (sideSize / 2) + 'px');
    } else if (dock === 'bottom') {
      root.style.setProperty('--score-ai-chat-bottom', horizontalSize + 'px');
      root.style.setProperty('--score-ai-chat-overlay-y', -(horizontalSize / 2) + 'px');
    } else {
      root.style.setProperty('--score-ai-chat-top', horizontalSize + 'px');
      root.style.setProperty('--score-ai-chat-overlay-y', (horizontalSize / 2) + 'px');
    }
  }

  clearMainPanelInset(): void {
    const root = document.documentElement;
    root.style.setProperty('--score-ai-chat-top', '0px');
    root.style.setProperty('--score-ai-chat-right', '0px');
    root.style.setProperty('--score-ai-chat-bottom', '0px');
    root.style.setProperty('--score-ai-chat-left', '0px');
    root.style.setProperty('--score-ai-chat-overlay-x', '0px');
    root.style.setProperty('--score-ai-chat-overlay-y', '0px');
  }

  clamp(value: number, min: number, max: number): number {
    return Math.min(Math.max(value, min), Math.max(min, max));
  }
}
