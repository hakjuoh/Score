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
    (fixture.nativeElement.querySelector('.composer-menu-button') as HTMLButtonElement).click();
    fixture.detectChanges();
    const attachment = document.querySelector(
      '.ai-chat-composer-menu .composer-attachment-menu-item'
    ) as HTMLButtonElement;
    expect(prompt.disabled).toBe(false);
    expect(attachment.disabled).toBe(true);
  });

  it('disables prompt and attachment controls while reconciliation is blocked', async () => {
    component.blocked = true;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const prompt = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    (fixture.nativeElement.querySelector('.composer-menu-button') as HTMLButtonElement).click();
    fixture.detectChanges();
    const attachment = document.querySelector(
      '.ai-chat-composer-menu .composer-attachment-menu-item'
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

  it('opens the file picker from the composer menu', () => {
    const requested = vi.fn();
    component.filePickerRequested.subscribe(requested);
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector(
      'input[type="file"]'
    ) as HTMLInputElement;
    expect(fixture.nativeElement.querySelector('.attachment-button')).toBeNull();

    (fixture.nativeElement.querySelector('.composer-menu-button') as HTMLButtonElement).click();
    fixture.detectChanges();
    const attachment = document.querySelector(
      '.ai-chat-composer-menu .composer-attachment-menu-item'
    ) as HTMLButtonElement;
    attachment.click();

    expect(requested).toHaveBeenCalledWith(input);
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

  it('caps the composer at four message lines', async () => {
    fixture.detectChanges();

    const prompt = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
    Object.defineProperty(prompt, 'scrollHeight', {value: 100, configurable: true});

    component.resize();
    await new Promise(resolve => setTimeout(resolve));

    expect(prompt.style.height).toBe('72px');
  });

  it('exposes the ATIF trajectory through the composer overflow menu', () => {
    component.trajectoryUrl = '/api/ai/chat/conversations/conversation-1/trajectory';
    fixture.detectChanges();

    const trigger = fixture.nativeElement.querySelector(
      '.composer-menu-button'
    ) as HTMLButtonElement;
    expect(trigger).not.toBeNull();
    trigger.click();
    fixture.detectChanges();

    const item = document.querySelector(
      '.ai-chat-menu a[href="/api/ai/chat/conversations/conversation-1/trajectory"]'
    ) as HTMLAnchorElement;
    expect(item).not.toBeNull();
    expect(item.getAttribute('target')).toBe('_blank');
    expect(item.getAttribute('rel')).toBe('noopener');
    expect(item.textContent).toContain('Trajectory');
    expect(item.textContent).toContain('account_tree');
  });

  it('disables the trajectory menu item without an active conversation', () => {
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.composer-menu-button') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(document.querySelector('.ai-chat-menu a[href]')).toBeNull();
    const disabled = document.querySelector(
      '.ai-chat-menu button[disabled]'
    ) as HTMLButtonElement;
    expect(disabled).not.toBeNull();
    expect(disabled.closest('.ai-chat-composer-menu')).not.toBeNull();
    expect(disabled.textContent).toContain('Trajectory');
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
