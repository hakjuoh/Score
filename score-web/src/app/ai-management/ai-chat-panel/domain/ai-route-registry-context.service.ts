import {inject, Injectable} from '@angular/core';
import {AI_RESOURCE_ROUTES} from './resource-routes/ai-resource-routes';
import {AiResourceRoute, AiUiRouteManifest} from './ai-chat-panel.model';
import {AuthService} from '../../../authentication/auth.service';

@Injectable({
  providedIn: 'root'
})
export class AiRouteRegistryContextService {
  private auth = inject(AuthService);

  routeManifest(): AiUiRouteManifest {
    return {
      schemaVersion: 1,
      routes: this.allowedRoutes().map(route => ({
        resource: route.resource,
        listPath: route.listPath,
        detailPatterns: route.detailPatterns
          ? {...route.detailPatterns}
          : route.detailPattern ? {default: route.detailPattern} : {},
        idFields: [...route.idFields],
        linkableFields: [...route.linkableFields],
        ...(route.listQuery ? {
          listQuery: {
            codec: route.listQuery.codec || 'plain',
            defaultParams: {...(route.listQuery.defaultParams || {})},
            allowedPlainParams: Object.keys(route.listQuery.allowedPlainParams || {}),
            toolParamAliases: {...(route.listQuery.toolParamAliases || {})},
            dateRangeParamAliases: {...(route.listQuery.dateRangeParamAliases || {})}
          }
        } : {})
      }))
    };
  }

  private allowedRoutes(): AiResourceRoute[] {
    const requesterRoles = new Set((this.auth.getUserToken()?.roles ?? []).map(role => role.toLowerCase()));
    return AI_RESOURCE_ROUTES.filter(route => this.isAllowed(route, requesterRoles));
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
      listQuery.codec ? `codec=${listQuery.codec}` : undefined,
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
