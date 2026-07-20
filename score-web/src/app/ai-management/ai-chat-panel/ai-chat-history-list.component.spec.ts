import {ComponentFixture, TestBed} from '@angular/core/testing';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {MaterialModule} from '../../material.module';
import {AiChatHistoryListComponent} from './ai-chat-history-list.component';

describe('AiChatHistoryListComponent', () => {
  let fixture: ComponentFixture<AiChatHistoryListComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [AiChatHistoryListComponent],
      imports: [MaterialModule, NoopAnimationsModule]
    }).compileComponents();
    fixture = TestBed.createComponent(AiChatHistoryListComponent);
  });

  it('keeps existing conversations visible alongside a reload error without a duplicate retry', () => {
    fixture.componentInstance.conversations = [{
      conversationId: 'conversation-1', title: 'Existing conversation',
      visibleMessageCount: 2, compacted: false
    }];
    fixture.componentInstance.loadFailed = true;
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent)
      .toContain('Could not load history.');
    expect(fixture.nativeElement.textContent).toContain('Existing conversation');
    expect(fixture.nativeElement.textContent).not.toContain('No history');
    expect(fixture.nativeElement.querySelector('.chat-history-status button')).toBeNull();
  });

  it('keeps the full conversation title in the delete control accessible name', () => {
    const title = 'A long conversation title that is visually truncated';
    fixture.componentInstance.conversations = [{
      conversationId: 'conversation-1', title, visibleMessageCount: 2, compacted: false
    }];
    fixture.detectChanges();

    const historyAction = fixture.nativeElement.querySelector('.history-action') as HTMLButtonElement;
    expect(historyAction.hasAttribute('mattooltip')).toBe(false);
    expect((fixture.nativeElement.querySelector('.history-delete-button') as HTMLButtonElement)
      .getAttribute('aria-label')).toBe(`Delete conversation ${title}`);
  });

  it('restores and reports the history scroll position', async () => {
    fixture.componentInstance.scrollTop = 140;
    fixture.detectChanges();
    await new Promise(resolve => window.setTimeout(resolve));

    const panel = fixture.nativeElement.querySelector('.chat-history-panel') as HTMLElement;
    expect(panel.scrollTop).toBe(140);

    const positions: number[] = [];
    fixture.componentInstance.scrollTopChange.subscribe(value => positions.push(value));
    panel.scrollTop = 75;
    panel.dispatchEvent(new Event('scroll'));

    expect(positions).toEqual([75]);
  });
});
