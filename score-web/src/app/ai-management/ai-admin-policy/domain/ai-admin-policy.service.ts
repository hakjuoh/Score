import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Observable} from 'rxjs';
import {
  AiAdminModel,
  AiModelUpdate,
  AiAdminUsage,
  AiProviderUpdate,
  AiProviderView,
  AiPolicyUpdate,
  AiPolicyUserSummary,
  AiPolicyView
} from './ai-admin-policy';

@Injectable()
export class AiAdminPolicyService {
  private readonly http = inject(HttpClient);

  users(): Observable<AiPolicyUserSummary[]> {
    return this.http.get<AiPolicyUserSummary[]>('/api/admin/ai/users');
  }

  models(): Observable<AiAdminModel[]> {
    return this.http.get<AiAdminModel[]>('/api/admin/ai/models');
  }

  model(modelId: number): Observable<AiAdminModel> {
    return this.http.get<AiAdminModel>(`/api/admin/ai/models/${modelId}`);
  }

  createModel(update: AiModelUpdate): Observable<AiAdminModel> {
    return this.http.post<AiAdminModel>('/api/admin/ai/models', update);
  }

  updateModel(modelId: number, update: AiModelUpdate): Observable<AiAdminModel> {
    return this.http.put<AiAdminModel>(`/api/admin/ai/models/${modelId}`, update);
  }

  usage(userId: string): Observable<AiAdminUsage> {
    return this.http.get<AiAdminUsage>(`/api/admin/ai/users/${userId}/usage`);
  }

  adjustQuota(userId: string, deltaTokens: number, reason: string): Observable<AiAdminUsage> {
    return this.http.post<AiAdminUsage>(`/api/admin/ai/users/${userId}/quota-adjustments`,
      {deltaTokens, reason});
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

  reset(userId: string, expectedVersion: number, reason: string): Observable<void> {
    return this.http.delete<void>(`/api/admin/ai/users/${userId}/policy`, {
      params: {expectedVersion, reason}
    });
  }

  providers(): Observable<AiProviderView[]> {
    return this.http.get<AiProviderView[]>('/api/admin/ai/providers');
  }

  provider(providerId: number): Observable<AiProviderView> {
    return this.http.get<AiProviderView>(`/api/admin/ai/providers/${providerId}`);
  }

  createProvider(update: AiProviderUpdate): Observable<AiProviderView> {
    return this.http.post<AiProviderView>('/api/admin/ai/providers', update);
  }

  updateProvider(providerId: number, update: AiProviderUpdate): Observable<AiProviderView> {
    return this.http.put<AiProviderView>(`/api/admin/ai/providers/${providerId}`, update);
  }

}
