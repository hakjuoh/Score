/**
 * Registers BIE resources and their supported query and detail-route metadata.
 */

import {AiResourceRoute} from '../ai-chat-panel.model';
import {
  AUTHENTICATED_ROLES,
  DEFAULT_LAST_UPDATED_PAGE,
  DEFAULT_MULTI_LAST_UPDATED_PAGE,
  MULTI_PAGE_PARAMS,
  PAGE_PARAMS,
  UPDATED_RANGE_ALIASES,
  base64ListQuery
} from './ai-resource-route-query';

export const AI_BIE_RESOURCE_ROUTES: AiResourceRoute[] = [
  {
    resource: 'bie',
    listPath: '/profile_bie',
    listQuery: base64ListQuery('/profile_bie', DEFAULT_LAST_UPDATED_PAGE, {
      ...PAGE_PARAMS,
      releaseIds: 'release IDs',
      states: 'BIE states',
      deprecated: 'true or false',
      types: 'BIE types',
      access: 'access',
      ownerLoginIdList: 'owner login IDs',
      updaterLoginIdList: 'updater login IDs',
      updatedDateStart: 'updated range start',
      updatedDateEnd: 'updated range end',
      den: 'DEN filter',
      propertyTerm: 'property term',
      businessContext: 'business context text',
      version: 'version text',
      remark: 'remark text',
      asccpManifestId: 'ASCCP manifest ID',
      topLevelAsbiepIds: 'BIE IDs',
      basedTopLevelAsbiepIds: 'base BIE IDs',
      excludeTopLevelAsbiepIds: 'excluded BIE IDs'
    }, {
      owner: 'ownerLoginIdList',
      updater: 'updaterLoginIdList',
      state: 'states',
      release_id_list: 'releaseIds',
      release_id: 'releaseIds',
      is_deprecated: 'deprecated',
      property_term: 'propertyTerm',
      business_context: 'businessContext',
      limit: 'pageSize'
    }, UPDATED_RANGE_ALIASES),
    detailPrefixes: ['/profile_bie/'],
    detailPattern: '/profile_bie/{id}',
    idFields: ['topLevelAsbiepId', 'id'],
    linkableFields: ['den', 'name', 'topLevelAsbiepId'],
    label: 'BIE',
    roles: AUTHENTICATED_ROLES
  },
  {resource: 'bie-package', listPath: '/bie_package', listQuery: base64ListQuery('/bie_package', DEFAULT_MULTI_LAST_UPDATED_PAGE, {...MULTI_PAGE_PARAMS, releaseIds: 'release IDs', states: 'states', ownerLoginIdList: 'owner login IDs', updaterLoginIdList: 'updater login IDs', updatedDateStart: 'updated range start', updatedDateEnd: 'updated range end', name: 'name', versionId: 'version ID', versionName: 'version name', description: 'description', den: 'DEN', businessTerm: 'business term', version: 'version', remark: 'remark'}, {owner: 'ownerLoginIdList', updater: 'updaterLoginIdList', state: 'states', release_id: 'releaseIds', business_term: 'businessTerm', limit: 'pageSize'}, UPDATED_RANGE_ALIASES), detailPrefixes: ['/bie_package/'], detailPattern: '/bie_package/{id}', idFields: ['biePackageId', 'id'], linkableFields: ['name', 'biePackageId'], label: 'BIE Package', roles: AUTHENTICATED_ROLES},
  {resource: 'openapi-document', listPath: '/profile_bie/express/oas_doc', listQuery: base64ListQuery('/profile_bie/express/oas_doc', DEFAULT_MULTI_LAST_UPDATED_PAGE, {...MULTI_PAGE_PARAMS, access: 'access', updaterUsernameList: 'updater usernames', updatedDateStart: 'updated range start', updatedDateEnd: 'updated range end', title: 'title', description: 'description'}, {updater: 'updaterUsernameList', limit: 'pageSize'}, UPDATED_RANGE_ALIASES), detailPrefixes: ['/profile_bie/express/oas_doc/'], detailPattern: '/profile_bie/express/oas_doc/{id}', idFields: ['oasDocId', 'id'], linkableFields: ['title', 'oasDocId'], label: 'OpenAPI Document', roles: AUTHENTICATED_ROLES}
];
