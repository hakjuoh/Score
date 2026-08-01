import {HttpClient} from '@angular/common/http';
import {TestBed} from '@angular/core/testing';
import {UserToken} from '../../../authentication/domain/auth';
import {PreferencesInfo} from './preferences';
import {SettingsPreferencesService} from './settings-preferences.service';

describe('SettingsPreferencesService AI column compatibility', () => {
  let token: UserToken;
  let service: SettingsPreferencesService;

  beforeEach(() => {
    localStorage.clear();
    token = new UserToken();
    token.username = 'column-test-user';
    TestBed.configureTestingModule({providers: [SettingsPreferencesService,
      {provide: HttpClient, useValue: {}}]});
    service = TestBed.inject(SettingsPreferencesService);
  });

  it('falls back when a same-length provider preference still contains API Key', () => {
    const preferences = new PreferencesInfo();
    const staleColumns = preferences.tableColumnsInfo.columnsOfAiProviderPage.map(column =>
      column.name === 'Updated On' ? {...column, name: 'API Key'} : column);
    localStorage.setItem(
      `X-Score-${service.TABLE_COLUMNS_FOR_AI_PROVIDER_PAGE_KEY}[${token.username}]`,
      btoa(JSON.stringify({value: JSON.stringify(staleColumns)})));

    service.loadColumnsInfo(preferences, token,
      service.TABLE_COLUMNS_FOR_AI_PROVIDER_PAGE_KEY, 'columnsOfAiProviderPage');

    expect(preferences.tableColumnsInfo.columnsOfAiProviderPage.map(column => column.name))
      .toEqual(['Name', 'Type', 'Endpoint', 'Status', 'Updated On']);
  });

  it('falls back when a same-length policy preference still contains Last Policy Change', () => {
    const preferences = new PreferencesInfo();
    const staleColumns = preferences.tableColumnsInfo.columnsOfAiPolicyPage.map(column =>
      column.name === 'Updated On' ? {...column, name: 'Last Policy Change'} : column);
    localStorage.setItem(
      `X-Score-${service.TABLE_COLUMNS_FOR_AI_POLICY_PAGE_KEY}[${token.username}]`,
      btoa(JSON.stringify({value: JSON.stringify(staleColumns)})));

    service.loadColumnsInfo(preferences, token,
      service.TABLE_COLUMNS_FOR_AI_POLICY_PAGE_KEY, 'columnsOfAiPolicyPage');

    expect(preferences.tableColumnsInfo.columnsOfAiPolicyPage.map(column => column.name))
      .toContain('Updated On');
    expect(preferences.tableColumnsInfo.columnsOfAiPolicyPage.map(column => column.name))
      .not.toContain('Last Policy Change');
  });
});
