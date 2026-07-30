import {HttpClient, HttpContext, HttpParams} from '@angular/common/http';
import {Injectable, inject} from '@angular/core';
import {map, Observable, timeout} from 'rxjs';
import {HANDLE_HTTP_ERROR_LOCALLY} from '../../../authentication/auth.service';
import {REQUEST_STATUS_TIMEOUT_MS} from './ai-chat-panel.constants';
import {
  AiChatConversationSummary,
  AiChatConversationDetails,
  AiChatModelInfo,
  AiMcpStatusResponse,
  AiReasoningEffortInfo,
  AiCancellationCommand,
  AiCancellationResponse,
  AiChangeConfirmationDecisionResponse,
  AiPublicExecutionRequestStatus,
  AiChatRequest,
  AiChatRestResponse,
  AiConversationModelResponse,
  normalizeAiReasoningEffort
} from './ai-chat-panel.model';

interface AiChatModelWire {
  name: string;
  displayName: string;
  description?: string;
  provider: string;
  defaultModel: boolean;
  defaultReasoningEffort: string;
  reasoningEfforts?: Array<AiReasoningEffortInfo | string>;
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
    return this.http.post<AiChatRestResponse>('/api/ai/chat', request, this.localErrorHandling());
  }

  decideChangeConfirmation(
    conversationId: string,
    confirmationRequestId: string,
    decision: 'APPROVE' | 'DENY',
    revisionPrompt?: string
  ): Observable<AiChangeConfirmationDecisionResponse> {
    if (!conversationId.trim() || !confirmationRequestId.trim()) {
      throw new Error('A conversationId and confirmationRequestId are required.');
    }
    return this.http.post<AiChangeConfirmationDecisionResponse>(
      '/api/ai/chat/conversations/' + encodeURIComponent(conversationId)
      + '/change-confirmations/' + encodeURIComponent(confirmationRequestId)
      + '/decision',
      {decision, ...(revisionPrompt ? {revisionPrompt} : {})},
      this.localErrorHandling()
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
      '/api/ai/chat/' + encodeURIComponent(requestId) + '/cancel', command,
      this.localErrorHandling()
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
      '/api/ai/chat/' + encodeURIComponent(requestId) + '/status', {
        params,
        context: this.localErrorHandling().context
      }
      // Every caller treats this poll as a liveness probe. A blocked backend
      // must surface as an error so recovery runs instead of waiting forever.
    ).pipe(timeout(REQUEST_STATUS_TIMEOUT_MS));
  }

  getActiveRequest(): Observable<AiPublicExecutionRequestStatus | null> {
    return this.http.get<AiPublicExecutionRequestStatus | null>(
      '/api/ai/chat/active-request', this.localErrorHandling()
    );
  }

  getConversationHistory(): Observable<AiChatConversationSummary[]> {
    return this.http.get<AiChatConversationSummary[]>(
      '/api/ai/chat/conversations', this.localErrorHandling()
    );
  }

  getAvailableModels(): Observable<AiChatModelInfo[]> {
    return this.http.get<AiChatModelWire[]>(
      '/api/ai/chat/models', this.localErrorHandling()
    ).pipe(
      map(models => models.map(model => ({
        ...model,
        defaultReasoningEffort: normalizeAiReasoningEffort(model.defaultReasoningEffort),
        description: model.description?.trim() || this.defaultModelDescription(model.provider),
        reasoningEfforts: (model.reasoningEfforts || []).map(effort => this.normalizeReasoningEffort(effort)),
        contextWindow: this.positiveIntegerOrNull(model.contextWindow),
        outputReserveTokens: this.nonNegativeIntegerOrNull(model.outputReserveTokens),
        autoCompactThresholdTokens: this.positiveIntegerOrNull(model.autoCompactThresholdTokens),
        emergencyHeadroomTokens: this.nonNegativeIntegerOrNull(model.emergencyHeadroomTokens)
      })))
    );
  }

  getMcpStatus(): Observable<AiMcpStatusResponse> {
    return this.http.get<AiMcpStatusResponse>(
      '/api/ai/chat/mcp', this.localErrorHandling()
    );
  }

  updateConversationModel(conversationId: string, modelName: string,
                          reasoningEffort: string): Observable<AiConversationModelResponse> {
    if (!conversationId.trim() || !modelName.trim() || !reasoningEffort.trim()) {
      throw new Error('A conversationId, modelName, and reasoningEffort are required.');
    }
    return this.http.patch<AiConversationModelResponse>(
      '/api/ai/chat/conversations/' + encodeURIComponent(conversationId) + '/model',
      {modelName, reasoningEffort},
      this.localErrorHandling()
    ).pipe(
      map(response => ({
        ...response,
        reasoningEffort: normalizeAiReasoningEffort(response.reasoningEffort)
      }))
    );
  }

  getConversation(conversationId: string): Observable<AiChatConversationDetails> {
    return this.http.get<AiChatConversationDetails>(
      '/api/ai/chat/conversations/' + encodeURIComponent(conversationId),
      this.localErrorHandling()
    );
  }

  deleteConversation(conversationId: string): Observable<{deleted?: boolean}> {
    return this.http.delete<{deleted?: boolean}>(
      '/api/ai/chat/conversations/' + encodeURIComponent(conversationId),
      this.localErrorHandling()
    );
  }

  private localErrorHandling(): {context: HttpContext} {
    return {
      context: new HttpContext().set(HANDLE_HTTP_ERROR_LOCALLY, true)
    };
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
      const name = normalizeAiReasoningEffort(effort.name);
      return {
        ...effort,
        name,
        displayName: name === 'disabled' ? 'Disabled' : effort.displayName
      };
    }
    const name = normalizeAiReasoningEffort(effort);
    return {
      name,
      displayName: name === 'xhigh' || name === 'max'
        ? 'Extra High' : name === 'disabled' ? 'Disabled' : name.charAt(0).toUpperCase() + name.slice(1),
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
      case 'disabled': return 'Reasoning is disabled for this session.';
      case 'low': return 'Fast responses with lighter reasoning.';
      case 'medium': return 'Balances speed and reasoning depth for everyday tasks.';
      case 'high': return 'Greater reasoning depth for complex problems.';
      case 'xhigh':
      case 'max': return 'Maximum reasoning depth for the most complex problems.';
      default: return 'Configurable reasoning depth.';
    }
  }
}
