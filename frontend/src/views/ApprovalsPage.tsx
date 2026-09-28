import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import { parseApprovals, parseDecision, statusLabel, type Approval } from '../model.ts';
import { useResource } from '../useResource.ts';
import { PageHeading, ResourceView, Time, seg, useQuery } from '../ui.tsx';
import { useSession } from '../SessionContext.tsx';
import { sendCommand } from '../mutation.ts';
import { errorMessage } from '../session.ts';

type Decision = 'approve' | 'reject';
type ItemState =
  | { phase: 'confirm'; decision: Decision }
  | { phase: 'sending'; decision: Decision }
  | { phase: 'unknown'; decision: Decision; reconciled: boolean };
type Notice = { id: string; runId: string; text: string; tone: 'ok' | 'warn' | 'error' };

function Item({ a, state, focused, stale, onStart, onCancel, onConfirm }: {
  a: Approval; state: ItemState | undefined; focused: boolean;
  stale: boolean; onStart: (d: Decision) => void; onCancel: () => void; onConfirm: (d: Decision) => void;
}) {
  const approveRef = useRef<HTMLButtonElement>(null), rejectRef = useRef<HTMLButtonElement>(null), itemRef = useRef<HTMLLIElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const confirming = state?.phase === 'confirm';
  useEffect(() => { if (confirming) confirmRef.current?.focus(); }, [confirming]);
  const last = useRef<Decision>('approve');
  useEffect(() => { if (focused) itemRef.current?.scrollIntoView({ block: 'center' }); }, [focused]);
  const blocked = stale || state?.phase === 'sending' || (state?.phase === 'unknown' && !state.reconciled);
  const close = () => { onCancel(); requestAnimationFrame(() => (last.current === 'approve' ? approveRef : rejectRef).current?.focus()); };
  const label = `${a.node_id} in run ${a.run_id}`;
  return <li ref={itemRef} className={`approval${focused ? ' focused' : ''}`} aria-label={`Approval ${a.id}`}
    onKeyDown={e => { if (e.key === 'Escape' && state?.phase === 'confirm') { e.preventDefault(); close(); } }}>
    <div className="approval-head">
      <p className="message">{a.message || <span className="muted">(empty message)</span>}</p>
      <dl className="fields compact">
        <div><dt>Run</dt><dd><Link to={`/console/runs/${seg(a.run_id)}`}><code>{a.run_id}</code></Link></dd></div>
        {a.workflow_id && <div><dt>Workflow</dt><dd><code>{a.workflow_id}</code></dd></div>}
        <div><dt>Node</dt><dd><code>{a.node_id}</code> (step {a.step_sequence})</dd></div>
        <div><dt>Requested</dt><dd><Time value={a.created_at} /></dd></div>
        <div><dt>Request ID</dt><dd><code>{a.id}</code></dd></div>
      </dl>
    </div>
    {state?.phase === 'unknown' && <p className="result warn" role="status">Decision outcome unknown — your {state.decision} request may have been saved.
      {state.reconciled ? ' The request still shows as pending. You may decide again; it will conflict if the first request was saved.' : ' Waiting for fresh data before allowing another decision.'}</p>}
    {stale && <p className="result warn" role="status">Decisions are disabled until the approval list refreshes successfully.</p>}
    {state?.phase === 'confirm' || state?.phase === 'sending'
      ? <div className="confirm" role="group" aria-label={`Confirm ${state.decision} for ${label}`}>
        <p>{state.decision === 'approve' ? `Approve “${a.node_id}” in run ${a.run_id}? The run will resume.` : `Reject “${a.node_id}” in run ${a.run_id}? This ends the run as cancelled.`}</p>
        <button ref={confirmRef} type="button" className={state.decision === 'reject' ? 'danger' : ''} disabled={stale || state.phase === 'sending'} onClick={() => onConfirm(state.decision)}>
          {state.phase === 'sending' ? 'Saving…' : state.decision === 'approve' ? 'Confirm approve' : 'Confirm reject'}<span className="sr-only"> for {label}</span></button>
        <button type="button" className="secondary" disabled={state.phase === 'sending'} onClick={close}>Cancel<span className="sr-only"> decision for {label}</span></button>
      </div>
      : <div className="actions">
        <button ref={approveRef} type="button" disabled={blocked} onClick={() => { last.current = 'approve'; onStart('approve'); }}>Approve<span className="sr-only"> {label}</span></button>
        <button ref={rejectRef} type="button" className="danger" disabled={blocked} onClick={() => { last.current = 'reject'; onStart('reject'); }}>Reject<span className="sr-only"> {label}</span></button>
      </div>}
  </li>;
}

