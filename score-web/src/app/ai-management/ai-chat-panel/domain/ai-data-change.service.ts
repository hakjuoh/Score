import {Injectable} from '@angular/core';
import {Observable, Subject} from 'rxjs';

export interface AiDataChangedEvent {
  resource: string;
  action?: string;
  targetPath?: string;
  ids?: string[];
}

@Injectable({
  providedIn: 'root'
})
export class AiDataChangeService {
  private dataChangedSubject = new Subject<AiDataChangedEvent>();

  get dataChanged$(): Observable<AiDataChangedEvent> {
    return this.dataChangedSubject.asObservable();
  }

  notify(event: AiDataChangedEvent): void {
    this.dataChangedSubject.next(event);
  }
}
