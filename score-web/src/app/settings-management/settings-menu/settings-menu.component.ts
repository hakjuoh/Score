import { Component, OnInit, HostAttributeToken, inject, ChangeDetectionStrategy } from '@angular/core';
import {AuthService} from '../../authentication/auth.service';

@Component({
  standalone: false,
  selector: 'score-settings-menu',
  templateUrl: './settings-menu.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrls: ['./settings-menu.component.css']
})
export class SettingsMenuComponent implements OnInit {
  active = inject(new HostAttributeToken('active'), { optional: true });
  private auth = inject(AuthService);


  ngOnInit(): void {
  }

  get isAdmin(): boolean {
    return this.auth.isAdmin();
  }

}
