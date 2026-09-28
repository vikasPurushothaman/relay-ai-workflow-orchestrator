import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { TERMINAL, cancellationText, formatDuration, formatTokens, loadRunTrace, parseDecision, type Attempt, type RunDetail, type Step } from '../model.ts';
import { useResource } from '../useResource.ts';
import { Fields, JsonBlock, PageHeading, ResourceView, Status, Time, seg } from '../ui.tsx';
import { useSession } from '../SessionContext.tsx';
import { sendCommand } from '../mutation.ts';
import { errorMessage } from '../session.ts';

function AttemptRow({ a }: { a: Attempt }) {
  return <tr>
    <td data-label="Attempt">{a.attempt_no}</td>
    <td data-label="Cause">{a.cause.replace('_', ' ')}</td>
    <td data-label="Status">{a.status === 'uncertain' ? <span className="warn">Outcome unknown — the remote call may have completed.</span> : <Status value={a.status} />}</td>
    <td data-label="Started"><Time value={a.started_at} /></td>
    <td data-label="Duration">{formatDuration(a.duration_ms)}</td>
    <td data-label="Provider">{a.provider ? `${a.provider} / ${a.model ?? 'unknown model'}` : <span className="muted">—</span>}</td>
    <td data-label="Tokens">{a.provider ? formatTokens(a.tokens_prompt, a.tokens_completion) : <span className="muted">—</span>}</td>
    <td data-label="Error">{a.error === null ? <span className="muted">None</span> : <JsonBlock value={a.error} label={`Attempt ${a.attempt_no} error`} />}</td>
  </tr>;
}

function StepDetail({ s, runId }: { s: Step; runId: string }) {
  return <div className="step-detail">
    <Fields items={[
      ['Started', <Time value={s.started_at} missing="Not started" />], ['Finished', <Time value={s.finished_at} missing="Not finished" />],
      ['Duration (including waits)', formatDuration(s.duration_ms)],
      ['Next node taken', s.selected_next_node_id ? <code>{s.selected_next_node_id}</code> : s.status === 'succeeded' ? 'End' : <span className="muted">Not decided</span>],
      ...(s.resume_at ? [['Delay resumes at', <Time value={s.resume_at} />] as [string, ReactNode]] : []),
      ...(s.retry_due_at ? [['Next retry due', <Time value={s.retry_due_at} />] as [string, ReactNode]] : []),
      ...(s.idempotency_key ? [['Idempotency key', <code>{s.idempotency_key}</code>] as [string, ReactNode]] : []),
      ...(s.node_type === 'ai' ? [['AI tokens', formatTokens(s.tokens_prompt, s.tokens_completion, s.ai_usage_complete)] as [string, ReactNode],
        ['Schema repairs used', `${s.ai_repair_count} of 1`] as [string, ReactNode]] : []),
    ]} />
    {s.approval && <section aria-label="Approval"><h4>Approval</h4><Fields items={[
      ['Request', <Link to={`/console/approvals?focus=${encodeURIComponent(s.approval.id)}`}><code>{s.approval.id}</code></Link>],
      ['Status', <Status value={s.approval.status} />], ['Message', <span className="message">{s.approval.message}</span>],
      ...(s.approval.decided_by ? [['Decided by', s.approval.decided_by] as [string, ReactNode], ['Decided at', <Time value={s.approval.decided_at} />] as [string, ReactNode]] : []),
      ...(s.approval.closed_at ? [['Closed', <><Time value={s.approval.closed_at} /> — {s.approval.close_reason === 'run_cancelled' ? 'run was cancelled (no human decision)' : s.approval.close_reason}</>] as [string, ReactNode]] : []),
    ]} /></section>}
    <h4>Resolved input</h4>{s.resolved_input === null ? <p className="muted">Not resolved</p> : <JsonBlock value={s.resolved_input} label={`Input of step ${s.sequence}`} />}
    <h4>Output</h4>{s.output === null ? <p className="muted">{s.status === 'succeeded' ? 'null' : 'No output recorded'}</p> : <JsonBlock value={s.output} label={`Output of step ${s.sequence}`} />}
    {s.error !== null && <><h4>Error</h4><JsonBlock value={s.error} label={`Error of step ${s.sequence}`} /></>}
    <h4>Attempts ({s.attempts.length})</h4>
    {s.attempts.length === 0 ? <p className="muted">No attempts were prepared for this step.</p>
      : <div className="table-wrap"><table className="attempts"><caption className="sr-only">Attempts for step {s.sequence} of run {runId}</caption>
        <thead><tr><th scope="col">#</th><th scope="col">Cause</th><th scope="col">Status</th><th scope="col">Started</th><th scope="col">Duration</th><th scope="col">Provider / model</th><th scope="col">Tokens</th><th scope="col">Error</th></tr></thead>
        <tbody>{s.attempts.map(a => <AttemptRow key={a.attempt_no} a={a} />)}</tbody></table></div>}
  </div>;
}

