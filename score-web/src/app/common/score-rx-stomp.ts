import {Injectable} from '@angular/core';
import {HttpClient, HttpContext} from '@angular/common/http';
import {IFrame} from '@stomp/stompjs';
import {RxStomp, RxStompState} from '@stomp/rx-stomp';
import {catchError, filter, map, of, Subscription, switchMap, take, timer} from 'rxjs';
import {scoreRxStompConfig} from './score-rx-stomp-config';
import {AuthService, SUPPRESS_ERROR_ALERT} from '../authentication/auth.service';

interface GatewayHealthResponse {
  ready?: boolean;
}

@Injectable({
  providedIn: 'root',
})
export class RxStompService extends RxStomp {
  private healthPingSubscription?: Subscription;
  private connectionStateSubscription?: Subscription;
  private shouldReconnect = false;

  public constructor (private http: HttpClient, private auth: AuthService) {
    super();
    this.connectionStateSubscription = this.connectionState$.subscribe(state => {
      if (state === RxStompState.OPEN) {
        this.stopHealthPingLoop();
        return;
      }

      if (state === RxStompState.CLOSED && this.shouldReconnect && !this.active) {
        this.startHealthPingLoop();
      }
    });
    this.stompErrors$.subscribe(frame => this.handleStompError(frame));
  }

  override activate(): void {
    this.shouldReconnect = true;
    this.startHealthPingLoop();
  }

  override deactivate(options?: { force?: boolean }): Promise<void> {
    this.shouldReconnect = false;
    this.stopHealthPingLoop();
    return super.deactivate(options);
  }

  private startHealthPingLoop(): void {
    if (this.active || this.healthPingSubscription) {
      return;
    }

    this.healthPingSubscription = timer(0, 500).pipe(
      switchMap(() => this.checkGatewayHealth()),
      filter(Boolean),
      take(1)
    ).subscribe(() => {
      this.stopHealthPingLoop();
      if (this.shouldReconnect && !this.active) {
        super.activate();
      }
    });
  }

  private stopHealthPingLoop(): void {
    this.healthPingSubscription?.unsubscribe();
    this.healthPingSubscription = undefined;
  }

  private checkGatewayHealth() {
    return this.http.get<GatewayHealthResponse | null>('/api/health', {
      context: new HttpContext().set(SUPPRESS_ERROR_ALERT, true)
    }).pipe(
      map(response => response?.ready ?? true),
      catchError(() => of(false))
    );
  }

  private handleStompError(frame: IFrame): void {
    if (this.auth.isLogoutInProgress() || !this.isAuthenticationStompError(frame)) {
      return;
    }
    this.deactivate({force: true}).finally(() => {
      this.auth.logout(window.location.pathname);
    });
  }

  private isAuthenticationStompError(frame: IFrame): boolean {
    const detail = `${frame.headers?.['message'] || ''} ${frame.body || ''}`.toLowerCase();
    return detail.includes('authentication_failed') ||
      detail.includes('signed-in user') ||
      detail.includes('score user cannot be resolved') ||
      detail.includes('no longer valid') ||
      detail.includes('no longer exists') ||
      detail.includes('authenticationcredentialsnotfoundexception') ||
      detail.includes('disabledexception');
  }
}

export function rxStompServiceFactory(http: HttpClient, auth: AuthService) {
  const rxStomp = new RxStompService(http, auth);
  rxStomp.configure(scoreRxStompConfig);
  rxStomp.activate();
  return rxStomp;
}
