import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError } from '../src/api.ts';
import { cancellationText, formatDuration, formatTime, formatTokens, loadRunTrace, mergeRunPages, nodeEdges, parseApprovals, parseRun, parseRunPage,
  parseWorkflow, parseWorkflows, runFilters, runsQuery, statusLabel } from '../src/model.ts';
import { backoffDelay, isPermanent, nextDelay } from '../src/polling.ts';

const step = (sequence: number, extra: Record<string, unknown> = {}) => ({ sequence, node_id: 'n' + sequence, node_type: 'delay', status: 'succeeded', wait_reason: null,
  attempt_count: 1, selected_next_node_id: null, resume_at: null, started_at: null, finished_at: null, duration_ms: 5, resolved_input: {}, output: {}, error: null,
  idempotency_key: null, ai_repair_count: 0, tokens_prompt: null, tokens_completion: null, ai_usage_complete: true, retry_due_at: null, approval: null, attempts: [], ...extra });
const run = (steps: unknown[], next: number | null = null, extra: Record<string, unknown> = {}) => ({ run_id: 'r', workflow_id: 'wf', workflow_name: 'W', entry: 'n1', status: 'running',
  trigger_type: 'manual', current_node_id: 'n1', steps_executed: 1, max_steps: 5, created_at: '2026-09-28T10:00:00.123456Z', started_at: null, finished_at: null,
  error: null, cancel_requested_at: null, cancel_requested_by: null, cancellation_reason: null, input: {}, ai_tokens_used: 0, ai_usage_complete: true,
  steps, steps_next_after: next, ...extra });
const format = (fn: () => unknown) => assert.throws(fn, (e: unknown) => e instanceof ApiError && e.kind === 'format');

