import {Component, OnInit, inject} from '@angular/core';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiAdminModel} from './domain/ai-admin-policy';

@Component({
  standalone: false,
  selector: 'score-ai-model-list',
  template: `
    <div class="context-section ai-admin-page">
      <mat-toolbar class="bg-white">
        <a mat-icon-button routerLink="/ai-admin/users"><mat-icon>arrow_back</mat-icon></a>
        <span class="title">AI Model Catalog</span>
      </mat-toolbar>
      <div class="p-3 container-fluid">
        <mat-card><mat-card-content>
          <div class="actions"><a mat-flat-button color="primary" routerLink="/ai-admin/models/new">Add model</a></div>
          @if (loading) { <mat-progress-bar mode="indeterminate"></mat-progress-bar> }
          @else if (loadFailed) { <p role="alert">Models could not be loaded.</p><button mat-stroked-button (click)="load()">Retry</button> }
          @else {
          <table class="table align-middle"><thead><tr><th>Model</th><th>Provider</th>
            <th>Status</th><th>Default effort</th><th>Efforts</th></tr></thead><tbody>
            @for (model of models; track model.modelKey) {
              <tr><td><a [routerLink]="['/ai-admin/models', model.aiModelId]">{{ model.displayName }}</a><small class="d-block">{{ model.modelKey }}</small></td>
                <td>{{ model.provider }}</td><td>{{model.enabled ? 'Enabled' : 'Disabled'}}{{model.defaultModel ? ' · Default' : ''}}</td><td>{{ defaultEffort(model) }}</td>
                <td>{{ effortNames(model) }}</td></tr>
            }
          </tbody></table> }
        </mat-card-content></mat-card>
      </div>
    </div>`,
  styleUrls: ['./ai-admin-policy.component.css']
})
export class AiModelListComponent implements OnInit {
  private readonly service = inject(AiAdminPolicyService);
  models: AiAdminModel[] = [];
  loading = true;
  loadFailed = false;
  ngOnInit(): void { this.load(); }
  load(): void {
    this.loading = true;
    this.loadFailed = false;
    this.service.models().subscribe({
      next: models => { this.models = models; this.loading = false; },
      error: () => { this.loading = false; this.loadFailed = true; }
    });
  }
  effortNames(model: AiAdminModel): string {
    return model.reasoningEfforts.map(effort => effort.displayName).join(', ');
  }
  defaultEffort(model: AiAdminModel): string {
    return model.reasoningEfforts.find(effort => effort.defaultEffort)?.displayName || '—';
  }
}
