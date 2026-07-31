/**
 * Synchronizes the main panel and pop-out window through messaging, heartbeat, and geometry state.
 */

import {Injectable, inject} from '@angular/core';
import {AuthService} from '../../../authentication/auth.service';

const POPOUT_QUERY_PARAMETER = 'aiAssistantPopout';
const POPOUT_WINDOW_NAME = 'score-connectcenter-assistant';
const MESSAGE_MARKER = 'score-ai-chat-window';
const HEARTBEAT_INTERVAL_MS = 750;
const HEARTBEAT_TIMEOUT_MS = 90000;
const INITIAL_POPOUT_TIMEOUT_MS = 15000;
const POPOUT_GEOMETRY_KEY_PREFIX = 'score.ai-chat.popout-geometry';

type AiChatWindowMessageType =
  | 'ping'
  | 'ready'
  | 'heartbeat'
  | 'focus'
  | 'dock'
  | 'reattach'
  | 'closed';

interface AiChatWindowMessage {
  marker: typeof MESSAGE_MARKER;
  type: AiChatWindowMessageType;
}

export interface AiChatWindowCoordinatorCallbacks {
  popoutReady(): void;
  reattachRequested(): void;
  assistantClosed(): void;
  popoutMissing(): void;
  popoutExpected(): boolean;
}

@Injectable()
export class AiChatWindowCoordinatorService {
  private auth = inject(AuthService);
  private channel?: BroadcastChannel;
  private callbacks?: AiChatWindowCoordinatorCallbacks;
  private popup?: Window | null;
  private heartbeatInterval?: number;
  private watchdogInterval?: number;
  private lastPopoutSignal = Date.now();
  private receivedPopoutSignal = false;
  private connected = false;

  readonly popoutMode = new URLSearchParams(window.location.search)
    .get(POPOUT_QUERY_PARAMETER) === '1';

  connect(callbacks: AiChatWindowCoordinatorCallbacks): void {
    if (this.connected) return;
    this.connected = true;
    this.callbacks = callbacks;
    window.addEventListener('message', this.onWindowMessage);
    if (typeof BroadcastChannel !== 'undefined') {
      this.channel = new BroadcastChannel(this.channelName());
      this.channel.addEventListener('message', this.onBroadcastMessage);
    }
    if (this.popoutMode) {
      this.persistPopoutGeometry();
      this.send('ready');
      this.heartbeatInterval = window.setInterval(
        () => {
          this.persistPopoutGeometry();
          this.send('heartbeat');
        }, HEARTBEAT_INTERVAL_MS
      );
      return;
    }
    this.lastPopoutSignal = Date.now();
    this.watchdogInterval = window.setInterval(() => this.checkPopout(), HEARTBEAT_INTERVAL_MS);
    this.send('ping');
  }

  openPopout(): boolean {
    const url = new URL(document.baseURI);
    url.search = '';
    url.hash = '';
    url.searchParams.set(POPOUT_QUERY_PARAMETER, '1');
    const geometry = this.restorePopoutGeometry();
    const geometryFeatures = geometry
      ? `,left=${geometry.left},top=${geometry.top},width=${geometry.width},height=${geometry.height}`
      : ',width=560,height=760';
    this.popup = window.open(
      url.toString(), POPOUT_WINDOW_NAME,
      `popup=yes,resizable=yes,scrollbars=yes${geometryFeatures}`
    );
    if (!this.popup) return false;
    this.lastPopoutSignal = Date.now();
    this.receivedPopoutSignal = false;
    this.popup.focus();
    return true;
  }

  focusPopout(): void {
    if (this.popup && !this.popup.closed) {
      this.popup.focus();
      return;
    }
    this.send('focus');
  }

  requestReattach(): void {
    this.send('reattach');
    window.close();
  }

  closePopoutForReattach(): void {
    this.send('dock');
  }

  notifyAssistantClosed(): void {
    this.send('closed');
    window.close();
  }

  destroy(): void {
    if (this.popoutMode) this.persistPopoutGeometry();
    window.removeEventListener('message', this.onWindowMessage);
    this.channel?.removeEventListener('message', this.onBroadcastMessage);
    this.channel?.close();
    this.channel = undefined;
    if (this.heartbeatInterval !== undefined) {
      window.clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = undefined;
    }
    if (this.watchdogInterval !== undefined) {
      window.clearInterval(this.watchdogInterval);
      this.watchdogInterval = undefined;
    }
    this.connected = false;
  }

