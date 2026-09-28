import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, apiOrigin, createApiClient, pathSegment } from '../src/api.ts';

const base = 'http://localhost:8080';
const json = (data: unknown) => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } });
const hasKind = (kind: string, status?: number) => (error: unknown) => {
  assert.ok(error instanceof ApiError);
  assert.equal(error.kind, kind);
  assert.equal(error.status, status);
  assert.ok(!error.message.includes('private-sentinel'));
  return true;
};

for (const value of ['', 'http://user:private-sentinel@host', 'https://host/path', 'file:///tmp', 'https://host?token=private-sentinel', 'https://host#x', ' http://host']) {
  test(`reject invalid API origin ${value.split(':')[0]}`, () => assert.throws(() => apiOrigin(value), hasKind('configuration')));
}
test('origin and path segments', () => {
  assert.equal(apiOrigin('https://relay.test/'), 'https://relay.test');
  assert.equal(pathSegment('a/b?c'), 'a%2Fb%3Fc');
  for (const id of ['', '.', '..']) assert.throws(() => pathSegment(id));
});
for (const path of ['https://other.test/workflows', '//other.test/workflows', '/console/workflows', '/workflows/../../outside', '/workflows\\other', '/workflows#secret']) {
  test('reject path ' + path, async () => {
    const client = createApiClient(base, async () => { throw new Error('must not fetch'); });
    await assert.rejects(client(path), hasKind('configuration'));
  });
}
test('JSON mutation, exact origin, token and no browser credentials/redirect/cache', async () => {
  let calls = 0;
  const client = createApiClient(base, async (url, options) => {
    calls++;
    assert.equal(String(url), base + '/workflows');
    assert.equal(options?.method, 'POST');
    assert.equal(options?.body, '{"name":"test"}');
    const headers = new Headers(options?.headers);
    assert.equal(headers.get('Authorization'), 'Bearer private-sentinel');
    assert.equal(headers.get('Content-Type'), 'application/json');
    assert.equal(options?.credentials, 'omit');
    assert.equal(options?.redirect, 'error');
    assert.equal(options?.cache, 'no-store');
    return json({ id: 'wf_1' });
  });
  assert.deepEqual(await client('/workflows', { method: 'POST', token: 'private-sentinel', body: { name: 'test' } }), { id: 'wf_1' });
  assert.equal(calls, 1);
});
test('204 has no JSON body, token is not retained between requests', async () => {
  const seen: (string | null)[] = [];
  const client = createApiClient(base, async (_, options) => {
    seen.push(new Headers(options?.headers).get('Authorization'));
    return new Response(null, { status: 204 });
  });
  assert.equal(await client('/runs/1', { token: 'secret' }), undefined);
  await client('/runs/2');
  assert.deepEqual(seen, ['Bearer secret', null]);
});
for (const status of [401, 403, 404, 409, 429, 500]) {
  test(`HTTP ${status} sanitized, mutation never retried`, async () => {
    let calls = 0;
    const client = createApiClient(base, async () => { calls++; return new Response('private-sentinel', { status }); });
    await assert.rejects(client('/approvals/1/approve', { method: 'POST' }), hasKind('http', status));
    assert.equal(calls, 1);
  });
}
for (const type of ['text/html', 'application/json']) {
  test('invalid response ' + type, async () => {
    const client = createApiClient(base, async () => new Response('private-sentinel', { headers: { 'Content-Type': type } }));
    await assert.rejects(client('/workflows'), hasKind('format'));
  });
}
test('network failure sanitized', async () => {
  const client = createApiClient(base, async () => { throw new Error('private-sentinel'); });
  await assert.rejects(client('/workflows'), hasKind('network'));
});
const pending: typeof fetch = async (_, options) => new Promise((_, reject) => {
  if (options?.signal?.aborted) reject(new Error('aborted'));
  options?.signal?.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
});
test('timeout bounded and classified', async () => {
  await assert.rejects(createApiClient(base, pending, 5)('/runs/1', { method: 'POST' }), hasKind('timeout'));
});
test('pre-aborted and in-flight requests cancel', async () => {
  const controller = new AbortController();
  const client = createApiClient(base, pending);
  const result = client('/runs/1', { signal: controller.signal });
  controller.abort();
  await assert.rejects(result, hasKind('aborted'));
  await assert.rejects(client('/runs/2', { signal: controller.signal }), hasKind('aborted'));
});
test('aborting one concurrent request does not cancel another', async () => {
  const client = createApiClient(base, async (url, options) => String(url).endsWith('/1') ? pending(url, options) : json({ id: 2 }));
  const controller = new AbortController();
  const first = client('/runs/1', { signal: controller.signal });
  const second = client('/runs/2');
  controller.abort();
  await assert.rejects(first, hasKind('aborted'));
  assert.deepEqual(await second, { id: 2 });
});
test('invalid timeout and GET body fail before transport', async () => {
  for (const timeout of [0, -1, 60001, 1.5]) assert.throws(() => createApiClient(base, fetch, timeout), hasKind('configuration'));
  await assert.rejects(createApiClient(base)('/runs', { body: {} }), hasKind('configuration'));
});

test('invalid header and circular body are sanitized before fetching', async () => {
  const client = createApiClient(base, async () => { throw new Error('must not fetch'); });
  await assert.rejects(client('/workflows', { token: 'private-sentinel\ninvalid' }), hasKind('configuration'));
  const body: Record<string, unknown> = {}; body.self = body;
  await assert.rejects(client('/workflows', { method: 'POST', body }), hasKind('configuration'));
});
