import {Component} from '@angular/core';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {ScoreCommonModule} from '../score-common.module';

@Component({
  standalone: true,
  imports: [ScoreCommonModule],
  template: `<table><tr>
    <th class="mat-column-name" score-table-column-resize
        (onResize)="resizes.push($event)">Name</th>
    <th class="mat-column-status">Status</th>
  </tr></table>`
})
class ResizeHostComponent {
  resizes: Array<{name: string; width: number | string}> = [];
}

describe('ScoreTableColumnResizeDirective keyboard support', () => {
  let fixture: ComponentFixture<ResizeHostComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({imports: [ResizeHostComponent]}).compileComponents();
    fixture = TestBed.createComponent(ResizeHostComponent);
    fixture.detectChanges();
  });

  it('exposes a focusable separator and resizes adjacent columns with arrow keys', () => {
    const headers = fixture.nativeElement.querySelectorAll('th');
    Object.defineProperty(headers[0], 'offsetWidth', {value: 100});
    Object.defineProperty(headers[1], 'offsetWidth', {value: 80});
    const handle = headers[0].querySelector('.resize-handle') as HTMLElement;

    expect(handle.getAttribute('role')).toBe('separator');
    expect(handle.getAttribute('tabindex')).toBe('0');
    expect(handle.getAttribute('aria-valuenow')).toBe('0');
    expect(handle.getAttribute('aria-valuemax')).toBe('10000');
    handle.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowRight', bubbles: true}));
    fixture.detectChanges();

    expect(fixture.componentInstance.resizes).toEqual([
      {name: 'Name', width: 110}, {name: 'Status', width: 70}
    ]);
    expect(handle.getAttribute('aria-valuenow')).toBe('110');
  });
});
