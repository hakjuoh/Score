import {CanActivateAdmin} from '../../authentication/auth.service';
import {AI_ADMIN_ROUTES} from './ai-admin-policy.module';

describe('AI admin routes', () => {
  it('protects every catalog and policy route with the administrator guard', () => {
    expect(AI_ADMIN_ROUTES).toHaveLength(6);
    for (const route of AI_ADMIN_ROUTES) {
      expect(route.canActivate).toEqual([CanActivateAdmin]);
    }
  });
});
