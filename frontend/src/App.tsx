import { useEffect, useState } from 'react';
import { AccessPanel } from './AccessPanel.tsx';
import { Link, NavLink, Navigate, Route, Routes } from 'react-router';
import { ManagementSession } from './session.ts';
import { relayClient } from './client.ts';
import { SessionContext } from './SessionContext.tsx';
import { PageHeading } from './ui.tsx';
import { WorkflowsPage } from './views/WorkflowsPage.tsx';
import { WorkflowDetailPage } from './views/WorkflowDetailPage.tsx';
import { RunsPage } from './views/RunsPage.tsx';
import { RunDetailPage } from './views/RunDetailPage.tsx';
import { ApprovalsPage } from './views/ApprovalsPage.tsx';

function NotFound() {
  return <>
    <PageHeading title="Page not found" description="The requested console address is unavailable." />
    <section className="notice"><h2>This page does not exist</h2><p>Use the navigation to return to the console.</p>
      <Link to="/console/workflows">Go to Workflows</Link></section>
  </>;
}

export function App() {
  // One memory-only session for the whole console; disconnect/401 clears every view's data and polling.
  const [session] = useState(() => new ManagementSession(relayClient()));
  useEffect(() => () => session.disconnect(), [session]);
  return <SessionContext.Provider value={session}><div className="app">
    <a className="skip-link" href="#content">Skip to content</a>
    <header><Link className="brand" to="/console/workflows" aria-label="Relay home"><span aria-hidden="true">R</span>Relay</Link>
      <nav aria-label="Primary">
        <NavLink to="/console/workflows">Workflows</NavLink>
        <NavLink to="/console/runs">Runs</NavLink>
        <NavLink to="/console/approvals">Approvals</NavLink>
      </nav><span className="environment">Local development</span>
    </header>
    <main id="content" tabIndex={-1}>
      <AccessPanel />
      <Routes>
        <Route path="/" element={<Navigate to="/console/workflows" replace />} />
        <Route path="/console" element={<Navigate to="/console/workflows" replace />} />
        <Route path="/console/workflows" element={<WorkflowsPage />} />
        <Route path="/console/workflows/:workflowId" element={<WorkflowDetailPage />} />
        <Route path="/console/runs" element={<RunsPage />} />
        <Route path="/console/runs/:runId" element={<RunDetailPage />} />
        <Route path="/console/approvals" element={<ApprovalsPage />} />
        <Route path="*" element={<NotFound />} />
      </Routes>
    </main>
    <footer>Relay <span aria-hidden="true">·</span> Workflow orchestration</footer>
  </div></SessionContext.Provider>;
}