export function ApprovalsPage() {
  const session = useSession();
  const focus = useQuery().get('focus');
  const approvals = useResource('approvals:pending', async (s, signal) => parseApprovals(await s.request('/approvals?status=pending', { signal })), { intervalMs: 2_000 });
  const [items, setItems] = useState<Record<string, ItemState>>({});
  const [notice, setNotice] = useState<Notice | null>(null);
  const noticeRef = useRef<HTMLDivElement>(null);
  const updatedAt = approvals.updatedAt?.getTime();
  // A fresh successful read after an unknown outcome allows an explicit new decision; nothing is resent automatically.
  const pendingUnknown = useRef<Record<string, number>>({});
  useEffect(() => {
    if (!updatedAt) return;
    setItems(prev => {
      let changed = false; const next = { ...prev };
      for (const [id, s] of Object.entries(prev)) if (s.phase === 'unknown' && !s.reconciled && updatedAt > (pendingUnknown.current[id] ?? 0)) {
        if (approvals.data?.some(a => a.id === id)) next[id] = { ...s, reconciled: true }; else delete next[id];
        changed = true;
      }
      return changed ? next : prev;
    });
  }, [updatedAt, approvals.data]);
  useEffect(() => { if (notice) noticeRef.current?.focus(); }, [notice]);
  const decide = async (a: Approval, decision: Decision) => {
    setItems(s => ({ ...s, [a.id]: { phase: 'sending', decision } }));
    const out = await sendCommand(session, `/approvals/${seg(a.id)}/${decision}`);
    const clear = () => setItems(s => { const n = { ...s }; delete n[a.id]; return n; });
    if (out.kind === 'ok') {
      clear();
      let status = ''; try { status = parseDecision(out.value).status; } catch { /* shown generically */ }
      setNotice({ id: a.id, runId: a.run_id, tone: 'ok', text: decision === 'approve'
        ? `Approved by the demo operator. Run is now ${status ? statusLabel(status).toLowerCase() : 'resuming'}.` : 'Rejected. The run was cancelled.' });
    } else if (out.kind === 'conflict') {
      clear(); setNotice({ id: a.id, runId: a.run_id, tone: 'warn', text: 'This request changed before your decision was saved. Open the run to see the recorded outcome.' });
    } else if (out.kind === 'missing') {
      clear(); setNotice({ id: a.id, runId: a.run_id, tone: 'error', text: 'This approval request no longer exists.' });
    } else if (out.kind === 'unknown') {
      pendingUnknown.current[a.id] = Date.now();
      setItems(s => ({ ...s, [a.id]: { phase: 'unknown', decision, reconciled: false } }));
    } else {
      clear(); setNotice({ id: a.id, runId: a.run_id, tone: 'error', text: errorMessage(out.error) });
    }
    approvals.refresh();
  };
  return <>
    <PageHeading title="Approvals" description="Review requests that need a human decision. Messages are shown as plain text." />
    {notice && <div ref={noticeRef} tabIndex={-1} className={`result ${notice.tone}`} role="status">
      <p>{notice.text}</p><p><Link to={`/console/runs/${seg(notice.runId)}`}>View run</Link> · <button type="button" className="link" onClick={() => setNotice(null)}>Dismiss</button></p></div>}
    <ResourceView resource={approvals} label="approvals">{rows => {
      const focusMissing = focus && !rows.some(a => a.id === focus);
      return <>
        {focusMissing && <p className="notice-inline">Request <code>{focus}</code> is not pending. It may have been decided or closed — check its run trace for the recorded outcome.</p>}
        {rows.length === 0 ? <section className="notice"><h2>No pending approvals.</h2></section>
          : <ul className="approvals">{rows.map(a => <Item key={a.id} a={a} state={items[a.id]} focused={a.id === focus} stale={!!approvals.error}
            onStart={d => setItems(s => ({ ...s, [a.id]: { phase: 'confirm', decision: d } }))}
            onCancel={() => setItems(s => { const n = { ...s }; delete n[a.id]; return n; })}
            onConfirm={d => void decide(a, d)} />)}</ul>}
      </>;
    }}</ResourceView>
  </>;
}
