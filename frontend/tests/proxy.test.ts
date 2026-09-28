import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer as httpServer } from 'node:http';
import { once } from 'node:events';
import type { AddressInfo } from 'node:net';
import { createServer } from 'vite';

test('development proxy preserves API paths/status/auth and excludes console/prefix lookalikes', async () => {
  const received: string[] = [];
  const upstream = httpServer((request, response) => {
    received.push(request.url ?? '');
    assert.equal(request.headers.authorization, 'Bearer test-only');
    response.writeHead(401, { 'Content-Type': 'application/json' });
    response.end('{"error":"test-only"}');
  }).listen(0, '127.0.0.1');
  await once(upstream, 'listening');
  const previous = process.env.VITE_RELAY_API_BASE_URL;
  process.env.VITE_RELAY_API_BASE_URL = `http://127.0.0.1:${(upstream.address() as AddressInfo).port}`;
  let vite;
  try {
    vite = await createServer({ server: { port: 0, open: false } });
    await vite.listen();
    const base = `http://127.0.0.1:${(vite.httpServer!.address() as AddressInfo).port}`;
    for (const path of ['/workflows', '/runs/1', '/approvals?status=pending', '/actuator/health/liveness', '/hooks/wf_1']) {
      const response = await fetch(base + path, { headers: { Authorization: 'Bearer test-only' } });
      assert.equal(response.status, 401);
      assert.deepEqual(await response.json(), { error: 'test-only' });
    }
    for (const path of ['/console/workflows/wf_1', '/workflows-extra']) {
      const response = await fetch(base + path, { headers: { Accept: 'text/html' } });
      assert.equal(response.status, 200);
      assert.match(await response.text(), /<div id="root">/);
    }
    assert.equal(received.length, 5);
  } finally {
    if (previous === undefined) delete process.env.VITE_RELAY_API_BASE_URL;
    else process.env.VITE_RELAY_API_BASE_URL = previous;
    await vite?.close();
    upstream.closeAllConnections();
    await new Promise<void>(resolve => upstream.close(() => resolve()));
  }
});
