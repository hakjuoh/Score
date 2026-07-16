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
