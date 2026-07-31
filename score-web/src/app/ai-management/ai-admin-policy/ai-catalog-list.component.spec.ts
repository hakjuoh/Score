import {TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiProviderListComponent} from './ai-provider-list.component';
import {AiModelListComponent} from './ai-model-list.component';

describe('AI catalog list retries', () => {
  it('retries a failed provider catalog load', () => {
    let attempts = 0;
    TestBed.configureTestingModule({providers: [{provide: AiAdminPolicyService, useValue: {providers: () => ++attempts === 1 ? throwError(() => new Error()) : of([])}}]});
    const component = TestBed.runInInjectionContext(() => new AiProviderListComponent()); component.ngOnInit();
    expect(component.loadFailed).toBe(true); component.load(); expect(component.loadFailed).toBe(false); expect(attempts).toBe(2);
  });

  it('retries a failed model catalog load', () => {
    let attempts = 0;
    TestBed.configureTestingModule({providers: [{provide: AiAdminPolicyService, useValue: {models: () => ++attempts === 1 ? throwError(() => new Error()) : of([])}}]});
    const component = TestBed.runInInjectionContext(() => new AiModelListComponent()); component.ngOnInit();
    expect(component.loadFailed).toBe(true); component.load(); expect(component.loadFailed).toBe(false); expect(attempts).toBe(2);
  });
});
