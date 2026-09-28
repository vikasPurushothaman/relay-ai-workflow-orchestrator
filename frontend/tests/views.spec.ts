import { test, expect, type Page, type Route } from '@playwright/test';

// Mocked Relay API for console views (tasks 6.3–6.11). Live-stack verification is e2e-live.spec.ts (6.12).
const API = 'http://localhost:8080';
const T = '2026-09-28T10:00:00.123456Z';
type Handler = (route: Route, url: URL, method: string) => Promise<void> | void;
const json = (route: Route, body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

const workflows = [
  { id: 'wf_pub', name: 'Published flow', status: 'published', trigger_type: 'webhook', updated_at: T },
  { id: 'wf_draft', name: 'Draft flow', status: 'draft', trigger_type: 'manual', updated_at: null },
];
const definition = (name: string) => ({ id: 'wf_pub', name, trigger: { type: 'webhook' }, entry: 'check', limits: { max_steps: 10, timeout_seconds: 60 },
  nodes: [
    { id: 'check', type: 'condition', params: { left: '{{trigger.body.amount}}', op: 'greater_than', right: '100' }, on_true: 'gate', on_false: null },
    { id: 'gate', type: 'approval', params: { message: 'Approve?' }, next: 'ghost' },
    { id: 'odd', type: 'teleport', params: {}, next: null },
  ] });
const step = (sequence: number, extra: Record<string, unknown> = {}) => ({ sequence, node_id: `n${sequence}`, node_type: 'delay', status: 'succeeded', wait_reason: null,
  attempt_count: 1, selected_next_node_id: null, resume_at: null, started_at: T, finished_at: T, duration_ms: 12, resolved_input: { seconds: 0 }, output: {}, error: null,
  idempotency_key: null, ai_repair_count: 0, tokens_prompt: null, tokens_completion: null, ai_usage_complete: true, retry_due_at: null, approval: null,
  attempts: [{ attempt_no: 1, status: 'succeeded', cause: 'initial', error: null, output: {}, provider: null, model: null, tokens_prompt: null, tokens_completion: null, started_at: T, finished_at: T, duration_ms: 3 }], ...extra });
const run = (id: string, extra: Record<string, unknown> = {}) => ({ run_id: id, workflow_id: 'wf_pub', workflow_name: 'Published flow', entry: 'n1', status: 'running',
  trigger_type: 'webhook', current_node_id: 'n1', steps_executed: 1, max_steps: 10, created_at: T, started_at: T, finished_at: null, error: null,
  cancel_requested_at: null, cancel_requested_by: null, cancellation_reason: null, input: { amount: 250, api_key: '[REDACTED]' }, ai_tokens_used: 0, ai_usage_complete: true,
  steps: [], steps_next_after: null, ...extra });
const approval = (id: string, runId: string, message = 'Approve refund') => ({ id, run_id: runId, workflow_id: 'wf_pub', step_sequence: 2, node_id: 'gate', message, status: 'pending',
  created_at: T, decided_by: null, decided_at: null, closed_at: null });

async function mock(page: Page, handler: Handler) {
  await page.route(`${API}/**`, async route => {
    const url = new URL(route.request().url());
    expect(route.request().headers().authorization).toBe('Bearer view-token');
    if (await handled(handler, route, url)) return;
    if (url.pathname === '/workflows' && route.request().method() === 'GET') await json(route, workflows);
    else await json(route, { error: { message: 'Not found', code: 'not_found' } }, 404);
  });
}
async function handled(handler: Handler, route: Route, url: URL) {
  let done = false;
  const wrapped = { ...route, fulfill: async (o: Parameters<Route['fulfill']>[0]) => { done = true; await route.fulfill(o); },
    abort: async (e?: string) => { done = true; await route.abort(e); } } as unknown as Route;
  await handler(wrapped, url, route.request().method());
  return done;
}
async function connect(page: Page, path: string) {
  await page.goto(path);
  await page.getByLabel('Management token').fill('view-token');
  await page.getByLabel('Management token').press('Enter');
  await expect(page.getByRole('button', { name: 'Disconnect' })).toBeVisible();
}

test('6.3/6.4 workflow list, links, stale refresh failure and disconnect clears data', async ({ page }) => {
  let fail = false;
  await mock(page, async (route, url) => { if (url.pathname === '/workflows' && fail) await json(route, { error: {} }, 503); });
  await connect(page, '/console/workflows');
  const table = page.getByRole('table', { name: 'Workflows' });
  await expect(table.getByRole('link', { name: 'Published flow' })).toHaveAttribute('href', '/console/workflows/wf_pub');
  await expect(table).toContainText('Published'); await expect(table).toContainText('Draft'); await expect(table).toContainText('Not recorded');
  await expect(page.getByRole('button', { name: 'Create' })).toHaveCount(0);
  fail = true;
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.getByText(/refresh failed/)).toBeVisible();
  await expect(table.getByRole('link', { name: 'Published flow' })).toBeVisible();
  await page.getByRole('button', { name: 'Disconnect' }).click();
  await expect(page.getByLabel('Management token')).toBeFocused();
  await expect(page.getByText('Published flow')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Connect to view data' })).toBeVisible();
});

test('6.4 empty workflow list explains how to add workflows', async ({ page }) => {
  await mock(page, async (route, url) => { if (url.pathname === '/workflows') await json(route, []); });
  await connect(page, '/console/workflows');
  await expect(page.getByRole('heading', { name: 'No workflows yet' })).toBeVisible();
  await expect(page.getByText('docs/CONSOLE_DEMO.md')).toBeVisible();
});

test('6.5/6.6 published definition, node table, edges, End, dangling and unknown type; secret never shown', async ({ page }) => {
  await mock(page, async (route, url) => {
    if (url.pathname === '/workflows/wf_pub') await json(route, { id: 'wf_pub', name: 'Published flow', description: 'Refund review', status: 'published',
      definition: definition('Draft copy'), secret_configured: true, created_at: T, updated_at: T, published_at: T, published_definition: definition('Frozen'), published_secret_configured: true });
  });
  await connect(page, '/console/workflows/wf_pub');
  await expect(page.getByRole('heading', { name: 'Published definition (frozen)' })).toBeVisible();
  await expect(page.getByText('webhook — Secret configured')).toBeVisible();
  await expect(page.getByText('(stored; not enforced)')).toBeVisible();
  const nodes = page.getByRole('table', { name: /Nodes in definition order/ });
  await expect(nodes.getByText('Entry')).toBeVisible();
  await expect(nodes).toContainText('If false: End');
  await expect(nodes).toContainText('ghost (missing node)');
  await expect(nodes).toContainText('Unrecognized: "teleport"');
  await nodes.getByRole('link', { name: 'gate' }).click();
  await expect(page.locator('#node-gate')).toBeFocused();
  await page.getByText('Show parameters').first().click();
  await expect(page.getByLabel('Parameters of check')).toContainText('{{trigger.body.amount}}');
  await expect(page.getByRole('link', { name: 'View runs for this workflow' })).toHaveAttribute('href', '/console/runs?workflow=wf_pub');
  expect(await page.content()).not.toContain('"secret"');
});

test('6.5 draft with older publication offers both definitions; 404 shows Not found', async ({ page }) => {
  await mock(page, async (route, url) => {
    if (url.pathname === '/workflows/wf_draft') await json(route, { id: 'wf_draft', name: 'Draft flow', description: null, status: 'draft',
      definition: { ...definition('Draft'), nodes: [{ id: 'only', type: 'delay', params: { seconds: 1 }, next: null }], entry: 'only' },
      secret_configured: false, created_at: T, updated_at: T, published_at: T, published_definition: definition('Old'), published_secret_configured: true });
  });
  await connect(page, '/console/workflows/wf_draft');
  await expect(page.getByRole('heading', { name: 'Draft definition', exact: true })).toBeVisible();
  await expect(page.getByText('New triggers require republishing the draft.')).toBeVisible();
  await page.getByRole('button', { name: 'Last published definition' }).click();
  await expect(page.getByRole('heading', { name: 'Published definition (frozen)' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Last published definition' })).toHaveAttribute('aria-pressed', 'true');
  await page.goto('/console/workflows/missing');
  await page.getByLabel('Management token').fill('view-token'); await page.getByLabel('Management token').press('Enter');
  await expect(page.getByRole('heading', { name: 'Not found' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Back to workflows' })).toBeVisible();
});

test('6.8 run history filters go to the server, paging works, invalid filter sends nothing', async ({ page }) => {
  const seen: string[] = [];
  await mock(page, async (route, url) => {
    if (url.pathname !== '/runs') return;
    seen.push(url.search);
    const cursor = url.searchParams.get('cursor');
    if (!cursor) await json(route, { runs: [run('run_new', { status: 'succeeded', finished_at: T }), run('run_mid')], next_cursor: 'CUR1' });
    else await json(route, { runs: [run('run_old', { status: 'failed' })], next_cursor: null });
  });
  await connect(page, '/console/runs?status=paused');
  await expect(page.getByRole('heading', { name: 'Invalid filter' })).toBeVisible();
  expect(seen).toEqual([]);
  await page.getByRole('button', { name: 'Reset filters' }).first().click();
  await expect(page.getByRole('link', { name: 'run_new' })).toBeVisible();
  await expect(page.getByRole('row', { name: /run_new/ })).toContainText('1 of 10');
  await expect(page.getByRole('table', { name: /Runs, newest first/ })).toContainText('Not finished');
  await page.getByRole('button', { name: 'Next' }).click();
  await expect(page.getByRole('link', { name: 'run_old' })).toBeVisible();
  await expect(page.getByText('End of list')).toBeVisible();
  expect(seen.some(s => s.includes('cursor=CUR1'))).toBe(true);
  await page.getByRole('button', { name: 'Previous' }).click();
  await expect(page.getByRole('link', { name: 'run_new' })).toBeVisible();
  await page.getByLabel('Status').selectOption('failed');
  await expect(page).toHaveURL(/status=failed/);
  await expect.poll(() => seen.some(s => s.includes('status=failed') && !s.includes('cursor'))).toBe(true);
  await page.getByLabel('Workflow').selectOption('wf_pub');
  await expect.poll(() => seen.some(s => s.includes('workflow_id=wf_pub') && s.includes('status=failed'))).toBe(true);
  await page.goBack();
  await expect(page).not.toHaveURL(/workflow=wf_pub/);
});

test('6.8/6.9 run detail loads every step page, shows cap reason and inspects attempts safely', async ({ page }) => {
  const calls: string[] = [];
  const attempts = [
    { attempt_no: 1, status: 'uncertain', cause: 'initial', error: { code: 'unknown_effect' }, output: null, provider: 'mock-http', model: 'alpha-small', tokens_prompt: null, tokens_completion: null, started_at: T, finished_at: null, duration_ms: null },
    { attempt_no: 2, status: 'succeeded', cause: 'recovery', error: null, output: { label: '<img src=x onerror=alert(1)>' }, provider: 'mock-http', model: 'alpha-small', tokens_prompt: 7, tokens_completion: 3, started_at: T, finished_at: T, duration_ms: 40 },
  ];
  await mock(page, async (route, url) => {
    if (url.pathname !== '/runs/run_x') return;
    calls.push(url.search);
    const terminal = { status: 'failed', finished_at: T, error: { code: 'max_steps', node_id: 'n3' }, steps_executed: 3, max_steps: 3 };
    if (!url.searchParams.get('steps_after')) await json(route, run('run_x', { ...terminal, steps: [step(1, { node_type: 'ai', attempts, attempt_count: 2, tokens_prompt: 7, tokens_completion: 3,
      output: { label: '<img src=x onerror=alert(1)>' } }), step(2)], steps_next_after: 2 }));
    else await json(route, run('run_x', { ...terminal, steps: [step(3, { status: 'failed', error: { code: 'max_steps' }, attempts: [] })], steps_next_after: null }));
  });
  const dialogs: string[] = []; page.on('dialog', d => { dialogs.push(d.message()); void d.dismiss(); });
  await connect(page, '/console/runs/run_x');
  await expect(page.getByText('Step cap reached (max_steps)')).toBeVisible();
  await expect(page.getByText('3 of 3 allowed')).toBeVisible();
  await expect(page.locator('.trace-row')).toHaveCount(3);
  expect(calls).toContain('?steps_after=2');
  // Failed row is revealed on first load.
  await expect(page.getByRole('button', { name: /Hide step 3/ })).toHaveAttribute('aria-expanded', 'true');
  await expect(page.getByText('No attempts were prepared for this step.')).toBeVisible();
  await page.getByRole('button', { name: /Inspect step 1/ }).click();
  const attemptsTable = page.getByRole('table', { name: /Attempts for step 1/ });
  await expect(attemptsTable).toContainText('Outcome unknown — the remote call may have completed.');
  await expect(attemptsTable).toContainText('Unknown');
  await expect(attemptsTable).toContainText('10 (prompt 7, completion 3)');
  await expect(page.getByLabel('Output of step 1')).toContainText('<img src=x onerror=alert(1)>');
  await expect(page.getByRole('button', { name: 'Cancel run' })).toHaveCount(0);
  const count = calls.length;
  await page.waitForTimeout(2_600);
  expect(calls.length).toBe(count); // terminal: polling stopped
  await expect(page.getByRole('button', { name: /Hide step 1/ })).toBeVisible();
  expect(dialogs).toEqual([]);
});

test('6.8 cancellation: confirm, Escape, exactly one POST, 202 notice and 409 reconciliation', async ({ page }) => {
  let posts = 0, mode: 'accept' | 'conflict' = 'accept', state: Record<string, unknown> = { status: 'running', steps: [step(1, { status: 'running', finished_at: null, duration_ms: null })] };
  await mock(page, async (route, url, method) => {
    if (url.pathname === '/runs/run_c/cancel' && method === 'POST') {
      posts++;
      if (mode === 'conflict') { state = { status: 'succeeded', finished_at: T }; await json(route, { error: { code: 'conflict' } }, 409); return; }
      state = { ...state, cancel_requested_at: T, cancel_requested_by: 'demo-operator', cancellation_reason: 'operator_cancelled' };
      await json(route, { run_id: 'run_c', status: 'running' }, 202); return;
    }
    if (url.pathname === '/runs/run_c') await json(route, run('run_c', state));
  });
  await connect(page, '/console/runs/run_c');
  await page.getByRole('button', { name: 'Cancel run' }).click();
  await expect(page.getByText('Stop future work; the current step may finish.')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button', { name: 'Cancel run' })).toBeFocused();
  expect(posts).toBe(0);
  await page.getByRole('button', { name: 'Cancel run' }).click();
  await page.getByRole('button', { name: 'Confirm cancel' }).click();
  await expect(page.getByText('Cancellation requested. The current step may finish')).toBeVisible();
  await expect(page.getByText(/Cancellation requested at/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Cancel run' })).toHaveCount(0);
  expect(posts).toBe(1);

  mode = 'conflict'; state = { status: 'running', steps: [] };
  await page.goto('/console/runs/run_c');
  await page.getByLabel('Management token').fill('view-token'); await page.getByLabel('Management token').press('Enter');
  await page.getByRole('button', { name: 'Cancel run' }).click();
  await page.getByRole('button', { name: 'Confirm cancel' }).click();
  await expect(page.getByText('This run changed before cancellation was saved')).toBeVisible();
  await expect(page.locator('.fields').first()).toContainText('Succeeded');
  expect(posts).toBe(2);
});

test('6.8 unknown cancellation blocks repeats until a post-command read reconciles', async ({ page }) => {
  const posts: Record<string, number> = { active: 0, pending: 0, terminal: 0 };
  await mock(page, async (route, url, method) => {
    const match = /^\/runs\/run_(active|pending|terminal)(?:\/cancel)?$/.exec(url.pathname);
    if (!match) return;
    const kind = match[1];
    if (method === 'POST') { posts[kind]++; await route.abort('failed'); return; }
    if (posts[kind] > 0) await new Promise(resolve => setTimeout(resolve, 500));
    const extra = posts[kind] === 0 ? {} : kind === 'pending'
      ? { cancel_requested_at: T, cancel_requested_by: 'demo-operator', cancellation_reason: 'operator_cancelled' }
      : kind === 'terminal' ? { status: 'succeeded', finished_at: T } : {};
    await json(route, run(`run_${kind}`, extra));
  });
  await connect(page, '/console/runs/run_active');
  for (const kind of ['active', 'pending', 'terminal'] as const) {
    if (kind !== 'active') {
      await page.goto(`/console/runs/run_${kind}`);
      await page.getByLabel('Management token').fill('view-token');
      await page.getByLabel('Management token').press('Enter');
    }
    await expect(page.getByRole('button', { name: 'Cancel run' })).toBeVisible();
    await page.getByRole('button', { name: 'Cancel run' }).click();
    await page.getByRole('button', { name: 'Confirm cancel' }).click();
    await expect(page.getByText(/Cancellation outcome unknown/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Cancel run' })).toHaveCount(0);
    if (kind === 'active') {
      await expect(page.getByText(/You may deliberately try again/)).toBeVisible({ timeout: 4_000 });
      await page.getByRole('button', { name: 'Cancel run' }).click();
      await page.getByRole('button', { name: 'Confirm cancel' }).click();
      await expect.poll(() => posts.active).toBe(2);
    } else {
      await expect(page.getByText(/Cancellation outcome reconciled/)).toBeVisible({ timeout: 4_000 });
      await expect(page.getByRole('button', { name: 'Cancel run' })).toHaveCount(0);
    }
  }
  expect(posts).toEqual({ active: 2, pending: 1, terminal: 1 });
});

test('6.9 invalid trace continuation is reported instead of showing a partial trace', async ({ page }) => {
  await mock(page, async (route, url) => {
    if (url.pathname !== '/runs/run_bad_pages') return;
    await json(route, run('run_bad_pages', { steps: [step(url.search ? 2 : 1)], steps_next_after: 1 }));
  });
  await connect(page, '/console/runs/run_bad_pages');
  await expect(page.getByRole('heading', { name: 'Could not load run' })).toBeVisible();
  await expect(page.getByText(/trace is incomplete/)).toBeVisible();
  await expect(page.locator('.trace-row')).toHaveCount(0);
});

test('6.10 approvals: focus, approve once, reject wording, conflict and unknown outcome without resend', async ({ page }) => {
  let pending = [approval('apr_1', 'run_1', 'Refund <b>$250</b>\nsecond line'), approval('apr_2', 'run_2'), approval('apr_3', 'run_3'), approval('apr_4', 'run_4')];
  const posts: string[] = [];
  let slowNext = false;
  await mock(page, async (route, url, method) => {
    if (url.pathname === '/approvals' && method === 'GET') {
      expect(url.searchParams.get('status')).toBe('pending');
      if (slowNext) { slowNext = false; await new Promise(r => setTimeout(r, 1_500)); }
      await json(route, pending); return;
    }
    const m = /^\/approvals\/([^/]+)\/(approve|reject)$/.exec(url.pathname);
    if (m && method === 'POST') {
      posts.push(`${m[1]}:${m[2]}`);
      if (m[1] === 'apr_3') { pending = pending.filter(a => a.id !== 'apr_3'); await json(route, { error: { code: 'conflict' } }, 409); return; }
      if (m[1] === 'apr_4') { slowNext = true; await route.abort('failed'); return; }
      pending = pending.filter(a => a.id !== m[1]);
      await json(route, { run_id: m[1] === 'apr_1' ? 'run_1' : 'run_2', status: m[2] === 'approve' ? 'running' : 'cancelled' });
    }
  });
  await connect(page, '/console/approvals?focus=apr_2');
  await expect(page.getByRole('listitem', { name: 'Approval apr_2' })).toHaveClass(/focused/);
  await expect(page.getByText('Refund <b>$250</b>')).toBeVisible();
  const first = page.getByRole('listitem', { name: 'Approval apr_1' });
  await first.getByRole('button', { name: /^Approve/ }).click();
  await expect(first.getByText('The run will resume.')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(first.getByRole('button', { name: /^Approve/ })).toBeFocused();
  await first.getByRole('button', { name: /^Approve/ }).click();
  await first.getByRole('button', { name: /Confirm approve/ }).click();
  await expect(page.getByText('Approved by the demo operator. Run is now running.')).toBeVisible();
  await expect(page.getByRole('link', { name: 'View run' })).toHaveAttribute('href', '/console/runs/run_1');
  await expect(page.getByRole('listitem', { name: 'Approval apr_1' })).toHaveCount(0);

  const second = page.getByRole('listitem', { name: 'Approval apr_2' });
  await second.getByRole('button', { name: /^Reject/ }).click();
  await expect(second.getByText('This ends the run as cancelled.')).toBeVisible();
  await second.getByRole('button', { name: /Confirm reject/ }).click();
  await expect(page.getByText('Rejected. The run was cancelled.')).toBeVisible();

  const third = page.getByRole('listitem', { name: 'Approval apr_3' });
  await third.getByRole('button', { name: /^Approve/ }).click();
  await third.getByRole('button', { name: /Confirm approve/ }).click();
  await expect(page.getByText('This request changed before your decision was saved.')).toBeVisible();

  const fourth = page.getByRole('listitem', { name: 'Approval apr_4' });
  await fourth.getByRole('button', { name: /^Approve/ }).click();
  await fourth.getByRole('button', { name: /Confirm approve/ }).click();
  await expect(fourth.getByText(/Decision outcome unknown/)).toBeVisible();
  await expect(fourth.getByRole('button', { name: /^Approve/ })).toBeDisabled();
  await expect(fourth.getByText(/You may decide again/)).toBeVisible({ timeout: 6_000 });
  await expect(fourth.getByRole('button', { name: /^Approve/ })).toBeEnabled();
  expect(posts).toEqual(['apr_1:approve', 'apr_2:reject', 'apr_3:approve', 'apr_4:approve']);

  await page.goto('/console/approvals?focus=apr_gone');
  await page.getByLabel('Management token').fill('view-token'); await page.getByLabel('Management token').press('Enter');
  await expect(page.getByText(/is not pending/)).toBeVisible();
});

test('6.10 stale approval data disables actions and an open confirmation until recovery', async ({ page }) => {
  let fail = false, posts = 0;
  await mock(page, async (route, url, method) => {
    if (url.pathname === '/approvals' && method === 'GET') {
      if (fail) await json(route, { error: {} }, 503); else await json(route, [approval('apr_stale', 'run_stale')]);
      return;
    }
    if (url.pathname === '/approvals/apr_stale/approve' && method === 'POST') { posts++; await json(route, { run_id: 'run_stale', status: 'running' }); }
  });
  await connect(page, '/console/approvals');
  const item = page.getByRole('listitem', { name: 'Approval apr_stale' });
  await item.getByRole('button', { name: /^Approve/ }).click();
  fail = true;
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(page.getByText(/Decisions are disabled until/)).toBeVisible();
  await expect(item.getByRole('button', { name: /Confirm approve/ })).toBeDisabled();
  expect(posts).toBe(0);
  fail = false;
  await page.getByRole('button', { name: 'Refresh' }).click();
  await expect(item.getByRole('button', { name: /Confirm approve/ })).toBeEnabled();
  await item.getByRole('button', { name: /Confirm approve/ }).click();
  await expect.poll(() => posts).toBe(1);
});

test('6.10/6.11 empty inbox, first-load failure with Retry, and 401 during polling disconnects', async ({ page }) => {
  let mode: 'fail' | 'empty' | 'unauth' = 'fail';
  await mock(page, async (route, url) => {
    if (url.pathname !== '/approvals') return;
    if (mode === 'fail') await json(route, { error: {} }, 503);
    else if (mode === 'empty') await json(route, []);
    else await json(route, { error: { code: 'unauthorized' } }, 401);
  });
  await connect(page, '/console/approvals');
  await expect(page.getByRole('heading', { name: 'Could not load approvals' })).toBeVisible();
  mode = 'empty';
  await page.getByRole('button', { name: 'Retry' }).click();
  await expect(page.getByRole('heading', { name: 'No pending approvals.' })).toBeVisible();
  mode = 'unauth';
  await expect(page.getByLabel('Management token')).toBeFocused({ timeout: 6_000 });
  await expect(page.getByText('Your token was not accepted.')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'No pending approvals.' })).toHaveCount(0);
});

test('6.11 loading state is labeled and has no fake rows', async ({ page }) => {
  let release!: () => void;
  const gate = new Promise<void>(r => { release = r; });
  await mock(page, async (route, url) => { if (url.pathname === '/runs') { await gate; await json(route, { runs: [], next_cursor: null }); } });
  await connect(page, '/console/runs');
  await expect(page.getByRole('status').filter({ hasText: 'Loading runs…' })).toBeVisible();
  await expect(page.locator('tbody tr')).toHaveCount(0);
  release();
  await expect(page.getByRole('heading', { name: 'No runs yet' })).toBeVisible();
});

test('6.11 320px width: long IDs and JSON stay inside the page on history, trace and inbox', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 720 });
  const long = 'run_' + 'x'.repeat(140);
  await mock(page, async (route, url) => {
    if (url.pathname === '/runs') await json(route, { runs: [run(long)], next_cursor: null });
    else if (url.pathname === `/runs/${long}`) await json(route, run(long, { steps: [step(1, { resolved_input: { blob: 'y'.repeat(600) } })] }));
    else if (url.pathname === '/approvals') await json(route, [approval('apr_' + 'z'.repeat(140), long, 'm'.repeat(400))]);
  });
  await connect(page, '/console/runs');
  const fits = () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
  await expect(page.getByRole('link', { name: long })).toBeVisible();
  expect(await fits()).toBe(true);
  await page.getByRole('link', { name: long }).click();
  await page.getByRole('button', { name: /Inspect step 1/ }).click();
  await expect(page.getByLabel('Input of step 1')).toBeVisible();
  expect(await fits()).toBe(true);
  await page.screenshot({ path: 'test-results/trace-mobile.png', fullPage: true });
  await page.getByRole('link', { name: 'Approvals', exact: true }).click();
  await expect(page.getByRole('button', { name: /^Approve/ })).toBeVisible();
  expect(await fits()).toBe(true);
  await page.screenshot({ path: 'test-results/approvals-mobile.png', fullPage: true });
});

test('6.11 keyboard only: navigate, open a run, confirm and dismiss a decision', async ({ page }) => {
  await mock(page, async (route, url) => {
    if (url.pathname === '/approvals') await json(route, [approval('apr_k', 'run_k')]);
  });
  await connect(page, '/console/approvals');
  await expect(page.getByRole('heading', { name: 'Approvals', exact: true })).toBeFocused();
  const approve = page.getByRole('button', { name: /^Approve/ });
  for (let i = 0; i < 30 && !(await approve.evaluate(el => el === document.activeElement)); i++) await page.keyboard.press('Tab');
  await expect(approve).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: /Confirm approve/ })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(approve).toBeFocused();
  await page.screenshot({ path: 'test-results/approvals-desktop.png', fullPage: true });
});
