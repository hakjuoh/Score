export const AUTHENTICATED_ROLES = ['admin', 'developer', 'end-user'];
export const ADMIN_ROLES = ['admin'];

const BASE64_ENCODING = 'q = base64(UTF-8 form query string). Do not compute q in the model.';

export const DEFAULT_LAST_UPDATED_PAGE = {
  sortActive: 'lastUpdateTimestamp',
  sortDirection: 'desc',
  pageIndex: '0',
  pageSize: '10'
};
export const DEFAULT_NAME_PAGE = {sortActive: 'name', sortDirection: 'asc', pageIndex: '0', pageSize: '10'};
export const DEFAULT_MULTI_LAST_UPDATED_PAGE = {
  sortActives: 'lastUpdateTimestamp',
  sortDirections: 'desc',
  pageIndex: '0',
  pageSize: '10'
};

export const PAGE_PARAMS = {
  pageSize: 'page size',
  pageIndex: 'zero-based page',
  sortActive: 'sort field',
  sortDirection: 'asc or desc'
};

export const MULTI_PAGE_PARAMS = {
  pageSize: 'page size',
  pageIndex: 'zero-based page',
  sortActives: 'comma-separated sort fields',
  sortDirections: 'comma-separated sort directions'
};

export const UPDATED_RANGE_ALIASES = {
  last_updated_on: 'updatedDateStart,updatedDateEnd',
  updated_on: 'updatedDateStart,updatedDateEnd',
  updatedDate: 'updatedDateStart,updatedDateEnd'
};

export const CREATED_RANGE_ALIASES = {
  created_on: 'createdDateStart,createdDateEnd',
  creation_timestamp: 'createdDateStart,createdDateEnd',
  createdDate: 'createdDateStart,createdDateEnd'
};

export function base64ListQuery(
  listPath: string,
  defaultParams: {[key: string]: string},
  allowedPlainParams: {[key: string]: string},
  toolParamAliases?: {[key: string]: string},
  dateRangeParamAliases?: {[key: string]: string}
) {
  return {
    finalFormat: `${listPath}?q=<base64Utf8QueryString>`,
    encoding: BASE64_ENCODING,
    formatterRule: `Write allowed plain query params on ${listPath}; backend rewrites them into q.`,
    defaultParams,
    allowedPlainParams,
    toolParamAliases,
    dateRangeParamAliases
  };
}
