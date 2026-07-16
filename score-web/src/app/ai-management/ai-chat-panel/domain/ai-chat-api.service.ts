import {HttpClient, HttpParams} from '@angular/common/http';
import {Injectable, inject} from '@angular/core';
import {map, Observable} from 'rxjs';
import {
  AiChatConversationSummary,
  AiChatConversationDetails,
  AiChatModelInfo,
  AiChatRuntimeInfo,
  AiChatRuntimeSettingInfo,
  AiReasoningEffortInfo,
  AiCancellationCommand,
  AiCancellationResponse,
  AiMutationConfirmationDecisionResponse,
  AiPublicExecutionRequestStatus,
  AiChatRequest,
  AiChatRestResponse,
  AiConversationModelResponse,
  AiRuntimeOptions
} from './ai-chat-panel.model';

type AiChatRuntimeWire = Omit<AiChatRuntimeInfo, 'settings'> & {
  settings?: AiChatRuntimeSettingInfo[];
};

interface AiChatModelWire {
  name: string;
  displayName: string;
  description?: string;
  provider: string;
  defaultModel: boolean;
  defaultReasoningEffort: string;
  reasoningEfforts?: Array<AiReasoningEffortInfo | string>;
  defaultRuntime?: string;
  runtimes?: AiChatRuntimeWire[];
  contextWindow?: number | null;
  outputReserveTokens?: number | null;
  autoCompactThresholdTokens?: number | null;
  emergencyHeadroomTokens?: number | null;
}

@Injectable({
  providedIn: 'root'
})
export class AiChatApiService {

  private http = inject(HttpClient);

  sendChat(request: AiChatRequest): Observable<AiChatRestResponse> {
    return this.http.post<AiChatRestResponse>('/api/ai/chat', request);
  }

  decideMutationConfirmation(
    conversationId: string,
    confirmationRequestId: string,
    decision: 'APPROVE' | 'DENY',
    revisionPrompt?: string
  ): Observable<AiMutationConfirmationDecisionResponse> {
    if (!conversationId.trim() || !confirmationRequestId.trim()) {
      throw new Error('A conversationId and confirmationRequestId are required.');
    }
    return this.http.post<AiMutationConfirmationDecisionResponse>(
      '/api/ai/chat/conversations/' + encodeURIComponent(conversationId)
      + '/mutation-confirmations/' + encodeURIComponent(confirmationRequestId)
      + '/decision',
      {decision, ...(revisionPrompt ? {revisionPrompt} : {})}
    );
  }

  cancelRequest(requestId: string, command: AiCancellationCommand): Observable<AiCancellationResponse> {
    const hasConversation = !!command.conversationId?.trim();
    const hasGeneration = command.expectedGeneration !== undefined;
    if (!command.cancellationRequestId.trim()) {
      throw new Error('cancellationRequestId must not be blank.');
    }
    if (hasConversation !== hasGeneration
      || (hasGeneration && !this.isPositiveGeneration(command.expectedGeneration!))) {
      throw new Error('conversationId and a positive expectedGeneration must be supplied together.');
    }
    return this.http.post<AiCancellationResponse>(
      '/api/ai/chat/' + encodeURIComponent(requestId) + '/cancel', command
    );
  }

  getRequestStatus(requestId: string, conversationId: string,
                   expectedGeneration: number): Observable<AiPublicExecutionRequestStatus> {
    if (!conversationId.trim() || !this.isPositiveGeneration(expectedGeneration)) {
      throw new Error('A conversationId and positive expectedGeneration are required.');
    }
    const params = new HttpParams()
      .set('conversationId', conversationId)
      .set('expectedGeneration', expectedGeneration);
    return this.http.get<AiPublicExecutionRequestStatus>(
      '/api/ai/chat/' + encodeURIComponent(requestId) + '/status', {params}
    );
  }

  getActiveRequest(): Observable<AiPublicExecutionRequestStatus | null> {
    return this.http.get<AiPublicExecutionRequestStatus | null>('/api/ai/chat/active-request');
  }

