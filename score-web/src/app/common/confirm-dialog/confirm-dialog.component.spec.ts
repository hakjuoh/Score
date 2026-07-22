import {ConfirmDialogComponent} from './confirm-dialog.component';
import {readFileSync} from 'node:fs';

function parseHexColor(color: string): number[] {
  return color.match(/[0-9a-f]{2}/gi)?.map(channel => parseInt(channel, 16)) ?? [];
}

function relativeLuminance(color: number[]): number {
  const channels = color.map(channel => {
    const normalized = channel / 255;
    return normalized <= 0.04045
      ? normalized / 12.92
      : ((normalized + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
}

function contrastRatio(foreground: number[], background: number[]): number {
  const luminances = [relativeLuminance(foreground), relativeLuminance(background)]
    .sort((left, right) => right - left);
  return (luminances[0] + 0.05) / (luminances[1] + 0.05);
}

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

  it('keeps the main-window button style in every dialog overlay context', () => {
    const template = readFileSync(
      'src/app/common/confirm-dialog/confirm-dialog.component.html', 'utf8'
    );
    const styles = readFileSync(
      'src/app/common/confirm-dialog/confirm-dialog.component.css', 'utf8'
    );
    const dialogButtons = template.match(/<button[^>]*class="dialog-button"[^>]*>/gs) ?? [];
    const dialogButtonStyles = styles.match(/button\.dialog-button\s*\{([^}]*)}/s)?.[1];
    const dialogButtonHoverStyles = styles
      .match(/button\.dialog-button:hover\s*\{([^}]*)}/s)?.[1];
    const buttonColor = dialogButtonStyles
      ?.match(/--confirm-dialog-button-color:\s*(#[0-9a-f]{6})/i)?.[1];
    const hoverColor = dialogButtonStyles
      ?.match(/--confirm-dialog-button-hover-color:\s*(#[0-9a-f]{6})/i)?.[1];

    expect(dialogButtons).toHaveLength(2);
    expect(dialogButtons.every(button => !/(?:^|\s)color=/.test(button))).toBe(true);
    expect(template).not.toMatch(/class="[^"]*\bbtn(?:-outline-primary)?\b/);
    expect(dialogButtonStyles).toBeDefined();
    expect(dialogButtonStyles).toContain('--confirm-dialog-button-color: #1a73e8;');
    expect(dialogButtonStyles).toContain('--confirm-dialog-button-hover-color: #1967d2;');
    expect(dialogButtonStyles).toContain('--mat-button-text-hover-state-layer-opacity: 0;');
    expect(dialogButtonStyles).toContain('color: var(--confirm-dialog-button-color);');
    expect(dialogButtonHoverStyles).toContain(
      'background-color: color-mix(in srgb, var(--confirm-dialog-button-color) 6%, transparent);'
    );
    expect(dialogButtonHoverStyles).toContain(
      'color: var(--confirm-dialog-button-hover-color);'
    );

    const hoverBackground = parseHexColor(buttonColor ?? '')
      .map(channel => Math.round(channel * 0.06 + 255 * 0.94));
    expect(contrastRatio(parseHexColor(hoverColor ?? ''), hoverBackground))
      .toBeGreaterThanOrEqual(4.5);
  });
});
