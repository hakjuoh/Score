import {Component, OnInit, inject} from '@angular/core';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiProviderView} from './domain/ai-admin-policy';

@Component({
  standalone: false,
  selector: 'score-ai-provider-list',
  template: `
    <div class="context-section ai-admin-page">
      <mat-toolbar class="bg-white">
        <a mat-icon-button routerLink="/ai-admin/users"><mat-icon>arrow_back</mat-icon></a>
        <span class="title">AI Provider Catalog</span><span class="flex-11-auto"></span>
        <a mat-flat-button color="primary" routerLink="/ai-admin/providers/new">Add provider</a>
      </mat-toolbar>
      <div class="p-3 container-fluid"><mat-card><mat-card-content>
        @if (loading) { <mat-progress-bar mode="indeterminate"></mat-progress-bar> }
        @else if (loadFailed) { <p role="alert">Providers could not be loaded.</p><button mat-stroked-button (click)="load()">Retry</button> }
        @else {
        <table class="table table-hover align-middle"><thead><tr><th>Name</th><th>Type</th>
          <th>Endpoint</th><th>API key</th><th>Status</th><th>Version</th><th></th></tr></thead>
          <tbody>@for (provider of providers; track provider.aiProviderId) {
            <tr><td>{{provider.providerName}}</td><td>{{provider.providerType}}</td>
              <td>{{provider.baseUrl || provider.messagesUrl || 'Not configured'}}</td>
              <td>{{provider.apiKeyConfigured ? 'Configured' : 'Not configured'}}</td>
              <td><span class="status-pill" [class.disabled]="!provider.enabled">
                {{provider.enabled ? 'Enabled' : 'Disabled'}}</span></td>
              <td>{{provider.catalogVersion}}</td>
              <td><a mat-button color="primary" [routerLink]="['/ai-admin/providers', provider.aiProviderId]">Manage</a></td></tr>
          }</tbody></table> }
      </mat-card-content></mat-card></div>
    </div>`,
  styleUrls: ['./ai-admin-policy.component.css']
})
export class AiProviderListComponent implements OnInit {
  private readonly service = inject(AiAdminPolicyService);
  providers: AiProviderView[] = [];
  loading = true;
  loadFailed = false;
  ngOnInit(): void { this.load(); }
  load(): void {
    this.loading = true;
    this.loadFailed = false;
    this.service.providers().subscribe({
      next: value => { this.providers = value; this.loading = false; },
      error: () => { this.loading = false; this.loadFailed = true; }
    });
  }
}
