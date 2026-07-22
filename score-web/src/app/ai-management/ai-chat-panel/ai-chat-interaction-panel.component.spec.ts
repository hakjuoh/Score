import {ComponentFixture, TestBed} from '@angular/core/testing';
import {describe, expect, it, vi} from 'vitest';
import {AiChatInteractionPanelComponent} from './ai-chat-interaction-panel.component';

describe('AiChatInteractionPanelComponent', () => {
  let fixture: ComponentFixture<AiChatInteractionPanelComponent>;
  let component: AiChatInteractionPanelComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [AiChatInteractionPanelComponent]
    }).compileComponents();
    fixture = TestBed.createComponent(AiChatInteractionPanelComponent);
    component = fixture.componentInstance;
  });

  it('renders mutation approval as inline chat choices', () => {
    component.mutation = {
      mode: 'confirm', busy: false, toolName: 'create_business_context',
      argumentsSummary: '{"name":"Example"}'
    };
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent || '';
    expect(text).toContain('Approve this action?');
    expect(text).toContain('1. Approve action');
    expect(text).toContain('2. Deny');
    expect(text).toContain('3. Modify action');
    expect(text).toContain('The modified action will run without another confirmation.');
    expect(text).toContain('create_business_context');
    expect(text).toContain('{"name":"Example"}');
  });

  it('renders one shared parallel approval and emits one batch decision', () => {
    const approve = vi.fn();
    component.mutationBatchApproved.subscribe(approve);
    component.mutationApprovalBatch = {
      batchId: 'batch-1', requestId: 'request-1', conversationId: 'conversation-1',
      parallel: true, expiresAt: '2099-07-15T00:00:00Z', items: [
        {confirmationRequestId: 'confirmation-1', toolName: 'update_bbie',
          argumentsSummary: '{"id":1}', agentLabel: 'Material agent'},
        {confirmationRequestId: 'confirmation-2', toolName: 'delete_bbie',
          argumentsSummary: '{"id":2}', agentLabel: 'Context agent'}
      ]
    };
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('Approve 2 actions?');
    expect(element.textContent).toContain('Parallel assistants have reached one shared approval barrier.');
    expect(element.textContent).toContain('Material agent');
    expect(element.textContent).toContain('Context agent');
    const liveRegion = element.querySelector('[role="region"][aria-live="assertive"]');
    expect(liveRegion?.getAttribute('aria-label')).toBe('Data change approvals');
    expect(Array.from(element.querySelectorAll('.model-command-options[role="group"]'))
      .map(group => group.getAttribute('aria-label'))).toEqual([
      'Decision for update_bbie', 'Decision for delete_bbie'
    ]);
    Array.from(element.querySelectorAll('button'))
      .find(button => button.textContent?.includes('Approve all'))?.click();
    expect(approve).toHaveBeenCalledOnce();
  });

  it('collects mixed per-item choices and submits them as one decision', () => {
    const decide = vi.fn();
    component.mutationBatchDecided.subscribe(decide);
    component.mutationApprovalBatch = {
      batchId: 'batch-1', requestId: 'request-1', conversationId: 'conversation-1',
      parallel: true, expiresAt: '2099-07-15T00:00:00Z', items: [
        {confirmationRequestId: 'confirmation-1', toolName: 'update_bbie',
          argumentsSummary: '{"id":1}'},
        {confirmationRequestId: 'confirmation-2', toolName: 'delete_bbie',
          argumentsSummary: '{"id":2}'}
      ]
    };
    component.ngOnChanges({mutationApprovalBatch: {
      previousValue: undefined, currentValue: component.mutationApprovalBatch,
      firstChange: true, isFirstChange: () => true
    }});
    component.chooseMutationDecision('confirmation-1', 'APPROVE');
    component.chooseMutationDecision('confirmation-2', 'DENY');
    component.submitMutationBatchDecision();

    expect(decide).toHaveBeenCalledWith([
      {confirmationRequestId: 'confirmation-1', decision: 'APPROVE'},
      {confirmationRequestId: 'confirmation-2', decision: 'DENY'}
    ]);
  });

  it('sends the user to Chat without approving or denying immediately', () => {
    const requestChanges = vi.fn();
    const approve = vi.fn();
    const deny = vi.fn();
    component.mutationChangeRequested.subscribe(requestChanges);
    component.mutationApproved.subscribe(approve);
    component.mutationDenied.subscribe(deny);
    component.mutation = {
      mode: 'confirm', busy: false, toolName: 'create_business_context',
      argumentsSummary: '{"name":"Example"}'
    };
    fixture.detectChanges();

    const buttons = Array.from((fixture.nativeElement as HTMLElement)
      .querySelectorAll<HTMLButtonElement>('.model-command-option'));
    buttons.find(button => button.textContent?.includes('Modify action'))?.click();

    expect(requestChanges).toHaveBeenCalledOnce();
    expect(approve).not.toHaveBeenCalled();
    expect(deny).not.toHaveBeenCalled();
  });

  it('turns model-provided enum and nested fields into structured content', () => {
    const response = vi.fn();
    component.elicitationResponded.subscribe(response);
    component.elicitation = {
      elicitationId: 'elicitation-1', requestId: 'request-1',
      conversationId: 'conversation-1', expiresAt: '2099-01-01T00:00:00Z',
      message: 'Choose import behavior.',
      requestedSchema: {
        type: 'object', properties: {
          strategy: {type: 'string', title: 'Strategy', oneOf: [
            {const: 'merge', title: 'Merge', description: 'Keep existing values.'},
            {const: 'replace', title: 'Replace', description: 'Overwrite existing values.'}
          ]},
          details: {type: 'object', title: 'Details', properties: {
            note: {type: 'string', title: 'Note', minLength: 1}
          }, required: ['note']}
        }, required: ['strategy']
      }
    };
    component.ngOnChanges({elicitation: {
      previousValue: undefined, currentValue: component.elicitation,
      firstChange: true, isFirstChange: () => true
    }});

    const strategy = component.fields[0] as any;
    component.choose(strategy, strategy.options[1]);
    component.textChanged(component.fields[1] as any, {target: {value: 'Confirmed'}} as any);
    component.respond('ACCEPT');

    expect(response).toHaveBeenCalledWith({
      action: 'ACCEPT',
      content: {strategy: 'replace', details: {note: 'Confirmed'}}
    });
  });
});
