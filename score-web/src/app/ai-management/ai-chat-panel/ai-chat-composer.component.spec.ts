import {FormsModule} from '@angular/forms';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {MaterialModule} from '../../material.module';
import {AiChatComposerComponent} from './ai-chat-composer.component';
import {AI_CHAT_ATTACHMENT_ACCEPT} from './domain/ai-chat-panel.constants';

describe('AiChatComposerComponent cancellation actions', () => {
  let fixture: ComponentFixture<AiChatComposerComponent>;
  let component: AiChatComposerComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [AiChatComposerComponent],
      imports: [FormsModule, MaterialModule, NoopAnimationsModule]
    }).compileComponents();
    fixture = TestBed.createComponent(AiChatComposerComponent);
    component = fixture.componentInstance;
  });

  it('emits retry and force-safe-stop as distinct recovery actions', () => {
    const retry = vi.fn();
    const forceSafeStop = vi.fn();
    component.cancellationRetryRequested.subscribe(retry);
    component.forceSafeStopRequested.subscribe(forceSafeStop);

    component.cancellationRetryRequested.emit();
    component.forceSafeStopRequested.emit();

    expect(retry).toHaveBeenCalledOnce();
    expect(forceSafeStop).toHaveBeenCalledOnce();
  });

  it('keeps the focused Stop control mounted while cancellation becomes busy', () => {
    component.pending = true;
    fixture.detectChanges();
    const before = fixture.nativeElement.querySelector(
      '.composer-stop-button'
    ) as HTMLButtonElement;
    before.focus();
    expect(document.activeElement).toBe(before);

    component.cancellationInProgress = true;
    fixture.detectChanges();
    const after = fixture.nativeElement.querySelector(
      '.composer-stop-button'
    ) as HTMLButtonElement;

    expect(after).toBe(before);
    expect(document.activeElement).toBe(after);
    expect(after.getAttribute('aria-busy')).toBe('true');
    expect(after.getAttribute('aria-disabled')).toBe('true');
  });

  it('keeps the prompt enabled for the cancel command while a request is pending', async () => {
    component.pending = true;
    fixture.detectChanges();
    await fixture.whenStable();

    const prompt = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    const attachment = fixture.nativeElement.querySelector('.attachment-button') as HTMLButtonElement;
    expect(prompt.disabled).toBe(false);
    expect(attachment.disabled).toBe(true);
  });

  it('disables prompt and attachment controls while reconciliation is blocked', async () => {
    component.blocked = true;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const prompt = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    const attachment = fixture.nativeElement.querySelector(
      '.attachment-button'
    ) as HTMLButtonElement;
    expect(prompt.disabled).toBe(true);
    expect(attachment.disabled).toBe(true);
  });

  it('uses the shared attachment allowlist for the native file picker', () => {
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector(
      'input[type="file"]'
    ) as HTMLInputElement;
    expect(input.accept).toBe(AI_CHAT_ATTACHMENT_ACCEPT);
  });

  it('shows a contextual placeholder while requesting mutation changes', async () => {
    component.placeholder = 'Describe changes to this action';
    fixture.detectChanges();
    await fixture.whenStable();

    const prompt = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    expect(prompt.placeholder).toBe('Describe changes to this action');
  });

  it('gives the message composer a persistent accessible name', () => {
    fixture.detectChanges();

    const prompt = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    expect(prompt.getAttribute('aria-label')).toBe('Message to connectCenter Assistant');
  });

  it('renders command suggestions next to the composer and emits the selected command', () => {
    const selected = vi.fn();
    component.showCommandSuggestions = true;
    component.commandSuggestions = [{
      name: '/model', description: 'Change the model.', kind: 'local'
    }];
    component.commandSuggestionSelected.subscribe(selected);
    fixture.detectChanges();

    const list = fixture.nativeElement.querySelector('.command-suggestions') as HTMLElement;
    const option = list.querySelector('.command-suggestion') as HTMLButtonElement;
    expect(list.getAttribute('role')).toBe('listbox');
    expect(option.textContent).toContain('/model');
    expect(option.getAttribute('aria-selected')).toBe('true');

    option.click();
    expect(selected).toHaveBeenCalledWith(component.commandSuggestions[0]);
  });

});
