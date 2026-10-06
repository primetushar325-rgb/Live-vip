# Live VIP Smart Relay Server

One upstream, N destinations:

```
PHONE ── ONE upstream RTMP ──► RELAY ──┬──► YouTube A
                                       ├──► YouTube B
                                       └──► YouTube C …
```

**Why:** streaming directly to 3 destinations at 6 Mbps costs the phone
~18 Mbps upload. With the relay the phone uploads **one** 6 Mbps stream;
the relay's server uplink handles the fan-out.

**How:** `node-media-server` accepts the RTMP publish; each destination is an
`ffmpeg -c copy` process pulling the local upstream (codec copy — no
re-encoding, near-zero CPU per destination). Every fan-out has independent
restart-with-backoff, so one failing destination never affects the others.

## Deploy

```bash
# quick local run
RELAY_TOKEN=$(openssl rand -hex 24) RTMP_PUBLIC_HOST=your.server.com node server.js

# docker
docker compose up -d --build
```

Requirements: Node ≥ 18 (or Docker) and `ffmpeg` on PATH.

## Configure in the app

Settings → **Smart Relay**:
- API URL: `https://relay.example.com` (HTTPS required — stream keys are sent here)
- Token: the same `RELAY_TOKEN`

Then choose **Smart Relay** as the broadcast mode in any project with multiple
destinations.

## API (Bearer token auth)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/sessions` | Register destinations → returns private ingest URL + key |
| `GET` | `/api/v1/sessions/:id` | Real per-destination fan-out status |
| `DELETE` | `/api/v1/sessions/:id` | End session, stop all fan-outs |
| `GET` | `/api/v1/health` | Liveness + active session count |

Session body:

```json
{
  "name": "My Music Live",
  "destinations": [
    { "name": "YouTube A", "platform": "YOUTUBE", "url": "rtmp://a.rtmp.youtube.com/live2", "key": "…" },
    { "name": "YouTube B", "platform": "YOUTUBE", "url": "rtmp://b.rtmp.youtube.com/live2", "key": "…" }
  ]
}
```

The relay auto-expires sessions when the upstream ends. Unknown publish keys
are rejected. Destination stream keys live only in process memory and are
**never logged**.

## Security notes

- Put the API behind TLS (Caddy/nginx/Cloudflare). The RTMP port itself stays
  plain RTMP (TLS terminates at the API level; the ingest key is a random
  session id).
- Run `npm test` for the API logic tests.
- Capacity: each fan-out is codec-copy, so a 2-vCPU box typically handles
  10+ simultaneous 1080p fan-outs — size your uplink as
  `bitrate × destinationCount × 1.3`.
