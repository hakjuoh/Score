import {inject, Injectable} from '@angular/core';
import {AI_RESOURCE_ROUTES} from './resource-routes/ai-resource-routes';
import {AiResourceRoute} from './ai-chat-panel.model';
import {AuthService} from '../../../authentication/auth.service';

@Injectable({
  providedIn: 'root'
})
export class AiRouteRegistryContextService {
  private auth = inject(AuthService);

  registryContext(): string {
    const requesterRoles = new Set((this.auth.getUserToken()?.roles ?? []).map(role => role.toLowerCase()));
    return AI_RESOURCE_ROUTES.filter(route => this.isAllowed(route, requesterRoles)).map(route => {
      const detail = route.detailPattern ? `detail=${route.detailPattern}` :
        route.detailPatterns ? `detailPatterns=${this.formatDetailPatterns(route.detailPatterns)}` :
          'detail=none';
      const idFields = route.idFields.length ? route.idFields.join('|') : 'none';
      const linkableFields = route.linkableFields.length ? route.linkableFields.join('|') : 'none';
      const roles = route.roles.length ? route.roles.join('|') : 'none';
      const listQuery = route.listQuery ? `; listQuery=${this.formatListQuery(route.listQuery)}` : '';
      return `- ${route.resource}: label=${route.label}; list=${route.listPath}; ${detail}; idFields=${idFields}; linkableFields=${linkableFields}; roles=${roles}${listQuery}`;
    }).join('\n');
  }

  private isAllowed(route: AiResourceRoute, requesterRoles: Set<string>): boolean {
    return route.roles.length === 0 || route.roles.some(role => requesterRoles.has(role.toLowerCase()));
  }

  formatDetailPatterns(detailPatterns: {[key: string]: string}): string {
    return Object.entries(detailPatterns)
      .map(([type, pattern]) => `${type}=${pattern}`)
      .join(', ');
  }

  formatListQuery(listQuery: NonNullable<AiResourceRoute['listQuery']>): string {
    return [
      `finalFormat=${listQuery.finalFormat}`,
      listQuery.encoding ? `encoding=${listQuery.encoding}` : undefined,
      listQuery.formatterRule ? `formatterRule=${listQuery.formatterRule}` : undefined,
      listQuery.defaultParams ? `defaultParams=${this.formatObject(listQuery.defaultParams)}` : undefined,
      listQuery.allowedPlainParams ? `allowedPlainParams=${this.formatObject(listQuery.allowedPlainParams)}` : undefined,
      listQuery.toolParamAliases ? `toolParamAliases=${this.formatObject(listQuery.toolParamAliases)}` : undefined,
      listQuery.dateRangeParamAliases ? `dateRangeParamAliases=${this.formatObject(listQuery.dateRangeParamAliases)}` : undefined
    ].filter(Boolean).join('; ');
  }

  private formatObject(value: {[key: string]: string}): string {
    return Object.entries(value)
      .map(([key, item]) => `${key}:${item}`)
      .join('|');
  }
}
