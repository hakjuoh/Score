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

    const context = service.registryContext();

    expect(context).toContain('- business-context:');
    expect(context).not.toContain('- admin-account:');
    expect(context).not.toContain('- tenant:');
    expect(context).not.toContain('- settings:');
  });

  it('includes admin routes for administrators', () => {
    roles = ['admin', 'developer'];

    const context = service.registryContext();

    expect(context).toContain('- admin-account:');
    expect(context).toContain('- tenant:');
    expect(context).toContain('- settings:');
  });
});
