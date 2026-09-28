import { useCallback, useEffect, useRef, useState } from 'react';
import { useConnected, useSession } from './SessionContext.tsx';
import { nextDelay } from './polling.ts';
import type { ManagementSession } from './session.ts';

export type Resource<T> = {
  data: T | null; error: unknown; loading: boolean; updatedAt: Date | null; successfulReadId: number; failedAt: Date | null; refresh: () => void;
  /** Monotonic ID of the latest read that has started, including one still in flight. */
  readStartedId: () => number;
  /** Replaces data after a mutation's reconciliation read without waiting for the next poll. */
  replace: (value: T) => void;
};
type Loader<T> = (session: ManagementSession, signal: AbortSignal) => Promise<T>;

/**
 * One request at a time per resource; the next poll is scheduled after completion. A new key, a disconnect or
 * unmount bumps the generation so late responses can never overwrite the current view.
 */
export function useResource<T>(key: string | null, loader: Loader<T>, options: { intervalMs: number; stopWhen?: (value: T) => boolean }): Resource<T> {
  const session = useSession();
  const connected = useConnected();
  const [state, setState] = useState<{ key: string | null; data: T | null; error: unknown; loading: boolean; updatedAt: Date | null; successfulReadId: number; failedAt: Date | null }>(
    { key: null, data: null, error: null, loading: false, updatedAt: null, successfulReadId: 0, failedAt: null });
  const generation = useRef(0);
  const readSequence = useRef(0);
  const inFlight = useRef<AbortController | null>(null);
  const refreshRequested = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const failures = useRef(0);
  const run = useRef<() => void>(() => {});
  const latest = useRef({ loader, options });
  latest.current = { loader, options };
  const activeKey = connected ? key : null;

  useEffect(() => {
    const gen = ++generation.current;
    failures.current = 0;
    refreshRequested.current = false;
    setState({ key: activeKey, data: null, error: null, loading: activeKey !== null, updatedAt: null, successfulReadId: 0, failedAt: null });
    if (activeKey === null) { run.current = () => {}; return; }
    const clear = () => { if (timer.current) clearTimeout(timer.current); timer.current = null; };
    const schedule = (delay: number | null) => {
      clear();
      if (delay !== null && gen === generation.current && document.visibilityState !== 'hidden') timer.current = setTimeout(fetchNow, delay);
    };
    async function fetchNow() {
      if (gen !== generation.current) return;
      if (inFlight.current) { refreshRequested.current = true; return; }
      clear();
      const controller = new AbortController();
      const readId = ++readSequence.current;
      inFlight.current = controller;
      setState(s => (s.key === activeKey ? { ...s, loading: true } : s));
      let stopped = false, error: unknown = null;
      try {
        const value = await latest.current.loader(session, controller.signal);
        if (gen !== generation.current) return;
        failures.current = 0;
        stopped = latest.current.options.stopWhen?.(value) ?? false;
        setState({ key: activeKey, data: value, error: null, loading: false, updatedAt: new Date(), successfulReadId: readId, failedAt: null });
      } catch (e) {
        if (gen !== generation.current) return;
        failures.current++; error = e;
        setState(s => ({ ...s, error: e, loading: false, failedAt: new Date() }));
      } finally {
        if (inFlight.current === controller) inFlight.current = null;
      }
      if (refreshRequested.current) {
        refreshRequested.current = false;
        schedule(0);
      } else schedule(nextDelay({ intervalMs: latest.current.options.intervalMs, failures: failures.current, stopped, error }));
    }
    run.current = () => { void fetchNow(); };
    const visibility = () => { if (document.visibilityState === 'visible') void fetchNow(); else clear(); };
    document.addEventListener('visibilitychange', visibility);
    void fetchNow();
    return () => {
      generation.current++;
      refreshRequested.current = false;
      clear();
      inFlight.current?.abort();
      inFlight.current = null;
      document.removeEventListener('visibilitychange', visibility);
    };
  }, [activeKey, session]);

  const refresh = useCallback(() => run.current(), []);
  const replace = useCallback((value: T) => setState(s => ({ ...s, data: value, error: null, updatedAt: new Date() })), []);
  const current = state.key === activeKey ? state : { data: null, error: null, loading: activeKey !== null, updatedAt: null, successfulReadId: 0, failedAt: null };
  return { data: current.data, error: current.error, loading: current.loading, updatedAt: current.updatedAt,
    successfulReadId: current.successfulReadId, readStartedId: () => readSequence.current, failedAt: current.failedAt, refresh, replace };
}
