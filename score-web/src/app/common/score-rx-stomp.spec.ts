/**
 * Verifies that the shared STOMP connection follows the authenticated browser session.
 */

import {HttpClient} from '@angular/common/http';
import {Subject, of} from 'rxjs';
import {AuthService} from '../authentication/auth.service';
import {RxStompService} from './score-rx-stomp';

describe('RxStompService authenticated session lifecycle', () => {
  it('disconnects on logout and reconnects only after the next identity is stored', async () => {
    const identities = new Subject<string | undefined>();
    const auth = {
      sessionIdentityChanges$: identities.asObservable(),
      isLogoutInProgress: vi.fn(() => false),
      logout: vi.fn()
    } as unknown as AuthService;
    const http = {
      get: vi.fn(() => of({ready: true}))
    } as unknown as HttpClient;
    const service = new RxStompService(http, auth);
    const deactivate = vi.spyOn(service, 'deactivate').mockResolvedValue();
    const activate = vi.spyOn(service, 'activate').mockImplementation(() => undefined);

    identities.next(undefined);
    await Promise.resolve();

    expect(deactivate).toHaveBeenCalledWith({force: true});
    expect(activate).not.toHaveBeenCalled();

    identities.next('test_dev');
    await Promise.resolve();

    expect(deactivate).toHaveBeenCalledTimes(2);
    expect(activate).toHaveBeenCalledOnce();
  });

  it('does not reconnect an identity superseded while disconnection is pending', async () => {
    const identities = new Subject<string | undefined>();
    const auth = {
      sessionIdentityChanges$: identities.asObservable(),
      isLogoutInProgress: vi.fn(() => false),
      logout: vi.fn()
    } as unknown as AuthService;
    const http = {
      get: vi.fn(() => of({ready: true}))
    } as unknown as HttpClient;
    const service = new RxStompService(http, auth);
    const pendingDeactivations: Array<() => void> = [];
    vi.spyOn(service, 'deactivate').mockImplementation(() =>
      new Promise<void>(resolve => pendingDeactivations.push(resolve)));
    const activate = vi.spyOn(service, 'activate').mockImplementation(() => undefined);

    identities.next('oagis');
    identities.next('test_dev');

    pendingDeactivations[0]();
    await Promise.resolve();
    expect(activate).not.toHaveBeenCalled();

    pendingDeactivations[1]();
    await Promise.resolve();
    expect(activate).toHaveBeenCalledOnce();
  });
});
