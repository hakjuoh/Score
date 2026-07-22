import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {MatIconModule} from '@angular/material/icon';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {MarkdownModule} from 'ngx-markdown';
import {AiChatMessageListComponent} from './ai-chat-message-list.component';
import {AiChatInteractionPanelComponent} from './ai-chat-interaction-panel.component';
import {AiChatToolCallComponent} from './ai-chat-tool-call.component';

describe('AiChatMessageListComponent', () => {
  let fixture: ComponentFixture<AiChatMessageListComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [
        AiChatMessageListComponent,
        AiChatInteractionPanelComponent,
        AiChatToolCallComponent
      ],
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

  it('hides completed tool calls until the completed request is expanded', async () => {
    fixture.componentInstance.messages = [
      {role: 'user', content: 'Add values'},
      {
        role: 'tool_call', content: 'get_context_schemes completed.',
        toolStatus: 'completed', toolName: 'get_context_schemes'
      },
      {role: 'assistant', content: 'The values were added and verified.'}
    ];
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const userRow = fixture.nativeElement.querySelector('.message-row.user') as HTMLElement;
    const toggle = userRow.querySelector('.message-history-toggle') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(fixture.nativeElement.querySelector('.message-row.tool_call')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('The values were added and verified.');

    toggle.click();
    fixture.detectChanges();

    const toolRow = fixture.nativeElement.querySelector('.message-row.tool_call') as HTMLElement;
    expect(toolRow).not.toBeNull();
    expect(toolRow.textContent).toContain('get_context_schemes completed.');
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
    expect(row.querySelector('score-ai-chat-tool-call')).not.toBeNull();
    expect(row.classList.contains('tool_failed')).toBe(true);
    const a11yStatus = row.querySelector('.tool-call-a11y-status') as HTMLElement;
    expect(a11yStatus.getAttribute('role')).toBe('status');
    expect(a11yStatus.getAttribute('aria-live')).toBe('polite');
    expect(a11yStatus.getAttribute('aria-atomic')).toBe('true');
    expect(row.textContent).toContain('Tool failed:');
    expect(row.textContent).toContain('GitHub search failed.');
    expect(row.querySelector('mat-icon')?.textContent).toContain('error_outline');
  });

  it('announces a chat error as an atomic alert', () => {
    fixture.componentInstance.messages = [{
      role: 'error', content: 'Could not send your response.'
    }];
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('.message-row.error') as HTMLElement;
    expect(row.getAttribute('role')).toBe('alert');
    expect(row.getAttribute('aria-live')).toBe('assertive');
    expect(row.getAttribute('aria-atomic')).toBe('true');
    expect(row.textContent).toContain('Could not send your response.');
  });

  it('does not label a completed tool row as failed', () => {
    fixture.componentInstance.messages = [{
      role: 'tool_call', content: 'Searched GitHub.', toolStatus: 'completed'
    }];
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('.message-row.tool_call') as HTMLElement;
    expect(row.classList.contains('tool_failed')).toBe(false);
    expect(row.textContent).toContain('Tool completed:');
    expect(row.querySelector('mat-icon')?.textContent).toContain('build');
  });

  it('labels a guard-blocked tool row as awaiting approval', () => {
    fixture.componentInstance.messages = [{
      role: 'tool_call',
      content: 'create_business_context is awaiting approval.',
      toolStatus: 'blocked'
    }];
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('.message-row.tool_call') as HTMLElement;
    expect(row.classList.contains('tool_failed')).toBe(false);
    expect(row.textContent).toContain('Awaiting approval:');
    expect(row.textContent).toContain('create_business_context is awaiting approval.');
    expect(row.querySelector('mat-icon')?.textContent).toContain('build');
  });

  it('labels a stop-intercepted tool row as stopped', () => {
    fixture.componentInstance.messages = [{
      role: 'tool_call',
      content: 'create_business_context was stopped before execution.',
      toolStatus: 'cancelled'
    }];
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('.message-row.tool_call') as HTMLElement;
    expect(row.classList.contains('tool_failed')).toBe(false);
    expect(row.textContent).toContain('Stopped:');
    expect(row.textContent).toContain('create_business_context was stopped before execution.');
    expect(row.querySelector('mat-icon')?.textContent).toContain('build');
  });

  it('renders a focused specialist blocked tool with the awaiting-approval status', () => {
    fixture.componentInstance.agentFocus = {
      agentId: 'request-1:agent:1', agentName: 'Verifier',
      agentRole: 'Evidence and edge cases', status: 'started',
      content: 'Creating the record...', inProgress: true, isLead: false,
      firstSeenAt: 0, lastUpdateAt: 5000,
      events: [{
        status: 'tool', content: 'create_business_context is awaiting approval.',
        toolKey: 'fanout-1:call-1', toolStatus: 'blocked'
      }]
    };
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector(
      '.agent-focus-events .message-row.tool_call'
    ) as HTMLElement;
    expect(row.textContent).toContain('Awaiting approval:');
    expect(row.textContent).toContain('create_business_context is awaiting approval.');
    expect(row.querySelector('mat-progress-spinner')).toBeNull();
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

  it('shows the selected session settings at the top of an empty chat', () => {
    fixture.componentInstance.availableModels = [{
      name: 'gpt-5_6-sol', displayName: 'GPT-5.6 SOL', description: 'GPT model.',
      provider: 'azure-openai', defaultModel: true,
      defaultReasoningEffort: 'high', reasoningEfforts: [
        {name: 'high', displayName: 'High', description: 'Greater reasoning.'}
      ]
    }];
    fixture.componentInstance.selectedModelName = 'gpt-5_6-sol';
    fixture.componentInstance.selectedReasoningEffort = 'high';
    fixture.componentInstance.permissionMode = 'full_access';
    fixture.detectChanges();

    const flow = fixture.nativeElement.querySelector('.terminal-flow') as HTMLElement;
    const summary = flow.querySelector('.session-summary') as HTMLElement;
    const terms = summary.querySelectorAll('dt') as NodeListOf<HTMLElement>;
    const values = summary.querySelectorAll('dd') as NodeListOf<HTMLElement>;

    expect(flow.firstElementChild).toBe(summary);
    expect(summary.getAttribute('aria-label')).toBe('Current assistant session settings');
    expect(Array.from(terms, term => term.textContent?.trim())).toEqual([
      'model', 'permissions'
    ]);
    expect(Array.from(values, value => value.textContent?.trim())).toEqual([
      'GPT-5.6 SOL with High effort', 'Full access'
    ]);
    expect(summary.textContent).toContain('/model');
    expect(summary.textContent).not.toContain('/runtime');
    expect(summary.textContent).toContain('/permissions');
  });

  it('keeps the session settings at the top after the conversation starts', () => {
    fixture.componentInstance.messages = [{role: 'user', content: '/model'}];
    fixture.componentInstance.availableModels = [{
      name: 'gpt-5_6-sol', displayName: 'GPT-5.6 SOL', description: 'GPT model.',
      provider: 'azure-openai', defaultModel: true,
      defaultReasoningEffort: 'high', reasoningEfforts: [
        {name: 'high', displayName: 'High', description: 'Greater reasoning.'}
      ]
    }];
    fixture.componentInstance.selectedModelName = 'gpt-5_6-sol';
    fixture.detectChanges();

    const flow = fixture.nativeElement.querySelector('.terminal-flow') as HTMLElement;
    const summary = flow.querySelector('.session-summary') as HTMLElement;

    expect(summary).not.toBeNull();
    expect(flow.firstElementChild).toBe(summary);
    expect(flow.textContent).toContain('/model');
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
    expect(row.querySelector('mat-icon')?.textContent).toContain('build');
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
        provider: 'azure-foundry', defaultModel: true,
        defaultReasoningEffort: 'high', reasoningEfforts: [
          {name: 'low', displayName: 'Low', description: 'Fast responses.'},
          {name: 'medium', displayName: 'Medium', description: 'Balanced reasoning.'},
          {name: 'high', displayName: 'High', description: 'Greater reasoning.'}
        ]
      },
      {
        name: 'gpt-5_6-sol', displayName: 'GPT-5.6 SOL', description: 'GPT model.',
        provider: 'azure-openai', defaultModel: false,
        defaultReasoningEffort: 'medium', reasoningEfforts: [
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
    fixture.detectChanges();
    await fixture.whenStable();

    const sections = fixture.nativeElement.querySelectorAll('.model-command-section') as NodeListOf<HTMLElement>;
    expect(sections).toHaveLength(2);
    expect(sections[0].textContent).toContain('Claude Fable 5');
    expect(sections[0].textContent).toContain('GPT-5.6 SOL');
    expect(sections[1].textContent).toContain('(current)');
    expect(sections[1].textContent).toContain('Low');
    expect(sections[1].textContent).toContain('High');
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
    expect(options[1].textContent).toContain('Ask only for risky actions');
    expect(options[2].textContent).toContain('Full access');
    expect(options[0].textContent).toContain('(current)');

    options[2].click();
    expect(selections).toEqual(['full_access']);
  });

  it('shows only current when the selected value is also the default', async () => {
    fixture.componentInstance.modelSettingsOpen = true;
    fixture.componentInstance.availableModels = [{
      name: 'claude-fable-5', displayName: 'Claude Fable 5', description: 'Claude model.',
      provider: 'azure-foundry', defaultModel: true,
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
      provider: 'azure-foundry', defaultModel: false,
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

  it('renders one consolidated agent group block for a fan-out', () => {
    fixture.componentInstance.messages = [
      {role: 'user', content: 'Verify this request.'},
      {
        role: 'workflow_group', content: 'Parallel workflow', activities: [
          {
            agentId: 'request-1:agent:1', agentName: 'Verifier',
            agentRole: 'Evidence and edge cases', taskLabel: 'Sync Purchase Order', status: 'started',
            content: 'Reading Sync Purchase Order.', inProgress: true, isLead: false,
            workflow: 'parallel',
            executionKind: 'parallel',
            firstSeenAt: Date.now(), lastUpdateAt: Date.now(),
            events: [{status: 'started', content: 'Reading Sync Purchase Order.'}]
          },
          {
            agentId: 'request-1:lead', agentName: 'Lead agent',
            status: 'synthesizing', content: 'Analyzing specialist findings.', inProgress: true,
            isLead: true, firstSeenAt: 1000, lastUpdateAt: 13000,
            workflow: 'parallel',
            executionKind: 'parallel',
            plannedAgentCount: 3, activeVerb: 'Analyzing', completedVerb: 'Analyzed',
            events: [{status: 'synthesizing', content: 'Analyzing specialist findings.'}]
          }
        ]
      }
    ];
    fixture.detectChanges();

    const blocks = fixture.nativeElement.querySelectorAll('.agent-group-block') as NodeListOf<HTMLElement>;
    expect(blocks).toHaveLength(1);
    const block = blocks[0];
    expect(block.getAttribute('role')).toBe('status');
    expect(block.getAttribute('aria-live')).toBe('polite');
    expect(block.getAttribute('aria-label')).toContain('Parallel workflow');
    expect(block.dataset['workflow']).toBe('parallel');
    expect(block.dataset['executionKind']).toBe('parallel');
    expect(block.textContent).toContain('Analyzing...');
    expect(block.querySelector('.agent-group-workflow')?.textContent).toContain('Parallel workflow');
    expect(block.textContent).toContain('· 3 tasks');
    expect(block.textContent).toContain('synthesizing');
    expect(block.textContent).toContain('Analyzing specialist findings.');

    const rows = block.querySelectorAll('.agent-group-row') as NodeListOf<HTMLButtonElement>;
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('Sync Purchase Order');
    expect(rows[0].textContent).toContain('Verifier');
    expect(rows[0].textContent).toContain('Reading Sync Purchase Order.');
    expect(rows[0].querySelector('mat-progress-spinner')).not.toBeNull();

    const focused = vi.fn();
    fixture.componentInstance.agentFocusRequested.subscribe(focused);
    rows[0].click();
    expect(focused).toHaveBeenCalledWith('request-1:agent:1');
  });

  it('labels one delegated worker as a specialist instead of a multi-agent workflow', () => {
    fixture.componentInstance.messages = [
      {role: 'user', content: 'Inspect the current context records.'},
      {
        role: 'agent_group', content: 'Delegated workflow', activities: [{
          agentId: 'request-1:worker:1', agentName: 'Evidence researcher',
          status: 'started', content: 'Inspecting current records.', inProgress: true,
          isLead: false, workflow: 'direct', executionKind: 'multi_agent',
          firstSeenAt: 1000, lastUpdateAt: 2000,
          events: [{status: 'started', content: 'Inspecting current records.'}]
        }]
      }
    ];

    fixture.detectChanges();

    const block = fixture.nativeElement.querySelector('.agent-group-block') as HTMLElement;
    expect(block.getAttribute('aria-label')).toContain('Specialist workflow');
    expect(block.querySelector('.agent-group-workflow')?.textContent)
      .toContain('Specialist workflow');
    expect(block.textContent).toContain('· 1 specialist');
    expect(block.textContent).toContain('A specialist is gathering evidence for the lead.');
    expect(block.textContent).not.toContain('Multi-agent workflow');
  });

  it('keeps the settled group block in request history at its execution position', () => {
    fixture.componentInstance.messages = [
      {role: 'user', content: 'Verify this request.'},
      {role: 'guide', content: 'Reviewing the existing records first.'},
      {
        role: 'agent_group', content: 'Parallel agents', activities: [{
          agentId: 'request-1:agent:1', agentName: 'Verifier', status: 'failed',
          content: 'Verifier stopped before completing.', inProgress: false, isLead: false,
          firstSeenAt: 0, lastUpdateAt: 4000,
          events: [{status: 'failed', content: 'Verifier stopped before completing.'}]
        }]
      },
      {role: 'guide', content: 'Creating and reading the records back.'},
      {
        role: 'tool_call', content: 'create_business_context completed.',
        toolStatus: 'completed', toolName: 'create_business_context'
      },
      {role: 'assistant', content: 'Done.'}
    ];
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.agent-group-block')).toBeNull();
    const toggle = fixture.nativeElement.querySelector('.message-history-toggle') as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();

    const block = fixture.nativeElement.querySelector('.agent-group-block') as HTMLElement;
    expect(block.textContent).toContain('1 failed');
    const row = block.querySelector('.agent-group-row') as HTMLElement;
    expect(row.classList.contains('agent-terminal-warn')).toBe(true);
    expect(row.querySelector('mat-icon')?.textContent).toContain('error_outline');
    expect(row.querySelector('mat-progress-spinner')).toBeNull();

    const historyRows = Array.from(fixture.nativeElement.querySelectorAll(
      '.message-history-panel > .message-row'
    )) as HTMLElement[];
    expect(historyRows.map(historyRow => historyRow.classList.contains('agent_group')
      ? 'agent_group' : historyRow.classList.contains('tool_call')
        ? 'tool_call' : 'guide')).toEqual([
      'guide', 'agent_group', 'guide', 'tool_call'
    ]);
  });

  it('renders each fan-out block from its own activity snapshot', () => {
    const settled = [{
      agentId: 'fanout-1-agent-01', agentName: 'First checker', status: 'completed' as const,
      content: 'First checker finished.', inProgress: false, isLead: false,
      firstSeenAt: 0, lastUpdateAt: 1000,
      events: [{status: 'completed' as const, content: 'First checker finished.'}]
    }];
    const live = [{
      agentId: 'fanout-2-agent-01', agentName: 'Second checker', status: 'started' as const,
      content: 'Second checker running.', inProgress: true, isLead: false,
      firstSeenAt: Date.now(), lastUpdateAt: Date.now(),
      events: [{status: 'started' as const, content: 'Second checker running.'}]
    }];
    fixture.componentInstance.messages = [
      {role: 'user', content: 'First fan-out'},
      {role: 'agent_group', content: 'Parallel agents', activities: settled},
      {role: 'assistant', content: 'First answer.'},
      {role: 'user', content: 'Second fan-out'},
      {role: 'agent_group', content: 'Parallel agents', activities: live}
    ];
    fixture.detectChanges();

    const firstTurnToggle = fixture.nativeElement.querySelector(
      '.message-turn .message-history-toggle'
    ) as HTMLButtonElement;
    firstTurnToggle.click();
    fixture.detectChanges();

    const blocks = fixture.nativeElement.querySelectorAll('.agent-group-block') as NodeListOf<HTMLElement>;
    expect(blocks).toHaveLength(2);
    expect(blocks[0].textContent).toContain('First checker finished.');
    expect(blocks[0].textContent).toContain('1 done');
    expect(blocks[0].textContent).not.toContain('Second checker');
    expect(blocks[1].textContent).toContain('Second checker running.');
    expect(blocks[1].textContent).toContain('1 running');
    expect(blocks[1].textContent).not.toContain('First checker');
  });

  it('replaces the conversation flow with the focused per-agent view', async () => {
    fixture.componentInstance.messages = [
      {role: 'user', content: 'Verify this request.'},
      {role: 'assistant', content: 'Done.'}
    ];
    fixture.componentInstance.agentFocus = {
      agentId: 'request-1:agent:1', agentName: 'Verifier',
      agentRole: 'Evidence and edge cases', status: 'completed',
      content: 'Verifier finished.', inProgress: false, isLead: false,
      firstSeenAt: 0, lastUpdateAt: 5000,
      events: [
        {status: 'started', content: 'Checking independent evidence.'},
        {status: 'tool', content: 'get_libraries completed', key: 'tool_call:mcp:call-1:completed'},
        {status: 'completed', content: 'Verifier finished.'}
      ]
    };
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.message-turn')).toBeNull();
    expect(fixture.nativeElement.querySelector('.message-row.user')).toBeNull();
    const header = fixture.nativeElement.querySelector('.agent-focus-header') as HTMLElement;
    expect(header.textContent).toContain('Verifier');
    expect(header.textContent).toContain('Evidence and edge cases');
    expect(header.textContent).toContain('completed');

    expect(fixture.nativeElement.querySelector('.agent-focus-event')).toBeNull();
    const conversationRows = fixture.nativeElement.querySelectorAll(
      '.agent-focus-events .message-row.assistant'
    ) as NodeListOf<HTMLElement>;
    expect(conversationRows).toHaveLength(2);
    expect(conversationRows[0].textContent).not.toContain('started');
    expect(conversationRows[0].textContent).toContain('Checking independent evidence.');
    const toolRow = fixture.nativeElement.querySelector(
      '.agent-focus-events .message-row.tool_call'
    ) as HTMLElement;
    expect(toolRow.querySelector('score-ai-chat-tool-call')).not.toBeNull();
    expect(toolRow.querySelector('.agent-focus-tool-icon')?.textContent).toContain('build');
    expect(toolRow.textContent).toContain('get_libraries completed');
    expect(conversationRows[1].textContent).not.toContain('completed');
    expect(conversationRows[1].textContent).toContain('Verifier finished.');

    const closed = vi.fn();
    fixture.componentInstance.agentFocusClosed.subscribe(closed);
    const back = header.querySelector('.agent-focus-back') as HTMLButtonElement;
    expect(back.getAttribute('aria-label')).toBe('Back to conversation');
    expect(back.textContent).toContain('arrow_back');
    back.click();
    expect(closed).toHaveBeenCalledOnce();
  });

  it('renders a focused tool event with detail as an expandable disclosure', () => {
    fixture.componentInstance.agentFocus = {
      agentId: 'request-1:agent:1', agentName: 'Verifier',
      agentRole: 'Evidence and edge cases', status: 'started',
      content: 'Reading releases...', inProgress: true, isLead: false,
      firstSeenAt: 0, lastUpdateAt: 5000,
      events: [
        {
          status: 'tool', content: 'Read libraries',
          key: 'tool_call:mcp:call-1:completed', toolKey: 'mcp:call-1',
          toolStatus: 'completed',
          detail: 'get_libraries\nArguments: {}\nResult: {"total_items":3}'
        },
        {status: 'tool', content: 'Reading releases...', toolKey: 'mcp:call-2', toolStatus: 'started'}
      ]
    };
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll(
      '.agent-focus-events .message-row.tool_call'
    ) as NodeListOf<HTMLElement>;
    expect(rows).toHaveLength(2);
    const disclosure = rows[0].querySelector('details.tool-call-disclosure') as HTMLDetailsElement;
    expect(disclosure).not.toBeNull();
    expect(disclosure.open).toBe(false);
    expect(disclosure.querySelector('.tool-call-summary')?.textContent).toContain('Read libraries');
    expect(disclosure.querySelector('.agent-focus-tool-icon')?.textContent).toContain('build');
    expect(disclosure.querySelector('.tool-call-detail')?.textContent)
      .toContain('Result: {"total_items":3}');
    // A running tool renders a spinner row, not a disclosure.
    expect(rows[1].querySelector('details')).toBeNull();
    expect(rows[1].querySelector('mat-progress-spinner')).not.toBeNull();
    expect(rows[1].textContent).toContain('Reading releases...');
  });

  it('stops the tool spinner once the agent itself has settled', () => {
    fixture.componentInstance.agentFocus = {
      agentId: 'request-1:agent:1', agentName: 'Verifier',
      agentRole: 'Evidence and edge cases', status: 'cancelled',
      content: 'Verifier stopped when the request was cancelled.',
      inProgress: false, isLead: false,
      firstSeenAt: 0, lastUpdateAt: 5000,
      events: [
        // A sealed recorder can drop the terminal tool event; the row must not
        // imply live activity inside a settled agent.
        {status: 'tool', content: 'Reading libraries...', toolKey: 'mcp:call-3', toolStatus: 'started'}
      ]
    };
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector(
      '.agent-focus-events .message-row.tool_call'
    ) as HTMLElement;
    expect(row.querySelector('mat-progress-spinner')).toBeNull();
    expect(row.querySelector('.agent-focus-tool-icon')?.textContent).toContain('build');
  });

  it('keeps the mutation decision panel visible while an agent focus is open', () => {
    fixture.componentInstance.agentFocus = {
      agentId: 'request-1:agent:1', agentName: 'Verifier', status: 'started',
      content: 'Checking independent evidence.', inProgress: true, isLead: false,
      firstSeenAt: 0, lastUpdateAt: 0,
      events: [{status: 'started', content: 'Checking independent evidence.'}]
    };
    fixture.componentInstance.mutationInteraction = {
      toolName: 'create_business_context',
      argumentsSummary: '{"name":"Example"}',
      mode: 'confirm',
      busy: false
    };
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.agent-focus-view')).not.toBeNull();
    const interaction = fixture.nativeElement.querySelector(
      '.interaction-command-panel'
    ) as HTMLElement;
    expect(interaction).not.toBeNull();
    expect(interaction.textContent).toContain('create_business_context');
  });

});
