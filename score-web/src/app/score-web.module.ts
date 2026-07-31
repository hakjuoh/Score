import {NgModule} from '@angular/core';
import {ActivatedRouteSnapshot, BaseRouteReuseStrategy, RouteReuseStrategy, RouterModule, TitleStrategy} from '@angular/router';
import {BrowserModule} from '@angular/platform-browser';
import {BrowserAnimationsModule} from '@angular/platform-browser/animations';
import {MatIconRegistry} from '@angular/material/icon';
import {HTTP_INTERCEPTORS, HttpClient, HttpClientModule} from '@angular/common/http';
import {TranslatePipe, provideTranslateService} from '@ngx-translate/core';
import {provideTranslateHttpLoader} from '@ngx-translate/http-loader';
import {FormsModule, ReactiveFormsModule} from '@angular/forms';
import {MarkdownModule, MARKED_OPTIONS} from 'ngx-markdown';
import {FontAwesomeModule} from '@fortawesome/angular-fontawesome';
import {RxStompService, rxStompServiceFactory} from './common/score-rx-stomp';

import {AgencyIdListModule} from './agency-id-list-management/agency-id-list.module';
import {AuthService, ErrorAlertInterceptor, XhrInterceptor} from './authentication/auth.service';
import {LogManagementModule} from './log-management/log-management.module';

import {ScoreWebComponent} from './score-web.component';

import {BasisModule} from './basis/basis.module';
import {AccountManagementModule} from './account-management/account-management.module';
import {ContextManagementModule} from './context-management/context-management.module';
import {CodeListModule} from './code-list-management/code-list.module';
import {BieManagementModule} from './bie-management/bie-management.module';
import {CcManagementModule} from './cc-management/cc-management.module';
import {NamespaceManagementModule} from './namespace-management/namespace-management.module';
import {ReleaseManagementModule} from './release-management/release-management.module';
import {ModuleManagementModule} from './module-management/module-management.module';
import {LibraryManagementModule} from './library-management/library-management.module';
import {MessageManagementModule} from './message-management/message-management.module';
import {BusinessTermManagementModule} from './business-term-management/business-term-management.module';
import {SettingsManagementModule} from './settings-management/settings-management.module';
import {MaterialModule} from './material.module';
import {ConfirmDialogModule} from './common/confirm-dialog/confirm-dialog.module';

import {SCORE_WEBAPP_ROUTES} from './basis/routes';
import {WebPageInfoService} from './basis/basis.service';
import {MailService} from './common/score-mail.service';
import {AppTitleStrategy} from './common/app-title.strategy';
import {AiChatPanelComponent} from './ai-management/ai-chat-panel/ai-chat-panel.component';
import {AiChatPanelHeaderComponent} from './ai-management/ai-chat-panel/ai-chat-panel-header.component';
import {AiChatPanelTabsComponent} from './ai-management/ai-chat-panel/ai-chat-panel-tabs.component';
import {AiChatMessageListComponent} from './ai-management/ai-chat-panel/ai-chat-message-list.component';
import {AiChatToolCallComponent} from './ai-management/ai-chat-panel/ai-chat-tool-call.component';
import {AiChatComposerComponent} from './ai-management/ai-chat-panel/ai-chat-composer.component';
import {AiChatHistoryListComponent} from './ai-management/ai-chat-panel/ai-chat-history-list.component';
import {AiChatInteractionPanelComponent} from './ai-management/ai-chat-panel/ai-chat-interaction-panel.component';
import {AiContextBudgetChartComponent} from './ai-management/ai-chat-panel/ai-context-budget-chart.component';
import {AiWorkingStatusComponent} from './ai-management/ai-chat-panel/ai-working-status.component';
import {AiAdminPolicyModule} from './ai-management/ai-admin-policy/ai-admin-policy.module';

const httpInterceptorsProviders = [
  {provide: HTTP_INTERCEPTORS, useClass: XhrInterceptor, multi: true},
  {provide: HTTP_INTERCEPTORS, useClass: ErrorAlertInterceptor, multi: true},
];

class ShouldReuseRouteFalseRouteReuseStrategy extends BaseRouteReuseStrategy {
  shouldReuseRoute(future: ActivatedRouteSnapshot, curr: ActivatedRouteSnapshot): boolean {
    return false;
  }
}

@NgModule({
  imports: [
    BrowserModule,
    BrowserAnimationsModule,
    RouterModule.forRoot(SCORE_WEBAPP_ROUTES, { onSameUrlNavigation: 'reload' }),
    HttpClientModule,
    FormsModule,
    ReactiveFormsModule,
    MarkdownModule.forRoot({
      loader: HttpClient,
      markedOptions: {
        provide: MARKED_OPTIONS,
        useValue: {
          gfm: true,
          breaks: false,
          pedantic: false,
        }
      }
    }),
    BasisModule,
    AccountManagementModule,
    AiAdminPolicyModule,
    SettingsManagementModule,
    BieManagementModule,
    ContextManagementModule,
    CcManagementModule,
    CodeListModule,
    AgencyIdListModule,
    LogManagementModule,
    NamespaceManagementModule,
    ReleaseManagementModule,
    ModuleManagementModule,
    LibraryManagementModule,
    MessageManagementModule,
    BusinessTermManagementModule,
    MaterialModule,
    ConfirmDialogModule,
    FontAwesomeModule,
    AiContextBudgetChartComponent
  ],
  declarations: [
    ScoreWebComponent,
    AiChatPanelComponent,
    AiChatPanelHeaderComponent,
    AiChatPanelTabsComponent,
    AiChatMessageListComponent,
    AiChatToolCallComponent,
    AiChatInteractionPanelComponent,
    AiChatComposerComponent,
    AiChatHistoryListComponent,
    AiWorkingStatusComponent
  ],
  providers: [
    provideTranslateService({
      loader: provideTranslateHttpLoader({prefix: './assets/i18n/', suffix: '.json'})
    }),
    MatIconRegistry,
    {
      provide: RouteReuseStrategy,
      useClass: ShouldReuseRouteFalseRouteReuseStrategy
    },
    {
      provide: TitleStrategy,
      useClass: AppTitleStrategy
    },
    AuthService,
    WebPageInfoService,
    MailService,
    httpInterceptorsProviders,
	    {
	      provide: RxStompService,
	      useFactory: rxStompServiceFactory,
	      deps: [HttpClient, AuthService]
	    }
  ],
  bootstrap: [
    ScoreWebComponent
  ]
})
export class ScoreWebModule {
}
