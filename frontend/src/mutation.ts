import { ApiError } from './api.ts';
import type { ManagementSession } from './session.ts';

export type MutationOutcome =
  | { kind: 'ok'; value: unknown }
  | { kind: 'conflict' }
  | { kind: 'missing' }
  | { kind: 'unknown' }
  | { kind: 'error'; error: unknown };

/** Sends exactly one command. Never retries: a timeout or dropped response may mean the command committed. */
export async function sendCommand(session: ManagementSession, path: string): Promise<MutationOutcome> {
  try {
    return { kind: 'ok', value: await session.request(path, { method: 'POST' }) };
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.kind === 'http' && error.status === 409) return { kind: 'conflict' };
      if (error.kind === 'http' && error.status === 404) return { kind: 'missing' };
      if (['timeout', 'aborted', 'network', 'format'].includes(error.kind) || (error.kind === 'http' && (error.status ?? 0) >= 500)) return { kind: 'unknown' };
    }
    return { kind: 'error', error };
  }
}
