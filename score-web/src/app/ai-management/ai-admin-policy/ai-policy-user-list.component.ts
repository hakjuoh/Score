import {Component, OnInit, ViewChild, inject} from '@angular/core';
import {MatSort} from '@angular/material/sort';
import {MatTableDataSource} from '@angular/material/table';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiPolicyUserSummary} from './domain/ai-admin-policy';

@Component({
  standalone: false,
  selector: 'score-ai-policy-user-list',
  templateUrl: './ai-policy-user-list.component.html',
  styleUrls: ['./ai-admin-policy.component.css']
})
export class AiPolicyUserListComponent implements OnInit {
  private readonly policies = inject(AiAdminPolicyService);

  users: AiPolicyUserSummary[] = [];
  filter = '';
  loading = true;
  loadFailed = false;
  accessFilter = 'ALL';
  quotaFilter = 'ALL';
  multiAgentFilter = 'ALL';
  columns = this.defaultColumns();
  readonly dataSource = new MatTableDataSource<AiPolicyUserSummary>();

  @ViewChild(MatSort) set tableSort(sort: MatSort | undefined) {
    if (sort) this.dataSource.sort = sort;
  }

  constructor() {
    this.dataSource.sortingDataAccessor = (user, column) => {
      switch (column) {
        case 'loginId': return user.loginId.toLowerCase();
        case 'name': return user.name.toLowerCase();
        case 'organization': return user.organization.toLowerCase();
        case 'access': return Number(user.enabled);
        case 'models': return user.allowedModelCount;
        case 'multiAgent': return Number(user.multiAgentEnabled);
        case 'quota': return user.quotaLimitTokens == null
          ? Number.MAX_SAFE_INTEGER : user.quotaConsumedTokens + user.quotaReservedTokens;
        case 'active': return user.activeRequests;
        case 'lastPolicyChange': return user.lastPolicyChange
          ? Date.parse(user.lastPolicyChange) : 0;
        default: return '';
      }
    };
  }

  get displayedColumns(): string[] {
    const columnNames = new Map([
      ['Login ID', 'loginId'], ['Name', 'name'], ['Organization', 'organization'],
      ['AI Access', 'access'], ['Models', 'models'], ['Multi-agent', 'multiAgent'],
      ['Quota', 'quota'], ['Active', 'active'], ['Last Policy Change', 'lastPolicyChange']
    ]);
    return this.columns.filter(column => column.selected)
      .map(column => columnNames.get(column.name))
      .filter((name): name is string => !!name);
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadFailed = false;
    this.policies.users().subscribe({
      next: users => {
        this.users = users;
        this.refreshDataSource();
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.loadFailed = true;
      }
    });
  }

  onSearch(): void {
    this.filter = this.filter.trim();
    this.refreshDataSource();
  }

  onFilterChange(filter: string): void {
    this.filter = filter;
    this.refreshDataSource();
  }

  applyFilters(): void {
    this.refreshDataSource();
  }

  onColumnsChange(columns: {name: string; selected: boolean}[]): void {
    this.columns = [...columns];
  }

  onColumnsReset(): void {
    this.columns = this.defaultColumns();
  }

  get filteredUsers(): AiPolicyUserSummary[] {
    const query = this.filter.trim().toLowerCase();
    return this.users.filter(user => (!query || [user.loginId, user.name, user.organization]
      .some(value => value?.toLowerCase().includes(query)))
      && (this.accessFilter === 'ALL' || user.enabled === (this.accessFilter === 'ENABLED'))
      && (this.multiAgentFilter === 'ALL' || user.multiAgentEnabled === (this.multiAgentFilter === 'ENABLED'))
      && (this.quotaFilter === 'ALL' || this.matchesQuota(user)));
  }

  private matchesQuota(user: AiPolicyUserSummary): boolean {
    if (user.quotaLimitTokens == null) return false;
    const used = user.quotaConsumedTokens + user.quotaReservedTokens;
    if (this.quotaFilter === 'EXHAUSTED') return used >= user.quotaLimitTokens;
    return used >= user.quotaLimitTokens * 0.8 && used < user.quotaLimitTokens;
  }

  private defaultColumns(): {name: string; selected: boolean}[] {
    return ['Login ID', 'Name', 'Organization', 'AI Access', 'Models', 'Multi-agent',
      'Quota', 'Active', 'Last Policy Change'].map(name => ({name, selected: true}));
  }

  private refreshDataSource(): void {
    this.dataSource.data = this.filteredUsers;
  }
}
