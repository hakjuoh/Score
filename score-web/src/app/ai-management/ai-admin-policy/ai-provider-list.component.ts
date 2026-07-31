import {Component, OnInit, inject} from '@angular/core';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiProviderView} from './domain/ai-admin-policy';

@Component({
  standalone: false,
  selector: 'score-ai-provider-list',
  templateUrl: './ai-provider-list.component.html',
  styleUrls: ['./ai-admin-policy.component.css']
})
export class AiProviderListComponent implements OnInit {
  private readonly service = inject(AiAdminPolicyService);

  providers: AiProviderView[] = [];
  filter = '';
  loading = true;
  loadFailed = false;
  columns = this.defaultColumns();

  get filteredProviders(): AiProviderView[] {
    const query = this.filter.trim().toLowerCase();
    return this.providers.filter(provider => !query || [provider.providerName, provider.providerType,
      provider.baseUrl, provider.messagesUrl].some(value => value?.toLowerCase().includes(query)));
  }

  get displayedColumns(): string[] {
    const columnNames = new Map([
      ['Name', 'name'], ['Type', 'type'], ['Endpoint', 'endpoint'], ['API Key', 'apiKey'],
      ['Status', 'status'], ['Version', 'version']
    ]);
    return this.columns.filter(column => column.selected)
      .map(column => columnNames.get(column.name))
      .filter((name): name is string => !!name)
      .concat('actions');
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadFailed = false;
    this.service.providers().subscribe({
      next: value => {
        this.providers = value;
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

  private defaultColumns(): {name: string; selected: boolean}[] {
    return ['Name', 'Type', 'Endpoint', 'API Key', 'Status', 'Version']
      .map(name => ({name, selected: true}));
  }
}
