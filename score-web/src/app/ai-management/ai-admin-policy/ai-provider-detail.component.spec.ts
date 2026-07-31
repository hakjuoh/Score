import {TestBed} from '@angular/core/testing';
import {ActivatedRoute, Router, convertToParamMap} from '@angular/router';
import {MatSnackBar} from '@angular/material/snack-bar';
import {Subject, of, throwError} from 'rxjs';
import {ConfirmDialogService} from '../../common/confirm-dialog/confirm-dialog.service';
import {AiProviderDetailComponent} from './ai-provider-detail.component';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiProviderView} from './domain/ai-admin-policy';

describe('AiProviderDetailComponent', () => {
  const provider: AiProviderView = {aiProviderId: 7, providerName: 'OpenAI',
    providerType: 'openai', baseUrl: 'https://api.openai.com', messagesUrl: null,
    anthropicVersion: null, apiVersion: null, enabled: true, apiKeyConfigured: false,
    catalogVersion: 3};

  it('offers a conflict reload and refreshes the catalog version', () => {
    const reload = new Subject<void>();
    const service = {provider: vi.fn(() => of(provider)), updateProvider: vi.fn(() =>
      throwError(() => ({status: 409, error: {message: 'Version conflict'}})))};
    const snackBar = {open: vi.fn(() => ({onAction: () => reload}))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}},
      {provide: MatSnackBar, useValue: snackBar},
      {provide: ConfirmDialogService, useValue: {}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());
    component.ngOnInit();
    component.form.reason = 'Update provider endpoint';

    component.save();
    expect(component.message).toBe('Version conflict');
    expect(snackBar.open).toHaveBeenCalledWith('The provider changed elsewhere.', 'Reload',
      {duration: 5000});
    reload.next();
    expect(service.provider).toHaveBeenCalledTimes(2);
  });

  it('requires confirmation for key replacement and clears the write-only key after success', () => {
    const config: any = {data: {}};
    const service = {provider: vi.fn(() => of(provider)), rotateProviderKey: vi.fn(() => of({...provider, apiKeyConfigured: true}))};
    const dialog = {newConfig: vi.fn(() => config), open: vi.fn()
      .mockReturnValueOnce({afterClosed: () => of(false)})
      .mockReturnValueOnce({afterClosed: () => of(true)})};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service},
      {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}}, {provide: MatSnackBar, useValue: {open: vi.fn()}},
      {provide: ConfirmDialogService, useValue: dialog}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());
    component.ngOnInit(); component.apiKey = 'secret'; component.form.reason = 'Rotate provider key';
    component.confirmRotateKey();
    expect(service.rotateProviderKey).not.toHaveBeenCalled();
    component.confirmRotateKey();
    expect(service.rotateProviderKey).toHaveBeenCalledTimes(1);
    expect(component.apiKey).toBe('');
  });

  it('requires confirmation to remove a key and clears key state on success', () => {
    const configured = {...provider, apiKeyConfigured: true};
    const service = {provider: vi.fn(() => of(configured)), removeProviderKey: vi.fn(() => of(provider))};
    const dialog = {newConfig: () => ({data: {}}), open: vi.fn()
      .mockReturnValueOnce({afterClosed: () => of(false)}).mockReturnValueOnce({afterClosed: () => of(true)})};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service}, {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}}, {provide: MatSnackBar, useValue: {open: vi.fn()}}, {provide: ConfirmDialogService, useValue: dialog}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent());
    component.ngOnInit(); component.apiKey = 'discard'; component.form.reason = 'Remove provider key';
    component.confirmRemoveKey(); expect(service.removeProviderKey).not.toHaveBeenCalled();
    component.confirmRemoveKey(); expect(service.removeProviderKey).toHaveBeenCalledTimes(1);
    expect(component.provider!.apiKeyConfigured).toBe(false); expect(component.apiKey).toBe('');
  });

  it('shows a safe server error when disabling a provider is rejected', () => {
    const service = {provider: () => of(provider), updateProvider: vi.fn(() => throwError(() => ({status: 400, error: {message: 'Active models use this provider.'}})))};
    TestBed.configureTestingModule({providers: [
      {provide: AiAdminPolicyService, useValue: service}, {provide: ActivatedRoute, useValue: {snapshot: {paramMap: convertToParamMap({id: '7'})}}},
      {provide: Router, useValue: {navigate: vi.fn()}}, {provide: MatSnackBar, useValue: {open: vi.fn()}}, {provide: ConfirmDialogService, useValue: {}}
    ]});
    const component = TestBed.runInInjectionContext(() => new AiProviderDetailComponent()); component.ngOnInit();
    component.form.enabled = false; component.form.reason = 'Disable provider now'; component.save();
    expect(component.message).toBe('Active models use this provider.'); expect(component.form.enabled).toBe(false);
  });
});
