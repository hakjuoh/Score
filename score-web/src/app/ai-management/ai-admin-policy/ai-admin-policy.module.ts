import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';
import {RouterModule, Routes} from '@angular/router';
import {MaterialModule} from '../../material.module';
import {CanActivateAdmin} from '../../authentication/auth.service';
import {AiPolicyUserListComponent} from './ai-policy-user-list.component';
import {AiPolicyUserDetailComponent} from './ai-policy-user-detail.component';
import {AiModelListComponent} from './ai-model-list.component';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiProviderListComponent} from './ai-provider-list.component';
import {AiProviderDetailComponent} from './ai-provider-detail.component';
import {AiModelDetailComponent} from './ai-model-detail.component';
import {SearchBarModule} from '../../common/search-bar/search-bar.module';
import {ColumnSelectorModule} from '../../common/column-selector/column-selector.module';
import {JsonOptionEditorComponent} from './json-option-editor.component';
import {FontAwesomeModule} from '@fortawesome/angular-fontawesome';

export const AI_ADMIN_ROUTES: Routes = [
  {path: 'ai-admin/users', component: AiPolicyUserListComponent,
    canActivate: [CanActivateAdmin], title: 'AI Policy Administration'},
  {path: 'ai-admin/users/:id', component: AiPolicyUserDetailComponent,
    canActivate: [CanActivateAdmin], title: 'Manage AI Policy'},
  {path: 'ai-admin/models', component: AiModelListComponent,
    canActivate: [CanActivateAdmin], title: 'AI Model Catalog'},
  {path: 'ai-admin/models/:id', component: AiModelDetailComponent,
    canActivate: [CanActivateAdmin], title: 'Manage AI Model'},
  {path: 'ai-admin/providers', component: AiProviderListComponent,
    canActivate: [CanActivateAdmin], title: 'AI Provider Catalog'},
  {path: 'ai-admin/providers/:id', component: AiProviderDetailComponent,
    canActivate: [CanActivateAdmin], title: 'Manage AI Provider'}
];

@NgModule({
  imports: [CommonModule, FormsModule, MaterialModule, SearchBarModule, ColumnSelectorModule,
    FontAwesomeModule, RouterModule.forChild(AI_ADMIN_ROUTES)],
  declarations: [AiPolicyUserListComponent, AiPolicyUserDetailComponent, AiModelListComponent,
    AiProviderListComponent, AiProviderDetailComponent, AiModelDetailComponent,
    JsonOptionEditorComponent],
  providers: [AiAdminPolicyService]
})
export class AiAdminPolicyModule {}
