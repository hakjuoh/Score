import {Injectable, inject} from '@angular/core';
import {Router} from '@angular/router';
import {AiResourceRoute} from './ai-chat-panel.model';
import {AiRouteRegistryContextService} from './ai-route-registry-context.service';

@Injectable({
  providedIn: 'root'
})
export class AiPageSnapshotService {

  private router = inject(Router);
  private routeRegistryContext = inject(AiRouteRegistryContextService);

  currentPageContext(route?: AiResourceRoute): string {
    const mainPanel = document.querySelector('div.body') as HTMLElement | null;
    const scope = mainPanel || document.body;
    const headings = this.visibleTexts(scope.querySelectorAll('h1, h2, h3, h4, .title, .page-title, .mat-mdc-card-title'), 8);
    const actions = this.visibleTexts(scope.querySelectorAll('button, a[routerlink], a[href], [role="button"]'), 24, true);
    const labels = this.visibleTexts(scope.querySelectorAll('label, mat-label, .mat-mdc-form-field-label, .mat-mdc-floating-label'), 16);

    return [
      `URL path: ${this.router.url || window.location.pathname}`,
      `Document title: ${document.title || 'connectCenter'}`,
      route ? `Current resource: ${route.label}` : undefined,
      route ? `List URL: ${route.listPath}` : undefined,
      route?.detailPattern ? `Detail URL pattern: ${route.detailPattern}` : undefined,
      route?.detailPatterns ? `Detail URL patterns: ${this.routeRegistryContext.formatDetailPatterns(route.detailPatterns)}` : undefined,
      route?.listQuery ? `List query: ${this.routeRegistryContext.formatListQuery(route.listQuery)}` : undefined,
      headings.length ? `Visible headings: ${headings.join(', ')}` : undefined,
      actions.length ? `Visible actions: ${actions.join(', ')}` : undefined,
      labels.length ? `Visible form labels: ${labels.join(', ')}` : undefined
    ].filter(Boolean).join('\n');
  }

  private visibleTexts(elements: NodeListOf<Element>, limit: number, excludeTableContent: boolean = false): string[] {
    const values: string[] = [];
    for (const element of Array.from(elements)) {
      if (values.length >= limit) {
        break;
      }
      if (element.closest('score-ai-chat-panel')) {
        continue;
      }
      if (excludeTableContent && element.closest('table, .mat-mdc-table, .mat-table')) {
        continue;
      }
      const htmlElement = element as HTMLElement;
      const rect = htmlElement.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) {
        continue;
      }
      const text = this.compactText(htmlElement.innerText || htmlElement.getAttribute('aria-label') || htmlElement.getAttribute('title') || '');
      if (text && !values.includes(text)) {
        values.push(text);
      }
    }
    return values;
  }

  private compactText(value: string): string {
    const normalized = value.replace(/\s+/g, ' ').trim();
    if (!normalized || normalized.length > 80) {
      return '';
    }
    return normalized;
  }
}
