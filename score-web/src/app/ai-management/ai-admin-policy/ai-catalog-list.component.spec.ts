import {TestBed} from '@angular/core/testing';
import {of, Subject, throwError} from 'rxjs';
import {AuthService} from '../../authentication/auth.service';
import {PreferencesInfo} from '../../settings-management/settings-preferences/domain/preferences';
import {SettingsPreferencesService} from '../../settings-management/settings-preferences/domain/settings-preferences.service';
import {AiModelListComponent} from './ai-model-list.component';
import {AiProviderListComponent} from './ai-provider-list.component';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiAdminModel, AiProviderView} from './domain/ai-admin-policy';
import {AccountListService} from '../../account-management/domain/account-list.service';
import {AiAdminListNavigationService} from './domain/ai-admin-list-navigation.service';

describe('AI catalog list pagination and retries', () => {
  const response = <T>(list: T[]) => ({list, page: 0, size: 10, length: list.length});
  const dependencies = (service: object) => [
    {provide: AiAdminPolicyService, useValue: service},
    {provide: SettingsPreferencesService, useValue: {
      load: () => of(new PreferencesInfo()),
      updateTableColumnsForAiProviderPage: () => of(undefined),
      updateTableColumnsForAiModelPage: () => of(undefined)
    }},
    {provide: AccountListService, useValue: {getAccountNames: () => of(['admin'])}},
    {provide: AiAdminListNavigationService, useValue: {
      queryParamMap: {get: () => null}, restoreAdvancedSearch: vi.fn(), replaceState: vi.fn()
    }},
    {provide: AuthService, useValue: {getUserToken: () => ({})}}
  ];
  const initialize = <T extends AiProviderListComponent | AiModelListComponent>(component: T,
      active: string): T => {
    component.sort = {active, direction: 'asc', sort: vi.fn(),
      sortChange: {subscribe: vi.fn()}} as never;
    component.paginator = {pageIndex: 0, pageSize: 10, length: 0} as never;
    component.ngOnInit();
    return component;
  };

  it('retries a failed provider catalog page', () => {
    let attempts = 0;
    const service = {searchProviders: () => ++attempts === 1
      ? throwError(() => new Error()) : of(response([]))};
    TestBed.configureTestingModule({providers: dependencies(service)});
    const component = initialize(TestBed.runInInjectionContext(
      () => new AiProviderListComponent()), 'name');
    expect(component.loadFailed).toBe(true);
    component.load();
    expect(component.loadFailed).toBe(false);
    expect(attempts).toBe(2);
  });

  it('retries a failed model catalog page', () => {
    let attempts = 0;
    const service = {searchModels: () => ++attempts === 1
      ? throwError(() => new Error()) : of(response([]))};
    TestBed.configureTestingModule({providers: dependencies(service)});
    const component = initialize(TestBed.runInInjectionContext(
      () => new AiModelListComponent()), 'model');
    expect(component.loadFailed).toBe(true);
    component.load();
    expect(component.loadFailed).toBe(false);
    expect(attempts).toBe(2);
  });

  it('ignores an older provider page and keeps loading until the newest request completes', () => {
    const requests = [new Subject<{list: AiProviderView[]; page: number; size: number;
      length: number}>(), new Subject<{list: AiProviderView[]; page: number; size: number;
      length: number}>()];
    let index = 0;
    TestBed.configureTestingModule({providers: dependencies({
      searchProviders: () => requests[index++].asObservable()
    })});
    const component = initialize(TestBed.runInInjectionContext(
      () => new AiProviderListComponent()), 'name');
    component.load();
    requests[0].next(response([{aiProviderId: 1, providerName: 'Old', providerType: 'openai',
      baseUrl: null, messagesUrl: null, apiVersion: null, enabled: true,
      apiKeyConfigured: false, updaterLoginId: 'admin', lastUpdatedAt: null}]));
    requests[0].complete();
    expect(component.loading).toBe(true);
    expect(component.dataSource.data).toEqual([]);
    requests[1].next(response([{aiProviderId: 2, providerName: 'New', providerType: 'anthropic',
      baseUrl: null, messagesUrl: null, apiVersion: null, enabled: true,
      apiKeyConfigured: false, updaterLoginId: 'admin', lastUpdatedAt: null}]));
    requests[1].complete();
    expect(component.loading).toBe(false);
    expect(component.dataSource.data[0].providerName).toBe('New');
  });

  it('passes detailed provider filters and applies selected columns', () => {
    const provider: AiProviderView = {aiProviderId: 2, providerName: 'Anthropic',
      providerType: 'anthropic', baseUrl: null,
      messagesUrl: 'https://example.test/messages', apiVersion: '2023-06-01',
      enabled: true, apiKeyConfigured: true, updaterLoginId: 'admin', lastUpdatedAt: null};
    const searchProviders = vi.fn(request => of(response([provider])));
    TestBed.configureTestingModule({providers: dependencies({searchProviders})});
    const component = initialize(TestBed.runInInjectionContext(
      () => new AiProviderListComponent()), 'name');
    component.request.filters.name = 'anthropic';
    component.request.filters.endpoint = 'example.test';
    component.onSearch();
    expect(searchProviders).toHaveBeenLastCalledWith(component.request);
    expect(component.dataSource.data).toEqual([provider]);
    expect(component.paginator.length).toBe(1);
    component.onColumnsChange(component.columns.map(column => ({...column,
      selected: column.name !== 'Updated On'})));
    expect(component.displayedColumns).not.toContain('updatedOn');
  });

  it('passes detailed model filters and resets selected columns', () => {
    const model = {displayName: 'GPT Test', modelKey: 'gpt-test', provider: 'OpenAI',
      providerModelName: 'gpt-test', reasoningEfforts: []} as AiAdminModel;
    const searchModels = vi.fn(request => of(response([model])));
    TestBed.configureTestingModule({providers: dependencies({searchModels})});
    const component = initialize(TestBed.runInInjectionContext(
      () => new AiModelListComponent()), 'model');
    component.request.filters.provider = 'openai';
    component.request.filters.enabled = [true];
    component.onSearch();
    expect(searchModels).toHaveBeenLastCalledWith(component.request);
    expect(component.dataSource.data).toEqual([model]);
    component.onColumnsChange(component.columns.map(column => ({...column, selected: false})));
    expect(component.displayedColumns).toEqual([]);
    component.onColumnsReset();
    expect(component.displayedColumns.length).toBe(6);
  });
});
