import {Component, OnInit, ViewChild, inject} from '@angular/core';
import {MatSort} from '@angular/material/sort';
import {MatTableDataSource} from '@angular/material/table';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiAdminModel} from './domain/ai-admin-policy';

@Component({
  standalone: false,
  selector: 'score-ai-model-list',
  templateUrl: './ai-model-list.component.html',
  styleUrls: ['./ai-admin-policy.component.css']
})
export class AiModelListComponent implements OnInit {
  private readonly service = inject(AiAdminPolicyService);

  models: AiAdminModel[] = [];
  filter = '';
  loading = true;
  loadFailed = false;
  columns = this.defaultColumns();
  readonly dataSource = new MatTableDataSource<AiAdminModel>();

  @ViewChild(MatSort) set tableSort(sort: MatSort | undefined) {
    if (sort) this.dataSource.sort = sort;
  }

  constructor() {
    this.dataSource.sortingDataAccessor = (model, column) => {
      switch (column) {
        case 'model': return model.displayName.toLowerCase();
        case 'provider': return model.provider.toLowerCase();
        case 'status': return Number(model.enabled);
        case 'defaultEffort': return this.defaultEffort(model).toLowerCase();
        case 'efforts': return this.effortNames(model).toLowerCase();
        default: return '';
      }
    };
  }

  get filteredModels(): AiAdminModel[] {
    const query = this.filter.trim().toLowerCase();
    return this.models.filter(model => !query || [model.displayName, model.modelKey, model.provider,
      model.providerModelName].some(value => value?.toLowerCase().includes(query)));
  }

  get displayedColumns(): string[] {
    const columnNames = new Map([
      ['Model', 'model'], ['Provider', 'provider'], ['Status', 'status'],
      ['Default Effort', 'defaultEffort'], ['Efforts', 'efforts']
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
    this.service.models().subscribe({
      next: models => {
        this.models = models;
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

  onColumnsChange(columns: {name: string; selected: boolean}[]): void {
    this.columns = [...columns];
  }

  onColumnsReset(): void {
    this.columns = this.defaultColumns();
  }

  effortNames(model: AiAdminModel): string {
    return model.reasoningEfforts.map(effort => effort.displayName).join(', ');
  }

  defaultEffort(model: AiAdminModel): string {
    return model.reasoningEfforts.find(effort => effort.defaultEffort)?.displayName || '—';
  }

  private defaultColumns(): {name: string; selected: boolean}[] {
    return ['Model', 'Provider', 'Status', 'Default Effort', 'Efforts']
      .map(name => ({name, selected: true}));
  }

  private refreshDataSource(): void {
    this.dataSource.data = this.filteredModels;
  }
}