  private checkPopout(): void {
    if (!this.callbacks?.popoutExpected()) return;
    if (this.popup?.closed) {
      this.popup = undefined;
      this.callbacks.popoutMissing();
      return;
    }
    if (this.popup) return;
    const timeout = this.receivedPopoutSignal
      ? HEARTBEAT_TIMEOUT_MS : INITIAL_POPOUT_TIMEOUT_MS;
    if (Date.now() - this.lastPopoutSignal > timeout) {
      this.callbacks.popoutMissing();
    }
  }

  private readonly onBroadcastMessage = (event: MessageEvent<unknown>): void => {
    this.handleMessage(event.data);
  };

  private readonly onWindowMessage = (event: MessageEvent<unknown>): void => {
    if (event.origin !== window.location.origin) return;
    this.handleMessage(event.data);
  };

  private handleMessage(value: unknown): void {
    if (!this.isMessage(value)) return;
    if (this.popoutMode) {
      if (value.type === 'ping') this.send('ready');
      if (value.type === 'focus') window.focus();
      if (value.type === 'dock') this.callbacks?.reattachRequested();
      return;
    }
    if (value.type === 'ready' || value.type === 'heartbeat') {
      this.lastPopoutSignal = Date.now();
      this.receivedPopoutSignal = true;
      this.callbacks?.popoutReady();
    } else if (value.type === 'reattach') {
      this.callbacks?.reattachRequested();
    } else if (value.type === 'closed') {
      this.callbacks?.assistantClosed();
    }
  }

  private send(type: AiChatWindowMessageType): void {
    const message: AiChatWindowMessage = {marker: MESSAGE_MARKER, type};
    this.channel?.postMessage(message);
    if (this.popoutMode && window.opener && !window.opener.closed) {
      window.opener.postMessage(message, window.location.origin);
    } else if (!this.popoutMode && this.popup && !this.popup.closed) {
      this.popup.postMessage(message, window.location.origin);
    }
  }

  private isMessage(value: unknown): value is AiChatWindowMessage {
    return value !== null && typeof value === 'object'
      && (value as {marker?: unknown}).marker === MESSAGE_MARKER
      && ['ping', 'ready', 'heartbeat', 'focus', 'dock', 'reattach', 'closed']
        .includes(String((value as {type?: unknown}).type));
  }

  private channelName(): string {
    const username = this.auth.getUserToken()?.username?.trim() || 'unknown';
    return `score-ai-chat-window:${encodeURIComponent(username)}`;
  }

  private persistPopoutGeometry(): void {
    const geometry = {
      left: window.screenX,
      top: window.screenY,
      width: window.outerWidth,
      height: window.outerHeight
    };
    if (geometry.width < 320 || geometry.height < 240) return;
    try {
      localStorage.setItem(this.geometryStorageKey(), JSON.stringify(geometry));
    } catch {
      // Geometry persistence is best effort only.
    }
  }

  private restorePopoutGeometry(): {
    left: number;
    top: number;
    width: number;
    height: number;
  } | undefined {
    try {
      const value: unknown = JSON.parse(localStorage.getItem(this.geometryStorageKey()) || 'null');
      if (!value || typeof value !== 'object') return undefined;
      const geometry = value as Record<string, unknown>;
      const left = this.geometryNumber(geometry['left'], -10000, 10000);
      const top = this.geometryNumber(geometry['top'], -10000, 10000);
      const width = this.geometryNumber(geometry['width'], 320, 5000);
      const height = this.geometryNumber(geometry['height'], 240, 5000);
      return left === undefined || top === undefined || width === undefined || height === undefined
        ? undefined : {left, top, width, height};
    } catch {
      return undefined;
    }
  }

  private geometryNumber(value: unknown, minimum: number, maximum: number): number | undefined {
    return typeof value === 'number' && Number.isFinite(value)
      && value >= minimum && value <= maximum ? Math.round(value) : undefined;
  }

  private geometryStorageKey(): string {
    const username = this.auth.getUserToken()?.username?.trim() || 'unknown';
    return `${POPOUT_GEOMETRY_KEY_PREFIX}:${encodeURIComponent(username)}`;
  }
}
