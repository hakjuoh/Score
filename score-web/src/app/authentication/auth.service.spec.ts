import {
  HttpContext,
  HttpErrorResponse,
  HttpHandler,
  HttpRequest
} from '@angular/common/http';
import {Clipboard} from '@angular/cdk/clipboard';
import {TestBed} from '@angular/core/testing';
import {MatSnackBar} from '@angular/material/snack-bar';
import {Router} from '@angular/router';
import {firstValueFrom, throwError} from 'rxjs';
import {
  AuthService,
  ErrorAlertInterceptor,
  HANDLE_HTTP_ERROR_LOCALLY
} from './auth.service';

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
