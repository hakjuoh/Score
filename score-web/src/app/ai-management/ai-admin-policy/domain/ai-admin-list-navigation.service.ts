import {Location} from '@angular/common';
import {inject, Injectable} from '@angular/core';
import {ActivatedRoute, ParamMap, Router} from '@angular/router';
import {SearchBarComponent} from '../../../common/search-bar/search-bar.component';

/** Keeps AI administration list state consistent across navigation and browser history. */
@Injectable()
export class AiAdminListNavigationService {
  private readonly location = inject(Location);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  get queryParamMap(): ParamMap {
    return this.route.snapshot.queryParamMap;
  }

  restoreAdvancedSearch(searchBar: SearchBarComponent): void {
    searchBar.showAdvancedSearch = this.route.snapshot.queryParamMap.get('adv_ser') === 'true';
  }

  replaceState(request: {toQuery(): string}, searchBar: SearchBarComponent): void {
    const path = this.router.url.split('?')[0];
    this.location.replaceState(path,
      `${request.toQuery()}&adv_ser=${searchBar.showAdvancedSearch}`);
  }
}
