import {inject, Injectable} from '@angular/core';
import {HttpClient, HttpParams} from '@angular/common/http';
import {Observable} from 'rxjs';
import {
  AiAdminModel,
  AiModelCommand,
  AiModelProfile,
  AiAdminUsage,
  AiProviderConnectionTestResult,
  AiProviderApiKeyView,
  AiProviderUpdate,
  AiProviderView,
  AiModelListRequest,
  AiPolicyUserListRequest,
  AiPolicyUpdate,
  AiPolicyUserSummary,
  AiPolicyView,
  AiProviderListRequest
} from './ai-admin-policy';
import {PageRequest, PageResponse} from '../../../basis/basis';

@Injectable()
export class AiAdminPolicyService {
  private readonly http = inject(HttpClient);

  users(): Observable<AiPolicyUserSummary[]> {
    return this.http.get<AiPolicyUserSummary[]>('/api/admin/ai/users');
  }

  searchUsers(request: AiPolicyUserListRequest): Observable<PageResponse<AiPolicyUserSummary>> {
    let params = this.pageParams(request.page);
    params = this.text(params, 'loginId', request.filters.loginId);
    params = this.text(params, 'name', request.filters.name);
    params = this.text(params, 'organization', request.filters.organization);
    params = this.value(params, 'enabled', this.singleBoolean(request.filters.enabled));
    params = this.value(params, 'modelCount', request.filters.modelCount);
    params = this.value(params, 'multiAgentEnabled',
      this.singleBoolean(request.filters.multiAgentEnabled));
    params = this.text(params, 'quota', request.filters.quota);
    params = this.value(params, 'activeRequests', request.filters.activeRequests);
    params = this.list(params, 'updaterLoginIdList', request.filters.updaterLoginIdList);
    params = this.date(params, 'updatedAfter', request.filters.updatedAfter);
    params = this.date(params, 'updatedBefore', request.filters.updatedBefore, true);
    return this.http.get<PageResponse<AiPolicyUserSummary>>('/api/admin/ai/users/search', {params});
  }

  models(): Observable<AiAdminModel[]> {
    return this.http.get<AiAdminModel[]>('/api/admin/ai/models');
  }

  searchModels(request: AiModelListRequest): Observable<PageResponse<AiAdminModel>> {
    let params = this.pageParams(request.page);
    params = this.text(params, 'model', request.filters.model);
    params = this.text(params, 'provider', request.filters.provider);
    params = this.value(params, 'enabled', this.singleBoolean(request.filters.enabled));
    params = this.value(params, 'defaultModel', this.singleBoolean(request.filters.defaultModel));
    params = this.text(params, 'defaultEffort', request.filters.defaultEffort);
    params = this.text(params, 'effort', request.filters.effort);
    params = this.list(params, 'updaterLoginIdList', request.filters.updaterLoginIdList);
    params = this.date(params, 'updatedAfter', request.filters.updatedAfter);
    params = this.date(params, 'updatedBefore', request.filters.updatedBefore, true);
    return this.http.get<PageResponse<AiAdminModel>>('/api/admin/ai/models/search', {params});
  }

  model(modelId: number): Observable<AiAdminModel> {
    return this.http.get<AiAdminModel>(`/api/admin/ai/models/${modelId}`);
  }

  modelProfiles(providerId: number): Observable<AiModelProfile[]> {
    return this.http.get<AiModelProfile[]>(
      `/api/admin/ai/providers/${providerId}/model-profiles`);
  }

  createModel(update: AiModelCommand): Observable<AiAdminModel> {
    return this.http.post<AiAdminModel>('/api/admin/ai/models', update);
  }

  updateModel(modelId: number, update: AiModelCommand): Observable<AiAdminModel> {
    return this.http.put<AiAdminModel>(`/api/admin/ai/models/${modelId}`, update);
  }

  usage(userId: string): Observable<AiAdminUsage> {
    return this.http.get<AiAdminUsage>(`/api/admin/ai/users/${userId}/usage`);
  }

  adjustQuota(userId: string, deltaTokens: number): Observable<AiAdminUsage> {
    return this.http.post<AiAdminUsage>(`/api/admin/ai/users/${userId}/quota-adjustments`,
      {deltaTokens});
  }