function Trace({ run }: { run: RunDetail }) {
  const [open, setOpen] = useState<Set<number>>(() => new Set());
  const revealed = useRef(false);
  // On first load, reveal the active or failed row; later polls never move focus or scroll.
  useEffect(() => {
    if (revealed.current || run.steps.length === 0) return;
    revealed.current = true;
    const target = run.steps.find(s => ['running', 'waiting', 'failed'].includes(s.status));
    if (target) { setOpen(o => new Set(o).add(target.sequence)); requestAnimationFrame(() => document.getElementById(`step-${target.sequence}`)?.scrollIntoView({ block: 'nearest' })); }
  }, [run.steps]);
  const toggle = (seq: number) => setOpen(o => { const n = new Set(o); if (n.has(seq)) n.delete(seq); else n.add(seq); return n; });
  if (run.steps.length === 0) return <p className="muted">{run.status === 'queued' ? 'No step has started yet.' : 'No steps were recorded.'}</p>;
  return <ol className="trace">{run.steps.map(s => <li key={s.sequence} id={`step-${s.sequence}`} className={`trace-row status-row-${s.status}`}>
    <div className="trace-head">
      <span className="seq">#{s.sequence}</span>
      <span><code>{s.node_id}</code> <span className="muted">{s.node_type}</span></span>
      <span><Status value={s.status} />{s.wait_reason && <span className="muted"> ({s.wait_reason})</span>}</span>
      <span className="muted">{formatDuration(s.duration_ms)}{s.attempts.length > 1 ? ` · ${s.attempts.length} attempts` : ''}</span>
      <button type="button" className="secondary" aria-expanded={open.has(s.sequence)} aria-controls={`step-detail-${s.sequence}`} onClick={() => toggle(s.sequence)}>
        {open.has(s.sequence) ? 'Hide' : 'Inspect'}<span className="sr-only"> step {s.sequence} ({s.node_id})</span></button>
    </div>
    {open.has(s.sequence) && <div id={`step-detail-${s.sequence}`}><StepDetail s={s} runId={run.run_id} /></div>}
  </li>)}</ol>;
}

type CancelState = { phase: 'idle' | 'confirm' | 'sending' }
  | { phase: 'done'; text: string; tone: 'ok' | 'warn' | 'error'; allowRetry: boolean }
  | { phase: 'unknown'; afterReadId: number; reconciled: boolean };
