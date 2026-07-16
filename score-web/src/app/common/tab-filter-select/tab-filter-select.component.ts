import {
  Component,
  DoCheck,
  ElementRef,
  forwardRef,
  Input,
  OnChanges,
  OnDestroy,
  OnInit,
  QueryList,
  SimpleChanges,
  ViewChild,
  ViewChildren
} from '@angular/core';
import {ControlValueAccessor, FormControl, NG_VALUE_ACCESSOR} from '@angular/forms';
import {MatSelect} from '@angular/material/select';
import {ReplaySubject, Subject} from 'rxjs';
import {takeUntil} from 'rxjs/operators';

export interface TabFilterSelectTab {
  id: string;
  label: string;
  valuePrefix?: string;
  valueSuffix?: string;
  optionLabelPrefix?: string;
  optionLabelSuffix?: string;
  selectedLabelPrefix?: string;
  selectedLabelSuffix?: string;
}

@Component({
  standalone: false,
  selector: 'score-tab-filter-select',
  templateUrl: './tab-filter-select.component.html',
  styleUrls: ['./tab-filter-select.component.css'],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => TabFilterSelectComponent),
      multi: true
    }
  ]
})
export class TabFilterSelectComponent implements ControlValueAccessor, DoCheck, OnChanges, OnDestroy, OnInit {

  @Input() label = 'Filter';
  @Input() options: string[] = [];
  @Input() tabs: TabFilterSelectTab[] = [{id: 'default', label: 'Options'}];
  @Input() placeholderLabel = 'Search...';
  @Input() noEntriesFoundLabel = 'No matching entries found.';
  @Input() optionLabelFormatter: (option: string) => string = option => option;

  activeTabId = '';
  selectionCtrl: FormControl = new FormControl([]);
  searchCtrl: FormControl = new FormControl();
  filteredOptions: ReplaySubject<string[]> = new ReplaySubject<string[]>(1);
  value: string[] = [];
  disabled = false;

  @ViewChild(MatSelect) matSelect: MatSelect;
  @ViewChildren('optionsContainer') optionContainers: QueryList<ElementRef<HTMLElement>>;

  private destroyed = new Subject<void>();
  private optionsSignature = '';
  private tabsSignature = '';
  private onChange: (value: string[]) => void = () => {};
  private onTouched: () => void = () => {};

  get displayTabs(): TabFilterSelectTab[] {
    return this.normalizedTabs();
  }

