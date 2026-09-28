import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { useSession } from './SessionContext.tsx';

export function AccessPanel() {
  const session = useSession();
  const state = useSyncExternalStore(session.subscribe, session.snapshot);
  const [token, setToken] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const previous = useRef(state.phase);
  useEffect(() => {
    if (state.phase === 'disconnected' && previous.current !== 'disconnected') input.current?.focus();
    // After connecting the form disappears; move focus to the page heading instead of losing it to <body>.
    if (state.phase === 'connected' && previous.current !== 'connected') document.querySelector<HTMLElement>('main h1')?.focus();
    previous.current = state.phase;
  }, [state.phase]);
  return <section className="access-panel" aria-label="API access">
    <div><h2>API access</h2><p>Your token stays in memory and is cleared on reload.</p></div>
    {state.phase === 'connected' ? <button onClick={() => session.disconnect()}>Disconnect</button> :
      <form onSubmit={event => { event.preventDefault(); const value = token; setToken(''); void session.connect(value); }}>
        <label htmlFor="management-token">Management token</label>
        <div className="access-controls"><input id="management-token" ref={input} type="password" autoComplete="off" spellCheck={false}
          value={token} onChange={event => setToken(event.target.value)} disabled={state.phase === 'connecting'} required />
          <button disabled={state.phase === 'connecting'}>{state.phase === 'connecting' ? 'Connecting…' : 'Connect'}</button>
          {state.phase === 'connecting' && <button type="button" onClick={() => session.disconnect()}>Cancel</button>}
        </div>
      </form>}
    <p role="status" className="access-message">{state.message}</p>
  </section>;
}
