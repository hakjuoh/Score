/**
 * Selects resource-specific refresh strategies after AI-driven data changes.
 */

import {Injectable, inject} from '@angular/core';
import {AiChatSocketEvent, AiResourceRoute} from './ai-chat-panel.model';
import {AiDataChangeService} from './ai-data-change.service';

export interface AiResourceRefreshStrategy {
  readonly resource: string;
  refresh(event: AiChatSocketEvent, targetPath: string): void;
}

@Injectable({
  providedIn: 'root'
})
export class AiBusinessContextRefreshStrategy implements AiResourceRefreshStrategy {
  readonly resource = 'business-context';

  private dataChangeService = inject(AiDataChangeService);

  refresh(event: AiChatSocketEvent, targetPath: string): void {
    this.dataChangeService.notify({
      resource: event.resource,
      action: event.action,
      targetPath,
      ids: event.ids
    });
  }
}

@Injectable({
  providedIn: 'root'
})
export class AiResourceRefreshService {

  private businessContextStrategy = inject(AiBusinessContextRefreshStrategy);
  private strategyByResource = new Map<string, AiResourceRefreshStrategy>();

  constructor() {
    this.register(this.businessContextStrategy);
  }

  register(strategy: AiResourceRefreshStrategy): void {
    this.strategyByResource.set(strategy.resource, strategy);
  }

  refresh(route: AiResourceRoute, event: AiChatSocketEvent, targetPath: string): boolean {
    const strategy = this.strategyByResource.get(route.resource);
    if (!strategy) {
      return false;
    }
    strategy.refresh(event, targetPath);
    return true;
  }
}
