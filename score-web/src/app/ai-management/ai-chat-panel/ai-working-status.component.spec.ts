/**
 * Verifies the AI Working Status component's rendering and interaction contract.
 */

import {ComponentFixture, TestBed} from '@angular/core/testing';
import {AiWorkingStatusComponent} from './ai-working-status.component';

describe('AiWorkingStatusComponent', () => {
  let fixture: ComponentFixture<AiWorkingStatusComponent>;

  beforeEach(async () => {
    vi.useFakeTimers();
    vi.setSystemTime(10_000);
    await TestBed.configureTestingModule({
      declarations: [AiWorkingStatusComponent]
    }).compileComponents();
    fixture = TestBed.createComponent(AiWorkingStatusComponent);
  });

  afterEach(() => {
    if (!fixture.componentRef.hostView.destroyed) fixture.destroy();
    vi.useRealTimers();
  });

  it('renders Working without an ellipsis and updates elapsed whole seconds', () => {
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent.trim()).toBe('Working (0s)');

    vi.advanceTimersByTime(3_000);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent.trim()).toBe('Working (3s)');
    expect(fixture.nativeElement.getAttribute('aria-label')).toBe('Working');
  });

  it('continues from a supplied operation start time', () => {
    fixture.componentRef.setInput('startedAt', 6_750);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent.trim()).toBe('Working (3s)');

    vi.advanceTimersByTime(750);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent.trim()).toBe('Working (4s)');
  });

  it('cancels its scheduled update when destroyed', () => {
    fixture.detectChanges();
    expect(vi.getTimerCount()).toBe(1);

    fixture.destroy();

    expect(vi.getTimerCount()).toBe(0);
  });
});
