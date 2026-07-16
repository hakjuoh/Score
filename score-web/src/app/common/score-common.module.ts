import {NgModule} from '@angular/core';
import {
  ArraySortPipe,
  DateAgoPipe,
  HighlightSearch,
  JoinPipe,
  PastTensePipe,
  ReplaceAllPipe,
  SeparatePipe,
  TruncatePipe,
  UnboundedPipe,
  UndefinedPipe
} from './utility';
import {MatDialogModule} from '@angular/material/dialog';
import {MatCardModule} from '@angular/material/card';
import {CommonModule} from '@angular/common';
import {FormsModule, ReactiveFormsModule} from '@angular/forms';
import {MatButtonModule} from '@angular/material/button';
import {ConfirmDialogModule} from './confirm-dialog/confirm-dialog.module';
import {MultiActionsSnackBarModule} from './multi-actions-snack-bar/multi-actions-snack-bar.module';
import {ScoreTableColumnResizeDirective} from './score-table-column-resize/score-table-column-resize.directive';
import {TabFilterSelectComponent} from './tab-filter-select/tab-filter-select.component';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatSelectModule} from '@angular/material/select';
import {NgxMatSelectSearchModule} from 'ngx-mat-select-search';
import {MatCheckboxModule} from '@angular/material/checkbox';

@NgModule({
  declarations: [
    UnboundedPipe,
    HighlightSearch,
    DateAgoPipe,
    UndefinedPipe,
    SeparatePipe,
    JoinPipe,
    ArraySortPipe,
    TruncatePipe,
    PastTensePipe,
    ReplaceAllPipe,
    ScoreTableColumnResizeDirective,
    TabFilterSelectComponent
  ],
  imports: [
    MatDialogModule,
    MatCardModule,
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatSelectModule,
    NgxMatSelectSearchModule,
    ConfirmDialogModule,
    MultiActionsSnackBarModule
  ],
  exports: [
    UnboundedPipe,
    HighlightSearch,
    DateAgoPipe,
    UndefinedPipe,
    SeparatePipe,
    JoinPipe,
    ArraySortPipe,
    TruncatePipe,
    PastTensePipe,
    ReplaceAllPipe,
    ScoreTableColumnResizeDirective,
    TabFilterSelectComponent
  ]
})
export class ScoreCommonModule {
}
