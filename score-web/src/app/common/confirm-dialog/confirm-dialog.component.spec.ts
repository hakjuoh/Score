import {ConfirmDialogComponent} from './confirm-dialog.component';
import {readFileSync} from 'node:fs';

describe('ConfirmDialogComponent', () => {
  it('should be defined', () => {
    expect(ConfirmDialogComponent).toBeTruthy();
  });

  it('gives the first tabbable close control an accessible name', () => {
    const template = readFileSync(
      'src/app/common/confirm-dialog/confirm-dialog.component.html', 'utf8'
    );
    expect(template).toContain('aria-label="Close dialog"');
  });
});
