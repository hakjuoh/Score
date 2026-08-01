import {convertToParamMap} from '@angular/router';
import {
  AiModelListRequest,
  AiPolicyUserListRequest,
  AiProviderListRequest
} from './ai-admin-policy';

describe('AI administration list URL state', () => {
  const restore = <T>(request: {toQuery(): string}, create: (q: string) => T): T =>
    create(request.toQuery().substring(2));

  it('round-trips provider filters, dates, sort, and page', () => {
    const request = new AiProviderListRequest();
    request.filters = {name: 'Claude', type: 'anthropic', endpoint: '/messages',
      enabled: [true], updaterLoginIdList: ['admin', '!reviewer'],
      updatedAfter: new Date('2026-07-01T04:00:00.000Z'),
      updatedBefore: new Date('2026-08-01T04:00:00.000Z')};
    request.page.pageIndex = 3;
    request.page.pageSize = 25;

    const restored = restore(request,
      q => new AiProviderListRequest(convertToParamMap({q})));

    expect(restored.filters).toEqual(request.filters);
    expect(restored.page).toEqual(request.page);
  });

  it('round-trips model boolean and effort filters', () => {
    const request = new AiModelListRequest();
    request.filters.enabled = [true, false];
    request.filters.defaultModel = [false];
    request.filters.defaultEffort = 'high';
    request.filters.effort = 'medium';

    const restored = restore(request,
      q => new AiModelListRequest(convertToParamMap({q})));

    expect(restored.filters).toEqual(request.filters);
  });

  it('round-trips policy numeric and boolean filters', () => {
    const request = new AiPolicyUserListRequest();
    request.filters.enabled = [false];
    request.filters.model = 'GPT-5';
    request.filters.multiAgentEnabled = [true];
    request.filters.quotaTokens = 1000000;
    request.filters.activeRequests = 2;

    const restored = restore(request,
      q => new AiPolicyUserListRequest(convertToParamMap({q})));

    expect(restored.filters).toEqual(request.filters);
  });
});
