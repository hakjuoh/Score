import {Component, OnInit, inject} from '@angular/core';
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
}
