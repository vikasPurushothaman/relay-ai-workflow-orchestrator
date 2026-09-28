import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { RUN_STATUSES, parseRunPage, parseWorkflows, runFilters, runsQuery, statusLabel } from '../model.ts';
import { useResource } from '../useResource.ts';
import { PageHeading, ResourceView, Status, Time, seg } from '../ui.tsx';
import { useConnected } from '../SessionContext.tsx';
import { DemoHint } from './DemoHint.tsx';

export function RunsPage() {
  const [params, setParams] = useSearchParams();
  const filters = runFilters(params);
  const filterKey = `${filters.workflow ?? ''}|${filters.status ?? ''}`;
  // Cursor stack for Previous/Next; cursors are opaque and stay out of the URL.
  const [cursors, setCursors] = useState<(string | null)[]>([null]);
  const cursor = cursors[cursors.length - 1];
  const lastFilter = useRef(filterKey);
  useEffect(() => { if (lastFilter.current !== filterKey) { lastFilter.current = filterKey; setCursors([null]); } }, [filterKey]);
  const connected = useConnected();
  const workflows = useResource('workflows', async (s, signal) => parseWorkflows(await s.request('/workflows', { signal })), { intervalMs: 30_000 });
  const key = filters.error ? null : `runs:${filterKey}:${cursor ?? ''}`;
  const runs = useResource(key, async (s, signal) => parseRunPage(await s.request(runsQuery(filters, cursor), { signal })), { intervalMs: 5_000 });
  const names = new Map((workflows.data ?? []).map(w => [w.id, w.name]));
  const update = (name: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(name, value); else next.delete(name);
    setParams(next);
  };
  const workflowOptions = [...(workflows.data ?? []).map(w => w.id)];
  if (filters.workflow && !workflowOptions.includes(filters.workflow)) workflowOptions.push(filters.workflow);
  return <>
    <PageHeading title="Runs" description="Follow workflow runs, newest first, and open their execution traces." />
    {connected && <form className="filters" onSubmit={e => e.preventDefault()} aria-label="Run filters">
      <label>Workflow<select value={filters.workflow ?? ''} onChange={e => update('workflow', e.target.value)}>
        <option value="">All workflows</option>
        {workflowOptions.map(id => <option key={id} value={id}>{names.get(id) ? `${names.get(id)} (${id})` : id}</option>)}
      </select></label>
      <label>Status<select value={filters.error ? '' : filters.status ?? ''} onChange={e => update('status', e.target.value)}>
        <option value="">All statuses</option>
        {RUN_STATUSES.map(s => <option key={s} value={s}>{statusLabel(s)}</option>)}
      </select></label>
      {(filters.workflow || filters.status) && <button type="button" className="secondary" onClick={() => setParams(new URLSearchParams())}>Reset filters</button>}
    </form>}
    {connected && filters.error ? <section className="notice error" role="alert"><h2>Invalid filter</h2><p>{filters.error} No runs were requested.</p>
      <button type="button" onClick={() => setParams(new URLSearchParams())}>Reset filters</button></section>
      : <ResourceView resource={runs} label="runs">{page => <>
        {page.runs.length === 0
          ? <section className="notice"><h2>{cursors.length > 1 ? 'No more runs' : filters.workflow || filters.status ? 'No runs match these filters' : 'No runs yet'}</h2>
            {cursors.length > 1 ? <button type="button" onClick={() => setCursors([null])}>Back to first page</button>
              : filters.workflow || filters.status ? <button type="button" onClick={() => setParams(new URLSearchParams())}>Reset filters</button> : <DemoHint />}</section>
          : <div className="table-wrap"><table>
            <caption className="sr-only">Runs, newest first</caption>
            <thead><tr><th scope="col">Run</th><th scope="col">Workflow</th><th scope="col">Status</th><th scope="col">Trigger</th><th scope="col">Accepted</th><th scope="col">Started</th><th scope="col">Finished</th><th scope="col">Steps</th></tr></thead>
            <tbody>{page.runs.map(r => <tr key={r.run_id}>
              <td data-label="Run"><Link to={`/console/runs/${seg(r.run_id)}`}><code>{r.run_id}</code></Link></td>
              <td data-label="Workflow">{names.get(r.workflow_id) ?? ''} <code>{r.workflow_id}</code></td>
              <td data-label="Status"><Status value={r.status} /></td>
              <td data-label="Trigger">{r.trigger_type}</td>
              <td data-label="Accepted"><Time value={r.created_at} /></td>
              <td data-label="Started"><Time value={r.started_at} missing="Not started" /></td>
              <td data-label="Finished"><Time value={r.finished_at} missing="Not finished" /></td>
              <td data-label="Steps">{r.steps_executed} of {r.max_steps ?? 'unknown'}</td>
            </tr>)}</tbody>
          </table></div>}
        <nav className="pager" aria-label="Run pages">
          <button type="button" className="secondary" disabled={cursors.length === 1} onClick={() => setCursors(c => c.slice(0, -1))}>Previous</button>
          <span>Page {cursors.length}</span>
          <button type="button" className="secondary" disabled={!page.next_cursor} onClick={() => setCursors(c => [...c, page.next_cursor])}>Next</button>
          {!page.next_cursor && page.runs.length > 0 && <span className="muted">End of list</span>}
        </nav>
      </>}</ResourceView>}
  </>;
}
