import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiProviderListComponent} from './ai-provider-list.component';
import {AiModelListComponent} from './ai-model-list.component';
import {AiAdminModel, AiProviderView} from './domain/ai-admin-policy';
import {MatSort} from '@angular/material/sort';

describe('AI catalog list retries', () => {
  it('retries a failed provider catalog load', () => {
    let attempts = 0;
    TestBed.configureTestingModule({providers: [{provide: AiAdminPolicyService, useValue: {providers: () => ++attempts === 1 ? throwError(() => new Error()) : of([])}}]});
    const component = TestBed.runInInjectionContext(() => new AiProviderListComponent()); component.ngOnInit();
    expect(component.loadFailed).toBe(true); component.load(); expect(component.loadFailed).toBe(false); expect(attempts).toBe(2);
  });

  it('retries a failed model catalog load', () => {
    let attempts = 0;
    TestBed.configureTestingModule({providers: [{provide: AiAdminPolicyService, useValue: {models: () => ++attempts === 1 ? throwError(() => new Error()) : of([])}}]});
    const component = TestBed.runInInjectionContext(() => new AiModelListComponent()); component.ngOnInit();
    expect(component.loadFailed).toBe(true); component.load(); expect(component.loadFailed).toBe(false); expect(attempts).toBe(2);
  });

  it('searches providers and applies selected columns', () => {
    const providers: AiProviderView[] = [
      {aiProviderId: 1, providerName: 'OpenAI', providerType: 'OPENAI', baseUrl: null,
        messagesUrl: null, anthropicVersion: null, apiVersion: null, enabled: true,
        apiKeyConfigured: true, catalogVersion: 1},
      {aiProviderId: 2, providerName: 'Anthropic', providerType: 'ANTHROPIC', baseUrl: null,
        messagesUrl: 'https://example.test/messages', anthropicVersion: null, apiVersion: null,
        enabled: true, apiKeyConfigured: true, catalogVersion: 1}
    ];
    TestBed.configureTestingModule({providers: [{provide: AiAdminPolicyService,
      useValue: {providers: () => of(providers)}}]});
    const component = TestBed.runInInjectionContext(() => new AiProviderListComponent());
    component.ngOnInit();
    component.onFilterChange('anthropic');
    expect(component.filteredProviders.map(provider => provider.providerName)).toEqual(['Anthropic']);
    expect(component.dataSource.data.map(provider => provider.providerName)).toEqual(['Anthropic']);
    const sorted = component.dataSource.sortData(providers,
      {active: 'name', direction: 'asc'} as MatSort);
    expect(sorted.map(provider => provider.providerName)).toEqual(['Anthropic', 'OpenAI']);
    expect(component.providerTypeLabel('azure-openai')).toBe('OpenAI');
    component.onColumnsChange(component.columns.map(column => ({...column, selected: column.name !== 'Version'})));
    expect(component.displayedColumns).not.toContain('version');
  });

  it('searches models and resets selected columns', () => {
    const model = {
      displayName: 'GPT Test', modelKey: 'gpt-test', provider: 'OpenAI', providerModelName: 'gpt-test',
      reasoningEfforts: []
    } as AiAdminModel;
    const otherModel = {...model, displayName: 'Claude Test', modelKey: 'claude-test',
      provider: 'Anthropic'};
    TestBed.configureTestingModule({providers: [{provide: AiAdminPolicyService,
      useValue: {models: () => of([model, otherModel])}}]});
    const component = TestBed.runInInjectionContext(() => new AiModelListComponent());
    component.ngOnInit();
    component.onFilterChange('openai');
    expect(component.filteredModels).toEqual([model]);
    expect(component.dataSource.data).toEqual([model]);
    const sorted = component.dataSource.sortData([model, otherModel],
      {active: 'model', direction: 'asc'} as MatSort);
    expect(sorted.map(item => item.displayName)).toEqual(['Claude Test', 'GPT Test']);
    component.onColumnsChange(component.columns.map(column => ({...column, selected: false})));
    expect(component.displayedColumns).toEqual([]);
    component.onColumnsReset();
    expect(component.displayedColumns.length).toBe(5);
  });
});
