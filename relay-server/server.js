#!/usr/bin/env node
/**
 * LIVE VIP SMART RELAY SERVER
 * ==================================
 *
 *   PHONE ── ONE upstream RTMP ──► THIS SERVER ──┬──► Destination A (YouTube)
 *                                               ├──► Destination B
 *                                               └──► Destination C …
 *
 * Why: the phone uploads ONE stream regardless of destination count, so
 * mobile upload bandwidth never grows linearly with destinations.
 *
 * How: node-media-server accepts the RTMP publish; each destination is an
 * `ffmpeg -c copy` process pulling the local upstream and pushing to the
 * destination (no re-encoding — near-zero CPU per destination). Each
 * fan-out has independent restart-with-backoff and never affects the others.
 *
 * SECURITY:
 *  - All API calls require `Authorization: Bearer $RELAY_TOKEN`.
 *  - Destination stream keys are held in memory only and NEVER logged.
 *  - Put this server behind HTTPS (reverse proxy) — stream keys travel in
 *    the create-session request body.
 *
 * ENV:
 *  RELAY_TOKEN        (required) API bearer token
 *  HTTP_PORT          default 8080  — control API
 *  RTMP_PORT          default 1935  — ingest
 *  RTMP_PUBLIC_HOST   optional      — advertised ingest host (default: request Host)
 *  FFMPEG_PATH        default ffmpeg
 */

'use strict';

const http = require('node:http');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');

const NodeMediaServer = require('node-media-server');

const RELAY_TOKEN = process.env.RELAY_TOKEN;
const HTTP_PORT = parseInt(process.env.HTTP_PORT || '8080', 10);
const RTMP_PORT = parseInt(process.env.RTMP_PORT || '1935', 10);
const FFMPEG_PATH = process.env.FFMPEG_PATH || 'ffmpeg';

if (!RELAY_TOKEN) {
  console.error('REFUSING TO START: set RELAY_TOKEN to a strong random value.');
  process.exit(1);
}

/** sessionId -> session */
const sessions = new Map();

const log = (...args) => console.log('[relay]', ...args);
const logWarn = (...args) => console.warn('[relay]', ...args);

/** Never let keys reach the logs. */
function maskUrl(url) {
  if (typeof url !== 'string') return '<invalid>';
  const i = url.lastIndexOf('/');
  return i > 0 ? url.slice(0, i + 1) + '***' : url;
}

// ------------------------------------------------------------------
// Session model
// ------------------------------------------------------------------

class FanOut {
  constructor(destination) {
    this.name = destination.name || 'destination';
    this.url = `${destination.url.replace(/\/+$/, '')}/${destination.key || ''}`;
    this.state = 'waiting'; // waiting|starting|live|reconnecting|failed|stopped
    this.restarts = 0;
    this.detail = null;
    this.proc = null;
    this.retryTimer = null;
    this.stopped = false;
  }

  start(sessionId) {
    if (this.stopped) return;
    this.state = 'starting';
    const args = [
      '-hide_banner', '-loglevel', 'error',
      '-rw_timeout', '10000000',
      '-i', `rtmp://127.0.0.1:${RTMP_PORT}/live/${sessionId}`,
      '-c', 'copy',
      '-f', 'flv',
      this.url,
    ];
    log(`fan-out start: ${this.name} -> ${maskUrl(this.url)}`);
    const proc = spawn(FFMPEG_PATH, args, { stdio: ['ignore', 'ignore', 'pipe'] });
    this.proc = proc;
    let stderrTail = '';

    proc.stderr.on('data', (chunk) => {
      stderrTail = (stderrTail + chunk.toString()).slice(-400);
    });

    proc.on('spawn', () => {
      // "live" once ffmpeg has been publishing for a moment without dying.
      setTimeout(() => {
        if (this.proc === proc && this.state === 'starting') {
          this.state = 'live';
          this.detail = null;
        }
      }, 2500);
    });

    proc.on('error', (err) => {
      this.state = 'failed';
      this.detail = `ffmpeg not available: ${err.message}`;
      logWarn(`fan-out error (${this.name}): ${err.message}`);
    });

    proc.on('close', (code) => {
      if (this.stopped) return;
      this.proc = null;
      const session = sessions.get(sessionId);
      const upstreamAlive = session && session.upstream === 'publishing';
      if (!upstreamAlive) {
        this.state = 'stopped';
        return;
      }
      // Upstream alive but our push died → restart with backoff.
      this.restarts += 1;
      if (this.restarts > 20) {
        this.state = 'failed';
        this.detail = 'too many restarts';
        logWarn(`fan-out failed permanently: ${this.name}`);
        return;
      }
      this.state = 'reconnecting';
      this.detail = stderrTail.split('\n').filter(Boolean).pop() || null;
      const delay = Math.min(30000, 1000 * Math.pow(2, Math.min(this.restarts, 5)));
      logWarn(`fan-out exited (code ${code}); restart #${this.restarts} of ${this.name} in ${delay}ms`);
      this.retryTimer = setTimeout(() => this.start(sessionId), delay);
    });
  }

  stop() {
    this.stopped = true;
    if (this.retryTimer) clearTimeout(this.retryTimer);
    if (this.proc) {
      try { this.proc.kill('SIGKILL'); } catch (_) { /* noop */ }
      this.proc = null;
    }
    this.state = 'stopped';
  }
}

