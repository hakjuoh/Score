import {HttpContext, HttpContextToken} from '@angular/common/http';

export const SCORE_REQUEST_TYPE = new HttpContextToken<string>(() => 'UI_HTTP_REQUEST');

export function scoreRequest(type: string): HttpContext {
  return new HttpContext().set(SCORE_REQUEST_TYPE, type);
}