function CancelControl({ run, onChanged, successfulReadId, readStartedId }: {
  run: RunDetail; onChanged: () => void; successfulReadId: number; readStartedId: () => number;
}) {
  const session = useSession();
  const [state, setState] = useState<CancelState>({ phase: 'idle' });
  const trigger = useRef<HTMLButtonElement>(null);
  const notice = useRef<HTMLParagraphElement>(null);
  const terminal = TERMINAL.has(run.status);
  useEffect(() => { if (state.phase === 'done' || state.phase === 'unknown') notice.current?.focus(); }, [state.phase]);
  useEffect(() => {
    if (state.phase !== 'unknown' || state.reconciled || successfulReadId <= state.afterReadId) return;
    if (terminal || run.cancel_requested_at) {
      setState({ phase: 'done', tone: 'warn', allowRetry: false, text: terminal
        ? 'Cancellation outcome reconciled. The run is now terminal.'
        : 'Cancellation outcome reconciled. The server recorded the cancellation request.' });
    } else setState({ ...state, reconciled: true });
  }, [run.cancel_requested_at, state, successfulReadId, terminal]);
  if (terminal && state.phase !== 'done' && state.phase !== 'unknown') return null;
  const pending = !!run.cancel_requested_at && !terminal;
  const confirm = async () => {
    setState({ phase: 'sending' });
    const out = await sendCommand(session, `/runs/${seg(run.run_id)}/cancel`);
    if (out.kind === 'ok') {
      let status = 'unknown'; try { status = parseDecision(out.value).status; } catch { /* reconcile below */ }
      setState({ phase: 'done', tone: 'ok', allowRetry: false, text: status === 'cancelled' ? 'Run cancelled. No further work will start.' : 'Cancellation requested. The current step may finish; no further work will start.' });
    } else if (out.kind === 'conflict') setState({ phase: 'done', tone: 'warn', allowRetry: false, text: 'This run changed before cancellation was saved (it may already have finished). Current state is shown below.' });
    else if (out.kind === 'unknown') setState({ phase: 'unknown', afterReadId: readStartedId(), reconciled: false });
    else if (out.kind === 'missing') setState({ phase: 'done', tone: 'error', allowRetry: false, text: 'This run no longer exists.' });
    else setState({ phase: 'done', tone: 'error', allowRetry: true, text: errorMessage(out.error) });
    onChanged();
  };
  return <section className="cancel" aria-label="Cancel run">
    {state.phase === 'done' && <p ref={notice} tabIndex={-1} role="status" className={`result ${state.tone}`}>{state.text}</p>}
    {state.phase === 'unknown' && <p ref={notice} tabIndex={-1} role="status" className="result warn">Cancellation outcome unknown — the request may have been saved.
      {state.reconciled ? ' A fresh read still shows an active run with no cancellation request. You may deliberately try again, but the first request could still conflict.' : ' Waiting for a fresh run read before allowing another cancellation.'}</p>}
    {pending && <p className="muted">Cancellation requested at <Time value={run.cancel_requested_at} />.</p>}
    {!terminal && !pending && (state.phase === 'idle' || (state.phase === 'done' && state.allowRetry) || (state.phase === 'unknown' && state.reconciled)) &&
      <button ref={trigger} type="button" className="danger" onClick={() => setState({ phase: 'confirm' })}>Cancel run</button>}
    {(state.phase === 'confirm' || state.phase === 'sending') && <div className="confirm" role="group" aria-label="Confirm cancellation"
      onKeyDown={e => { if (e.key === 'Escape' && state.phase === 'confirm') { setState({ phase: 'idle' }); requestAnimationFrame(() => trigger.current?.focus()); } }}>
      <p>Stop future work; the current step may finish. Completed external effects are not reversed.</p>
      <button type="button" className="danger" autoFocus disabled={state.phase === 'sending'} onClick={() => void confirm()}>{state.phase === 'sending' ? 'Cancelling…' : 'Confirm cancel'}</button>
      <button type="button" className="secondary" disabled={state.phase === 'sending'} onClick={() => { setState({ phase: 'idle' }); requestAnimationFrame(() => trigger.current?.focus()); }}>Keep running</button>
    </div>}
  </section>;
}

export function RunDetailPage() {
  const { runId = '' } = useParams();
  const run = useResource(`run:${runId}`, (s, signal) => loadRunTrace(runId, path => s.request(path, { signal }), { signal }),
    { intervalMs: 2_000, stopWhen: r => TERMINAL.has(r.status) });
  return <>
    <PageHeading title="Run detail" description="Inspect the steps and recorded outcomes of a run." />
    <ResourceView resource={run} label="run" notFound={{ text: `No run with ID “${runId}”.`, to: '/console/runs', link: 'Back to runs' }}>{r => <>
      <Fields items={[
        ['Run', <code>{r.run_id}</code>], ['Status', <><Status value={r.status} />{r.status === 'waiting_approval' && <> — <Link to="/console/approvals">open Approvals</Link></>}</>],
        ['Workflow', <Link to={`/console/workflows/${seg(r.workflow_id)}`}>{r.workflow_name ?? r.workflow_id} <code>{r.workflow_id}</code></Link>],
        ['Trigger', r.trigger_type], ['Accepted', <Time value={r.created_at} />], ['Started', <Time value={r.started_at} missing="Not started" />],
        ['Finished', <Time value={r.finished_at} missing="Not finished" />],
        ['Steps', `${r.steps_executed} of ${r.max_steps ?? 'unknown'} allowed`],
        ['AI usage', r.ai_tokens_used === null ? 'Unavailable' : `${r.ai_tokens_used} tokens${r.ai_usage_complete ? '' : ' (known usage; incomplete)'}`],
        ...(r.error ? [['Stopped because', <span className="warn">{r.error.code === 'max_steps' ? 'Step cap reached (max_steps)' : r.error.code}{r.error.node_id ? <> at <code>{r.error.node_id}</code></> : null}</span>] as [string, ReactNode]] : []),
        ...(cancellationText(r) ? [['Cancellation', <>{cancellationText(r)}{r.cancel_requested_by ? ` Requested by ${r.cancel_requested_by}.` : ''}</>] as [string, ReactNode]] : []),
      ]} />
      <CancelControl key={r.run_id} run={r} onChanged={run.refresh} successfulReadId={run.successfulReadId} readStartedId={run.readStartedId} />
      <details className="input"><summary>Trigger input</summary><JsonBlock value={r.input} label="Trigger input" /></details>
      <h2>Trace</h2>
      {!TERMINAL.has(r.status) && <p className="muted">Refreshing automatically every 2 seconds while the run is active.</p>}
      <Trace run={r} />
    </>}</ResourceView>
  </>;
}
