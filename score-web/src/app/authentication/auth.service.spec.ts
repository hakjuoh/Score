import {
  HttpContext,
  HttpErrorResponse,
  HttpHandler,
  HttpRequest,
  provideHttpClient
} from '@angular/common/http';
import {provideHttpClientTesting} from '@angular/common/http/testing';
import {Clipboard} from '@angular/cdk/clipboard';
import {TestBed} from '@angular/core/testing';
import {MatSnackBar} from '@angular/material/snack-bar';
import {Router} from '@angular/router';
import {firstValueFrom, throwError} from 'rxjs';
import {
  AuthService,
  ErrorAlertInterceptor,
  HANDLE_HTTP_ERROR_LOCALLY,
  XhrInterceptor
} from './auth.service';
import {of} from 'rxjs';
import {SCORE_REQUEST_TYPE} from '../common/score-request';
import {projectVersion} from '../../environments/version';

describe('XhrInterceptor SCORE request correlation', () => {
  it('adds a unique request identity, timestamp, and semantic request type', async () => {
    const interceptor = new XhrInterceptor();
    const request = new HttpRequest('POST', '/api/releases/42/draft', null, {
      context: new HttpContext().set(SCORE_REQUEST_TYPE, 'RELEASE_DRAFT')
    });
    let forwarded: HttpRequest<unknown>;
    const handler = {
      handle: (value: HttpRequest<unknown>) => {
        forwarded = value;
        return of({} as any);
      }
    } as HttpHandler;

    await firstValueFrom(interceptor.intercept(request, handler));

    expect(forwarded.headers.get('X-Score-Request-Type')).toBe('RELEASE_DRAFT');
    expect(forwarded.headers.get('X-Score-Request-Id')).toMatch(/^[0-9a-f-]{36}$/);
    expect(Date.parse(forwarded.headers.get('X-Score-Request-Timestamp'))).not.toBeNaN();
    expect(forwarded.headers.get('X-Score-Web-Version')).toBe(projectVersion);
    expect(forwarded.withCredentials).toBe(true);
  });
});

describe('AuthService.canUsePasskeys', () => {
  let service: AuthService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [AuthService, provideHttpClient(), provideHttpClientTesting(), {provide: Router, useValue: {}}]
    });
    service = TestBed.inject(AuthService);
  });

  function signedIn(authentication: string, passkeyEnabled: boolean | undefined) {
    service.storeUserInfo({
      username: 'alice', roles: ['end-user'], authentication, enabled: true,
      ...(passkeyEnabled === undefined ? {} : {passkey: {enabled: passkeyEnabled}})
    } as any);
  }

  it('is true for a password or passkey account when passkey sign-in is on', () => {
    signedIn('basic', true);
    expect(service.canUsePasskeys()).toBe(true);
    signedIn('passkey', true);
    expect(service.canUsePasskeys()).toBe(true);
  });

  it('is false when passkey sign-in is off', () => {
    signedIn('basic', false);
    expect(service.canUsePasskeys()).toBe(false);
  });

  it('is false for a single sign-on account, which uses its provider\'s passkeys', () => {
    signedIn('oauth2', true);
    expect(service.canUsePasskeys()).toBe(false);
  });

  it('is false for an older stored session that predates the setting', () => {
    signedIn('basic', undefined);
    expect(service.canUsePasskeys()).toBe(false);
  });

  it('is false when nobody is signed in', () => {
    expect(service.canUsePasskeys()).toBe(false);
  });
});

describe('ErrorAlertInterceptor local error handling', () => {
  let interceptor: ErrorAlertInterceptor;
  let auth: {
    isServiceUnavailableFailure: ReturnType<typeof vi.fn>;
    logout: ReturnType<typeof vi.fn>;
  };
  let snackBar: {
    open: ReturnType<typeof vi.fn>;
    openFromComponent: ReturnType<typeof vi.fn>;
  };
  let router: {navigate: ReturnType<typeof vi.fn>};

  beforeEach(() => {
    auth = {isServiceUnavailableFailure: vi.fn(() => false), logout: vi.fn()};
    snackBar = {open: vi.fn(), openFromComponent: vi.fn()};
    router = {navigate: vi.fn()};
    TestBed.configureTestingModule({providers: [
      {provide: AuthService, useValue: auth},
      {provide: Router, useValue: router},
      {provide: MatSnackBar, useValue: snackBar},
      {provide: Clipboard, useValue: {copy: vi.fn()}}
    ]});
    interceptor = TestBed.runInInjectionContext(() => new ErrorAlertInterceptor());
  });

  it('leaves a locally handled 500 for the feature without a global notification', async () => {
    const error = new HttpErrorResponse({status: 500, statusText: 'Server Error'});
    const request = new HttpRequest('GET', '/api/ai/chat/request-1/status', {
      context: new HttpContext().set(HANDLE_HTTP_ERROR_LOCALLY, true)
    });

    await expect(firstValueFrom(interceptor.intercept(
      request, failingHandler(error)
    ))).rejects.toBe(error);

    expect(snackBar.open).not.toHaveBeenCalled();
    expect(snackBar.openFromComponent).not.toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('still applies global authentication expiry handling to a local request', async () => {
    const error = new HttpErrorResponse({status: 401, statusText: 'Unauthorized'});
    const request = new HttpRequest('GET', '/api/ai/chat/request-1/status', {
      context: new HttpContext().set(HANDLE_HTTP_ERROR_LOCALLY, true)
    });

    await expect(firstValueFrom(interceptor.intercept(
      request, failingHandler(error)
    ))).rejects.toBe(error);

    expect(snackBar.open).toHaveBeenCalledWith(
      'Authentication Failure', '', {duration: 3000}
    );
    expect(auth.logout).toHaveBeenCalledWith(window.location.pathname);
  });

  it('does not open unrelated global alerts in the assistant popout', async () => {
    const originalUrl = window.location.href;
    window.history.pushState({}, '', '/?aiAssistantPopout=1');
    const error = new HttpErrorResponse({status: 500, statusText: 'Server Error'});
    const request = new HttpRequest('GET', '/api/messages/count-of-unread');

    try {
      await expect(firstValueFrom(interceptor.intercept(
        request, failingHandler(error)
      ))).rejects.toBe(error);
    } finally {
      window.history.pushState({}, '', new URL(originalUrl).pathname + new URL(originalUrl).search);
    }

    expect(snackBar.open).not.toHaveBeenCalled();
    expect(snackBar.openFromComponent).not.toHaveBeenCalled();
  });
});

function failingHandler(error: HttpErrorResponse): HttpHandler {
  return {handle: () => throwError(() => error)} as HttpHandler;
}
