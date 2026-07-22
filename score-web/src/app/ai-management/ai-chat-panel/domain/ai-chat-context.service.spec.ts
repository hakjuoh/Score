import {TestBed} from '@angular/core/testing';
import {Router} from '@angular/router';
import {AiChatContextService} from './ai-chat-context.service';
import {AiPageSnapshotService} from './ai-page-snapshot.service';
import {AiRouteRegistryContextService} from './ai-route-registry-context.service';
import {base64Decode} from '../../../common/utility';

describe('AiChatContextService', () => {
  it('keeps the route manifest stable while sending only the volatile page snapshot as page context', () => {
    const snapshot = vi.fn(() => 'Visible page state');
    const routeManifest = {schemaVersion: 1 as const, routes: []};
    TestBed.configureTestingModule({
      providers: [
        AiChatContextService,
        {provide: Router, useValue: {url: '/context_management/business_context'}},
        {provide: AiPageSnapshotService, useValue: {currentPageContext: snapshot}},
        {provide: AiRouteRegistryContextService, useValue: {routeManifest: () => routeManifest}}
      ]
    });
    const service = TestBed.inject(AiChatContextService);

    const first = service.nextContextUpdate();
    const followUp = service.nextContextUpdate();

    expect(first.pageContext).toContain('Visible page state');
    expect(followUp.pageContext).toContain('Visible page state');
    expect(followUp.pageContext).toContain('untrusted reference data');
    expect(first.routeManifest).toBe(routeManifest);
    expect(followUp.routeManifest).toBe(routeManifest);
    expect(snapshot).toHaveBeenCalledTimes(2);
  });

  it('rewrites aliases, Unicode, and date ranges into the UI base64 query format', () => {
    TestBed.configureTestingModule({
      providers: [
        AiChatContextService,
        {provide: Router, useValue: {url: '/context_management/business_context'}},
        {provide: AiPageSnapshotService, useValue: {currentPageContext: () => ''}},
        {provide: AiRouteRegistryContextService,
          useValue: {routeManifest: () => ({schemaVersion: 1, routes: []})}}
      ]
    });
    const service = TestBed.inject(AiChatContextService);
    const link = document.createElement('a');
    link.setAttribute('href', '/context_management/business_context' +
      '?biz_ctx_name=%ED%99%94%ED%95%99&limit=25&updated_on=[2026-01-01~2026-02-01]&ignored=value#results');

    const route = service.routeFromLink(link)!;
    const url = new URL(route, window.location.origin);
    const decoded = base64Decode(url.searchParams.get('q')!);

    expect(url.pathname).toBe('/context_management/business_context');
    expect(url.hash).toBe('#results');
    expect(decoded).toContain('name=%ED%99%94%ED%95%99');
    expect(decoded).toContain('pageSize=25');
    expect(decoded).toContain('sortActive=lastUpdateTimestamp');
    expect(decoded).toContain('updatedDateStart=2026-01-01');
    expect(decoded).toContain('updatedDateEnd=2026-02-01');
    expect(decoded).not.toContain('ignored');
  });

  it.each([
    '//evil.example/path',
    '/\\evil.example/path',
    'https://evil.example/path',
    '#local-fragment',
    'http://['
  ])('rejects unsafe or non-navigating href %s', href => {
    TestBed.configureTestingModule({
      providers: [
        AiChatContextService,
        {provide: Router, useValue: {url: '/context_management/business_context'}},
        {provide: AiPageSnapshotService, useValue: {currentPageContext: () => ''}},
        {provide: AiRouteRegistryContextService,
          useValue: {routeManifest: () => ({schemaVersion: 1, routes: []})}}
      ]
    });
    const link = document.createElement('a');
    link.setAttribute('href', href);

    expect(TestBed.inject(AiChatContextService).routeFromLink(link)).toBeUndefined();
  });

  it('drops unsupported list parameters', () => {
    TestBed.configureTestingModule({
      providers: [
        AiChatContextService,
        {provide: Router, useValue: {url: '/context_management/business_context'}},
        {provide: AiPageSnapshotService, useValue: {currentPageContext: () => ''}},
        {provide: AiRouteRegistryContextService,
          useValue: {routeManifest: () => ({schemaVersion: 1, routes: []})}}
      ]
    });
    const link = document.createElement('a');
    link.setAttribute('href', '/context_management/business_context?unsupported=value');

    expect(TestBed.inject(AiChatContextService).routeFromLink(link))
      .toBe('/context_management/business_context');
  });
});
