/**
 * Verifies the AI Chat Message List component's rendering and interaction contract.
 */

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
import {AiAgentActivity} from './domain/ai-agent-activity';
import {AiWorkingStatusComponent} from './ai-working-status.component';

describe('AiChatMessageListComponent', () => {
  let fixture: ComponentFixture<AiChatMessageListComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [
        AiChatMessageListComponent,
        AiChatInteractionPanelComponent,
        AiChatToolCallComponent,
        AiWorkingStatusComponent
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
    await new Promise(resolve => setTimeout(resolve, 50));
    fixture.detectChanges();

    const markdown = fixture.nativeElement.querySelector('.message-markdown') as HTMLElement;
    expect(markdown.classList.contains('markdown-body')).toBe(true);
    expect(markdown.querySelector('h1')?.textContent).toBe('Summary');
    expect(markdown.querySelectorAll('li')).toHaveLength(2);
  });

  it('renders generated files as accessible download links', () => {
    fixture.componentInstance.messages = [{
      role: 'assistant',
      content: 'The report is ready.',
      files: [{
        fileId: 'file-1', format: 'pdf', filename: 'work-report.pdf',
        mediaType: 'application/pdf', size: 1536, sha256: 'abc',
        downloadUrl: '/api/ai/chat/conversations/c1/files/file-1'
      }]
    }];
    fixture.detectChanges();

    const group = fixture.nativeElement.querySelector('.message-files') as HTMLElement;
    const link = group.querySelector('.message-file') as HTMLAnchorElement;
    expect(group.getAttribute('aria-label')).toBe('Generated files');
    expect(link.getAttribute('href')).toBe('/api/ai/chat/conversations/c1/files/file-1');
    expect(link.hasAttribute('download')).toBe(true);
    expect(link.textContent).toContain('work-report.pdf');
    expect(link.textContent).toContain('1.5 KB');
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
    await new Promise(resolve => setTimeout(resolve, 50));
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

  it('keeps intermediate tool calls and guides expanded while the request is in progress and collapses when finished', async () => {
    fixture.componentRef.setInput('pending', true);
    fixture.componentRef.setInput('messages', [
      {role: 'user', content: 'Compare components'},
      {role: 'guide', content: 'Comparing Address components...'},
      {
        role: 'tool_call', content: 'get_libraries completed.',
        toolStatus: 'completed', toolName: 'get_libraries'
      },
      {
        role: 'progress', content: 'Comparing the component details...',
        eventType: 'assistant_update', inProgress: true
      }
    ]);
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise(resolve => setTimeout(resolve, 50));
    fixture.detectChanges();

    const userRow = fixture.nativeElement.querySelector('.message-row.user') as HTMLElement;
    const toggle = userRow.querySelector('.message-history-toggle') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(fixture.nativeElement.querySelector('.message-history-panel')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.message-row.tool_call')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Comparing Address components...');

    const previousMessages = fixture.componentInstance.messages;
    fixture.componentRef.setInput('pending', false);
    fixture.componentRef.setInput('messages', [
      ...previousMessages.slice(0, -1),
      {role: 'assistant', content: 'Comparison complete.'}
    ]);
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise(resolve => setTimeout(resolve, 50));
    fixture.detectChanges();

    expect(fixture.componentInstance.isHistoryExpanded(0)).toBe(false);
    const updatedToggle = fixture.nativeElement.querySelector('.message-history-toggle') as HTMLButtonElement;
    expect(updatedToggle.getAttribute('aria-expanded')).toBe('false');
    expect(fixture.nativeElement.querySelector('.message-history-panel')).toBeNull();
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

  it('announces reconnect progress as an atomic polite status', () => {
    fixture.componentInstance.messages = [{
      role: 'progress', content: 'Reconnecting... (2/3)', inProgress: true
    }];
    fixture.componentInstance.pending = true;
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('.message-row.progress') as HTMLElement;
    expect(row.getAttribute('role')).toBe('status');
    expect(row.getAttribute('aria-live')).toBe('polite');
    expect(row.getAttribute('aria-atomic')).toBe('true');
    expect(row.querySelector('mat-progress-spinner')?.getAttribute('aria-hidden')).toBe('true');
    expect(fixture.nativeElement.querySelector('.request-pending-indicator')).toBeNull();
  });

  it('uses the shared elapsed status for exact, legacy, and guide Working values', () => {
    const startedAt = Date.now();
    fixture.componentInstance.messages = [
      {role: 'progress', content: 'Working', inProgress: true, statusStartedAt: startedAt},
      {role: 'progress', content: 'Working...', inProgress: true, statusStartedAt: startedAt},
      {
        role: 'guide', content: 'Working...', workflowStatus: 'started',
        statusStartedAt: startedAt
      }
    ];

    fixture.detectChanges();

    const statuses = fixture.nativeElement.querySelectorAll(
      'score-ai-working-status'
    ) as NodeListOf<HTMLElement>;
    expect(statuses).toHaveLength(3);
    Array.from(statuses).forEach(status => {
      expect(status.textContent?.trim()).toMatch(/^Working \(\d+s\)$/);
      expect(status.getAttribute('aria-label')).toBe('Working');
    });
    expect(fixture.nativeElement.textContent).not.toContain('Working...');
  });

  it('shows one live workflow timer and removes fallback Working content at termination', () => {
    fixture.componentInstance.messages = [{
      role: 'workflow_group', content: 'Working', workflowStatus: 'started',
      statusStartedAt: Date.now(), activities: [], children: []
    }];
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('score-ai-working-status')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('.agent-group-plan')).toBeNull();

    fixture.componentRef.setInput('messages', [{
      role: 'workflow_group', content: 'Working', workflowStatus: 'failed',
      statusStartedAt: Date.now(), activities: [], children: []
    }]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('score-ai-working-status')).toBeNull();
    expect(fixture.nativeElement.textContent).not.toContain('Working');
    expect(fixture.nativeElement.textContent).toContain('Workflow failed');
  });

  it('keeps retry narration visible while only the long provider reason shrinks', async () => {
    fixture.componentInstance.messages = [{
      role: 'progress', inProgress: true, statusTone: 'error',
      content: 'A very long provider error explaining why the request was overloaded.',
      statusSuffix: 'The model provider request failed; retrying (attempt 1 of 10).'
    }];
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('.message-row.status-error') as HTMLElement;
    const segmented = row.querySelector('.message-text.status-segmented') as HTMLElement;
    const primary = row.querySelector('.status-primary') as HTMLElement;
    const suffix = row.querySelector('.status-suffix') as HTMLElement;
    expect(primary.textContent).toContain('provider error');
    expect(suffix.textContent).toContain('retrying (attempt 1 of 10)');
    expect(Array.from(segmented.children)).toEqual([primary, suffix]);
  });

  it('does not repeatedly announce a streamed assistant update as a status', () => {
    fixture.componentInstance.messages = [{
      role: 'progress', eventType: 'assistant_update',
      content: 'A growing streamed response', inProgress: true
    }];
    fixture.componentInstance.pending = true;
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('.message-row.progress') as HTMLElement;
    expect(row.getAttribute('role')).toBeNull();
    expect(row.getAttribute('aria-live')).toBeNull();
    expect(row.getAttribute('aria-atomic')).toBeNull();
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

  it('labels an exact denied retry as denied', () => {
    fixture.componentInstance.messages = [{
      role: 'tool_call',
      content: 'delete_business_context was denied before execution.',
      toolStatus: 'denied'
    }];
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('.message-row.tool_call') as HTMLElement;
    expect(row.classList.contains('tool_failed')).toBe(false);
    expect(row.textContent).toContain('Denied:');
    expect(row.textContent).toContain('delete_business_context was denied before execution.');
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
    const toolRow = fixture.nativeElement.querySelector('.message-row.tool_call') as HTMLElement;
    expect(toolRow.textContent).toContain('tool_search_tool completed.');
    expect(toolRow.textContent).not.toContain('toolSearchTool');
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

  it('shows an initializing session status banner while models are loading', () => {
    fixture.componentInstance.availableModels = [];
    fixture.componentInstance.selectedModelName = '';
    fixture.detectChanges();

    const loadingSummary = fixture.nativeElement.querySelector('.session-summary-loading') as HTMLElement;
    expect(loadingSummary).not.toBeNull();
    expect(loadingSummary.getAttribute('aria-label')).toBe('Initializing assistant session');
    expect(loadingSummary.textContent).toContain('Initializing assistant session…');
    expect(loadingSummary.textContent).toContain('Loading models and session settings…');
    expect(loadingSummary.querySelector('mat-progress-spinner')).not.toBeNull();
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
    fixture.componentInstance.mcpStatus = {
      state: 'READY', servers: [
        {name: 'connect-center-mcp', status: 'CONNECTED', toolCount: 12}
      ]
    };
    fixture.detectChanges();

    const flow = fixture.nativeElement.querySelector('.terminal-flow') as HTMLElement;
    const summary = flow.querySelector('.session-summary') as HTMLElement;
    const terms = summary.querySelectorAll('dt') as NodeListOf<HTMLElement>;
    const values = summary.querySelectorAll('dd') as NodeListOf<HTMLElement>;

    expect(flow.firstElementChild).toBe(summary);
    expect(summary.getAttribute('aria-label')).toBe('Current assistant session settings');
    expect(Array.from(terms, term => term.textContent?.trim())).toEqual([
      'model', 'permissions', 'mcp servers'
    ]);
    expect(Array.from(values, value => value.textContent?.trim())).toEqual([
      'GPT-5.6 SOL with high reasoning effort', 'Full access',
      '1 connected · 12 tools'
    ]);
    expect(summary.textContent).toContain('/model');
    expect(summary.textContent).not.toContain('/runtime');
    expect(summary.textContent).toContain('/permissions');
    expect(summary.textContent).toContain('/mcp');
    expect(summary.querySelector('.session-summary-mcp-status.warning')).toBeNull();
  });

  it('warns when a configured MCP server is unavailable', () => {
    fixture.componentInstance.availableModels = [{
      name: 'gpt-5_6-sol', displayName: 'GPT-5.6 SOL', description: 'GPT model.',
      provider: 'azure-openai', defaultModel: true,
      defaultReasoningEffort: 'high', reasoningEfforts: [
        {name: 'high', displayName: 'High', description: 'Greater reasoning.'}
      ]
    }];
    fixture.componentInstance.selectedModelName = 'gpt-5_6-sol';
    fixture.componentInstance.mcpStatus = {
      state: 'READY', servers: [
        {name: 'connect-center-mcp', status: 'UNAVAILABLE', toolCount: 0}
      ]
    };

    fixture.detectChanges();

    const status = fixture.nativeElement.querySelector(
      '.session-summary-mcp-status.warning') as HTMLElement;
    expect(status.textContent?.trim()).toBe('0/1 connected · 0 tools');
    expect(status.getAttribute('aria-live')).toBe('polite');
  });

  it('warns when MCP connects without advertising any tools', () => {
    fixture.componentInstance.availableModels = [{
      name: 'gpt-5_6-sol', displayName: 'GPT-5.6 SOL', description: 'GPT model.',
      provider: 'azure-openai', defaultModel: true,
      defaultReasoningEffort: 'high', reasoningEfforts: [
        {name: 'high', displayName: 'High', description: 'Greater reasoning.'}
      ]
    }];
    fixture.componentInstance.selectedModelName = 'gpt-5_6-sol';
    fixture.componentInstance.mcpStatus = {
      state: 'READY', servers: [
        {name: 'connect-center-mcp', status: 'CONNECTED', toolCount: 0}
      ]
    };
    fixture.detectChanges();

    const status = fixture.nativeElement.querySelector(
      '.session-summary-mcp-status.warning') as HTMLElement;
    expect(status.textContent?.trim()).toBe(
      '1 connected · 0 tools'
    );
    expect(status.classList.contains('warning')).toBe(true);
  });

  it('summarizes partial availability across multiple MCP servers', () => {
    fixture.componentInstance.availableModels = [{
      name: 'gpt-5_6-sol', displayName: 'GPT-5.6 SOL', description: 'GPT model.',
      provider: 'azure-openai', defaultModel: true,
      defaultReasoningEffort: 'high', reasoningEfforts: [
        {name: 'high', displayName: 'High', description: 'Greater reasoning.'}
      ]
    }];
    fixture.componentInstance.selectedModelName = 'gpt-5_6-sol';
    fixture.componentInstance.mcpStatus = {
      state: 'READY',
      servers: [
        {name: 'connect-center-mcp', status: 'CONNECTED', toolCount: 127},
        {name: 'reference-mcp', status: 'UNAVAILABLE', toolCount: 0},
        {name: 'draft-mcp', status: 'NOT_CONFIGURED', toolCount: 0}
      ]
    };

    fixture.detectChanges();

    const status = fixture.nativeElement.querySelector(
      '.session-summary-mcp-status.warning') as HTMLElement;
    expect(status.textContent?.trim()).toBe('1/3 connected · 127 tools');
  });

  it('warns when no MCP server entries are configured', () => {
    fixture.componentInstance.availableModels = [{
      name: 'gpt-5_6-sol', displayName: 'GPT-5.6 SOL', description: 'GPT model.',
      provider: 'azure-openai', defaultModel: true,
      defaultReasoningEffort: 'high', reasoningEfforts: [
        {name: 'high', displayName: 'High', description: 'Greater reasoning.'}
      ]
    }];
    fixture.componentInstance.selectedModelName = 'gpt-5_6-sol';
    fixture.componentInstance.mcpStatus = {state: 'READY', servers: []};

    fixture.detectChanges();

    const status = fixture.nativeElement.querySelector(
      '.session-summary-mcp-status.warning') as HTMLElement;
    expect(status.textContent?.trim()).toBe('No servers configured');
  });

  it('shows without reasoning when the selected model disables reasoning', () => {
    fixture.componentInstance.availableModels = [{
      name: 'claude-sonnet-5', displayName: 'Claude Sonnet 5', description: 'Claude model.',
      provider: 'azure-foundry', defaultModel: true,
      defaultReasoningEffort: 'medium', reasoningEfforts: [
        {name: 'disabled', displayName: 'Disabled', description: 'Disable reasoning.'},
        {name: 'medium', displayName: 'Medium', description: 'Balanced reasoning.'}
      ]
    }];
    fixture.componentInstance.selectedModelName = 'claude-sonnet-5';
    fixture.componentInstance.selectedReasoningEffort = 'disabled';
    fixture.detectChanges();

    const value = fixture.nativeElement.querySelector('.session-summary-details dd') as HTMLElement;
    expect(value.textContent?.trim()).toBe('Claude Sonnet 5 without reasoning');
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
    expect(options[1].textContent).toContain('Ask only for risky changes');
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
      defaultReasoningEffort: null, reasoningEfforts: []
    }];
    fixture.componentInstance.modelDraftName = 'claude-haiku-4_5';
    fixture.componentInstance.modelDraftReasoningEffort = '';
    fixture.componentInstance.selectedModelName = 'claude-haiku-4_5';
    fixture.componentInstance.selectedReasoningEffort = '';
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
        role: 'workflow_group', content: 'Checking independent evidence.',
        workflowType: 'parallel', activities: [
          {
            agentId: 'request-1:agent:1', agentName: 'Verifier',
            agentRole: 'Evidence and edge cases', taskLabel: 'Sync Purchase Order', status: 'started',
            content: 'Reading Sync Purchase Order.', inProgress: true, isLead: false,
            workflowType: 'parallel',
            firstSeenAt: Date.now(), lastUpdateAt: Date.now(),
            events: [{status: 'started', content: 'Reading Sync Purchase Order.'}]
          },
          {
            agentId: 'request-1:lead', agentName: 'Lead agent',
            status: 'synthesizing', content: 'Analyzing specialist findings.', inProgress: true,
            isLead: true, firstSeenAt: 1000, lastUpdateAt: 13000,
            workflowType: 'parallel',
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
    expect(block.getAttribute('aria-label')).toBe('Execution: synthesizing');
    expect(block.dataset['workflowType']).toBe('parallel');
    expect(block.classList).toContain('workflow-parallel');
    expect(block.textContent).toContain('Analyzing...');
    expect(block.querySelector('.agent-group-workflow')).toBeNull();
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

  it('renders a one-item sequential workflow without exposing a type label', () => {
    fixture.componentInstance.messages = [
      {role: 'user', content: 'Inspect the current context records.'},
      {
        role: 'workflow_group', content: 'Inspecting the current records.',
        workflowType: 'sequential', activities: [{
          agentId: 'request-1:worker:1', agentName: 'Evidence researcher',
          status: 'started', content: 'Inspecting current records.', inProgress: true,
          isLead: false, workflowType: 'sequential',
          firstSeenAt: 1000, lastUpdateAt: 2000,
          events: [{status: 'started', content: 'Inspecting current records.'}]
        }]
      }
    ];

    fixture.detectChanges();

    const block = fixture.nativeElement.querySelector('.agent-group-block') as HTMLElement;
    expect(block.getAttribute('aria-label')).toBe('Execution: 1 running');
    expect(block.querySelector('.agent-group-workflow')).toBeNull();
    expect(block.textContent).toContain('· 1 task');
    expect(block.textContent).toContain('Inspecting the current records.');
    expect(block.textContent).not.toContain('workflow');
  });

  it('shows sequential task state with spinner and checkbox characters', () => {
    fixture.componentInstance.messages = [{
      role: 'workflow_group', content: 'Checking in order.', workflowType: 'sequential',
      activities: [
        {
          agentId: 'done', agentName: 'First task', status: 'completed',
          content: 'First task complete.', inProgress: false, isLead: false,
          firstSeenAt: 1000, lastUpdateAt: 2000, events: []
        },
        {
          agentId: 'running', agentName: 'Second task', status: 'started',
          content: 'Second task running.', inProgress: true, isLead: false,
          firstSeenAt: 2000, lastUpdateAt: 3000, events: []
        },
        {
          agentId: 'queued', agentName: 'Third task', status: 'planned',
          content: 'Third task queued.', inProgress: false, isLead: false,
          firstSeenAt: 2000, lastUpdateAt: 2000, events: []
        }
      ]
    }];

    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll(
      '.agent-group-row'
    ) as NodeListOf<HTMLElement>;
    expect(rows).toHaveLength(3);
    expect(rows[0].textContent).toContain('☑');
    expect(rows[1].querySelector('mat-progress-spinner')).not.toBeNull();
    expect(rows[2].textContent).toContain('☐');
  });

  it('shows every running parallel task with its own spinner', () => {
    fixture.componentInstance.messages = [{
      role: 'workflow_group', content: 'Checking concurrently.', workflowType: 'parallel',
      activities: ['First task', 'Second task'].map((agentName, index) => ({
        agentId: `parallel-${index}`, agentName, status: 'started' as const,
        content: `${agentName} running.`, inProgress: true, isLead: false,
        firstSeenAt: 1000, lastUpdateAt: 2000, events: []
      }))
    }];

    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll(
      '.agent-group-row mat-progress-spinner'
    )).toHaveLength(2);
  });

  it('keeps the composed plan count and lead completion label stable across a worker chain', () => {
    fixture.componentInstance.messages = [{
      role: 'workflow_group', content: 'Checking both stages.',
      workflowType: 'sequential', activities: [
        {
          agentId: 'request-1:composed:lead', agentName: 'Lead agent',
          status: 'completed', content: 'Checked.', inProgress: false, isLead: true,
          plannedAgentCount: 2, activeVerb: 'Checking', completedVerb: 'Checked',
          workflowType: 'sequential',
          firstSeenAt: 1000, lastUpdateAt: 4000,
          events: [{status: 'completed', content: 'Checked.'}]
        },
        {
          agentId: 'request-1:find-extenders', agentName: 'Evidence researcher',
          status: 'completed', content: 'Searched.', inProgress: false, isLead: false,
          completedVerb: 'Searched', firstSeenAt: 1000, lastUpdateAt: 2000,
          events: [{status: 'completed', content: 'Searched.'}]
        },
        {
          agentId: 'request-1:conflict-review', agentName: 'Critical reviewer',
          status: 'completed', content: 'Reviewed.', inProgress: false, isLead: false,
          completedVerb: 'Reviewed', firstSeenAt: 2000, lastUpdateAt: 3000,
          events: [{status: 'completed', content: 'Reviewed.'}]
        }
      ]
    }];

    fixture.detectChanges();

    const block = fixture.nativeElement.querySelector('.agent-group-block') as HTMLElement;
    expect(block.textContent).toContain('Checked');
    expect(block.textContent).toContain('· 2 tasks');
    expect(block.textContent).toContain('☑');
    expect(block.textContent).not.toContain('workflow');
    expect(block.querySelector('.agent-group-title')?.textContent).not.toContain('Searched');
  });

  it('keeps the lead phase between sequential worker transitions', () => {
    const lead: AiAgentActivity = {
      agentId: 'request-1:composed:lead', agentName: 'Lead agent',
      status: 'started', content: 'Checking both stages.', inProgress: true, isLead: true,
      plannedAgentCount: 2, activeVerb: 'Checking', completedVerb: 'Checked',
      workflowType: 'sequential',
      firstSeenAt: 1000, lastUpdateAt: 1000, events: []
    };
    const first: AiAgentActivity = {
      agentId: 'first', agentName: 'Evidence researcher', status: 'completed',
      content: 'Searched.', inProgress: false, isLead: false, completedVerb: 'Searched',
      firstSeenAt: 1000, lastUpdateAt: 2000, events: []
    };
    const second: AiAgentActivity = {
      agentId: 'second', agentName: 'Critical reviewer', status: 'planned',
      content: 'Review is queued.', inProgress: false, isLead: false, completedVerb: 'Reviewed',
      firstSeenAt: 1000, lastUpdateAt: 1000, events: []
    };
    fixture.componentInstance.messages = [{
      role: 'workflow_group', content: 'Checking both stages.',
      workflowType: 'sequential', activities: [lead, first, second]
    }];

    const phase = () => fixture.componentInstance.agentGroupPhase(
      fixture.componentInstance.messages[0]
    );
    expect(phase()).toBe('Checking...');

    second.status = 'completed';
    second.content = 'Reviewed.';
    expect(phase()).toBe('Checking...');

    lead.status = 'completed';
    lead.inProgress = false;
    lead.content = 'Checked.';
    expect(phase()).toBe('Checked');
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
    await new Promise(resolve => setTimeout(resolve, 50));
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

  it('recursively renders a nested Workflow inside an Agent conversation', async () => {
    fixture.componentInstance.agentFocus = {
      agentId: 'parent', agentName: 'Evidence researcher', status: 'started',
      content: 'Cross-checking the count.', inProgress: true, isLead: false,
      firstSeenAt: 1000, lastUpdateAt: 2000, events: [],
      messages: [
        {role: 'guide', content: 'I’ll cross-check this result independently.'},
        {
          role: 'workflow_group', content: 'Cross-checking independently.',
          workflowNodeId: 'nested', workflowType: 'parallel',
          workflowParentNodeId: 'parent', children: [], activities: [{
            agentId: 'child', agentName: 'Independent reader', status: 'started',
            content: 'Reading the release.', inProgress: true, isLead: false,
            firstSeenAt: 2000, lastUpdateAt: 3000, events: [], messages: []
          }]
        }
      ]
    };

    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise(resolve => setTimeout(resolve, 50));
    fixture.detectChanges();

    const focus = fixture.nativeElement.querySelector('.agent-focus-events') as HTMLElement;
    expect(focus.textContent).toContain('I’ll cross-check this result independently.');
    expect(focus.querySelector('.agent-group-block')).not.toBeNull();
    expect(focus.querySelector('.agent-group-row')?.textContent)
      .toContain('Independent reader');
  });

  it('keeps a nested Workflow behind a closed disclosure', () => {
    fixture.componentInstance.messages = [{
      role: 'workflow_group', content: 'Outer', workflowNodeId: 'outer',
      workflowType: 'sequential',
      workflowStatus: 'started', activities: [], children: [{
        role: 'progress', content: 'Working...', inProgress: true,
        eventType: 'composite_status'
      }, {
        role: 'workflow_group', content: 'Inner', workflowNodeId: 'inner',
        workflowParentNodeId: 'outer', workflowType: 'parallel', workflowStatus: 'started',
        activities: [], children: []
      }]
    }];

    fixture.detectChanges();

    const disclosure = fixture.nativeElement.querySelector(
      '.nested-workflow-item'
    ) as HTMLDetailsElement;
    const blocks = fixture.nativeElement.querySelectorAll(
      '.agent-group-block'
    ) as NodeListOf<HTMLElement>;
    expect(disclosure.open).toBe(false);
    expect(blocks).toHaveLength(2);
    expect(blocks[0].getAttribute('role')).toBe('group');
    expect(blocks[0].getAttribute('aria-live')).toBeNull();
    expect(disclosure.contains(blocks[1])).toBe(true);
    expect(blocks[1].getAttribute('role')).toBe('status');
    expect(blocks[1].getAttribute('aria-live')).toBe('polite');

    (disclosure.querySelector('summary') as HTMLElement).click();
    fixture.detectChanges();
    expect(disclosure.open).toBe(true);
  });

  it('renders terminal Workflow state even before an Agent appears', () => {
    fixture.componentInstance.messages = [{
      role: 'workflow_group', content: 'Workflow', workflowNodeId: 'empty',
      workflowStatus: 'failed', activities: [], children: []
    }];

    fixture.detectChanges();

    const block = fixture.nativeElement.querySelector('.agent-group-block') as HTMLElement;
    expect(block.textContent).toContain('Workflow failed');
    expect(block.textContent).toContain('failed');
    expect(block.textContent).not.toContain('Working...');
  });

  it('moves keyboard focus into Agent detail and back to its row', () => {
    const focused: AiAgentActivity = {
      agentId: 'focus-me', agentName: 'Verifier', status: 'completed',
      content: 'Verified.', inProgress: false, isLead: false,
      firstSeenAt: 0, lastUpdateAt: 1, events: [], messages: []
    };
    fixture.componentInstance.messages = [{
      role: 'workflow_group', content: 'Workflow', activities: [focused], children: []
    }];
    fixture.componentRef.setInput('agentFocus', focused);
    fixture.detectChanges();

    const back = fixture.nativeElement.querySelector('.agent-focus-back') as HTMLButtonElement;
    expect(document.activeElement).toBe(back);

    fixture.componentRef.setInput('agentFocus', undefined);
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector(
      '.agent-group-row[data-agent-id="focus-me"]'
    ) as HTMLButtonElement;
    expect(document.activeElement).toBe(row);
  });

  it('returns focus from a child Agent to its row in the parent detail', () => {
    const child: AiAgentActivity = {
      agentId: 'child', agentName: 'Child', status: 'completed',
      content: 'Checked.', inProgress: false, isLead: false,
      firstSeenAt: 0, lastUpdateAt: 1, events: [], messages: []
    };
    const parent: AiAgentActivity = {
      agentId: 'parent', agentName: 'Parent', status: 'completed',
      content: 'Verified.', inProgress: false, isLead: false,
      firstSeenAt: 0, lastUpdateAt: 2, events: [], messages: [{
        role: 'workflow_group', content: 'Nested workflow', activities: [child], children: []
      }]
    };
    fixture.componentRef.setInput('agentFocus', parent);
    fixture.detectChanges();
    fixture.componentRef.setInput('agentFocusBackLabel', 'Back to Parent agent activity');
    fixture.componentRef.setInput('agentFocus', child);
    fixture.detectChanges();

    expect((fixture.nativeElement.querySelector('.agent-focus-back') as HTMLButtonElement)
      .getAttribute('aria-label')).toBe('Back to Parent agent activity');

    fixture.componentRef.setInput('agentFocus', parent);
    fixture.detectChanges();

    const childRow = fixture.nativeElement.querySelector(
      '.agent-group-row[data-agent-id="child"]'
    ) as HTMLButtonElement;
    expect(document.activeElement).toBe(childRow);
  });

  it('does not revive suppressed lifecycle chatter from legacy events', () => {
    fixture.componentInstance.agentFocus = {
      agentId: 'planned', agentName: 'Evidence researcher', status: 'planned',
      content: 'Queued Count ACCs.', inProgress: false, isLead: false,
      firstSeenAt: 1000, lastUpdateAt: 1000,
      events: [{status: 'planned', content: 'Queued Count ACCs.'}],
      messages: []
    };

    fixture.detectChanges();

    const focus = fixture.nativeElement.querySelector('.agent-focus-events') as HTMLElement;
    expect(focus.textContent).not.toContain('Queued Count ACCs.');
  });

  it('settles a focused provider retry when its specialist is terminal', () => {
    fixture.componentInstance.agentFocus = {
      agentId: 'request-1:agent:1', agentName: 'Verifier', status: 'completed',
      content: 'Verifier finished.', inProgress: false, isLead: false,
      firstSeenAt: 0, lastUpdateAt: 5000,
      events: [
        {status: 'provider_error', content: 'Overloaded'},
        {status: 'provider_retry', content: 'Retrying provider request.'}
      ]
    };
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll(
      '.agent-focus-events .message-row'
    ) as NodeListOf<HTMLElement>;
    expect(rows).toHaveLength(2);
    expect(rows[0].classList.contains('error')).toBe(true);
    expect(rows[1].classList.contains('progress')).toBe(true);
    expect(rows[1].querySelector('.message-progress-spinner')).toBeNull();
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

  it('keeps the change decision panel visible while an agent focus is open', () => {
    fixture.componentInstance.agentFocus = {
      agentId: 'request-1:agent:1', agentName: 'Verifier', status: 'started',
      content: 'Checking independent evidence.', inProgress: true, isLead: false,
      firstSeenAt: 0, lastUpdateAt: 0,
      events: [{status: 'started', content: 'Checking independent evidence.'}]
    };
    fixture.componentInstance.changeInteraction = {
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
