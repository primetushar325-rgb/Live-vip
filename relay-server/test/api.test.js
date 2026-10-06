#!/usr/bin/env node
/**
 * Relay API logic tests (no RTMP traffic — the API layer only).
 * Run: npm test  (in relay-server/)
 *
 * These verify: auth enforcement, session creation validation, ingest URL
 * format, status lifecycle fields and the fan-out state model.
 */

'use strict';

const http = require('node:http');
const assert = require('node:assert');
const crypto = require('node:child_process');

process.env.RELAY_TOKEN = 'test-token-123';

// Reuse the same masking/url conventions as server.js by importing behavior
// through a tiny harness: we start the real server module with stubbed NMS.
// To keep the test dependency-free we spawn the server as a child process.
const SERVER = require.resolve('../server.js');
const { spawn } = crypto;

const HTTP_PORT = 18080;
const RTMP_PORT = 11935;

const child = spawn(process.execPath, [SERVER], {
  env: {
    ...process.env,
    RELAY_TOKEN: 'test-token-123',
    HTTP_PORT: String(HTTP_PORT),
    RTMP_PORT: String(RTMP_PORT),
    RTMP_PUBLIC_HOST: 'relay.example.com',
  },
  stdio: ['ignore', 'pipe', 'pipe'],
});

let output = '';
child.stdout.on('data', (c) => { output += c.toString(); });
child.stderr.on('data', (c) => { output += c.toString(); });

function request(method, path, body, token) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : null;
    const req = http.request({
      host: '127.0.0.1', port: HTTP_PORT, method, path,
      headers: {
        ...(token ? { authorization: `Bearer ${token}` } : {}),
        ...(data ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(data) } : {}),
      },
    }, (res) => {
      let buf = '';
      res.on('data', (c) => { buf += c.toString(); });
      res.on('end', () => {
        try { resolve({ code: res.statusCode, body: JSON.parse(buf || '{}') }); }
        catch (e) { reject(e); }
      });
    });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

async function waitForServer(tries = 50) {
  for (let i = 0; i < tries; i++) {
    try {
      await request('GET', '/api/v1/health', null, 'test-token-123');
      return;
    } catch (_) {
      await new Promise((r) => setTimeout(r, 100));
    }
  }
  throw new Error('server did not start. output:\n' + output);
}

async function main() {
  await waitForServer();

  // 1. Auth is enforced.
  const noAuth = await request('GET', '/api/v1/health', null, null);
  assert.strictEqual(noAuth.code, 401, 'unauthenticated request must be rejected');

  // 2. Health works with token.
  const health = await request('GET', '/api/v1/health', null, 'test-token-123');
  assert.strictEqual(health.code, 200);
  assert.strictEqual(health.body.ok, true);

  // 3. Session creation requires destinations.
  const bad = await request('POST', '/api/v1/sessions', { name: 'x', destinations: [] }, 'test-token-123');
  assert.strictEqual(bad.code, 400);

  // 4. Valid session creation returns ingest url + key.
  const created = await request('POST', '/api/v1/sessions', {
    name: 'My Music Live',
    destinations: [
      { name: 'YouTube A', platform: 'YOUTUBE', url: 'rtmp://a.rtmp.youtube.com/live2', key: 'aaa-111' },
      { name: 'YouTube B', platform: 'YOUTUBE', url: 'rtmp://b.rtmp.youtube.com/live2', key: 'bbb-222' },
      { name: 'YouTube C', platform: 'YOUTUBE', url: 'rtmp://c.rtmp.youtube.com/live2', key: 'ccc-333' },
    ],
  }, 'test-token-123');
  assert.strictEqual(created.code, 201);
  assert.ok(created.body.sessionId, 'sessionId present');
  assert.strictEqual(created.body.ingest.url, 'rtmp://relay.example.com:11935/live');
  assert.strictEqual(created.body.ingest.key, created.body.sessionId);

  // 5. Status endpoint returns per-destination states (waiting until publish).
  const status = await request('GET', `/api/v1/sessions/${created.body.sessionId}`, null, 'test-token-123');
  assert.strictEqual(status.code, 200);
  assert.strictEqual(status.body.upstream, 'waiting');
  assert.strictEqual(status.body.destinations.length, 3);
  assert.ok(status.body.destinations.every((d) => d.state === 'waiting'));

  // 6. Stream keys never appear in server output.
  await new Promise((r) => setTimeout(r, 200));
  assert.ok(!output.includes('aaa-111') && !output.includes('bbb-222'),
    'stream keys must never be logged');

  // 7. DELETE ends the session.
  const deleted = await request('DELETE', `/api/v1/sessions/${created.body.sessionId}`, null, 'test-token-123');
  assert.strictEqual(deleted.code, 200);
  const after = await request('GET', `/api/v1/sessions/${created.body.sessionId}`, null, 'test-token-123');
  assert.strictEqual(after.code, 404);

  console.log('✓ relay API tests passed (7/7)');
  child.kill('SIGKILL');
  process.exit(0);
}

main().catch((err) => {
  console.error('✗ relay API tests FAILED:', err.message);
  console.error(output);
  child.kill('SIGKILL');
  process.exit(1);
});
