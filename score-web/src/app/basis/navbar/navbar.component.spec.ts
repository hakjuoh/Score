import {NavbarComponent} from './navbar.component';
import {Subject, Subscription, of} from 'rxjs';

describe('NavbarComponent', () => {
  it('should be defined', () => {
    expect(NavbarComponent).toBeTruthy();
  });

  it('switches the signed-in user topic and stops listening when destroyed', () => {
    const component = Object.create(NavbarComponent.prototype) as NavbarComponent;
    const userMessages = new Subject<{body: string}>();
    const nextUserMessages = new Subject<{body: string}>();
    const pageMessages = new Subject<{body: string}>();
    const identities = new Subject<string | undefined>();
    const reloadNotiCount = vi.fn();
    const loadWebPageInfo = vi.fn(() => of(undefined));
    Object.assign(component as any, {
      subscriptions: new Subscription(),
      auth: {
        getUserToken: () => ({username: 'oagis', enabled: true}),
        sessionIdentityChanges$: identities.asObservable()
      },
      stompService: {
        watch: (destination: string) => destination === '/topic/message/oagis'
          ? userMessages
          : destination === '/topic/message/test_dev' ? nextUserMessages : pageMessages
      },
      webPageInfo: {load: loadWebPageInfo},
      elementRef: {nativeElement: null},
      ensureDefaultLibrarySelection: vi.fn(),
      reloadNotiCount,
      refreshBranding: vi.fn()
    });

    component.ngOnInit();
    userMessages.next({body: JSON.stringify({messageId: 1})});
    pageMessages.next({body: '{}'});

    expect(reloadNotiCount).toHaveBeenCalledTimes(2);
    expect(loadWebPageInfo).toHaveBeenCalledTimes(2);

    identities.next(undefined);
    userMessages.next({body: JSON.stringify({messageId: 2})});
    expect(reloadNotiCount).toHaveBeenCalledTimes(2);

    identities.next('test_dev');
    nextUserMessages.next({body: JSON.stringify({messageId: 3})});
    expect(reloadNotiCount).toHaveBeenCalledTimes(3);

    component.ngOnDestroy();
    nextUserMessages.next({body: JSON.stringify({messageId: 4})});
    pageMessages.next({body: '{}'});

    expect(reloadNotiCount).toHaveBeenCalledTimes(3);
    expect(loadWebPageInfo).toHaveBeenCalledTimes(2);
  });

  describe('Responsive Navbar & Sidenav Drawer', () => {
    it('should compute isCompact based on navbarWidth', () => {
      const component = Object.create(NavbarComponent.prototype);

      component.navbarWidth = 1400;
      expect(component.isCompact).toBe(false);

      component.navbarWidth = 1100;
      expect(component.isCompact).toBe(false);

      component.navbarWidth = 1099;
      expect(component.isCompact).toBe(true);

      component.navbarWidth = 800;
      expect(component.isCompact).toBe(true);
    });

    it('should compute showFullUserRole based on navbarWidth', () => {
      const component = Object.create(NavbarComponent.prototype);

      component.navbarWidth = 800;
      expect(component.showFullUserRole).toBe(true);

      component.navbarWidth = 740;
      expect(component.showFullUserRole).toBe(false);
    });

    it('should compute showNistLogo based on navbarWidth', () => {
      const component = Object.create(NavbarComponent.prototype);

      component.navbarWidth = 1200;
      expect(component.showNistLogo).toBe(true);

      component.navbarWidth = 1099;
      expect(component.showNistLogo).toBe(false);
    });

    it('should toggle, open, and close the drawer properly', () => {
      const component = Object.create(NavbarComponent.prototype);
      component.isDrawerOpen = false;

      component.toggleDrawer();
      expect(component.isDrawerOpen).toBe(true);

      component.toggleDrawer();
      expect(component.isDrawerOpen).toBe(false);

      component.openDrawer();
      expect(component.isDrawerOpen).toBe(true);

      component.closeDrawer();
      expect(component.isDrawerOpen).toBe(false);
    });

    it('should close the drawer on escape keypress', () => {
      const component = Object.create(NavbarComponent.prototype);
      component.isDrawerOpen = true;

      component.onEscape();
      expect(component.isDrawerOpen).toBe(false);
    });
  });
});