test('workflow lists accept array or wrapper and reject bad rows', () => {
  assert.equal(parseWorkflows([{ id: 'a', name: 'A', status: 'published', trigger_type: 'manual', updated_at: null }])[0].name, 'A');
  assert.equal(parseWorkflows({ workflows: [{ id: 'a', status: 'draft' }] })[0].trigger_type, 'unknown');
  for (const bad of [null, {}, [{}], [{ id: '', status: 'draft' }], [{ id: 'a', status: 'x' }], 'x']) format(() => parseWorkflows(bad));
});
test('workflow detail requires definition and never needs a secret value', () => {
  const w = parseWorkflow({ id: 'a', name: 'A', status: 'draft', definition: { nodes: [] }, secret_configured: true, published_definition: null });
  assert.equal(w.secret_configured, true); assert.equal(w.published_definition, null);
  format(() => parseWorkflow({ id: 'a', status: 'draft' }));
});
test('run page and run detail parse; unknown statuses are rejected', () => {
  const page = parseRunPage({ runs: [run([])], next_cursor: 'c' });
  assert.equal(page.runs[0].run_id, 'r'); assert.equal(page.runs[0].max_steps, 5); assert.equal(page.next_cursor, 'c');
  assert.equal(parseRunPage({ runs: [{ ...run([]), max_steps: null }], next_cursor: null }).runs[0].max_steps, null);
  format(() => parseRunPage({ runs: [{ ...run([]), status: 'paused' }], next_cursor: null }));
  const r = parseRun(run([step(1, { status: 'waiting', attempts: [{ attempt_no: 1, status: 'uncertain', cause: 'recovery', error: { code: 'unknown_effect' }, output: null,
    provider: null, model: null, tokens_prompt: null, tokens_completion: null, started_at: 'x', finished_at: null, duration_ms: null }],
    approval: { id: 'apr', status: 'closed', message: '<b>x</b>', decided_by: null, decided_at: null, closed_at: 't', close_reason: 'run_cancelled' } })]));
  assert.equal(r.steps[0].attempts[0].status, 'uncertain'); assert.equal(r.steps[0].approval?.close_reason, 'run_cancelled');
  format(() => parseRun(run([{ ...step(1), sequence: -1 }])));
  format(() => parseRun(run([step(1)], null, { ai_usage_complete: 'yes' })));
});
test('pages merge by sequence without duplicates and keep last continuation', () => {
  const merged = mergeRunPages([parseRun(run([step(1), step(2)], 2)), parseRun(run([step(2), step(3)], null))]);
  assert.deepEqual(merged.steps.map(s => s.sequence), [1, 2, 3]); assert.equal(merged.steps_next_after, null);
  format(() => mergeRunPages([]));
});
test('trace loader completes pages and rejects unsafe continuations', async () => {
  const paths: string[] = [];
  const complete = await loadRunTrace('r', async path => {
    paths.push(path);
    return paths.length === 1 ? run([step(1)], 1) : run([step(2)], null);
  });
  assert.deepEqual(paths, ['/runs/r', '/runs/r?steps_after=1']);
  assert.deepEqual(complete.steps.map(s => s.sequence), [1, 2]);

  await assert.rejects(() => loadRunTrace('r', async path => path.includes('?') ? run([step(2)], 1) : run([step(1)], 1)), /trace is incomplete/);
  await assert.rejects(() => loadRunTrace('r', async path => path.includes('?') ? run([step(3)], 1) : run([step(1)], 2)), /trace is incomplete/);
  await assert.rejects(() => loadRunTrace('r', async () => run([step(1)], 1), { maxPages: 1 }), /trace is incomplete/);

  const controller = new AbortController(); controller.abort(); let called = false;
  await assert.rejects(() => loadRunTrace('r', async () => { called = true; return run([], null); }, { signal: controller.signal }), /abort/i);
  assert.equal(called, false);
});
test('approvals accept array or wrapper with optional workflow/created_at', () => {
  const a = parseApprovals({ approvals: [{ id: 'a', run_id: 'r', step_sequence: 2, node_id: 'n', message: 'm', status: 'pending' }] });
  assert.equal(a[0].workflow_id, null); assert.equal(a[0].created_at, null);
  format(() => parseApprovals([{ id: 'a', run_id: 'r', step_sequence: 'x', node_id: 'n', status: 'pending' }]));
});
test('formatting: UTC, null wording, durations, tokens and labels', () => {
  assert.equal(formatTime('2026-09-28T10:00:00.123456Z'), '2026-09-28 10:00:00.123 UTC');
  assert.equal(formatTime(null, 'Not started'), 'Not started'); assert.equal(formatTime('garbage'), 'garbage');
  assert.equal(formatDuration(null), 'Unknown'); assert.equal(formatDuration(0), '0 ms'); assert.equal(formatDuration(1500), '1.50 s'); assert.equal(formatDuration(125_000), '2 min 5 s');
  assert.equal(formatTokens(null, null), 'Unavailable'); assert.equal(formatTokens(0, 0), '0 (prompt 0, completion 0)');
  assert.match(formatTokens(7, null), /incomplete/); assert.match(formatTokens(7, 3, false), /incomplete/);
  assert.equal(statusLabel('waiting_approval'), 'Waiting for approval'); assert.equal(statusLabel('weird'), 'weird');
});
test('cancellation wording distinguishes rejection, operator cancel and pending request', () => {
  assert.match(cancellationText({ status: 'cancelled', cancellation_reason: 'approval_rejected', cancel_requested_at: null })!, /rejected/);
  assert.match(cancellationText({ status: 'cancelled', cancellation_reason: 'operator_cancelled', cancel_requested_at: 't' })!, /operator/);
  assert.match(cancellationText({ status: 'running', cancellation_reason: 'operator_cancelled', cancel_requested_at: 't' })!, /requested/);
  assert.equal(cancellationText({ status: 'running', cancellation_reason: null, cancel_requested_at: null }), null);
});
test('node edges: next, condition branches, End and dangling targets', () => {
  const ids = new Set(['a', 'b']);
  assert.deepEqual(nodeEdges({ id: 'a', type: 'delay', next: 'b' }, ids), [{ label: 'Next', target: 'b', exists: true }]);
  assert.deepEqual(nodeEdges({ id: 'a', type: 'condition', on_true: null, on_false: 'zz' }, ids),
    [{ label: 'If true', target: null, exists: true }, { label: 'If false', target: 'zz', exists: false }]);
  assert.deepEqual(nodeEdges({ id: 'a', type: 'delay' }, ids), []);
});
test('run filters validate before requests and build encoded queries', () => {
  assert.equal(runFilters(new URLSearchParams('status=paused')).error !== null, true);
  assert.equal(runFilters(new URLSearchParams('workflow=' + 'x'.repeat(129))).error !== null, true);
  assert.deepEqual(runFilters(new URLSearchParams('workflow=wf&status=failed')), { workflow: 'wf', status: 'failed', error: null });
  assert.deepEqual(runFilters(new URLSearchParams('status=')), { workflow: null, status: null, error: null });
  assert.equal(runsQuery({ workflow: 'a&b', status: 'queued' }, 'c/d'), '/runs?limit=25&workflow_id=a%26b&status=queued&cursor=c%2Fd');
  assert.equal(runsQuery({ workflow: null, status: null }, null), '/runs?limit=25');
});
test('polling backoff 5/10/30, stop on permanent 4xx and terminal state', () => {
  assert.deepEqual([1, 2, 3, 9].map(backoffDelay), [5_000, 10_000, 30_000, 30_000]);
  assert.equal(isPermanent(new ApiError('http', 404)), true); assert.equal(isPermanent(new ApiError('http', 429)), false);
  assert.equal(isPermanent(new ApiError('http', 503)), false); assert.equal(isPermanent(new ApiError('network')), false);
  assert.equal(nextDelay({ intervalMs: 2000, failures: 0, stopped: false, error: null }), 2000);
  assert.equal(nextDelay({ intervalMs: 2000, failures: 0, stopped: true, error: null }), null);
  assert.equal(nextDelay({ intervalMs: 2000, failures: 2, stopped: false, error: new ApiError('network') }), 10_000);
  assert.equal(nextDelay({ intervalMs: 2000, failures: 1, stopped: false, error: new ApiError('http', 400) }), null);
});
