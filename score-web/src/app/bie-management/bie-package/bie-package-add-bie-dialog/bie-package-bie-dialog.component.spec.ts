import {ComponentFixture, fakeAsync, TestBed} from '@angular/core/testing';
import {BiePackageBieDialogComponent} from './bie-package-bie-dialog.component';

describe('BiePackageAddBieDialogComponent', () => {
  let component: BiePackageBieDialogComponent;
  let fixture: ComponentFixture<BiePackageBieDialogComponent>;

  beforeEach(fakeAsync(() => {
    TestBed.configureTestingModule({
      declarations: [BiePackageBieDialogComponent]
    })
        .compileComponents();
  }));

  beforeEach(() => {
    fixture = TestBed.createComponent(BiePackageBieDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});