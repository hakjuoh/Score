import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {AiPolicyUserListComponent} from './ai-policy-user-list.component';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiPolicyUserSummary} from './domain/ai-admin-policy';

describe('AiPolicyUserListComponent', () => {
  const user = (id: string, used: number, limit: number, overrides: Partial<AiPolicyUserSummary> = {}): AiPolicyUserSummary => ({
    userId: id, loginId: id, name: id, organization: 'Org', inherited: true,
    enabled: true, multiAgentEnabled: true, allowedModelCount: 2,
    quotaLimitTokens: limit, quotaConsumedTokens: used, quotaReservedTokens: 0,
    quotaRemainingTokens: limit - used, activeRequests: 0, lastPolicyChange: null, ...overrides
  });

  it('keeps near-limit and exhausted quota filters mutually exclusive', () => {
    TestBed.configureTestingModule({providers: [{provide: AiAdminPolicyService, useValue: {users: () => of([
      user('near', 80, 100), user('exhausted', 100, 100), user('low', 79, 100)
    ])}}]});
    const component = TestBed.runInInjectionContext(() => new AiPolicyUserListComponent());
    component.ngOnInit();
    component.quotaFilter = 'NEAR';
    expect(component.filteredUsers.map(item => item.userId)).toEqual(['near']);
    component.quotaFilter = 'EXHAUSTED';
    expect(component.filteredUsers.map(item => item.userId)).toEqual(['exhausted']);
  });

  it('exposes an error state and retries loading', () => {
    let attempts = 0;
    TestBed.configureTestingModule({providers: [{provide: AiAdminPolicyService, useValue: {
      users: () => ++attempts === 1 ? throwError(() => new Error('offline')) : of([])
    }}]});
    const component = TestBed.runInInjectionContext(() => new AiPolicyUserListComponent());
    component.ngOnInit();
    expect(component.loadFailed).toBe(true);
    component.load();
    expect(component.loadFailed).toBe(false);
    expect(attempts).toBe(2);
  });

  it('combines search, access, and multi-agent filters', () => {
    const users = [user('alice', 0, 100), user('bob', 0, 100, {enabled: false, inherited: false, multiAgentEnabled: false})];
    TestBed.configureTestingModule({providers: [{provide: AiAdminPolicyService, useValue: {users: () => of(users)}}]});
    const component = TestBed.runInInjectionContext(() => new AiPolicyUserListComponent());
    component.ngOnInit(); component.filter = 'bob'; component.accessFilter = 'DISABLED';
    component.multiAgentFilter = 'DISABLED';
    expect(component.filteredUsers.map(item => item.userId)).toEqual(['bob']);
  });

  it('updates and resets visible table columns', () => {
    TestBed.configureTestingModule({providers: [{provide: AiAdminPolicyService, useValue: {users: () => of([])}}]});
    const component = TestBed.runInInjectionContext(() => new AiPolicyUserListComponent());
    component.onColumnsChange(component.columns.map(column => ({
      ...column, selected: column.name !== 'Organization'
    })));
    expect(component.displayedColumns).not.toContain('organization');
    component.onColumnsReset();
    expect(component.displayedColumns).toContain('organization');
  });
});
