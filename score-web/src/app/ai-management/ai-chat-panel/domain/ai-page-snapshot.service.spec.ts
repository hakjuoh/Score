/**
 * Verifies the AI Page Snapshot service contract, failure handling, and edge cases.
 */

import {TestBed} from '@angular/core/testing';
import {Router} from '@angular/router';
import {AiPageSnapshotService} from './ai-page-snapshot.service';
import {AiRouteRegistryContextService} from './ai-route-registry-context.service';
import {AuthService} from '../../../authentication/auth.service';

describe('AiPageSnapshotService', () => {
  let service: AiPageSnapshotService;
  let rectSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        AiPageSnapshotService,
        AiRouteRegistryContextService,
        {provide: AuthService, useValue: {getUserToken: () => ({roles: ['developer']})}},
        {provide: Router, useValue: {url: '/context_management/business_context'}}
      ]
    });
    service = TestBed.inject(AiPageSnapshotService);
    rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      width: 100,
      height: 20,
      top: 0,
      right: 100,
      bottom: 20,
      left: 0,
      x: 0,
      y: 0,
      toJSON: () => ({})
    } as DOMRect);
    document.title = 'Score';
    document.body.innerHTML = `
      <div class="body">
        <h1 id="title"></h1>
        <button aria-label="Create business context"></button>
        <score-ai-chat-panel><button aria-label="Internal command"></button></score-ai-chat-panel>
      </div>
    `;
    (document.getElementById('title') as HTMLElement).innerText = 'Business Contexts';
  });

  afterEach(() => {
    rectSpy.mockRestore();
    document.body.innerHTML = '';
  });

  it('collects visible page text and skips chat panel internals', () => {
    const context = service.currentPageContext({
      resource: 'business-context',
      listPath: '/context_management/business_context',
      detailPattern: '/context_management/business_context/{id}',
      idFields: ['businessContextId'],
      linkableFields: ['name'],
      label: 'Business Context',
      roles: ['admin']
    });

    expect(context).toContain('URL path: /context_management/business_context');
    expect(context).toContain('Current resource: Business Context');
    expect(context).toContain('Visible headings: Business Contexts');
    expect(context).toContain('Visible actions: Create business context');
    expect(context).not.toContain('Internal command');
  });
});
