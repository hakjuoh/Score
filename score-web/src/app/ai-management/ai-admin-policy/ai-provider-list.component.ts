import {Component, OnInit, QueryList, ViewChild, ViewChildren, inject} from '@angular/core';
import {MatPaginator, PageEvent} from '@angular/material/paginator';
import {MatSort, SortDirection} from '@angular/material/sort';
import {MatTableDataSource} from '@angular/material/table';
import {finalize} from 'rxjs/operators';
import {catchError} from 'rxjs/operators';
import {forkJoin, of} from 'rxjs';
import {PageRequest} from '../../basis/basis';
import {AuthService} from '../../authentication/auth.service';
import {ScoreTableColumnResizeDirective} from '../../common/score-table-column-resize/score-table-column-resize.directive';
import {PreferencesInfo, TableColumnsInfo, TableColumnsProperty} from '../../settings-management/settings-preferences/domain/preferences';
import {SettingsPreferencesService} from '../../settings-management/settings-preferences/domain/settings-preferences.service';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiProviderListRequest, AiProviderView} from './domain/ai-admin-policy';
import {AccountListService} from '../../account-management/domain/account-list.service';
import {SearchBarComponent} from '../../common/search-bar/search-bar.component';
import {AiAdminListNavigationService} from './domain/ai-admin-list-navigation.service';

@Component({
  standalone: false,
  selector: 'score-ai-provider-list',
  templateUrl: './ai-provider-list.component.html',
  styleUrls: ['./ai-admin-policy.component.css']
})
export class AiProviderListComponent implements OnInit {
  private readonly service = inject(AiAdminPolicyService);
  private readonly preferencesService = inject(SettingsPreferencesService);
  private readonly auth = inject(AuthService);
  private readonly accountService = inject(AccountListService);
  private readonly navigation = inject(AiAdminListNavigationService);

  request = new AiProviderListRequest(this.navigation.queryParamMap);
  loading = false;
  loadFailed = false;
  private loadSequence = 0;
  preferencesInfo: PreferencesInfo;
  loginIdList: string[] = [];
  readonly dataSource = new MatTableDataSource<AiProviderView>();

  @ViewChild(MatSort, {static: true}) sort: MatSort;
  @ViewChild(MatPaginator, {static: true}) paginator: MatPaginator;
  @ViewChild(SearchBarComponent, {static: true}) searchBar: SearchBarComponent;
  @ViewChildren(ScoreTableColumnResizeDirective)
  tableColumnResizeDirectives: QueryList<ScoreTableColumnResizeDirective>;

  get columns(): TableColumnsProperty[] {
    return this.preferencesInfo?.tableColumnsInfo.columnsOfAiProviderPage || [];
  }

  set columns(columns: TableColumnsProperty[]) {
    if (!this.preferencesInfo) return;
    this.preferencesInfo.tableColumnsInfo.columnsOfAiProviderPage = columns;
    this.preferencesService.updateTableColumnsForAiProviderPage(
      this.auth.getUserToken(), this.preferencesInfo).subscribe();
  }

  get displayedColumns(): string[] {
    const names = new Map([
      ['Name', 'name'], ['Type', 'type'], ['Endpoint', 'endpoint'],
      ['Status', 'status'], ['Updated On', 'updatedOn']
    ]);
    return this.columns.filter(column => column.selected)
      .map(column => names.get(column.name)).filter((name): name is string => !!name);
  }

  ngOnInit(): void {
    this.navigation.restoreAdvancedSearch(this.searchBar);
    this.paginator.pageIndex = this.request.page.pageIndex;
    this.paginator.pageSize = this.request.page.pageSize;
    this.sort.active = this.request.page.sortActive;
    this.sort.direction = this.request.page.sortDirection as SortDirection;
    const originalSort = this.sort.sort;
    this.sort.sort = sortChange => {
      if (this.tableColumnResizeDirectives?.some(directive => directive.resizing)) return;
      originalSort.apply(this.sort, [sortChange]);
    };
    this.sort.sortChange.subscribe(() => this.onSearch());
    forkJoin([
      this.accountService.getAccountNames().pipe(catchError(() => of([]))),
      this.preferencesService.load(this.auth.getUserToken())
        .pipe(catchError(() => of(new PreferencesInfo())))
    ]).subscribe(([loginIds, preferences]) => {
      this.loginIdList = loginIds;
      this.preferencesInfo = preferences;
      this.load();
    });
  }

  load(): void {
    if (this.invalidDateRange) return;
    const sequence = ++this.loadSequence;
    this.loading = true;
    this.loadFailed = false;
    this.request.page = new PageRequest(this.sort.active, this.sort.direction,
      this.paginator.pageIndex, this.paginator.pageSize);
    this.service.searchProviders(this.request).pipe(finalize(() => {
      if (sequence === this.loadSequence) this.loading = false;
    }))
      .subscribe({next: response => {
        if (sequence !== this.loadSequence) return;
        this.dataSource.data = response.list;
        this.paginator.length = response.length;
        this.navigation.replaceState(this.request, this.searchBar);
      }, error: () => {
        if (sequence !== this.loadSequence) return;
        this.dataSource.data = [];
        this.paginator.length = 0;
        this.loadFailed = true;
      }});
  }

  onSearch(): void {
    this.paginator.pageIndex = 0;
    this.load();
  }

  onPageChange(_: PageEvent): void {
    this.load();
  }

  onColumnsChange(updated: {name: string; selected: boolean}[]): void {
    this.columns = updated.map(column => ({...column, width: this.width(column.name)}));
  }

  onColumnsReset(): void {
    this.columns = new TableColumnsInfo().columnsOfAiProviderPage;
  }

  onResizeWidth(event: {name: string; width: number | string}): void {
    const name = event.name === 'Updated on' ? 'Updated On' : event.name;
    const column = this.columns.find(value => value.name === name);
    if (!column) return;
    column.width = event.width;
    this.columns = [...this.columns];
  }

  width(name: string): number | string {
    return this.columns.find(column => column.name === name)?.width || 0;
  }

  providerTypeLabel(providerType: string): string {
    return providerType.toLowerCase() === 'anthropic' ? 'Anthropic' : 'OpenAI';
  }

  get invalidDateRange(): boolean {
    const after = this.request.filters.updatedAfter;
    const before = this.request.filters.updatedBefore;
    return !!after && !!before && after.getTime() > before.getTime();
  }
}