  getConversationHistory(): Observable<AiChatConversationSummary[]> {
    return this.http.get<AiChatConversationSummary[]>('/api/ai/chat/conversations');
  }

  getAvailableModels(): Observable<AiChatModelInfo[]> {
    return this.http.get<AiChatModelWire[]>('/api/ai/chat/models').pipe(
      map(models => models.map(model => ({
        ...model,
        description: model.description?.trim() || this.defaultModelDescription(model.provider),
        reasoningEfforts: (model.reasoningEfforts || []).map(effort => this.normalizeReasoningEffort(effort)),
        defaultRuntime: model.defaultRuntime?.trim() || 'default',
        contextWindow: this.positiveIntegerOrNull(model.contextWindow),
        outputReserveTokens: this.nonNegativeIntegerOrNull(model.outputReserveTokens),
        autoCompactThresholdTokens: this.positiveIntegerOrNull(model.autoCompactThresholdTokens),
        emergencyHeadroomTokens: this.nonNegativeIntegerOrNull(model.emergencyHeadroomTokens),
        runtimes: model.runtimes?.length ? model.runtimes.map(runtime => ({
          ...runtime,
          settings: runtime.settings || []
        })) : [{
          name: 'default', displayName: 'Default', description: 'Uses the Default runtime.', settings: []
        }]
      })))
    );
  }

  updateConversationModel(conversationId: string, modelName: string,
                          reasoningEffort: string, runtime = 'default',
                          runtimeOptions: AiRuntimeOptions = {}): Observable<AiConversationModelResponse> {
    if (!conversationId.trim() || !modelName.trim() || !reasoningEffort.trim() || !runtime.trim()) {
      throw new Error('A conversationId, modelName, reasoningEffort, and runtime are required.');
    }
    return this.http.patch<AiConversationModelResponse>(
      '/api/ai/chat/conversations/' + encodeURIComponent(conversationId) + '/model',
      {modelName, reasoningEffort, runtime, runtimeOptions: {...runtimeOptions}}
    );
  }

  getConversation(conversationId: string): Observable<AiChatConversationDetails> {
    return this.http.get<AiChatConversationDetails>(
      '/api/ai/chat/conversations/' + encodeURIComponent(conversationId)
    );
  }

  deleteConversation(conversationId: string): Observable<{deleted?: boolean}> {
    return this.http.delete<{deleted?: boolean}>(
      '/api/ai/chat/conversations/' + encodeURIComponent(conversationId)
    );
  }

  private isPositiveGeneration(value: number): boolean {
    return Number.isSafeInteger(value) && value > 0;
  }

  private positiveIntegerOrNull(value: unknown): number | null {
    return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : null;
  }

  private nonNegativeIntegerOrNull(value: unknown): number | null {
    return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null;
  }

  private normalizeReasoningEffort(effort: AiReasoningEffortInfo | string): AiReasoningEffortInfo {
    if (typeof effort !== 'string') {
      return effort;
    }
    const name = effort.trim().toLowerCase();
    return {
      name,
      displayName: name === 'xhigh' || name === 'max'
        ? 'Max' : name.charAt(0).toUpperCase() + name.slice(1),
      description: this.defaultReasoningDescription(name)
    };
  }

  private defaultModelDescription(provider: string): string {
    return provider === 'azure-openai'
      ? 'Azure OpenAI model for complex reasoning and tool-driven work.'
      : 'Azure AI Foundry model for complex reasoning and tool-driven work.';
  }

  private defaultReasoningDescription(name: string): string {
    switch (name) {
      case 'low': return 'Fast responses with lighter reasoning.';
      case 'medium': return 'Balances speed and reasoning depth for everyday tasks.';
      case 'high': return 'Greater reasoning depth for complex problems.';
      case 'xhigh':
      case 'max': return 'Maximum reasoning depth for the most complex problems.';
      default: return 'Configurable reasoning depth.';
    }
  }
}
