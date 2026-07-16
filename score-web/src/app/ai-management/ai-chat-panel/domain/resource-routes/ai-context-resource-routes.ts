import {AiResourceRoute} from '../ai-chat-panel.model';
import {
  AUTHENTICATED_ROLES,
  DEFAULT_LAST_UPDATED_PAGE,
  PAGE_PARAMS,
  UPDATED_RANGE_ALIASES,
  base64ListQuery
} from './ai-resource-route-query';

export const AI_CONTEXT_RESOURCE_ROUTES: AiResourceRoute[] = [
  {resource: 'context-category', listPath: '/context_management/context_category', listQuery: base64ListQuery('/context_management/context_category', DEFAULT_LAST_UPDATED_PAGE, {...PAGE_PARAMS, updaterLoginIdList: 'updater login IDs', updatedDateStart: 'updated range start', updatedDateEnd: 'updated range end', name: 'name', description: 'description'}, {updater: 'updaterLoginIdList', limit: 'pageSize'}, UPDATED_RANGE_ALIASES), detailPrefixes: ['/context_management/context_category/'], detailPattern: '/context_management/context_category/{id}', idFields: ['contextCategoryId', 'id'], linkableFields: ['name', 'contextCategoryId'], label: 'Context Category', roles: AUTHENTICATED_ROLES},
  {resource: 'context-scheme', listPath: '/context_management/context_scheme', listQuery: base64ListQuery('/context_management/context_scheme', DEFAULT_LAST_UPDATED_PAGE, {...PAGE_PARAMS, updaterLoginIdList: 'updater login IDs', updatedDateStart: 'updated range start', updatedDateEnd: 'updated range end', name: 'scheme name', schemeName: 'scheme name'}, {updater: 'updaterLoginIdList', scheme_name: 'name', limit: 'pageSize'}, UPDATED_RANGE_ALIASES), detailPrefixes: ['/context_management/context_scheme/'], detailPattern: '/context_management/context_scheme/{id}', idFields: ['contextSchemeId', 'id'], linkableFields: ['schemeName', 'name', 'contextSchemeId'], label: 'Context Scheme', roles: AUTHENTICATED_ROLES},
  {resource: 'business-context', listPath: '/context_management/business_context', listQuery: base64ListQuery('/context_management/business_context', DEFAULT_LAST_UPDATED_PAGE, {...PAGE_PARAMS, updaterLoginIdList: 'updater login IDs', updatedDateStart: 'updated range start', updatedDateEnd: 'updated range end', name: 'name'}, {updater: 'updaterLoginIdList', biz_ctx_name: 'name', limit: 'pageSize'}, UPDATED_RANGE_ALIASES), detailPrefixes: ['/context_management/business_context/'], detailPattern: '/context_management/business_context/{id}', idFields: ['businessContextId', 'biz_ctx_id', 'id'], linkableFields: ['name', 'businessContextId', 'biz_ctx_id'], label: 'Business Context', roles: AUTHENTICATED_ROLES}
];
