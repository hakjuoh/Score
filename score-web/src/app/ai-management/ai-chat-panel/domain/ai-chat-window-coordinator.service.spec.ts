/**
 * Verifies AI Chat Window Coordinator coordination, state transitions, and edge cases.
 */

import {TestBed} from '@angular/core/testing';
import {AuthService} from '../../../authentication/auth.service';
import {AiChatWindowCoordinatorService} from './ai-chat-window-coordinator.service';

describe('AiChatWindowCoordinatorService', () => {
  let service: AiChatWindowCoordinatorService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        AiChatWindowCoordinatorService,
        {provide: AuthService, useValue: {getUserToken: () => ({username: 'test_eu'})}}
      ]
    });
    service = TestBed.inject(AiChatWindowCoordinatorService);
  });

  afterEach(() => {
    service.destroy();
    vi.restoreAllMocks();
  });

  it('opens a named resizable window with pop-out mode in the URL', () => {
    const popup = {closed: false, focus: vi.fn(), postMessage: vi.fn()} as unknown as Window;
    const open = vi.spyOn(window, 'open').mockReturnValue(popup);

    expect(service.openPopout()).toBe(true);

    const [url, name, features] = open.mock.calls[0];
    const popoutUrl = new URL(String(url));
    expect(popoutUrl.pathname).toBe('/');
    expect(popoutUrl.searchParams.get('aiAssistantPopout')).toBe('1');
    expect(name).toBe('score-connectcenter-assistant');
    expect(features).toContain('resizable=yes');
    expect(popup.focus).toHaveBeenCalledOnce();
  });

  it('reports a blocked pop-up without retaining a window handle', () => {
    vi.spyOn(window, 'open').mockReturnValue(null);

    expect(service.openPopout()).toBe(false);
  });
});
