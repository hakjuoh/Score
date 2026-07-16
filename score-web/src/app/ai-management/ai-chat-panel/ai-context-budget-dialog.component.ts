import {Component, inject} from '@angular/core';
import {MatButtonModule} from '@angular/material/button';
import {MAT_DIALOG_DATA, MatDialogModule} from '@angular/material/dialog';
import {MatIconModule} from '@angular/material/icon';
import {AiContextBudgetChartComponent} from './ai-context-budget-chart.component';
import {AiContextBudgetData} from './ai-context-budget-chart.model';

export type AiContextBudgetDialogData = AiContextBudgetData;

@Component({
  standalone: true,
  selector: 'score-ai-context-budget-dialog',
  imports: [AiContextBudgetChartComponent, MatButtonModule, MatDialogModule, MatIconModule],
  templateUrl: './ai-context-budget-dialog.component.html',
  styleUrls: ['./ai-context-budget-dialog.component.css']
})
export class AiContextBudgetDialogComponent {
  readonly data = inject<AiContextBudgetDialogData>(MAT_DIALOG_DATA);
}
