import { createContext, useContext, useSyncExternalStore } from 'react';
import type { ManagementSession } from './session.ts';

export const SessionContext = createContext<ManagementSession | null>(null);
export function useSession(): ManagementSession {
  const session = useContext(SessionContext);
  if (!session) throw new Error('SessionContext missing');
  return session;
}
export function useConnected(): boolean {
  const session = useSession();
  return useSyncExternalStore(session.subscribe, session.snapshot).phase === 'connected';
}
