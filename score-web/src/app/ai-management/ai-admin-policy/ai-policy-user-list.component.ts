import {Component, OnInit, inject} from '@angular/core';
import {AiAdminPolicyService} from './domain/ai-admin-policy.service';
import {AiPolicyUserSummary} from './domain/ai-admin-policy';

@Component({
  standalone: false,
  selector: 'score-ai-policy-user-list',
  templateUrl: './ai-policy-user-list.component.html',
  styleUrls: ['./ai-admin-policy.component.css']
})
export class AiPolicyUserListComponent implements OnInit {
  private readonly policies = inject(AiAdminPolicyService);

  users: AiPolicyUserSummary[] = [];
  filter = '';
  loading = true;
  loadFailed = false;
  accessFilter = 'ALL';
  inheritanceFilter = 'ALL';
  quotaFilter = 'ALL';
  multiAgentFilter = 'ALL';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadFailed = false;
    this.policies.users().subscribe({
      next: users => {
        this.users = users;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.loadFailed = true;
      }
    });
  }

  get filteredUsers(): AiPolicyUserSummary[] {
    const query = this.filter.trim().toLowerCase();
    return this.users.filter(user => (!query || [user.loginId, user.name, user.organization]
      .some(value => value?.toLowerCase().includes(query)))
      && (this.accessFilter === 'ALL' || user.enabled === (this.accessFilter === 'ENABLED'))
      && (this.inheritanceFilter === 'ALL' || user.inherited === (this.inheritanceFilter === 'INHERITED'))
      && (this.multiAgentFilter === 'ALL' || user.multiAgentEnabled === (this.multiAgentFilter === 'ENABLED'))
      && (this.quotaFilter === 'ALL' || this.matchesQuota(user)));
  }

  private matchesQuota(user: AiPolicyUserSummary): boolean {
    if (user.quotaLimitTokens == null) return false;
    const used = user.quotaConsumedTokens + user.quotaReservedTokens;
    if (this.quotaFilter === 'EXHAUSTED') return used >= user.quotaLimitTokens;
    return used >= user.quotaLimitTokens * 0.8 && used < user.quotaLimitTokens;
  }
}
