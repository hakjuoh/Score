import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {AuthService} from '../../authentication/auth.service';
import {PreferencesInfo} from '../../settings-management/settings-preferences/domain/preferences';
import {SettingsPreferencesService} from '../../settings-management/settings-preferences/domain/settings-preferences.service';
import {AiPolicyUserListComponent} from './ai-policy-user-list.component';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiPolicyUserSummary} from './domain/ai-admin-policy';
import {AccountListService} from '../../account-management/domain/account-list.service';
import {AiAdminListNavigationService} from './domain/ai-admin-list-navigation.service';
import {AiAdminPolicyModule} from './ai-admin-policy.module';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {provideRouter} from '@angular/router';

describe('AiPolicyUserListComponent', () => {
  const response = <T>(list: T[]) => ({list, page: 0, size: 10, length: list.length});
  const user = (id: string): AiPolicyUserSummary => ({userId: id, loginId: id, name: id,
    organization: 'Org', inherited: true, enabled: true, multiAgentEnabled: true,
    allowedModelCount: 2, quotaLimitTokens: 100, quotaConsumedTokens: 80,
    quotaReservedTokens: 0, quotaRemainingTokens: 20, activeRequests: 0,
    updaterLoginId: null, lastUpdatedAt: null});
  const configure = (service: object): AiPolicyUserListComponent => {
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: SettingsPreferencesService, useValue: {
        load: () => of(new PreferencesInfo()),
        updateTableColumnsForAiPolicyPage: () => of(undefined)
      }},
      {provide: AccountListService, useValue: {getAccountNames: () => of(['admin'])}},
      {provide: AiAdminListNavigationService, useValue: {
        queryParamMap: {get: () => null}, restoreAdvancedSearch: vi.fn(), replaceState: vi.fn()
      }},
      {provide: AuthService, useValue: {getUserToken: () => ({})}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiPolicyUserListComponent());
    component.sort = {active: 'loginId', direction: 'asc', sort: vi.fn(),
      sortChange: {subscribe: vi.fn()}} as never;
    component.paginator = {pageIndex: 0, pageSize: 10, length: 0} as never;
    component.ngOnInit();
    return component;
  };

  const render = async (searchUsers: () => ReturnType<typeof of> | ReturnType<typeof throwError>) => {
    await TestBed.configureTestingModule({
      imports: [AiAdminPolicyModule, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        {provide: AiAdminPolicyService, useValue: {searchUsers}},
        {provide: SettingsPreferencesService, useValue: {
          load: () => of(new PreferencesInfo()),
          updateTableColumnsForAiPolicyPage: () => of(undefined)
        }},
        {provide: AccountListService, useValue: {getAccountNames: () => of(['admin'])}},
        {provide: AiAdminListNavigationService, useValue: {
          queryParamMap: {get: () => null}, restoreAdvancedSearch: vi.fn(), replaceState: vi.fn()
        }},
        {provide: AuthService, useValue: {getUserToken: () => ({})}}
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(AiPolicyUserListComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  it('sends all detailed filters to the paginated search', () => {
    const searchUsers = vi.fn(request => of(response([user('alice')])));
    const component = configure({searchUsers});
    component.request.filters.name = 'Alice';
    component.request.filters.organization = 'Org';
    component.request.filters.enabled = [true];
    component.request.filters.multiAgentEnabled = [true];
    component.request.filters.quota = 'NEAR';
    component.onSearch();
    expect(searchUsers).toHaveBeenLastCalledWith(component.request);
    expect(component.dataSource.data.map(item => item.userId)).toEqual(['alice']);
    expect(component.paginator.length).toBe(1);
  });

  it('allows the user to load again after a failed request', () => {
    let attempts = 0;
    const component = configure({searchUsers: () => ++attempts === 1
      ? throwError(() => new Error('offline')) : of(response([]))});
    component.load();
    expect(attempts).toBe(2);
  });

  it('renders no inline alert or retry control when loading fails', async () => {
    const element = await render(() => throwError(() => new Error('offline')));

    expect(element.querySelector('[role="alert"]')).toBeNull();
    expect(element.textContent).not.toContain('Retry');
    expect(element.textContent).not.toContain('Users could not be loaded.');
  });

  it('renders an em dash instead of Inherited when no policy update exists', async () => {
    const element = await render(() => of(response([user('alice')])));

    expect(element.textContent).toContain('—');
    expect(element.textContent).not.toContain('Inherited');
  });

  it('updates and resets visible table columns', () => {
    const component = configure({searchUsers: () => of(response([]))});
    component.onColumnsChange(component.columns.map(column => ({...column,
      selected: column.name !== 'Organization'})));
    expect(component.displayedColumns).not.toContain('organization');
    component.onColumnsReset();
    expect(component.displayedColumns).toContain('organization');
  });

  it('rejects a reversed updated-on date range', () => {
    const searchUsers = vi.fn(() => of(response([])));
    const component = configure({searchUsers});
    component.request.filters.updatedAfter = new Date(2026, 6, 31);
    component.request.filters.updatedBefore = new Date(2026, 6, 30);
    component.onSearch();
    component.load();
    component.onPageChange({} as never);
    expect(component.invalidDateRange).toBe(true);
    expect(searchUsers).toHaveBeenCalledTimes(1);
  });
});
