import {ComponentFixture, TestBed} from '@angular/core/testing';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {MaterialModule} from '../../material.module';
import {AiChatPanelTabsComponent} from './ai-chat-panel-tabs.component';

describe('AiChatPanelTabsComponent', () => {
  let fixture: ComponentFixture<AiChatPanelTabsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [AiChatPanelTabsComponent],
      imports: [MaterialModule, NoopAnimationsModule]
    }).compileComponents();
    fixture = TestBed.createComponent(AiChatPanelTabsComponent);
    fixture.componentInstance.idPrefix = 'assistant-test';
  });

  it('connects tabs to their panels and keeps only the active tab in the tab order', () => {
    fixture.detectChanges();
    const tabs = fixture.nativeElement.querySelectorAll('[role="tab"]') as NodeListOf<HTMLButtonElement>;

    expect(tabs[0].id).toBe('assistant-test-tab-chat');
    expect(tabs[0].getAttribute('aria-controls')).toBe('assistant-test-panel-chat');
    expect(tabs[0].tabIndex).toBe(0);
    expect(tabs[1].id).toBe('assistant-test-tab-history');
    expect(tabs[1].getAttribute('aria-controls')).toBe('assistant-test-panel-history');
    expect(tabs[1].tabIndex).toBe(-1);
  });

  it('activates and focuses the adjacent tab with arrow keys', () => {
    const selected = vi.fn();
    fixture.componentInstance.panelTabSelected.subscribe(selected);
    fixture.detectChanges();
    const tabs = fixture.nativeElement.querySelectorAll('[role="tab"]') as NodeListOf<HTMLButtonElement>;
    tabs[0].focus();

    tabs[0].dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowRight', bubbles: true}));

    expect(selected).toHaveBeenCalledWith('history');
    expect(document.activeElement).toBe(tabs[1]);
  });

  it('disables history refresh while a history request is in progress', () => {
    fixture.componentInstance.activePanelTab = 'history';
    fixture.componentInstance.historyLoading = true;
    fixture.detectChanges();

    const reload = fixture.nativeElement.querySelector(
      '[aria-label="Reload history"]'
    ) as HTMLButtonElement;
    expect(reload.disabled).toBe(true);
    expect(reload.getAttribute('aria-busy')).toBe('true');
  });
});
