import { test, expect } from '@playwright/test';

for (const [path, title] of [
  ['/console/workflows', 'Workflows'], ['/console/workflows/wf_1', 'Workflow detail'],
  ['/console/runs', 'Runs'], ['/console/runs/run_1', 'Run detail'], ['/console/approvals', 'Approvals'],
]) {
  test(`direct load ${path}`, async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    const apiRequests: string[] = [];
    page.on('request', request => { if (/^\/(workflows|runs|approvals)(\/|\?|$)/.test(new URL(request.url()).pathname)) apiRequests.push(request.url()); });
    await page.goto(path);
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeFocused();
    await expect(page.getByRole('heading', { name: 'Connect to view data' })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
    expect(errors).toEqual([]);
    expect(apiRequests).toEqual([]);
  });
}
test('root, navigation, history and current link', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/console\/workflows$/);
  await page.getByRole('link', { name: 'Runs', exact: true }).click();
  await expect(page.getByRole('link', { name: 'Runs', exact: true })).toHaveAttribute('aria-current', 'page');
  await page.getByRole('link', { name: 'Approvals', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Approvals', exact: true })).toBeFocused();
  await page.goBack();
  await expect(page.getByRole('heading', { name: 'Runs', exact: true })).toBeFocused();
  await page.goForward();
  await expect(page.getByRole('heading', { name: 'Approvals', exact: true })).toBeVisible();
});
test('not found offers recovery', async ({ page }) => {
  await page.goto('/console/unknown');
  await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
  await page.getByRole('link', { name: 'Go to Workflows' }).click();
  await expect(page).toHaveURL(/\/console\/workflows$/);
});
test('320px layout and escaped long ID', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 720 });
  const id = '<script>alert(1)</script>' + 'x'.repeat(150);
  await page.goto('/console/workflows/' + encodeURIComponent(id));
  await expect(page.getByRole('heading', { name: 'Workflow detail', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.querySelectorAll('script:not([type="module"])').length)).toBe(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await expect(page.getByRole('link', { name: 'Approvals', exact: true })).toBeVisible();
  await page.screenshot({ path: 'test-results/console-mobile.png', fullPage: true });
});
test('keyboard navigation and desktop rendering', async ({ page }) => {
  await page.goto('/console/workflows');
  await page.getByRole('link', { name: 'Runs', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Runs', exact: true })).toBeFocused();
  await page.screenshot({ path: 'test-results/console-desktop.png', fullPage: true });
});

test('connect, disconnect, reload and memory-only token', async ({ page }) => {
  await page.route('**/workflows', async route => {
    if (new URL(route.request().url()).pathname === '/console/workflows') { await route.continue(); return; }
    expect(route.request().headers().authorization).toBe('Bearer browser-test-token');
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
  });
  await page.goto('/console/workflows');
  await page.getByLabel('Management token').fill('browser-test-token');
  await page.getByLabel('Management token').press('Enter');
  await expect(page.getByRole('button', { name: 'Disconnect' })).toBeVisible();
  expect(await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }))).not.toContain('browser-test-token');
  expect(page.url()).not.toContain('browser-test-token');
  await page.getByRole('button', { name: 'Disconnect' }).click();
  await expect(page.getByLabel('Management token')).toBeFocused();
  await expect(page.getByLabel('Management token')).toHaveValue('');
  await page.getByLabel('Management token').fill('browser-test-token');
  await page.getByRole('button', { name: 'Connect', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Disconnect' })).toBeVisible();
  await page.reload();
  await expect(page.getByLabel('Management token')).toHaveValue('');
});
for (const status of [401, 404]) {
  test(`connection failure ${status} stays disconnected`, async ({ page }) => {
    await page.route('http://localhost:8080/workflows', route => route.fulfill({ status, contentType: 'application/json', body: '{}' }));
    await page.goto('/console/workflows');
    await page.getByLabel('Management token').fill('browser-test-token');
    await page.getByRole('button', { name: 'Connect', exact: true }).click();
    await expect(page.getByRole('status')).toContainText(status === 401 ? 'not accepted' : 'not found');
    await expect(page.getByLabel('Management token')).toBeFocused();
    await expect(page.getByRole('button', { name: 'Disconnect' })).toHaveCount(0);
  });
}
