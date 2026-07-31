import {MatSnackBar} from '@angular/material/snack-bar';

const SUCCESS_DURATION_MS = 3000;
const ERROR_DURATION_MS = 5000;

export function notifyAiAdminSuccess(snackBar: MatSnackBar, message: string): void {
  snackBar.open(message, '', {duration: SUCCESS_DURATION_MS});
}

export function notifyAiAdminError(snackBar: MatSnackBar, message: string): void {
  snackBar.open(message, '', {duration: ERROR_DURATION_MS});
}

export function notifyAiAdminConflict(snackBar: MatSnackBar, message: string,
                                      reload: () => void): void {
  const notice = snackBar.open(message, 'Reload', {duration: ERROR_DURATION_MS});
  notice.onAction().subscribe(reload);
}