class Session {
  constructor(name, destinations) {
    this.id = crypto.randomBytes(12).toString('hex');
    this.name = name || 'live';
    this.createdAt = Date.now();
    this.upstream = 'waiting'; // waiting|publishing|ended
    this.fanOuts = destinations.map((d) => new FanOut(d));
  }

  onUpstreamStarted() {
    this.upstream = 'publishing';
    this.fanOuts.forEach((f) => f.start(this.id));
  }

  onUpstreamEnded() {
    this.upstream = 'ended';
    this.fanOuts.forEach((f) => f.stop());
    // Keep the record around so the app can read final status, then GC.
    setTimeout(() => sessions.delete(this.id), 10 * 60 * 1000).unref();
  }

  stopAll() {
    this.fanOuts.forEach((f) => f.stop());
  }

  status() {
    return {
      sessionId: this.id,
      name: this.name,
      upstream: this.upstream,
      destinations: this.fanOuts.map((f) => ({
        name: f.name,
        state: f.state,
        restarts: f.restarts,
        detail: f.detail,
      })),
    };
  }
}

// ------------------------------------------------------------------
// RTMP ingest (node-media-server)
// ------------------------------------------------------------------

const nms = new NodeMediaServer({
  rtmp: { port: RTMP_PORT, chunk_size: 4096, gop_cache: false, ping: 30, ping_timeout: 60 },
  http: false, // no HTTP-FLV/API exposure
});

nms.on('prePublish', (id, streamPath) => {
  const key = streamPath.replace('/live/', '');
  const session = sessions.get(key);
  if (!session) {
    log(`rejecting unknown publish: ${key.slice(0, 4)}***`);
    const s = nms.getSession(id);
    if (s) s.reject();
    return;
  }
  log(`upstream publishing: session ${key.slice(0, 4)}***`);
  session.onUpstreamStarted();
});

nms.on('donePublish', (id, streamPath) => {
  const key = streamPath.replace('/live/', '');
  const session = sessions.get(key);
  if (session) {
    log(`upstream ended: session ${key.slice(0, 4)}***`);
    session.onUpstreamEnded();
  }
});

// ------------------------------------------------------------------
// Control API (plain node:http — no extra dependencies)
// ------------------------------------------------------------------

function readBody(req, limit = 512 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) { reject(new Error('body too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

function authorized(req) {
  const header = req.headers.authorization || '';
  return header === `Bearer ${RELAY_TOKEN}`;
}

function ingestUrlFor(req) {
  const host = process.env.RTMP_PUBLIC_HOST ||
    (req.headers.host ? req.headers.host.split(':')[0] : '127.0.0.1');
  return `rtmp://${host}:${RTMP_PORT}/live`;
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const path = url.pathname;

  try {
    if (!authorized(req)) {
      sendJson(res, 401, { error: 'unauthorized' });
      return;
    }

    if (req.method === 'GET' && path === '/api/v1/health') {
      sendJson(res, 200, { ok: true, sessions: sessions.size, uptimeSec: Math.floor(process.uptime()) });
      return;
    }

    if (req.method === 'POST' && path === '/api/v1/sessions') {
      const raw = await readBody(req);
      const body = JSON.parse(raw || '{}');
      const destinations = Array.isArray(body.destinations) ? body.destinations : [];
      if (destinations.length === 0) {
        sendJson(res, 400, { error: 'destinations required' });
        return;
      }
      for (const d of destinations) {
        if (!d || typeof d.url !== 'string' || !/^rtmps?:\/\//i.test(d.url)) {
          sendJson(res, 400, { error: 'each destination needs a valid rtmp/rtmps url' });
          return;
        }
      }
      const session = new Session(body.name, destinations);
      sessions.set(session.id, session);
      log(`session created: ${session.id.slice(0, 4)}*** (${destinations.length} destinations)`);
      sendJson(res, 201, {
        sessionId: session.id,
        ingest: { url: ingestUrlFor(req), key: session.id },
      });
      return;
    }

    const sessionMatch = path.match(/^\/api\/v1\/sessions\/([a-f0-9]+)$/);
    if (sessionMatch) {
      const session = sessions.get(sessionMatch[1]);
      if (!session) { sendJson(res, 404, { error: 'session not found' }); return; }

      if (req.method === 'GET') {
        sendJson(res, 200, session.status());
        return;
      }
      if (req.method === 'DELETE') {
        session.stopAll();
        session.upstream = 'ended';
        sessions.delete(session.id);
        log(`session ended by client: ${session.id.slice(0, 4)}***`);
        sendJson(res, 200, { ok: true });
        return;
      }
    }

    sendJson(res, 404, { error: 'not found' });
  } catch (err) {
    logWarn(`api error: ${err.message}`);
    sendJson(res, 500, { error: 'internal error' });
  }
});

// ------------------------------------------------------------------
// Start
// ------------------------------------------------------------------

nms.run();
server.listen(HTTP_PORT, () => {
  log(`control API on :${HTTP_PORT} (Bearer token auth)`);
  log(`rtmp ingest on :${RTMP_PORT}`);
});

function shutdown() {
  log('shutting down…');
  sessions.forEach((s) => s.stopAll());
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000).unref();
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
