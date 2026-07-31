/**
 * Verifies the AI Assistant Availability service contract, failure handling, and edge cases.
 */

import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {AiAssistantAvailabilityService} from './ai-assistant-availability.service';

describe('AiAssistantAvailabilityService', () => {
  let service: AiAssistantAvailabilityService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        AiAssistantAvailabilityService,
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    service = TestBed.inject(AiAssistantAvailabilityService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    service.ngOnDestroy();
    httpTesting.verify();
    vi.useRealTimers();
  });

  it('polls a transient deployment failure and stops after recovery', () => {
    service.load();
    httpTesting.expectOne('/api/info/ai-assistant').flush({
      enabled: false,
      reason: 'MODEL_PROVIDER_TEMPORARILY_UNAVAILABLE'
    });
    const recovery = httpTesting.expectOne('/api/ai/chat/availability/revalidate');
    expect(recovery.request.method).toBe('POST');
    recovery.flush({scheduled: true});
    expect(service.isAvailable()).toBe(false);

    service.load();
    httpTesting.expectNone('/api/info/ai-assistant');
    vi.advanceTimersByTime(30_000);
    httpTesting.expectOne('/api/info/ai-assistant').flush({enabled: true});
    expect(service.isAvailable()).toBe(true);

    vi.advanceTimersByTime(60_000);
    httpTesting.expectNone('/api/info/ai-assistant');
  });

  it('does not poll permanent configuration failures', () => {
    service.load();
    httpTesting.expectOne('/api/info/ai-assistant').flush({
      enabled: false,
      reason: 'MODEL_DEPLOYMENT_UNAVAILABLE'
    });

    vi.advanceTimersByTime(60_000);
    httpTesting.expectNone('/api/info/ai-assistant');
  });

  it('retries failed availability requests with bounded exponential backoff', () => {
    service.load();
    httpTesting.expectOne('/api/info/ai-assistant').flush('unavailable', {
      status: 503,
      statusText: 'Service Unavailable'
    });

    for (const delay of [30_000, 60_000, 120_000]) {
      vi.advanceTimersByTime(delay);
      httpTesting.expectOne('/api/info/ai-assistant').flush('unavailable', {
        status: 503,
        statusText: 'Service Unavailable'
      });
    }
    vi.advanceTimersByTime(240_000);
    httpTesting.expectNone('/api/info/ai-assistant');
  });

  it('cancels a scheduled recovery poll on reset', () => {
    service.load();
    httpTesting.expectOne('/api/info/ai-assistant').flush({
      enabled: false,
      reason: 'MODEL_PROVIDER_TEMPORARILY_UNAVAILABLE'
    });
    const recovery = httpTesting.expectOne('/api/ai/chat/availability/revalidate');

    service.reset();
    vi.advanceTimersByTime(60_000);

    httpTesting.expectNone('/api/info/ai-assistant');
    expect(recovery.cancelled).toBe(true);
    expect(service.isAvailable()).toBe(false);
  });
});
