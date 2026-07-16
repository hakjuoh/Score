import {HttpClient, HttpContext} from '@angular/common/http';
import {Injectable, OnDestroy, inject} from '@angular/core';
import {Subscription, of, timer} from 'rxjs';
import {catchError, finalize} from 'rxjs/operators';
import {SUPPRESS_ERROR_ALERT} from '../../../authentication/auth.service';

interface AiAssistantInfo {
  enabled: boolean;
  reason?: string;
}

@Injectable({
  providedIn: 'root'
})
export class AiAssistantAvailabilityService implements OnDestroy {

  private static readonly TRANSIENT_UNAVAILABLE_REASON = 'MODEL_PROVIDER_TEMPORARILY_UNAVAILABLE';
  private static readonly RECOVERY_POLL_MILLIS = 30_000;
  private static readonly MAX_AVAILABILITY_RETRIES = 3;

  private http = inject(HttpClient);
  private available = false;
  private loaded = false;
  private loading = false;
  private loadSubscription?: Subscription;
  private recoverySubscription?: Subscription;
  private recoveryRequestSubscription?: Subscription;
  private availabilityRetryCount = 0;

  isAvailable(): boolean {
    return this.available;
  }

  load(): void {
    if (this.loaded || this.loading) {
      return;
    }

    this.loading = true;
    this.loadSubscription = this.http.get<AiAssistantInfo>('/api/info/ai-assistant', {
      context: new HttpContext().set(SUPPRESS_ERROR_ALERT, true)
    }).pipe(
      catchError(() => of({enabled: false, reason: 'AVAILABILITY_CHECK_FAILED'})),
      finalize(() => {
        this.loaded = true;
        this.loading = false;
        this.loadSubscription = undefined;
      })
    ).subscribe(info => {
      this.available = !!info?.enabled;
      if (this.available) {
        this.availabilityRetryCount = 0;
        this.cancelRecoveryPoll();
        return;
      }
      if (info?.reason === AiAssistantAvailabilityService.TRANSIENT_UNAVAILABLE_REASON) {
        this.availabilityRetryCount = 0;
        this.requestRecoveryProbe();
        this.scheduleRecoveryPoll(AiAssistantAvailabilityService.RECOVERY_POLL_MILLIS);
        return;
      }
      if (info?.reason === 'AVAILABILITY_CHECK_FAILED'
        && this.availabilityRetryCount < AiAssistantAvailabilityService.MAX_AVAILABILITY_RETRIES) {
        const delay = AiAssistantAvailabilityService.RECOVERY_POLL_MILLIS
          * 2 ** this.availabilityRetryCount++;
        this.scheduleRecoveryPoll(delay);
        return;
      }
      this.cancelRecoveryPoll();
    });
  }

  reset(): void {
    this.loadSubscription?.unsubscribe();
    this.loadSubscription = undefined;
    this.recoveryRequestSubscription?.unsubscribe();
    this.recoveryRequestSubscription = undefined;
    this.cancelRecoveryPoll();
    this.available = false;
    this.loaded = false;
    this.loading = false;
    this.availabilityRetryCount = 0;
  }

  ngOnDestroy(): void {
    this.reset();
  }

  private requestRecoveryProbe(): void {
    if (this.recoveryRequestSubscription && !this.recoveryRequestSubscription.closed) {
      return;
    }
    this.recoveryRequestSubscription = this.http.post('/api/ai/chat/availability/revalidate', {}, {
      context: new HttpContext().set(SUPPRESS_ERROR_ALERT, true)
    }).pipe(
      catchError(() => of(null)),
      finalize(() => this.recoveryRequestSubscription = undefined)
    ).subscribe();
  }

  private scheduleRecoveryPoll(delay: number): void {
    if (this.recoverySubscription && !this.recoverySubscription.closed) {
      return;
    }
    this.recoverySubscription = timer(delay)
      .subscribe(() => {
        this.recoverySubscription = undefined;
        this.loaded = false;
        this.load();
      });
  }

  private cancelRecoveryPoll(): void {
    this.recoverySubscription?.unsubscribe();
    this.recoverySubscription = undefined;
  }
}
