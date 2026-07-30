import {Component, EventEmitter, Input, OnChanges, Output, SimpleChanges} from '@angular/core';
import {
  AiElicitationNotice,
  AiElicitationResponse,
  AiChangeApprovalBatchDecision,
  AiChangeApprovalBatchNotice,
  AiChangeInteraction
} from './domain/ai-chat-panel.model';
import {record} from './domain/ai-elicitation';

type Primitive = string | number | boolean | null;
type FieldKind = 'text' | 'textarea' | 'number' | 'boolean' | 'select' | 'multiselect' | 'json';

interface SchemaOption {
  value: Primitive;
  label: string;
  description?: string;
}

interface SchemaField {
  path: string[];
  title: string;
  description: string;
  kind: FieldKind;
  required: boolean;
  schema: Record<string, unknown>;
  options: SchemaOption[];
  value: unknown;
  error?: string;
}

@Component({
  standalone: false,
  selector: 'score-ai-chat-interaction-panel',
  templateUrl: './ai-chat-interaction-panel.component.html',
  styleUrls: [
    './ai-chat-panel.component.css',
    './ai-chat-panel-history.css',
    './ai-chat-panel-messages.css',
    './ai-chat-panel-composer.css'
  ]
})
export class AiChatInteractionPanelComponent implements OnChanges {

  @Input() change?: AiChangeInteraction;
  @Input() changeApprovalBatch?: AiChangeApprovalBatchNotice;
  @Input() changeApprovalBatchBusy = false;
  @Input() elicitation?: AiElicitationNotice;
  @Input() elicitationBusy = false;

  @Output() changeApproved = new EventEmitter<void>();
  @Output() changeDenied = new EventEmitter<void>();
  @Output() changeRevisionRequested = new EventEmitter<void>();
  @Output() changeRevoked = new EventEmitter<void>();
  @Output() changeDismissed = new EventEmitter<void>();
  @Output() changeBatchApproved = new EventEmitter<void>();
  @Output() changeBatchDenied = new EventEmitter<void>();
  @Output() changeBatchDecided = new EventEmitter<AiChangeApprovalBatchDecision[]>();
  @Output() elicitationResponded = new EventEmitter<AiElicitationResponse>();

