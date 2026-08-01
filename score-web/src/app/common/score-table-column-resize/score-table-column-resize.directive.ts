import {AfterViewInit, Directive, ElementRef, EventEmitter, HostListener, Input, OnChanges, OnInit, Output, Renderer2, SimpleChanges, inject} from '@angular/core';

@Directive({
  standalone: false,
  selector: '[score-table-column-resize]'
})
export class ScoreTableColumnResizeDirective implements OnInit, OnChanges, AfterViewInit {
  private static readonly MAX_WIDTH = 10000;
  private el = inject(ElementRef);
  private renderer = inject(Renderer2);

  private startX: number;
  private startWidth: number;
  private resizeHandle: HTMLElement;
  private innerLine: HTMLElement;
  private _resizing = false;
  private _title: string;
  private _width: number | string;

  private startSiblingWidth: number;
  private sibling: HTMLElement;
  private siblingTitle: string;

  @Input() defaultWidth: number | string;
  @Input() resizable = true;
  @Output() onResize: EventEmitter<{ name: string; width: number | string }> = new EventEmitter();

  ngOnInit() {
    this.setInitialWidth();
    this.createResizeHandle();
    this.applyResizableState();
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes.defaultWidth && changes.defaultWidth.currentValue !== undefined) {
      this.setInitialWidth();
    }
    if (changes.resizable) {
      this.applyResizableState();
    }
  }

  ngAfterViewInit(): void {
    this.updateAriaValue(this.el.nativeElement.offsetWidth || 0);
  }

  get resizing(): boolean {
    return this._resizing;
  }

  get title(): string {
    return this._title;
  }

  get width(): number | string {
    return this._width;
  }

  setInitialWidth() {
    if (this.defaultWidth) {
      this._width = this.defaultWidth;
      this.applyWidthToColumn(this.el.nativeElement, this._width);
    } else {
      this.clearWidthFromColumn(this.el.nativeElement);
    }
  }

  createResizeHandle() {
    // Create the visual resize handle
    this.resizeHandle = this.renderer.createElement('span');
    this.renderer.addClass(this.resizeHandle, 'resize-handle');

    // Find the child <div> element inside the <th>
    let divChild = this.el.nativeElement.querySelector('div');
    if (!divChild) {
      this._title = this.el.nativeElement.textContent?.trim();
      divChild = this.renderer.createElement('div');

      // Move all existing child nodes into the new divChild
      while (this.el.nativeElement.firstChild) {
        this.renderer.appendChild(divChild, this.el.nativeElement.firstChild);
      }

      this.renderer.appendChild(this.el.nativeElement, divChild);
    } else {
      this._title = divChild.textContent?.trim();
    }

    this.renderer.appendChild(divChild, this.resizeHandle);

    // Style the resize handle for positioning and interaction
    this.renderer.setStyle(this.resizeHandle, 'position', 'absolute');
    this.renderer.setStyle(this.resizeHandle, 'top', '0');
    this.renderer.setStyle(this.resizeHandle, 'right', '0');
    this.renderer.setStyle(this.resizeHandle, 'width', '22px');
    this.renderer.setStyle(this.resizeHandle, 'height', '100%');
    this.renderer.setStyle(this.resizeHandle, 'cursor', 'col-resize');
    this.renderer.setStyle(this.resizeHandle, 'background-color', 'transparent');
    this.renderer.setAttribute(this.resizeHandle, 'role', 'separator');
    this.renderer.setAttribute(this.resizeHandle, 'aria-orientation', 'vertical');
    this.renderer.setAttribute(this.resizeHandle, 'aria-label', `Resize ${this._title} column`);
    this.renderer.setAttribute(this.resizeHandle, 'aria-valuemin', '0');
    this.renderer.setAttribute(this.resizeHandle, 'aria-valuemax',
      `${ScoreTableColumnResizeDirective.MAX_WIDTH}`);
    this.renderer.setAttribute(this.resizeHandle, 'tabindex', '0');

    this.innerLine = this.renderer.createElement('span');
    this.renderer.setStyle(this.innerLine, 'position', 'absolute');
    this.renderer.setStyle(this.innerLine, 'top', '0');
    this.renderer.setStyle(this.innerLine, 'right', '50%');
    this.renderer.setStyle(this.innerLine, 'transform', 'translateX(50%)');
    this.renderer.setStyle(this.innerLine, 'width', '1px');
    this.renderer.setStyle(this.innerLine, 'height', '100%');
    this.renderer.setStyle(this.innerLine, 'background-color', '#ccc');

    this.renderer.appendChild(this.resizeHandle, this.innerLine);

    // Add dynamic matTooltip to the resize handle
    const tooltip = this.renderer.createElement('span');
    this.renderer.setAttribute(this.resizeHandle, 'matTooltip', `${this._width}`);
    this.renderer.setAttribute(this.resizeHandle, 'matTooltipPosition', 'above');

    this.renderer.listen(this.el.nativeElement, 'mouseenter', () => {
      this.renderer.setStyle(this.innerLine, 'background-color', '#999');
    });
    this.renderer.listen(this.el.nativeElement, 'mouseleave', () => {
      this.renderer.setStyle(this.innerLine, 'background-color', '#ccc');
    });

    this.renderer.listen(this.resizeHandle, 'mousedown', (event: MouseEvent) => {
      event.stopPropagation();
      event.preventDefault();

      this.onResizeStart(event);
    });
    this.renderer.listen(this.resizeHandle, 'keydown', (event: KeyboardEvent) => {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
      event.stopPropagation();
      event.preventDefault();
      const step = event.shiftKey ? 25 : 10;
      this.resizeBy(event.key === 'ArrowRight' ? step : -step);
    });
  }

  applyResizableState() {
    if (!this.resizeHandle) {
      return;
    }

    if (this.resizable) {
      this.renderer.removeStyle(this.resizeHandle, 'display');
      this.renderer.setStyle(this.resizeHandle, 'pointer-events', 'auto');
      this.renderer.setAttribute(this.resizeHandle, 'tabindex', '0');
    } else {
      this.renderer.setStyle(this.resizeHandle, 'display', 'none');
      this.renderer.setStyle(this.resizeHandle, 'pointer-events', 'none');
      this.renderer.setAttribute(this.resizeHandle, 'tabindex', '-1');
    }
  }

  @HostListener('mousedown', ['$event'])
  onResizeStart(event: MouseEvent) {
    if (!this.resizable) {
      return;
    }

    if (event.target === this.resizeHandle || event.target === this.innerLine) {
      this._resizing = true;  // Set resizing flag
      this.startX = event.pageX;
      this.startWidth = this.el.nativeElement.offsetWidth;
      this.sibling = this.el.nativeElement.nextElementSibling;

      if (this.sibling) {
        this.startSiblingWidth = this.sibling.offsetWidth;
        const siblingDivChild = this.sibling.querySelector('div');
        this.siblingTitle = siblingDivChild ? siblingDivChild.textContent?.trim() : this.sibling.textContent?.trim();
      }

      document.addEventListener('mousemove', this.onMouseMove);
      document.addEventListener('mouseup', this.onMouseUp);
    }
  }

  onMouseMove = (event: MouseEvent) => {
    const deltaX = event.pageX - this.startX;
    this._width = Math.min(ScoreTableColumnResizeDirective.MAX_WIDTH,
      Math.max(0, this.startWidth + deltaX));

    if (this.sibling) {
      const newSiblingWidth = this.startSiblingWidth - deltaX;
      if (newSiblingWidth >= 0) {
        this.applyWidthToColumn(this.sibling, newSiblingWidth);
      }
    }

    this.applyWidthToColumn(this.el.nativeElement, this._width);
    this.updateAriaValue(this._width);
  };

  onMouseUp = (event: MouseEvent) => {
    document.removeEventListener('mousemove', this.onMouseMove);
    document.removeEventListener('mouseup', this.onMouseUp);
    this.onResize.emit({
      name: this._title,
      width: this._width
    });

    // Emit resize event for the sibling element
    if (this.sibling) {
      const siblingWidth = this.sibling.offsetWidth;
      this.onResize.emit({
        name: this.siblingTitle,
        width: siblingWidth
      });
    }

    // Set a small timeout to differentiate click from drag
    setTimeout(() => {
      this._resizing = false;  // Reset resizing flag
    }, 0);
  };

  private resizeBy(delta: number): void {
    const width = Math.min(ScoreTableColumnResizeDirective.MAX_WIDTH,
      Math.max(0, this.el.nativeElement.offsetWidth + delta));
    const sibling = this.el.nativeElement.nextElementSibling as HTMLElement | null;
    this._width = width;
    this.applyWidthToColumn(this.el.nativeElement, width);
    this.updateAriaValue(width);
    this.onResize.emit({name: this._title, width});

    if (!sibling) return;
    const siblingWidth = Math.max(0, sibling.offsetWidth - delta);
    this.applyWidthToColumn(sibling, siblingWidth);
    const siblingDiv = sibling.querySelector('div');
    this.onResize.emit({
      name: (siblingDiv ? siblingDiv.textContent?.trim() : sibling.textContent?.trim()) || '',
      width: siblingWidth
    });
  }

  private updateAriaValue(width: number): void {
    if (!this.resizeHandle) return;
    this.renderer.setAttribute(this.resizeHandle, 'aria-valuenow', `${width}`);
    this.renderer.setAttribute(this.resizeHandle, 'aria-valuetext', `${width} pixels`);
  }

  private applyWidthToColumn(element: HTMLElement, width: number | string) {
    const targets = this.getColumnElements(element);
    if (targets.length === 0) {
      this.applyWidth(element, width);
      return;
    }

    targets.forEach(target => this.applyWidth(target, width));
  }

  private clearWidthFromColumn(element: HTMLElement) {
    const targets = this.getColumnElements(element);
    if (targets.length === 0) {
      this.renderer.removeStyle(element, 'width');
      return;
    }

    targets.forEach(target => this.renderer.removeStyle(target, 'width'));
  }

  private applyWidth(element: HTMLElement, width: number | string) {
    if (typeof width === 'string') {
      this.renderer.setStyle(element, 'width', `${width}`);
    } else {
      this.renderer.setStyle(element, 'width', `${width}px`);
    }
  }

  private getColumnElements(element: HTMLElement): HTMLElement[] {
    const table = element.closest('table');
    if (!table) {
      return [];
    }

    const selectors = Array.from(element.classList)
      .filter(className => className.startsWith('mat-column-') || className.startsWith('cdk-column-'))
      .map(className => `.${className}`);

    if (selectors.length === 0) {
      return [];
    }

    return Array.from(table.querySelectorAll(selectors.join(','))) as HTMLElement[];
  }
}
