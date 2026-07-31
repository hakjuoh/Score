/**
 * Registers and cancels cleanup callbacks for in-flight confirmed change requests.
 */

import {Injectable} from '@angular/core';

/**
 * External lifecycle coordinator for grant-bearing HTTP attempts.
 *
 * The component retains only a request ID. The callback-local subscription is
 * kept behind this private boundary until completion, canonical cancellation,
 * the absolute deadline, or component destruction.
 */
@Injectable()
export class AiConfirmedChangeRequestCoordinator {
  #cleanups = new Map<string, () => void>();

  register(requestId: string, cleanup: () => void): () => void {
    if (!requestId.trim()) {
      throw new Error('requestId must not be blank.');
    }
    this.cancel(requestId);
    this.#cleanups.set(requestId, cleanup);
    return () => {
      if (this.#cleanups.get(requestId) === cleanup) {
        this.#cleanups.delete(requestId);
      }
    };
  }

  cancel(requestId: string | undefined): void {
    if (!requestId) {
      return;
    }
    const cleanup = this.#cleanups.get(requestId);
    if (!cleanup) {
      return;
    }
    this.#cleanups.delete(requestId);
    cleanup();
  }
}
