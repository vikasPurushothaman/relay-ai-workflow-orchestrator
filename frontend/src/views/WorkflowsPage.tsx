import { Link } from 'react-router';
import { parseWorkflows } from '../model.ts';
import { useResource } from '../useResource.ts';
import { PageHeading, ResourceView, Status, Time, seg } from '../ui.tsx';
import { DemoHint } from './DemoHint.tsx';

export function WorkflowsPage() {
  const workflows = useResource('workflows', async (s, signal) => parseWorkflows(await s.request('/workflows', { signal })), { intervalMs: 5_000 });
  return <>
    <PageHeading title="Workflows" description="Inspect workflow definitions and their publication state." />
    <ResourceView resource={workflows} label="workflows">{rows => rows.length === 0
      ? <section className="notice"><h2>No workflows yet</h2><p>Start the API with seed loading enabled, or create and publish a workflow through the API.</p><DemoHint /></section>
      : <div className="table-wrap"><table>
        <caption className="sr-only">Workflows</caption>
        <thead><tr><th scope="col">Name</th><th scope="col">ID</th><th scope="col">Status</th><th scope="col">Trigger</th><th scope="col">Updated</th></tr></thead>
        <tbody>{rows.map(w => <tr key={w.id}>
          <td data-label="Name"><Link to={`/console/workflows/${seg(w.id)}`}>{w.name}</Link></td>
          <td data-label="ID"><code>{w.id}</code></td>
          <td data-label="Status"><Status value={w.status} /></td>
          <td data-label="Trigger">{w.trigger_type}</td>
          <td data-label="Updated"><Time value={w.updated_at} /></td>
        </tr>)}</tbody>
      </table></div>}
    </ResourceView>
  </>;
}
