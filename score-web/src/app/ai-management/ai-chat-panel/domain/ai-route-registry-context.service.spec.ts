import {TestBed} from '@angular/core/testing';
import {AuthService} from '../../../authentication/auth.service';
import {AiRouteRegistryContextService} from './ai-route-registry-context.service';

describe('AiRouteRegistryContextService', () => {
  let roles: string[];
  let service: AiRouteRegistryContextService;

  beforeEach(() => {
    roles = [];
    TestBed.configureTestingModule({
      providers: [
        AiRouteRegistryContextService,
        {provide: AuthService, useValue: {getUserToken: () => ({roles})}}
      ]
    });
    service = TestBed.inject(AiRouteRegistryContextService);
  });

  it('does not expose admin routes to end users', () => {
    roles = ['end-user'];

    const manifest = service.routeManifest();
    expect(manifest.schemaVersion).toBe(1);
    expect(manifest.routes.some(route => route.resource === 'business-context')).toBe(true);
    expect(manifest.routes.some(route => route.resource === 'admin-account')).toBe(false);
  });

  it('includes admin routes for administrators', () => {
    roles = ['admin', 'developer'];

    const manifest = service.routeManifest();

    expect(manifest.routes.some(route => route.resource === 'admin-account')).toBe(true);
    expect(manifest.routes.some(route => route.resource === 'tenant')).toBe(true);
    expect(manifest.routes.some(route => route.resource === 'settings')).toBe(true);
  });

  it('preserves detail variants and declarative query codecs without formatter prose', () => {
    roles = ['end-user'];

    const manifest = service.routeManifest();
    const coreComponent = manifest.routes.find(route => route.resource === 'core-component');
    const businessContext = manifest.routes.find(route => route.resource === 'business-context');

    expect(coreComponent?.detailPatterns).toEqual({
      ACC: '/core_component/acc/{manifestId}',
      ASCCP: '/core_component/asccp/{manifestId}',
      BCCP: '/core_component/bccp/{manifestId}',
      EXTENSION: '/core_component/extension/{manifestId}'
    });
    expect(businessContext?.detailPatterns)
      .toEqual({default: '/context_management/business_context/{id}'});
    expect(businessContext?.listQuery?.codec).toBe('base64-utf8-form');
    expect(businessContext?.listQuery?.allowedPlainParams).toContain('name');
    expect(JSON.stringify(manifest)).not.toContain('formatterRule');
  });
});
