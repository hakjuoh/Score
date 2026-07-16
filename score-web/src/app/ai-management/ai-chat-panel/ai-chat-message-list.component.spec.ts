import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {MatIconModule} from '@angular/material/icon';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {MarkdownModule} from 'ngx-markdown';
import {AiChatMessageListComponent} from './ai-chat-message-list.component';
import {AiChatInteractionPanelComponent} from './ai-chat-interaction-panel.component';

describe('AiChatMessageListComponent', () => {
  let fixture: ComponentFixture<AiChatMessageListComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [AiChatMessageListComponent, AiChatInteractionPanelComponent],
      imports: [
        CommonModule,
        FormsModule,
        NoopAnimationsModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MarkdownModule.forRoot()
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(AiChatMessageListComponent);
  });

  it('renders assistant Markdown with GitHub Markdown styling', async () => {
    fixture.componentInstance.messages = [{
      role: 'assistant',
      content: '# Summary\n\n- First item\n- Second item'
    }];
    fixture.detectChanges();
    await fixture.whenStable();

    const markdown = fixture.nativeElement.querySelector('.message-markdown') as HTMLElement;
    expect(markdown.classList.contains('markdown-body')).toBe(true);
    expect(markdown.querySelector('h1')?.textContent).toBe('Summary');
    expect(markdown.querySelectorAll('li')).toHaveLength(2);
  });

  it('uses the user message prefix to toggle request history', () => {
    fixture.componentInstance.messages = [
      {role: 'user', content: 'Summarize this request.'},
      {role: 'progress', content: 'Working...', inProgress: true},
      {role: 'assistant', content: 'Done.'}
    ];
    fixture.detectChanges();

    const userBubble = fixture.nativeElement.querySelector('.message-row.user .message-bubble') as HTMLElement;
    const toggle = userBubble.querySelector('.message-history-toggle') as HTMLButtonElement;

    expect(userBubble.firstElementChild).toBe(toggle);
    expect(toggle.classList.contains('message-prefix')).toBe(true);
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(userBubble.querySelectorAll(':scope > .message-prefix')).toHaveLength(1);

    toggle.click();
    fixture.detectChanges();

    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(toggle.textContent).toContain('expand_more');
    expect(fixture.nativeElement.querySelector('.message-history-panel')).not.toBeNull();
  });

  it('announces and visually distinguishes a failed tool row', () => {
    fixture.componentInstance.messages = [{
      role: 'tool_call',
      content: 'GitHub search failed.',
      toolStatus: 'failed',
      recoverable: true
    }];
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('.message-row.tool_call') as HTMLElement;
    expect(row.classList.contains('tool_failed')).toBe(true);
    const a11yStatus = row.querySelector('.tool-call-a11y-status') as HTMLElement;
    expect(a11yStatus.getAttribute('role')).toBe('status');
    expect(a11yStatus.getAttribute('aria-live')).toBe('polite');
    expect(a11yStatus.getAttribute('aria-atomic')).toBe('true');
    expect(row.textContent).toContain('Tool failed:');
    expect(row.textContent).toContain('GitHub search failed.');
    expect(row.querySelector('mat-icon')?.textContent).toContain('error_outline');
  });

  it('does not label a completed tool row as failed', () => {
    fixture.componentInstance.messages = [{
      role: 'tool_call', content: 'Searched GitHub.', toolStatus: 'completed'
    }];
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('.message-row.tool_call') as HTMLElement;
    expect(row.classList.contains('tool_failed')).toBe(false);
    expect(row.textContent).toContain('Tool completed:');
    expect(row.querySelector('mat-icon')?.textContent).toContain('check_circle_outline');
  });

  it('keeps a fallback spinner after tools complete until the request is terminal', () => {
    fixture.componentInstance.messages = [{
      role: 'tool_call', content: 'toolSearchTool completed.', toolStatus: 'completed'
    }];
    fixture.componentInstance.pending = true;
    fixture.detectChanges();

    const indicator = fixture.nativeElement.querySelector('.request-pending-indicator') as HTMLElement;
    expect(indicator).not.toBeNull();
    expect(indicator.querySelector('mat-progress-spinner')).not.toBeNull();
    expect(indicator.getAttribute('role')).toBe('status');

    fixture.componentRef.setInput('pending', false);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.request-pending-indicator')).toBeNull();
  });

  it('does not duplicate the spinner while an active progress row exists', () => {
    fixture.componentInstance.messages = [{
      role: 'progress', content: 'Streaming the answer.', inProgress: true
    }];
    fixture.componentInstance.pending = true;
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.request-pending-indicator')).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('mat-progress-spinner')).toHaveLength(1);
  });

  it('identifies the attachment in its remove control accessible name', () => {
    fixture.componentInstance.attachments = [{
      name: 'purchase-order.json', size: 128, mediaType: 'application/json', data: 'e30='
    }];
    fixture.detectChanges();

    const remove = fixture.nativeElement.querySelector('.attachment-chip button') as HTMLButtonElement;
    expect(remove.getAttribute('aria-label')).toBe('Remove attachment purchase-order.json');
  });

  it('ignores a stale in-progress row from an older turn when guarding the current request', () => {
    fixture.componentInstance.messages = [
      {role: 'user', content: 'Old request'},
      {role: 'progress', content: 'Stale progress', inProgress: true},
      {role: 'assistant', content: 'Old answer'},
      {role: 'user', content: 'Current request'},
      {role: 'tool_call', content: 'toolSearchTool completed.', toolStatus: 'completed'}
    ];
    fixture.componentInstance.pending = true;
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.request-pending-indicator')).not.toBeNull();
  });

  it('does not claim success for a restored tool row without persisted status', () => {
    fixture.componentInstance.messages = [{role: 'tool_call', content: 'Historical tool output.'}];
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('.message-row.tool_call') as HTMLElement;
    expect(row.textContent).toContain('Tool result:');
    expect(row.textContent).not.toContain('Tool completed:');
    expect(row.querySelector('mat-icon')?.textContent).toContain('terminal');
  });

  it('expands tool detail while keeping the tool row at the root alignment', () => {
    fixture.componentInstance.messages = [{
      role: 'tool_call',
      content: 'get_business_contexts completed.',
      toolDetail: 'get_business_contexts\nArguments: {"limit":1}\nResult: {"total_items":0}',
      toolStatus: 'completed'
    }];
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('.message-row.tool_call') as HTMLElement;
    const disclosure = row.querySelector('details.tool-call-disclosure') as HTMLDetailsElement;
    const detail = row.querySelector('.tool-call-detail') as HTMLElement;

    expect(disclosure.open).toBe(false);
    expect(row.querySelector('.tool-call-branch')).toBeNull();
    expect(detail.textContent).toContain('Arguments: {"limit":1}');
    expect(detail.textContent).toContain('Result: {"total_items":0}');

    (disclosure.querySelector('summary') as HTMLElement).click();
    fixture.detectChanges();

    expect(disclosure.open).toBe(true);
  });

  it('shows model and reasoning effort controls only for the model command', async () => {
    fixture.componentInstance.modelSettingsOpen = true;
    fixture.componentInstance.availableModels = [
      {
        name: 'claude-fable-5', displayName: 'Claude Fable 5', description: 'Claude model.',
        provider: 'azure-foundry', defaultModel: true, defaultRuntime: 'default', runtimes: [
          {name: 'default', displayName: 'Default', description: 'Default runtime.', settings: []},
          {name: 'claude', displayName: 'Claude', description: 'Claude runtime.', settings: []}
        ], defaultReasoningEffort: 'high', reasoningEfforts: [
          {name: 'low', displayName: 'Low', description: 'Fast responses.'},
          {name: 'medium', displayName: 'Medium', description: 'Balanced reasoning.'},
          {name: 'high', displayName: 'High', description: 'Greater reasoning.'}
        ]
      },
      {
        name: 'gpt-5_6-sol', displayName: 'GPT-5.6 SOL', description: 'GPT model.',
        provider: 'azure-openai', defaultModel: false, defaultRuntime: 'default', runtimes: [
          {name: 'default', displayName: 'Default', description: 'Default runtime.', settings: []},
          {name: 'openai', displayName: 'OpenAI', description: 'OpenAI runtime.', settings: []}
        ], defaultReasoningEffort: 'medium', reasoningEfforts: [
          {name: 'low', displayName: 'Low', description: 'Fast responses.'},
          {name: 'medium', displayName: 'Medium', description: 'Balanced reasoning.'},
          {name: 'high', displayName: 'High', description: 'Greater reasoning.'}
        ]
      }
    ];
    fixture.componentInstance.modelDraftName = 'gpt-5_6-sol';
    fixture.componentInstance.modelDraftReasoningEffort = 'high';
    fixture.componentInstance.selectedModelName = 'gpt-5_6-sol';
    fixture.componentInstance.selectedReasoningEffort = 'high';
    fixture.componentInstance.selectedRuntime = 'openai';
    fixture.detectChanges();
    await fixture.whenStable();

    const sections = fixture.nativeElement.querySelectorAll('.model-command-section') as NodeListOf<HTMLElement>;
    expect(sections).toHaveLength(2);
    expect(sections[0].textContent).toContain('Claude Fable 5');
    expect(sections[0].textContent).toContain('GPT-5.6 SOL');
    expect(sections[1].textContent).toContain('(current)');
    expect(sections[1].textContent).toContain('Low');
    expect(sections[1].textContent).toContain('High');
    expect(fixture.nativeElement.textContent).not.toContain('OpenAI');
  });

  it('shows compatible runtimes only for the runtime command', async () => {
    fixture.componentInstance.runtimeSettingsOpen = true;
    fixture.componentInstance.availableModels = [{
      name: 'gpt-5_6-sol', displayName: 'GPT-5.6 SOL', description: 'GPT model.',
      provider: 'azure-openai', defaultModel: true, defaultRuntime: 'default', runtimes: [
        {name: 'default', displayName: 'Default', description: 'Default runtime.', settings: []},
        {name: 'openai', displayName: 'OpenAI', description: 'OpenAI runtime.', settings: [
          {name: 'permissionMode', displayName: 'Permission mode', description: 'Controls tool approval.',
            type: 'select', defaultValue: 'default', options: [
              {value: 'default', displayName: 'Default'}, {value: 'auto', displayName: 'Auto'}
            ], minimum: null, maximum: null, step: null},
          {name: 'maxTurns', displayName: 'Maximum turns', description: 'Limits agent turns.',
            type: 'number', defaultValue: 20, options: [], minimum: 1, maximum: 100, step: 1},
          {name: 'verbose', displayName: 'Verbose', description: 'Shows verbose output.',
            type: 'boolean', defaultValue: false, options: [], minimum: null, maximum: null, step: null}
        ]}
      ], defaultReasoningEffort: 'medium', reasoningEfforts: [
        {name: 'medium', displayName: 'Medium', description: 'Balanced reasoning.'}
      ]
    }];
    fixture.componentInstance.selectedModelName = 'gpt-5_6-sol';
    fixture.componentInstance.selectedRuntime = 'openai';
    fixture.componentInstance.runtimeDraft = 'openai';
    fixture.componentInstance.runtimeDraftOptions = {
      permissionMode: 'auto', maxTurns: 40, verbose: true
    };
    const optionChanges: Array<{name: string; value: unknown}> = [];
    fixture.componentInstance.runtimeDraftOptionChange.subscribe(change => optionChanges.push(change));
    fixture.detectChanges();
    await fixture.whenStable();

    const sections = fixture.nativeElement.querySelectorAll('.model-command-section') as NodeListOf<HTMLElement>;
    expect(sections).toHaveLength(1);
    expect(sections[0].textContent).toContain('Default');
    expect(sections[0].textContent).toContain('OpenAI');
    expect(sections[0].textContent).toContain('(current)');
    expect(fixture.nativeElement.querySelector('.model-command-title').textContent)
      .toContain('Select Runtime and Settings');
    const select = fixture.nativeElement.querySelector('select') as HTMLSelectElement;
    const number = fixture.nativeElement.querySelector('input[type="number"]') as HTMLInputElement;
    const checkbox = fixture.nativeElement.querySelector('input[type="checkbox"]') as HTMLInputElement;
    expect(select.value).toBe('auto');
    expect(number.value).toBe('40');
    expect(number.min).toBe('1');
    expect(number.max).toBe('100');
    expect(checkbox.checked).toBe(true);

    number.value = '55';
    number.dispatchEvent(new Event('change'));
    checkbox.checked = false;
    checkbox.dispatchEvent(new Event('change'));
    expect(optionChanges).toEqual([
      {name: 'maxTurns', value: 55}, {name: 'verbose', value: false}
    ]);
  });

  it('shows the three permissions choices and emits the selected mode', () => {
    fixture.componentInstance.permissionSettingsOpen = true;
    fixture.componentInstance.permissionMode = 'ask';
    fixture.componentInstance.permissionDraft = 'ask';
    const selections: string[] = [];
    fixture.componentInstance.permissionDraftChange.subscribe(value => selections.push(value));
    fixture.detectChanges();

    const options = fixture.nativeElement.querySelectorAll(
      '.model-command-option'
    ) as NodeListOf<HTMLButtonElement>;
    expect(options).toHaveLength(3);
    expect(options[0].textContent).toContain('Ask for approval');
    expect(options[1].textContent).toContain('Approve for me');
    expect(options[2].textContent).toContain('Full access');
    expect(options[0].textContent).toContain('(current)');

    options[2].click();
    expect(selections).toEqual(['full_access']);
  });

  it('shows only current when the selected value is also the default', async () => {
    fixture.componentInstance.modelSettingsOpen = true;
    fixture.componentInstance.availableModels = [{
      name: 'claude-fable-5', displayName: 'Claude Fable 5', description: 'Claude model.',
      provider: 'azure-foundry', defaultModel: true, defaultRuntime: 'default', runtimes: [],
      defaultReasoningEffort: 'high', reasoningEfforts: [
        {name: 'low', displayName: 'Low', description: 'Fast responses.'},
        {name: 'high', displayName: 'High', description: 'Greater reasoning.'}
      ]
    }];
    fixture.componentInstance.modelDraftName = 'claude-fable-5';
    fixture.componentInstance.modelDraftReasoningEffort = 'high';
    fixture.componentInstance.selectedModelName = 'claude-fable-5';
    fixture.componentInstance.selectedReasoningEffort = 'high';
    fixture.detectChanges();
    await fixture.whenStable();

    const selectedNames = fixture.nativeElement.querySelectorAll(
      '.model-command-option.selected .model-command-option-name'
    ) as NodeListOf<HTMLElement>;
    expect(selectedNames).toHaveLength(2);
    selectedNames.forEach(name => {
      expect(name.textContent).toContain('(current)');
      expect(name.textContent).not.toContain('(default)');
    });
  });

  it('hides reasoning effort selection for a model without configurable effort', async () => {
    fixture.componentInstance.modelSettingsOpen = true;
    fixture.componentInstance.availableModels = [{
      name: 'claude-haiku-4_5', displayName: 'Claude Haiku 4.5', description: 'Fast Claude model.',
      provider: 'azure-foundry', defaultModel: false, defaultRuntime: 'default', runtimes: [],
      defaultReasoningEffort: 'default', reasoningEfforts: [
        {name: 'default', displayName: 'Default', description: 'Uses built-in behavior.'}
      ]
    }];
    fixture.componentInstance.modelDraftName = 'claude-haiku-4_5';
    fixture.componentInstance.modelDraftReasoningEffort = 'default';
    fixture.componentInstance.selectedModelName = 'claude-haiku-4_5';
    fixture.componentInstance.selectedReasoningEffort = 'default';
    fixture.detectChanges();
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelectorAll('.model-command-section')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('.model-command-title').textContent)
      .not.toContain('Effort');
  });
});