  ngOnInit(): void {
    this.activeTabId = this.activeTabId || this.firstTab().id;
    this.selectionCtrl.valueChanges
      .pipe(takeUntil(this.destroyed))
      .subscribe(values => this.onSelectionChange(values));
    this.searchCtrl.valueChanges
      .pipe(takeUntil(this.destroyed))
      .subscribe(() => this.refreshFilteredOptions());
    this.refreshFilteredOptions();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes.tabs) {
      this.tabsSignature = this.signatureOfTabs(this.tabs);
      this.ensureActiveTab();
    }
    if (changes.options || changes.tabs) {
      this.optionsSignature = this.signatureOfOptions(this.options);
      this.refreshFilteredOptions();
    }
  }

  ngDoCheck(): void {
    const optionsSignature = this.signatureOfOptions(this.options);
    const tabsSignature = this.signatureOfTabs(this.tabs);
    if (this.optionsSignature !== optionsSignature || this.tabsSignature !== tabsSignature) {
      this.optionsSignature = optionsSignature;
      this.tabsSignature = tabsSignature;
      this.ensureActiveTab();
      this.refreshFilteredOptions();
    }
  }

  ngOnDestroy(): void {
    this.destroyed.next();
    this.destroyed.complete();
  }

  writeValue(value: string[]): void {
    this.value = Array.isArray(value) ? value.filter(e => !!e) : [];
    this.selectionCtrl.setValue(this.value, {emitEvent: false});
    this.activeTabId = this.initialTabId(this.value);
    this.refreshFilteredOptions();
  }

  registerOnChange(fn: (value: string[]) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (isDisabled) {
      this.selectionCtrl.disable({emitEvent: false});
      this.searchCtrl.disable({emitEvent: false});
    } else {
      this.selectionCtrl.enable({emitEvent: false});
      this.searchCtrl.enable({emitEvent: false});
    }
  }

  setActiveTab(tabId: string, event?: Event): void {
    if (event) {
      event.preventDefault();
      event.stopPropagation();
    }
    this.activeTabId = tabId;
    this.resetPanelScroll();
  }

  isTabAllSelected(tab: TabFilterSelectTab): boolean {
    const tabValues = this.tabValues(tab);
    return tabValues.length > 0 && tabValues.every(value => this.value.includes(value));
  }

  isTabPartiallySelected(tab: TabFilterSelectTab): boolean {
    const tabValues = this.tabValues(tab);
    const selectedCount = tabValues.filter(value => this.value.includes(value)).length;
    return selectedCount > 0 && selectedCount < tabValues.length;
  }

  toggleTabSelection(tab: TabFilterSelectTab, checked: boolean): void {
    this.activeTabId = tab.id;

    const tabValues = this.tabValues(tab);
    const nextValues = this.value.filter(value => !tabValues.includes(value));
    if (checked) {
      nextValues.push(...tabValues);
    }

    const uniqueValues = this.uniqueValues(nextValues);
    this.value = uniqueValues;
    this.selectionCtrl.setValue(uniqueValues);
    this.resetPanelScroll();
  }

  onOpenedChange(opened: boolean): void {
    if (opened) {
      this.resetPanelScroll();
    }
  }

  onSelectionChange(values: string[]): void {
    this.value = (values || []).filter(e => typeof e === 'string' && e.length > 0);
    this.onChange(this.value);
    this.onTouched();
  }

  optionValue(option: string, tab: TabFilterSelectTab): string {
    return `${tab.valuePrefix || ''}${option}${tab.valueSuffix || ''}`;
  }

  optionLabel(option: string, tab: TabFilterSelectTab): string {
    return `${tab.optionLabelPrefix || ''}${this.optionLabelFormatter(option)}${tab.optionLabelSuffix || ''}`;
  }

  selectedLabels(): string[] {
    return this.value.map(e => this.selectedLabel(e));
  }

  private selectedLabel(value: string): string {
    const tab = this.matchingTab(value);
    const option = tab ? this.optionFromValue(value, tab) : value;
    return `${tab?.selectedLabelPrefix || tab?.optionLabelPrefix || ''}${this.optionLabelFormatter(option)}${tab?.selectedLabelSuffix || tab?.optionLabelSuffix || ''}`;
  }

  private refreshFilteredOptions(): void {
    const search = (this.searchCtrl.value || '').toString().toLowerCase();
    const options = this.allOptions();
    this.filteredOptions.next(
      search ? options.filter(option => this.optionLabelFormatter(option).toLowerCase().includes(search)) : options
    );
  }

  private allOptions(): string[] {
    const options = [...(this.options || [])];
    this.value
      .map(value => {
        const tab = this.matchingTab(value);
        return tab ? this.optionFromValue(value, tab) : value;
      })
      .filter(option => !!option && !options.includes(option))
      .forEach(option => options.push(option));
    return options;
  }

  private tabValues(tab: TabFilterSelectTab): string[] {
    return this.allOptions().map(option => this.optionValue(option, tab));
  }

  private matchingTab(value: string): TabFilterSelectTab {
    return this.normalizedTabs()
      .filter(tab => this.matchesTab(value, tab))
      .sort((a, b) => this.specificityOf(b) - this.specificityOf(a))[0];
  }

  private matchesTab(value: string, tab: TabFilterSelectTab): boolean {
    const prefix = tab.valuePrefix || '';
    const suffix = tab.valueSuffix || '';
    return value.startsWith(prefix) && value.endsWith(suffix) && value.length >= prefix.length + suffix.length;
  }

  private optionFromValue(value: string, tab: TabFilterSelectTab): string {
    const prefix = tab.valuePrefix || '';
    const suffix = tab.valueSuffix || '';
    return value.substring(prefix.length, suffix ? value.length - suffix.length : value.length);
  }

  private initialTabId(values: string[]): string {
    const matchedTabs = values.map(value => this.matchingTab(value)).filter(tab => !!tab);
    if (matchedTabs.length > 0 && matchedTabs.every(tab => tab.id === matchedTabs[0].id)) {
      return matchedTabs[0].id;
    }
    return this.firstTab().id;
  }

  private ensureActiveTab(): void {
    if (!this.normalizedTabs().some(tab => tab.id === this.activeTabId)) {
      this.activeTabId = this.firstTab().id;
    }
  }

  private firstTab(): TabFilterSelectTab {
    return this.normalizedTabs()[0];
  }

  private normalizedTabs(): TabFilterSelectTab[] {
    return this.tabs && this.tabs.length > 0 ? this.tabs : [{id: 'default', label: 'Options'}];
  }

  private specificityOf(tab: TabFilterSelectTab): number {
    return (tab.valuePrefix || '').length + (tab.valueSuffix || '').length;
  }

  private uniqueValues(values: string[]): string[] {
    return values.filter((value, index) => values.indexOf(value) === index);
  }

  private signatureOfOptions(options: string[]): string {
    return (options || []).join('\u0000');
  }

  private signatureOfTabs(tabs: TabFilterSelectTab[]): string {
    return (tabs || []).map(tab => [
      tab.id,
      tab.label,
      tab.valuePrefix || '',
      tab.valueSuffix || '',
      tab.optionLabelPrefix || '',
      tab.optionLabelSuffix || '',
      tab.selectedLabelPrefix || '',
      tab.selectedLabelSuffix || ''
    ].join('\u0001')).join('\u0000');
  }

  private resetPanelScroll(): void {
    setTimeout(() => {
      if (this.matSelect?.panel?.nativeElement) {
        this.matSelect.panel.nativeElement.scrollTop = 0;
      }
      this.optionContainers?.forEach(container => container.nativeElement.scrollTop = 0);
    });
  }
}
