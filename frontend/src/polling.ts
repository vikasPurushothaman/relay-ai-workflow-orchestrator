import { ApiError } from './api.ts';

/** Failure backoff from the console design: 5s, 10s, then a 30s ceiling; success resets it. */
export function backoffDelay(failures: number): number {
  return failures <= 1 ? 5_000 : failures === 2 ? 10_000 : 30_000;
}
/** Permanent client errors stop automatic polling; the user can still press Refresh. */
export function isPermanent(error: unknown): boolean {
  return error instanceof ApiError && error.kind === 'http' && error.status !== undefined
    && error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429;
}
export function nextDelay(options: { intervalMs: number; failures: number; stopped: boolean; error: unknown }): number | null {
  if (options.stopped) return null;
  if (options.failures > 0) return isPermanent(options.error) ? null : backoffDelay(options.failures);
  return options.intervalMs;
}