  cancelActiveRequests(userId: string): Observable<{cancelledRequests: number}> {
    return this.http.post<{cancelledRequests: number}>(
      `/api/admin/ai/users/${userId}/cancel-active-requests`, {});
  }

  policy(userId: string): Observable<AiPolicyView> {
    return this.http.get<AiPolicyView>(`/api/admin/ai/users/${userId}/policy`);
  }

  save(userId: string, update: AiPolicyUpdate): Observable<AiPolicyView> {
    return this.http.put<AiPolicyView>(`/api/admin/ai/users/${userId}/policy`, update);
  }

  reset(userId: string, expectedVersion: number): Observable<void> {
    return this.http.delete<void>(`/api/admin/ai/users/${userId}/policy`, {
      params: {expectedVersion}
    });
  }

  providers(): Observable<AiProviderView[]> {
    return this.http.get<AiProviderView[]>('/api/admin/ai/providers');
  }

  searchProviders(request: AiProviderListRequest): Observable<PageResponse<AiProviderView>> {
    let params = this.pageParams(request.page);
    params = this.text(params, 'name', request.filters.name);
    params = this.text(params, 'type', request.filters.type);
    params = this.text(params, 'endpoint', request.filters.endpoint);
    params = this.value(params, 'enabled', this.singleBoolean(request.filters.enabled));
    params = this.list(params, 'updaterLoginIdList', request.filters.updaterLoginIdList);
    params = this.date(params, 'updatedAfter', request.filters.updatedAfter);
    params = this.date(params, 'updatedBefore', request.filters.updatedBefore, true);
    return this.http.get<PageResponse<AiProviderView>>('/api/admin/ai/providers/search', {params});
  }

  provider(providerId: number): Observable<AiProviderView> {
    return this.http.get<AiProviderView>(`/api/admin/ai/providers/${providerId}`);
  }

  maskedProviderApiKey(providerId: number): Observable<AiProviderApiKeyView> {
    return this.http.get<AiProviderApiKeyView>(
      `/api/admin/ai/providers/${providerId}/api-key`);
  }

  revealProviderApiKey(providerId: number): Observable<AiProviderApiKeyView> {
    return this.http.post<AiProviderApiKeyView>(
      `/api/admin/ai/providers/${providerId}/api-key/reveal`, {});
  }

  createProvider(update: AiProviderUpdate): Observable<AiProviderView> {
    return this.http.post<AiProviderView>('/api/admin/ai/providers', update);
  }

  updateProvider(providerId: number, update: AiProviderUpdate): Observable<AiProviderView> {
    return this.http.put<AiProviderView>(`/api/admin/ai/providers/${providerId}`, update);
  }

  testProviderConnection(providerId: number,
                         update: AiProviderUpdate): Observable<AiProviderConnectionTestResult> {
    return this.http.post<AiProviderConnectionTestResult>(
      `/api/admin/ai/providers/${providerId}/connection-tests`, update);
  }

  private pageParams(page: PageRequest): HttpParams {
    let params = new HttpParams()
      .set('pageIndex', page.pageIndex)
      .set('pageSize', page.pageSize);
    if (page.sortActive && page.sortDirection) {
      params = params.set('orderBy', `${page.sortDirection === 'desc' ? '-' : '+'}${page.sortActive}`);
    }
    return params;
  }

  private text(params: HttpParams, name: string, value: string): HttpParams {
    return value?.trim() ? params.set(name, value.trim()) : params;
  }

  private list(params: HttpParams, name: string, values: string[]): HttpParams {
    return values?.length ? params.set(name, values.join(',')) : params;
  }

  private singleBoolean(values: boolean[]): boolean | null {
    return values?.length === 1 ? values[0] : null;
  }

  private value(params: HttpParams, name: string,
                value: boolean | number | null): HttpParams {
    return value == null ? params : params.set(name, value);
  }

  private date(params: HttpParams, name: string, value: Date | null,
               nextDay = false): HttpParams {
    if (!value) return params;
    const boundary = new Date(value);
    if (nextDay) boundary.setDate(boundary.getDate() + 1);
    return params.set(name, boundary.toISOString());
  }

}
