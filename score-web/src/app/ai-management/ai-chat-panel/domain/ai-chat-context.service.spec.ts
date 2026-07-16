import {TestBed} from '@angular/core/testing';
import {Router} from '@angular/router';
import {AiChatContextService} from './ai-chat-context.service';
import {AiPageSnapshotService} from './ai-page-snapshot.service';
import {AiRouteRegistryContextService} from './ai-route-registry-context.service';

describe('AiChatContextService', () => {
  it('sends the current page snapshot on every turn while sending the registry once', () => {
    const snapshot = vi.fn(() => 'Visible page state');
    TestBed.configureTestingModule({
      providers: [
        AiChatContextService,
        {provide: Router, useValue: {url: '/context_management/business_context'}},
        {provide: AiPageSnapshotService, useValue: {currentPageContext: snapshot}},
        {provide: AiRouteRegistryContextService, useValue: {registryContext: () => 'trusted routes'}}
      ]
    });
    const service = TestBed.inject(AiChatContextService);

    const first = service.nextContextUpdate(false);
    const followUp = service.nextContextUpdate(true, '/context_management/business_context');

    expect(first.pageContext).toContain('trusted routes');
    expect(first.pageContext).toContain('Visible page state');
    expect(followUp.pageContext).not.toContain('trusted routes');
    expect(followUp.pageContext).toContain('Visible page state');
    expect(followUp.pageContext).toContain('untrusted reference data');
    expect(snapshot).toHaveBeenCalledTimes(2);
  });
});
