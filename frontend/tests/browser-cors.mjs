import assert from 'node:assert/strict';
import http from 'node:http';
import { chromium } from '@playwright/test';

const [apiPort, originPort] = process.argv.slice(2);
const servers = [];
let browser;
async function origin(port) {
  const server = http.createServer((_request, response) => response.end('<!doctype html><title>CORS check</title>'));
  servers.push(server);
  await new Promise(resolve => server.listen(Number(port), '127.0.0.1', resolve));
  return `http://127.0.0.1:${server.address().port}`;
}
try {
  const allowed = await origin(originPort);
  const denied = await origin(0);
  browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto(allowed);
  const url = `http://127.0.0.1:${apiPort}/workflows`;
  const anonymous = await page.evaluate(async url => {
    const response = await fetch(url, { credentials: 'omit' });
    return { status: response.status, body: await response.json(), challenge: response.headers.get('WWW-Authenticate') };
  }, url);
  assert.equal(anonymous.status, 401);
  assert.equal(anonymous.body.error.code, 'unauthorized');
  assert.ok(anonymous.challenge?.startsWith('Bearer'));
  const authenticated = await page.evaluate(async ({ url, token }) => {
    const response = await fetch(url, { credentials: 'omit', headers: { Authorization: `Bearer ${token}` } });
    return response.status;
  }, { url, token: process.env.RELAY_DEMO_TOKEN });
  assert.equal(authenticated, 200);
  await page.goto(denied);
  const blocked = await page.evaluate(async url => {
    try { await fetch(url, { credentials: 'omit' }); return false; }
    catch (error) { return error instanceof TypeError; }
  }, url);
  assert.equal(blocked, true);
  console.log('PASS: real Chromium reads allowed-origin401/challenge and authenticated200 after preflight; rejects unlisted origin');
} finally {
  await browser?.close();
  await Promise.all(servers.map(server => new Promise(resolve => server.close(resolve))));
}