  fields: SchemaField[] = [];
  private activeElicitationId?: string;
  private activeChangeBatchId?: string;
  private readonly changeDecisions = new Map<string, 'APPROVE' | 'DENY'>();

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['elicitation'] && this.elicitation?.elicitationId !== this.activeElicitationId) {
      this.activeElicitationId = this.elicitation?.elicitationId;
      this.fields = this.elicitation
        ? this.schemaFields(this.elicitation.requestedSchema) : [];
    }
    if (changes['changeApprovalBatch']
      && this.changeApprovalBatch?.batchId !== this.activeChangeBatchId) {
      this.activeChangeBatchId = this.changeApprovalBatch?.batchId;
      this.changeDecisions.clear();
    }
  }

  chooseChangeDecision(confirmationRequestId: string, decision: 'APPROVE' | 'DENY'): void {
    if (!this.changeApprovalBatchBusy) {
      this.changeDecisions.set(confirmationRequestId, decision);
    }
  }

  changeDecisionSelected(
    confirmationRequestId: string, decision: 'APPROVE' | 'DENY'
  ): boolean {
    return this.changeDecisions.get(confirmationRequestId) === decision;
  }

  changeBatchDecisionComplete(): boolean {
    return !!this.changeApprovalBatch
      && this.changeApprovalBatch.items.every(item =>
        this.changeDecisions.has(item.confirmationRequestId));
  }

  submitChangeBatchDecision(): void {
    const batch = this.changeApprovalBatch;
    if (!batch || this.changeApprovalBatchBusy || !this.changeBatchDecisionComplete()) return;
    this.changeBatchDecided.emit(batch.items.map(item => ({
      confirmationRequestId: item.confirmationRequestId,
      decision: this.changeDecisions.get(item.confirmationRequestId)!
    })));
  }

  choose(field: SchemaField, option: SchemaOption): void {
    if (this.elicitationBusy) {
      return;
    }
    field.value = option.value;
    field.error = undefined;
  }

  toggle(field: SchemaField, option: SchemaOption): void {
    if (this.elicitationBusy) {
      return;
    }
    const values = Array.isArray(field.value) ? [...field.value] : [];
    const index = values.findIndex(value => this.sameValue(value, option.value));
    if (index >= 0) {
      values.splice(index, 1);
    } else {
      values.push(option.value);
    }
    field.value = values;
    field.error = undefined;
  }

  selected(field: SchemaField, option: SchemaOption): boolean {
    return field.kind === 'multiselect'
      ? Array.isArray(field.value)
        && field.value.some(value => this.sameValue(value, option.value))
      : this.sameValue(field.value, option.value);
  }

  textChanged(field: SchemaField, event: Event): void {
    field.value = (event.target as HTMLInputElement | HTMLTextAreaElement).value;
    field.error = undefined;
  }

  numberChanged(field: SchemaField, event: Event): void {
    const input = event.target as HTMLInputElement;
    field.value = input.value === '' ? undefined : input.valueAsNumber;
    field.error = undefined;
  }

  booleanChanged(field: SchemaField, event: Event): void {
    field.value = (event.target as HTMLInputElement).checked;
    field.error = undefined;
  }

  respond(action: AiElicitationResponse['action']): void {
    if (this.elicitationBusy) {
      return;
    }
    if (action !== 'ACCEPT') {
      this.elicitationResponded.emit({action, content: {}});
      return;
    }
    const content: Record<string, unknown> = {};
    let valid = true;
    for (const field of this.fields) {
      const value = this.normalizedValue(field);
      const error = this.fieldError(field, value);
      field.error = error;
      if (error) {
        valid = false;
        continue;
      }
      if (value !== undefined) {
        this.setPath(content, field.path, value);
      }
    }
    if (valid) {
      this.elicitationResponded.emit({action, content});
    }
  }

  private schemaFields(schema: Record<string, unknown>): SchemaField[] {
    const fields: SchemaField[] = [];
    this.appendFields(fields, [], schema, '', 0);
    return fields;
  }

  private appendFields(fields: SchemaField[], path: string[], schema: Record<string, unknown>,
                       parentTitle: string, depth: number): void {
    if (depth > 5 || fields.length >= 50) {
      return;
    }
    const properties = record(schema['properties']);
    const required = new Set(Array.isArray(schema['required'])
      ? schema['required'].filter((value): value is string => typeof value === 'string') : []);
    if (!properties) {
      return;
    }
    for (const [name, rawChild] of Object.entries(properties)) {
      const child = record(rawChild);
      if (!child || fields.length >= 50) {
        continue;
      }
      const childPath = [...path, name];
      const title = this.text(child['title']) || this.humanize(name);
      const childProperties = record(child['properties']);
      if ((child['type'] === 'object' || childProperties) && childProperties) {
        this.appendFields(fields, childPath, child, title, depth + 1);
        continue;
      }
      const options = this.options(child);
      const kind = this.fieldKind(child, options);
      fields.push({
        path: childPath,
        title: parentTitle ? `${parentTitle} · ${title}` : title,
        description: this.text(child['description']) || '',
        kind,
        required: required.has(name),
        schema: child,
        options,
        value: this.initialValue(child, kind, options)
      });
    }
  }

  private fieldKind(schema: Record<string, unknown>, options: SchemaOption[]): FieldKind {
    if (schema['type'] === 'array') {
      return options.length > 0 ? 'multiselect' : 'json';
    }
    if (options.length > 0) {
      return 'select';
    }
    if (schema['type'] === 'boolean') {
      return 'boolean';
    }
    if (schema['type'] === 'number' || schema['type'] === 'integer') {
      return 'number';
    }
    if (schema['type'] === 'string') {
      return schema['format'] === 'textarea'
        || (typeof schema['maxLength'] === 'number' && schema['maxLength'] > 160)
        ? 'textarea' : 'text';
    }
    return 'json';
  }

  private options(schema: Record<string, unknown>): SchemaOption[] {
    const optionSchema = schema['type'] === 'array' ? record(schema['items']) || {} : schema;
    if (Array.isArray(optionSchema['enum'])) {
      return optionSchema['enum'].filter(this.isPrimitive).map(value => ({
        value, label: String(value)
      }));
    }
    const alternatives = Array.isArray(optionSchema['oneOf'])
      ? optionSchema['oneOf'] : Array.isArray(optionSchema['anyOf']) ? optionSchema['anyOf'] : [];
    return alternatives.flatMap(alternative => {
      const option = record(alternative);
      const value = option?.['const'];
      return option && this.isPrimitive(value) ? [{
        value,
        label: this.text(option['title']) || String(value),
        description: this.text(option['description']) || undefined
      }] : [];
    });
  }

  private initialValue(schema: Record<string, unknown>, kind: FieldKind,
                       options: SchemaOption[]): unknown {
    if (schema['default'] !== undefined) {
      return schema['default'];
    }
    if (kind === 'multiselect') {
      return [];
    }
    if (kind === 'boolean') {
      return false;
    }
    if (kind === 'json') {
      return '';
    }
    return options.length === 1 ? options[0].value : undefined;
  }

  private normalizedValue(field: SchemaField): unknown {
    if (field.kind !== 'json') {
      return field.value;
    }
    if (typeof field.value !== 'string' || field.value.trim() === '') {
      return undefined;
    }
    try {
      return JSON.parse(field.value);
    } catch {
      return Symbol.for('invalid-json');
    }
  }

  private fieldError(field: SchemaField, value: unknown): string | undefined {
    if (value === Symbol.for('invalid-json')) {
      return 'Enter valid JSON.';
    }
    if (value === undefined || value === null || value === '') {
      return field.required ? 'This field is required.' : undefined;
    }
    if (typeof value === 'number') {
      if (!Number.isFinite(value)) {
        return 'Enter a valid number.';
      }
      if (field.schema['type'] === 'integer' && !Number.isInteger(value)) {
        return 'Enter a whole number.';
      }
      if (typeof field.schema['minimum'] === 'number' && value < field.schema['minimum']) {
        return `Minimum: ${field.schema['minimum']}`;
      }
      if (typeof field.schema['maximum'] === 'number' && value > field.schema['maximum']) {
        return `Maximum: ${field.schema['maximum']}`;
      }
    }
    if (typeof value === 'string') {
      if (typeof field.schema['minLength'] === 'number'
        && value.length < field.schema['minLength']) {
        return `Enter at least ${field.schema['minLength']} characters.`;
      }
      if (typeof field.schema['maxLength'] === 'number'
        && value.length > field.schema['maxLength']) {
        return `Enter no more than ${field.schema['maxLength']} characters.`;
      }
    }
    if (Array.isArray(value) && field.required && value.length === 0) {
      return 'Select at least one option.';
    }
    return undefined;
  }

  private setPath(target: Record<string, unknown>, path: string[], value: unknown): void {
    let cursor = target;
    path.forEach((name, index) => {
      if (index === path.length - 1) {
        cursor[name] = value;
        return;
      }
      const next = record(cursor[name]) || {};
      cursor[name] = next;
      cursor = next;
    });
  }

  private sameValue(first: unknown, second: unknown): boolean {
    return first === second;
  }

  private text(value: unknown): string | undefined {
    return typeof value === 'string' && value.trim() ? value.trim() : undefined;
  }

  private humanize(value: string): string {
    return value.replace(/[_-]+/g, ' ').replace(/\b\w/g, letter => letter.toUpperCase());
  }

  private isPrimitive(value: unknown): value is Primitive {
    return value === null || typeof value === 'string'
      || typeof value === 'number' || typeof value === 'boolean';
  }
}
