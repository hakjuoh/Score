import {TestBed} from '@angular/core/testing';
import {AiDataChangeService} from './ai-data-change.service';
import {AiResourceRefreshService, AiResourceRefreshStrategy} from './ai-resource-refresh.service';

describe('AiResourceRefreshService', () => {
  it('notifies the business-context data change strategy', () => {
    const notify = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        AiResourceRefreshService,
        {provide: AiDataChangeService, useValue: {notify}}
      ]
    });
    const service = TestBed.inject(AiResourceRefreshService);

    const handled = service.refresh(
      {resource: 'business-context', listPath: '/context_management/business_context', idFields: [], linkableFields: [], label: 'Business Context', roles: []},
      {requestId: 'r1', type: 'system', subtype: 'data_changed', resource: 'business-context', action: 'update', ids: ['7']},
      '/context_management/business_context'
    );

    expect(handled).toBe(true);
    expect(notify).toHaveBeenCalledWith({
      resource: 'business-context',
      action: 'update',
      targetPath: '/context_management/business_context',
      ids: ['7']
    });
  });

  it('allows resource strategies to be registered without changing navigation code', () => {
    TestBed.configureTestingModule({
      providers: [
        AiResourceRefreshService,
        {provide: AiDataChangeService, useValue: {notify: vi.fn()}}
      ]
    });
    const service = TestBed.inject(AiResourceRefreshService);
    const strategy: AiResourceRefreshStrategy = {resource: 'custom-resource', refresh: vi.fn()};
    service.register(strategy);

    const handled = service.refresh(
      {resource: 'custom-resource', listPath: '/custom', idFields: [], linkableFields: [], label: 'Custom', roles: []},
      {requestId: 'r1', type: 'system', subtype: 'data_changed', resource: 'custom-resource'},
      '/custom'
    );

    expect(handled).toBe(true);
    expect(strategy.refresh).toHaveBeenCalledOnce();
  });
});
