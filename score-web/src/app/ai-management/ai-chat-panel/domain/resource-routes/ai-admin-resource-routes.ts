import {AiResourceRoute} from '../ai-chat-panel.model';
import {
  ADMIN_ROLES,
  CREATED_RANGE_ALIASES,
  DEFAULT_NAME_PAGE,
  PAGE_PARAMS,
  base64ListQuery
} from './ai-resource-route-query';

export const AI_ADMIN_RESOURCE_ROUTES: AiResourceRoute[] = [
  {resource: 'admin-account', listPath: '/account', listQuery: base64ListQuery('/account', DEFAULT_NAME_PAGE, {...PAGE_PARAMS, loginId: 'login ID', name: 'name', organization: 'organization', status: 'status', roles: 'roles', tenantId: 'tenant ID', businessCtxIds: 'business context IDs'}, {login_id: 'loginId', role: 'roles', tenant_id: 'tenantId', business_context_id: 'businessCtxIds', limit: 'pageSize'}), detailPrefixes: ['/account/'], detailPattern: '/account/{id}', idFields: ['appUserId', 'accountId', 'userId', 'id'], linkableFields: ['loginId', 'name', 'appUserId'], label: 'Account', roles: ADMIN_ROLES},
  {resource: 'tenant', listPath: '/tenant', listQuery: base64ListQuery('/tenant', {sortActive: 'sortActive', sortDirection: 'asc', pageIndex: '0', pageSize: '10'}, {...PAGE_PARAMS, name: 'name'}, {limit: 'pageSize'}), detailPrefixes: ['/tenant/'], detailPattern: '/tenant/{id}', idFields: ['tenantId', 'id'], linkableFields: ['name', 'tenantId'], label: 'Tenant', roles: ADMIN_ROLES},
  {resource: 'pending-account', listPath: '/account/pending', listQuery: base64ListQuery('/account/pending', {sortActive: 'creationTimestamp', sortDirection: 'desc', pageIndex: '0', pageSize: '10'}, {...PAGE_PARAMS, createdDateStart: 'created range start', createdDateEnd: 'created range end', preferredUsername: 'preferred username', email: 'email', providerName: 'provider'}, {username: 'preferredUsername', provider: 'providerName', limit: 'pageSize'}, CREATED_RANGE_ALIASES), detailPrefixes: ['/account/pending/'], detailPattern: '/account/pending/{id}', idFields: ['appOauth2UserId', 'appUserId', 'id'], linkableFields: ['preferredUsername', 'email', 'appOauth2UserId'], label: 'Pending Account', roles: ADMIN_ROLES},
  {resource: 'settings', listPath: '/settings/application_settings', idFields: [], linkableFields: [], label: 'Settings', roles: ADMIN_ROLES}
];
