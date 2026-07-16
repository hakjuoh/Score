import {CommonModule} from '@angular/common';
import {Component, Input} from '@angular/core';
import {
  AiContextBudgetData,
  AiContextBudgetSlice,
  contextBudgetSlices
} from './ai-context-budget-chart.model';

@Component({
  standalone: true,
  selector: 'score-ai-context-budget-chart',
  imports: [CommonModule],
  templateUrl: './ai-context-budget-chart.component.html',
  styleUrls: ['./ai-context-budget-chart.component.css']
})
export class AiContextBudgetChartComponent {
  @Input() compact = false;

  data!: AiContextBudgetData;
  slices: AiContextBudgetSlice[] = [];
  pieBackground = '';

  @Input({required: true})
  set budgetData(data: AiContextBudgetData) {
    this.data = data;
    this.slices = contextBudgetSlices(data);
    this.pieBackground = this.buildPieBackground();
  }

  private buildPieBackground(): string {
    let cursor = 0;
    const segments = this.slices.map(slice => {
      const start = cursor;
      cursor += slice.percent;
      return `${slice.color} ${start}% ${cursor}%`;
    });
    return `conic-gradient(${segments.join(', ')})`;
  }
}
