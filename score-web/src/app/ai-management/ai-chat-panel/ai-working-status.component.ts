/**
 * Displays elapsed working time and animated status text for an active request.
 */

import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  Input,
  OnDestroy,
  OnInit
} from '@angular/core';
import {WORKING_STATUS_LABEL} from './domain/ai-chat-panel-display.constants';

@Component({
  standalone: false,
  selector: 'score-ai-working-status',
  template: `{{ label }} <span aria-hidden="true">({{ elapsedSeconds }}s)</span>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {'[attr.aria-label]': 'label'}
})
export class AiWorkingStatusComponent implements OnInit, OnDestroy {
  @Input() startedAt?: number;

  readonly label = WORKING_STATUS_LABEL;
  elapsedSeconds = 0;

  private renderedAt = Date.now();
  private timerId?: number;

  constructor(private readonly changeDetector: ChangeDetectorRef) {}

  ngOnInit(): void {
    this.updateElapsedTime();
  }

  ngOnDestroy(): void {
    this.clearTimer();
  }

  private updateElapsedTime(): void {
    const startedAt = this.startedAt ?? this.renderedAt;
    const elapsedMilliseconds = Math.max(0, Date.now() - startedAt);
    this.elapsedSeconds = Math.floor(elapsedMilliseconds / 1000);
    this.changeDetector.markForCheck();

    const millisecondsUntilNextSecond = 1000 - elapsedMilliseconds % 1000;
    this.timerId = window.setTimeout(
      () => this.updateElapsedTime(), millisecondsUntilNextSecond
    );
  }

  private clearTimer(): void {
    if (this.timerId === undefined) return;
    window.clearTimeout(this.timerId);
    this.timerId = undefined;
  }
}
