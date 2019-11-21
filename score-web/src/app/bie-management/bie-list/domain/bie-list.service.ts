import {HttpClient, HttpParams} from '@angular/common/http';
import {Injectable} from '@angular/core';
import {Observable} from 'rxjs';
import {BieList, BieListRequest} from './bie-list';
import {PageResponse} from '../../../basis/basis';
import {BusinessContext} from '../../../context-management/business-context/domain/business-context';

@Injectable()
export class BieListService {
  constructor(private http: HttpClient) {
  }

  getBieListWithRequest(request: BieListRequest): Observable<PageResponse<BieList>> {
    let params = new HttpParams()
      .set('sortActive', request.page.sortActive)
      .set('sortDirection', request.page.sortDirection)
      .set('pageIndex', '' + request.page.pageIndex)
      .set('pageSize', '' + request.page.pageSize);
    if (request.ownerLoginIds.length > 0) {
      params = params.set('ownerLoginIds', request.ownerLoginIds.join(','));
    }
    if (request.updaterLoginIds.length > 0) {
      params = params.set('updaterLoginIds', request.updaterLoginIds.join(','));
    }
    if (request.updatedDate.start) {
      params = params.set('updateStart', '' + request.updatedDate.start.getTime());
    }
    if (request.updatedDate.end) {
      params = params.set('updateEnd', '' + request.updatedDate.end.getTime());
    }
    if (request.filters.propertyTerm) {
      params = params.set('propertyTerm', request.filters.propertyTerm);
    }
    if (request.filters.businessContext) {
      params = params.set('businessContext', request.filters.businessContext);
    }
    if (request.states.length > 0) {
      params = params.set('states', request.states.join(','));
    }
    if (request.access) {
      params = params.set('access', request.access);
    }
    if (request.excludes.length > 0) {
      params = params.set('excludes', request.excludes.join(','));
    }
    return this.http.get<PageResponse<BieList>>('/api/bie_list', {params: params});
  }

  findBizCtxFromAbieId(id): Observable<BusinessContext> {
    return this.http.get<BusinessContext>('/api/profile_bie/business_ctx_from_abie/' + id);
  }

  getBieList(): Observable<BieList[]> {
    return this.http.get<BieList[]>('/api/profile_bie_list');
  }

  getBieListExcludeJsonRelated(): Observable<BieList[]> {
    return this.http.get<BieList[]>('/api/profile_bie_list?exclude_json_related=true');
  }

  getMetaHeaderBieList(): Observable<BieList[]> {
    return this.http.get<BieList[]>('/api/profile_bie_list/meta_header');
  }

  getPaginationResponseBieList(): Observable<BieList[]> {
    return this.http.get<BieList[]>('/api/profile_bie_list/pagination_response');
  }

  getBieListByBizCtxId(id): Observable<BieList[]> {
    return this.http.get<BieList[]>('/api/profile_bie_list?biz_ctx_id=' + id);
  }

  delete(topLevelAbieIds: number[]): Observable<any> {
    return this.http.post<any>('/api/profile_bie_list/delete', {
      topLevelAbieIds: topLevelAbieIds
    });
  }

  transferOwnership(topLevelAbieId: number, targetLoginId: string): Observable<any> {
    return this.http.post<any>('/api/profile_bie/' + topLevelAbieId + '/transfer_ownership', {
      targetLoginId: targetLoginId
    });
  }
}