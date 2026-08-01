import {Component, ElementRef, EventEmitter, Input, Output, ViewChild} from '@angular/core';

@Component({
  standalone: false,
  selector: 'score-json-option-editor',
  templateUrl: './json-option-editor.component.html',
  styleUrls: ['./json-option-editor.component.css']
})
export class JsonOptionEditorComponent {
  @ViewChild('input') private input?: ElementRef<HTMLTextAreaElement>;
  @ViewChild('highlight') private highlight?: ElementRef<HTMLElement>;
  @Input() value = '';
  @Input() controlId = '';
  @Input() describedBy = '';
  @Input() errorId = '';
  @Input() error = '';
  @Output() readonly valueChange = new EventEmitter<string>();

  get highlightedValue(): string {
    return highlightJson(this.value) + (this.value.endsWith('\n') ? '\n' : '');
  }

  onInput(event: Event): void {
    this.valueChange.emit((event.target as HTMLTextAreaElement).value);
  }

  synchronizeScroll(): void {
    if (!this.input || !this.highlight) return;
    this.highlight.nativeElement.scrollTop = this.input.nativeElement.scrollTop;
    this.highlight.nativeElement.scrollLeft = this.input.nativeElement.scrollLeft;
  }
}

function highlightJson(value: string): string {
  const tokenPattern = /("(?:\\u[\da-fA-F]{4}|\\[^u]|[^\\"])*"\s*:)|("(?:\\u[\da-fA-F]{4}|\\[^u]|[^\\"])*")|\b(true|false)\b|\b(null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g;
  let result = '';
  let cursor = 0;
  for (const match of value.matchAll(tokenPattern)) {
    const index = match.index ?? 0;
    result += escapeHtml(value.slice(cursor, index));
    const token = match[0];
    const kind = match[1] ? 'key' : match[2] ? 'string' : match[3] ? 'boolean'
      : match[4] ? 'null' : 'number';
    result += `<span class="json-${kind}">${escapeHtml(token)}</span>`;
    cursor = index + token.length;
  }
  return result + escapeHtml(value.slice(cursor));
}

function escapeHtml(value: string): string {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
}
