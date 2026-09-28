/** Runs are started through the API (no trigger button by design); this points to the demo steps. */
export function DemoHint() {
  return <p className="hint">To start a run, use the manual trigger or webhook API as described in <code>docs/CONSOLE_DEMO.md</code>.
    New runs appear under Runs, and <code>/console/runs/&lt;run_id&gt;</code> opens one directly.</p>;
}
