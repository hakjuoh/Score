import {Injectable, inject} from '@angular/core';
import {Router} from '@angular/router';
import {MatSnackBar} from '@angular/material/snack-bar';
import {AiChatContextService} from './ai-chat-context.service';
import {AiResourceRefreshService} from './ai-resource-refresh.service';
import {AiChatSocketEvent, AiResourceRoute} from './ai-chat-panel.model';

@Injectable({
  providedIn: 'root'
})
export class AiChatNavigationService {

  private router = inject(Router);
  private snackBar = inject(MatSnackBar);
  private contextService = inject(AiChatContextService);
  private resourceRefreshService = inject(AiResourceRefreshService);

  navigateLink(link: HTMLAnchorElement): boolean {
    const route = this.contextService.routeFromLink(link);
    if (!route) {
      return false;
    }
    this.navigateByUrl(route);
    return true;
  }

  handleDataChanged(event: AiChatSocketEvent): void {
    const route = this.contextService.resourceRoute(event.resource);
    const targetPath = event.targetPath || route?.listPath;
    if (!targetPath) {
      return;
    }

    const currentPath = this.contextService.currentPath();
    if (route && currentPath === route.listPath) {
      this.refreshCurrentResourcePage(route, event, targetPath);
      return;
    }

    if (route && this.isResourceDetailPath(route, currentPath)) {
      if (this.shouldRefreshCurrentDetail(event, currentPath, targetPath)) {
        this.refreshCurrentRoute();
      } else {
        this.offerNavigation(event, targetPath, route);
      }
      return;
    }

    if (currentPath === targetPath) {
      this.refreshCurrentRoute();
      return;
    }

    this.offerNavigation(event, targetPath, route);
  }

  private refreshCurrentResourcePage(route: AiResourceRoute, event: AiChatSocketEvent, targetPath: string): void {
    if (this.resourceRefreshService.refresh(route, event, targetPath)) {
      return;
    }
    this.refreshCurrentRoute();
  }

  private offerNavigation(event: AiChatSocketEvent, targetPath: string, route?: AiResourceRoute): void {
    const snackBarRef = this.snackBar.open(
      event.message || `${route?.label || 'Data'} changed. Open the related page?`,
      'Open',
      {duration: 10000}
    );
    snackBarRef.onAction().subscribe(() => {
      this.navigateByUrl(targetPath);
    });
  }

  private isResourceDetailPath(route: AiResourceRoute, path: string): boolean {
    return !!route.detailPrefixes?.some(prefix => path.startsWith(prefix));
  }

  private shouldRefreshCurrentDetail(event: AiChatSocketEvent, currentPath: string, targetPath: string): boolean {
    if (currentPath === targetPath) {
      return true;
    }
    const currentId = currentPath.split('/').filter(Boolean).pop();
    return !!currentId && !!event.ids?.includes(currentId) && event.action !== 'create';
  }

  private refreshCurrentRoute(): void {
    const sourceWindow = this.mainApplicationWindow();
    if (sourceWindow) {
      sourceWindow.location.reload();
      sourceWindow.focus();
      return;
    }
    this.router.navigateByUrl(this.router.url);
  }

  private navigateByUrl(route: string): void {
    const sourceWindow = this.mainApplicationWindow();
    if (sourceWindow) {
      sourceWindow.location.assign(route);
      sourceWindow.focus();
      return;
    }
    this.router.navigateByUrl(route);
  }

  private mainApplicationWindow(): Window | undefined {
    try {
      if (new URLSearchParams(window.location.search).get('aiAssistantPopout') === '1'
        && window.opener && !window.opener.closed
        && window.opener.location.origin === window.location.origin) {
        return window.opener;
      }
    } catch {
      // Cross-origin opener access is intentionally ignored.
    }
    return undefined;
  }
}
