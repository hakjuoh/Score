import {ReportDialogComponent} from './report-dialog.component';

describe('ReportDialogComponent', () => {
  it('should be defined', () => {
    expect(ReportDialogComponent).toBeTruthy();
  });

  it('shows a matched value-domain status when issues-only is enabled', () => {
    const component = Object.create(ReportDialogComponent.prototype) as ReportDialogComponent;
    component.hideSystemMatched = true;

    expect(component.show({status: 'Target Code List selected by GUID.', message: '', match: 'System'} as any)).toBe(true);
  });

  it('escapes CSV values containing quotes and commas', () => {
    const component = Object.create(ReportDialogComponent.prototype) as ReportDialogComponent;

    expect((component as any).toCsvRow(['A, B', 'He said "yes"'])).toBe('"A, B","He said ""yes"""');
  });
});
