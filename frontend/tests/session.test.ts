import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ManagementSession, errorMessage } from '../src/session.ts';
import { ApiError } from '../src/api.ts';

test('successful list confirms access without retaining rows in snapshot', async () => {
  const session = new ManagementSession(async (_, options) => { assert.equal(options?.token, 'secret'); return { workflows: [{ id: 'wf', status: 'draft', secret: 'hidden' }] }; });
  await session.connect('secret');
  assert.equal(session.snapshot().phase, 'connected');
  assert.ok(!JSON.stringify(session.snapshot()).includes('secret'));
  session.disconnect(); assert.equal(session.snapshot().phase, 'disconnected');
});
for (const value of [null, {}, { workflows: [{}] }, [{ id: 'wf', status: 'unknown' }]]) {
  test('invalid list does not confirm connection ' + JSON.stringify(value), async () => {
    const session = new ManagementSession(async () => value);
    await session.connect('secret');
    assert.equal(session.snapshot().phase, 'disconnected');
    assert.match(session.snapshot().message, /format/);
  });
}
for (const status of [400, 401, 403, 404, 409, 429, 500]) {
  test('connection error ' + status, async () => {
    const session = new ManagementSession(async () => { throw new ApiError('http', status); });
    await session.connect('secret'); assert.equal(session.snapshot().phase, 'disconnected');
    assert.equal(session.snapshot().message, errorMessage(new ApiError('http', status)));
  });
}
test('disconnect aborts connection and late response cannot connect', async () => {
  let resolve!: (value: unknown) => void; let signal: AbortSignal | undefined;
  const session = new ManagementSession(async (_, options) => { signal = options?.signal; return new Promise(r => { resolve = r; }); });
  const pending = session.connect('old'); session.disconnect();
  assert.equal(signal?.aborted, true); resolve([]); await pending;
  assert.equal(session.snapshot().phase, 'disconnected');
});
test('401 clears session, 403 preserves it; no mutation replay', async () => {
  let calls = 0; let status = 403;
  const session = new ManagementSession(async path => { calls++; if (path === '/workflows') return []; throw new ApiError('http', status); });
  await session.connect('secret');
  await assert.rejects(session.request('/approvals/a/approve', { method: 'POST' }));
  assert.equal(session.snapshot().phase, 'connected'); assert.equal(calls, 2);
  status = 401; await assert.rejects(session.request('/runs'));
  assert.equal(session.snapshot().phase, 'disconnected'); assert.equal(calls, 3);
});
test('old unauthorized response cannot clear a newer session', async () => {
  let reject!: (error: Error) => void;
  const session = new ManagementSession(async path => path === '/workflows' ? [] : new Promise((_, r) => { reject = r; }));
  await session.connect('old'); const pending = session.request('/runs');
  await session.connect('new'); reject(new ApiError('http', 401));
  await assert.rejects(pending, (e: ApiError) => e.kind === 'aborted');
  assert.equal(session.snapshot().phase, 'connected');
});
test('blank token sends nothing, network and timeout remain distinct', async () => {
  let calls = 0; const session = new ManagementSession(async () => { calls++; return []; });
  await session.connect(' '); assert.equal(calls, 0);
  assert.notEqual(errorMessage(new ApiError('network')), errorMessage(new ApiError('timeout')));
});
