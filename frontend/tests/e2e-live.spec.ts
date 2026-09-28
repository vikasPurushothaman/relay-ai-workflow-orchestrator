import { test, expect, type APIRequestContext, type Page } from '@playwright/test';

// Live end-to-end check (task 6.12). Requires a running MySQL/API/worker/mock world; nothing here edits the database.
const API = process.env.RELAY_E2E_API ?? 'http://localhost:8080';
const TOKEN = process.env.RELAY_E2E_TOKEN ?? '';
const EXPENSE_SECRET = process.env.RELAY_E2E_EXPENSE_SECRET ?? '';
test.skip(!TOKEN || !EXPENSE_SECRET, 'Set RELAY_E2E_TOKEN and RELAY_E2E_EXPENSE_SECRET to run against a live stack');

async function connect(page: Page, path: string) {
  await page.goto(path);
  await page.getByLabel('Management token').fill(TOKEN);
  await page.getByLabel('Management token').press('Enter');
  await expect(page.getByRole('button', { name: 'Disconnect' })).toBeVisible();
}
async function expense(request: APIRequestContext, amount: number): Promise<string> {
  const res = await request.post(`${API}/hooks/wf_expense_approval`, { headers: { 'X-Relay-Secret': EXPENSE_SECRET },
    data: { employee_email: 'e2e@example.com', amount_usd: amount, description: `e2e ${amount}` } });
  expect(res.status()).toBe(202);
  return (await res.json()).run_id;
}
async function manual(request: APIRequestContext, workflow: string, input: object): Promise<string> {
  const res = await request.post(`${API}/workflows/${workflow}/trigger`, { headers: { Authorization: `Bearer ${TOKEN}` }, data: { input } });
  expect(res.status()).toBe(202);
  return (await res.json()).run_id;
}
const summary = (page: Page) => page.locator('.fields').first();

test('workflows are listed as published and a definition shows nodes, branches and no secret', async ({ page }) => {
  await connect(page, '/console/workflows');
  const table = page.getByRole('table', { name: 'Workflows' });
  for (const id of ['wf_support_triage', 'wf_expense_approval', 'wf_slow_fulfillment', 'wf_runaway']) await expect(table).toContainText(id);
  await expect(table.getByText('Published')).toHaveCount(4);
  await table.getByRole('link').filter({ hasText: /expense/i }).click();
  await expect(page.getByRole('heading', { name: 'Published definition (frozen)' })).toBeVisible();
  await expect(page.getByText('webhook — Secret configured')).toBeVisible();
  const nodes = page.getByRole('table', { name: /Nodes in definition order/ });
  await expect(nodes).toContainText('finance_gate'); await expect(nodes).toContainText('If true');
  expect(await page.content()).not.toContain(EXPENSE_SECRET);
  await page.screenshot({ path: 'test-results/live-workflow.png', fullPage: true });
});

test('API-triggered run pauses for approval, is approved in the inbox and succeeds', async ({ page, request }) => {
  const runId = await expense(request, 250);
  await connect(page, `/console/runs?workflow=wf_expense_approval`);
  await expect(page.getByRole('link', { name: runId })).toBeVisible();
  await page.getByRole('link', { name: runId }).click();
  await expect(summary(page)).toContainText('Waiting for approval', { timeout: 30_000 });
  await expect(page.getByRole('button', { name: /Hide step 2/ })).toBeVisible();
  const approvalLink = page.getByRole('link', { name: /^apr_/ });
  await expect(approvalLink).toBeVisible();
  await page.screenshot({ path: 'test-results/live-run-waiting.png', fullPage: true });
  await approvalLink.click();
  const item = page.locator('li.approval.focused');
  await expect(item).toContainText(runId);
  await item.getByRole('button', { name: /^Approve/ }).click();
  await item.getByRole('button', { name: /Confirm approve/ }).click();
  await expect(page.getByText(/Approved by the demo operator/)).toBeVisible();
  await page.getByRole('link', { name: 'View run' }).click();
  await expect(summary(page)).toContainText('Succeeded', { timeout: 30_000 });
  await page.getByRole('button', { name: /Inspect step 2/ }).click();
  await expect(page.locator('#step-detail-2')).toContainText('demo-operator');
  await expect(page.locator('#step-detail-2')).toContainText('Approved');
  await page.screenshot({ path: 'test-results/live-run-approved.png', fullPage: true });
  expect(page.url()).not.toContain(TOKEN);
  expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain(TOKEN);
});

test('rejecting in the inbox cancels the run and the trace records it as a rejection', async ({ page, request }) => {
  const runId = await expense(request, 900);
  await connect(page, '/console/approvals');
  const item = page.locator('li.approval').filter({ hasText: runId });
  await expect(item).toBeVisible({ timeout: 30_000 });
  await item.getByRole('button', { name: /^Reject/ }).click();
  await expect(item.getByText('This ends the run as cancelled.')).toBeVisible();
  await item.getByRole('button', { name: /Confirm reject/ }).click();
  await expect(page.getByText('Rejected. The run was cancelled.')).toBeVisible();
  await page.getByRole('link', { name: 'View run' }).click();
  await expect(summary(page)).toContainText('Cancelled');
  await expect(summary(page)).toContainText('Cancelled because an approval was rejected.');
});

test('small expense takes the automatic branch without a human', async ({ page, request }) => {
  const runId = await expense(request, 40);
  await connect(page, `/console/runs/${runId}`);
  await expect(summary(page)).toContainText('Succeeded', { timeout: 30_000 });
  await expect(page.locator('.trace')).toContainText('auto_ok');
  await expect(page.locator('.trace')).not.toContainText('finance_gate');
});

test('runaway loop is stopped by the step cap and the reason is visible', async ({ page, request }) => {
  const runId = await manual(request, 'wf_runaway', {});
  await connect(page, `/console/runs/${runId}`);
  await expect(summary(page)).toContainText('Failed', { timeout: 60_000 });
  await expect(summary(page)).toContainText('Step cap reached (max_steps)');
  await expect(summary(page)).toContainText('12 of 12 allowed');
  await expect(page.locator('.trace-row')).toHaveCount(12);
});

test('a run waiting in a durable delay can be cancelled from the console', async ({ page, request }) => {
  const runId = await manual(request, 'wf_slow_fulfillment', { order_id: 'ord_2003', customer_email: 'lena@example.com' });
  await connect(page, `/console/runs/${runId}`);
  await expect(page.locator('.trace')).toContainText('(delay)', { timeout: 30_000 });
  await page.getByRole('button', { name: 'Cancel run' }).click();
  await page.getByRole('button', { name: 'Confirm cancel' }).click();
  await expect(page.getByText(/Run cancelled|Cancellation requested/)).toBeVisible();
  await expect(summary(page)).toContainText('Cancelled', { timeout: 30_000 });
  await expect(page.locator('.trace')).not.toContainText('create_shipment');
});
