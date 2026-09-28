import { useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router';
import { nodeEdges, parseWorkflow, type Json, type WorkflowDetail } from '../model.ts';
import { useResource } from '../useResource.ts';
import { Fields, JsonBlock, PageHeading, ResourceView, Status, Time, seg } from '../ui.tsx';

const KNOWN = new Set(['http_request', 'condition', 'delay', 'notify', 'ai', 'approval', 'order_action']);
type Def = Record<string, Json>;

function Nodes({ def }: { def: Def }) {
  const nodes = Array.isArray(def.nodes) ? def.nodes.filter((n): n is Def => !!n && typeof n === 'object' && !Array.isArray(n)) : [];
  const ids = new Set(nodes.map(n => String(n.id)));
  const anchor = (id: string) => `node-${encodeURIComponent(id)}`;
  const go = (id: string) => { const el = document.getElementById(anchor(id)); el?.focus(); el?.scrollIntoView({ block: 'center' }); };
  if (nodes.length === 0) return <p className="muted">This definition has no nodes.</p>;
  return <div className="table-wrap"><table className="nodes">
    <caption>Nodes in definition order (not execution order)</caption>
    <thead><tr><th scope="col">Node</th><th scope="col">Type</th><th scope="col">Parameters</th><th scope="col">Edges</th></tr></thead>
    <tbody>{nodes.map((n, i) => { const id = String(n.id); return <tr key={`${i}-${id}`} id={anchor(id)} tabIndex={-1}>
      <td data-label="Node"><code>{id}</code>{def.entry === id && <span className="tag">Entry</span>}</td>
      <td data-label="Type">{KNOWN.has(String(n.type)) ? String(n.type) : <span className="warn">Unrecognized: {JSON.stringify(n.type)}</span>}</td>
      <td data-label="Parameters"><details><summary>Show parameters</summary><JsonBlock value={n.params ?? null} label={`Parameters of ${id}`} /></details></td>
      <td data-label="Edges"><ul className="edges">{nodeEdges(n, ids).map(e => <li key={e.label}>{e.label}: {e.target === null ? <span>End</span>
        : e.exists ? <a href={`#${anchor(e.target)}`} onClick={ev => { ev.preventDefault(); go(e.target!); }}><code>{e.target}</code></a>
        : <span className="warn"><code>{e.target}</code> (missing node)</span>}</li>)}</ul></td>
    </tr>; })}</tbody>
  </table></div>;
}

function Definition({ def, secret }: { def: Def; secret: boolean }) {
  const limits = (def.limits && typeof def.limits === 'object' && !Array.isArray(def.limits) ? def.limits : {}) as Def;
  const trigger = (def.trigger && typeof def.trigger === 'object' && !Array.isArray(def.trigger) ? def.trigger : {}) as Def;
  return <>
    <Fields items={[
      ['Trigger', <>{String(trigger.type ?? 'unknown')}{trigger.type === 'webhook' && <> — {secret ? 'Secret configured' : 'No secret configured'}</>}</>],
      ['Entry node', <code>{String(def.entry ?? '')}</code>],
      ['Step cap (max_steps)', String(limits.max_steps ?? 'Not set')],
      ...(limits.timeout_seconds !== undefined ? [['Timeout seconds', <>{String(limits.timeout_seconds)} <span className="muted">(stored; not enforced)</span></>] as [string, ReactNode]] : []),
      ...(limits.max_ai_tokens !== undefined ? [['Max AI tokens', <>{String(limits.max_ai_tokens)} <span className="muted">(stored; not enforced)</span></>] as [string, ReactNode]] : []),
    ]} />
    <h2>Nodes and branches</h2>
    <Nodes def={def} />
  </>;
}

function Detail({ w }: { w: WorkflowDetail }) {
  const both = w.status === 'draft' && w.published_definition !== null;
  const [tab, setTab] = useState<'draft' | 'published'>('draft');
  const showPublished = w.status === 'published' ? w.published_definition !== null : both && tab === 'published';
  const def = showPublished ? w.published_definition! : w.definition;
  return <>
    <Fields items={[
      ['Name', w.name], ['ID', <code>{w.id}</code>], ['Status', <Status value={w.status} />],
      ['Description', w.description ?? <span className="muted">None</span>],
      ['Created', <Time value={w.created_at} />], ['Updated', <Time value={w.updated_at} />], ['Published', <Time value={w.published_at} missing="Never published" />],
    ]} />
    <p><Link className="button-link" to={`/console/runs?workflow=${encodeURIComponent(w.id)}`}>View runs for this workflow</Link></p>
    {both && <div className="tabs" role="group" aria-label="Definition version">
      <button type="button" className={tab === 'draft' ? '' : 'secondary'} aria-pressed={tab === 'draft'} onClick={() => setTab('draft')}>Draft definition</button>
      <button type="button" className={tab === 'published' ? '' : 'secondary'} aria-pressed={tab === 'published'} onClick={() => setTab('published')}>Last published definition</button>
    </div>}
    <h2>{showPublished ? 'Published definition (frozen)' : 'Draft definition'}</h2>
    {both && <p className="notice-inline">{tab === 'published' ? 'This is the definition new triggers currently use.' : 'New triggers require republishing the draft.'}</p>}
    {w.status === 'draft' && !both && <p className="notice-inline">Draft only — this workflow cannot be triggered until it is published.</p>}
    <Definition def={def} secret={showPublished ? w.published_secret_configured : w.secret_configured} />
  </>;
}

export function WorkflowDetailPage() {
  const { workflowId = '' } = useParams();
  const wf = useResource(`workflow:${workflowId}`, async (s, signal) => parseWorkflow(await s.request(`/workflows/${seg(workflowId)}`, { signal })), { intervalMs: 5_000 });
  return <>
    <PageHeading title="Workflow detail" description="Review a workflow definition, its branches and its run history." />
    <ResourceView resource={wf} label="workflow" notFound={{ text: `No workflow with ID “${workflowId}”.`, to: '/console/workflows', link: 'Back to workflows' }}>
      {w => <Detail w={w} />}
    </ResourceView>
  </>;
}
