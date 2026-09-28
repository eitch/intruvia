# Intruvia — implementation specification

Version: 1.0 · Date: 2026-09-28 · State: implementation-ready design; software not implemented.

## 1. Purpose and MVP boundary

Intruvia is a self-hosted, read-only security-event viewer. Fail2ban instances on multiple servers report ban events to an authenticated API. Intruvia normalizes and persists each event, enriches its source IP using a local MaxMind database, and pushes it to authenticated browsers. A world map highlights each newly received, geolocated event immediately.

The implementation must use Java, Strolch, JAX-RS, embedded Jetty, Maven, and a Vanilla JavaScript UI. The earlier conversation references the structure of Chronivaro, but its source code and dependency versions were not supplied. Reuse established project conventions when available; do not claim exact compatibility until task 001 verifies the build and runtime combination.

### Included

- Fail2ban BAN notifications only, with per-server ingestion credentials.
- Generic, versioned security-event envelope and a Fail2ban adapter.
- Durable event history, retry deduplication, bounded history API.
- Local MaxMind GeoLite2 City or GeoIP2 City enrichment for IPv4 and IPv6.
- Authenticated world map, recent-event list, connection state, and live highlighting.
- Reconnect recovery, basic operational diagnostics, bounded storage and queues.
- Installation guidance and an additive Fail2ban notification action.

### Explicitly deferred

Global firewall or blocklist management; ban/unban commands; bidirectional control; unban ingestion and active-ban state; additional event sources; generic public ingestion API; agents beyond the small notification helper; correlation, advanced analytics, heatmaps, risk scoring, ASN enrichment, threat intelligence; alerting/email; event export; full-text search; multi-tenancy; HA/clustering; message brokers; mobile apps; administrative UI; automatic historical re-enrichment. A received ban is an observation, not proof of an attack or the current firewall state.

## 2. Architecture and module boundaries

```text
Fail2ban action → notification helper → HTTPS POST
                                      ↓
Jetty / JAX-RS → authentication → Fail2ban adapter → Strolch ingestion service
                                                       ↓ local GeoIP lookup
                                                       ↓ transactional commit
                                              durable Strolch event store
                                                       ↓ committed sequence
                                             WebSocket stream component
                                                       ↓ WSS
                                       Vanilla JS map + recent-event list
```

Use one application process and one Strolch realm. Run behind a TLS reverse proxy or configure Jetty TLS; bind only to loopback by default behind a proxy. No external broker or separate frontend server.

| Maven module | Responsibility |
|---|---|
| `intruvia-core` | Domain contracts, normalization, Strolch model/services/commands/searches, persistence, GeoIP component, ordered event reading |
| `intruvia-rest` | JAX-RS resources, input/output DTOs, authentication integration, WebSocket endpoint; depends on core |
| `intruvia-web` | Packaged HTML, CSS, ES modules and pinned local map-library assets; no framework or required Node build |
| `intruvia-app` | Embedded Jetty bootstrap, Strolch configuration, runtime wiring and distribution; assembles other modules |

Root POM centralizes dependency management, compiler and test settings. Task 001 pins a supported Java LTS and a compatible Strolch/JAX-RS/Jetty/WebSocket combination; do not mix `javax` and `jakarta` APIs. Prefer the user's existing Strolch bootstrap, session, privilege and packaging conventions. No Spring or parallel ORM.

Strolch owns component lifecycle and persistence access. REST resources delegate to services; commands perform mutations inside transactions; searches provide reads. Map `SecurityEvent`, `EventStreamState`, and `IngestReceipt` to Strolch Resources with typed parameter bags. Keep serialization DTOs separate from mutable model elements. Use the supported Strolch PostgreSQL persistence implementation for production, with transaction rollback and restart recovery verified against the pinned release. Do not store production events solely in memory or raw log files. This module/design mapping is an Intruvia design choice, not a claim about existing project code.

## 3. Security-event model

One immutable `SecurityEvent` describes one observation. Repeated bans of the same IP are distinct observations. No uniqueness rule on IP, jail, or time alone.

| Field | Type / requirement / meaning |
|---|---|
| `schemaVersion` | Integer, initially 1 |
| `id` | Server-generated UUID |
| `sequence` | Monotonically increasing committed 64-bit integer, exposed as decimal string to JavaScript |
| `source.type` | Namespaced string; MVP `fail2ban` |
| `source.instanceId` | Stable configured server ID, resolved from authenticated credential |
| `source.eventId` | Producer UUID, stable across all retries of the same notification |
| `category` | MVP `network.security`; extensible vocabulary |
| `action` | MVP `ban`; future adapters may use other names |
| `occurredAt` | Producer RFC 3339 time normalized to UTC |
| `receivedAt` | Server UTC time at first successful ingestion |
| `subject.ip` | Canonical literal IP address; IPv4-mapped IPv6 normalized consistently to IPv4 |
| `observer.id` | Same configured server ID in MVP; observer means the host that performed the ban |
| `attributes.fail2ban.jail` | Required string |
| `attributes.fail2ban.failures` | Optional nonnegative integer |
| `geo.status` | `FOUND`, `NOT_FOUND`, `NON_PUBLIC`, `UNAVAILABLE`, or `ERROR` |
| `geo.countryCode`, `countryName`, `region`, `city` | Optional strings |
| `geo.latitude`, `longitude` | Both nullable; finite numbers in valid geographic ranges when present |
| `geo.accuracyRadiusKm` | Optional nonnegative number |
| `geo.provider`, `databaseEdition`, `databaseBuildAt`, `lookedUpAt` | Provenance; provider `maxmind` for attempted database lookups; nullable where unavailable |

Use typed bags for envelope/source/subject/observer/geo fields and a versioned, size-limited namespaced attributes representation. Task 003 documents the exact mapping supported by the pinned Strolch release. Future adapters can map into this envelope without altering the Fail2ban API. Do not implement an extension plugin framework now. Unknown future schema versions fail clearly; missing optional fields are tolerated in the UI. Do not store raw matching log lines, passwords, or arbitrary inbound JSON.

`EventStreamState` persists the last committed sequence and the minimum replay cursor. `IngestReceipt` stores `(instanceId, source.eventId)`, canonical validated payload digest, event ID, sequence, and expiry. Never include enrichment or server timestamps in the payload digest.

### Transaction and ordering invariants

Serialize ingestion commits in this single-process MVP using the stream-state lock and supported Strolch transaction locking. Atomically allocate the next sequence, write event and receipt, and advance stream state. Persist nothing and emit nothing on failure. Enrichment occurs before acquiring the write lock. Recheck deduplication under lock. Concurrent identical retries yield one event; identical keys with changed normalized payload yield conflict. An original request that committed but lost its HTTP response can safely retry.

The WebSocket dispatcher reads committed events in sequence order from storage. A post-commit wakeup accelerates delivery; a periodic catch-up check (250 ms default) handles a missed wakeup. A transaction callback alone is not the durable delivery mechanism. No distributed ordering or exactly-once network delivery is promised.

## 4. Fail2ban API

`POST /api/v1/fail2ban/events`, `Content-Type: application/json`, `Authorization: Bearer <server-token>`.

```json
{
  "schemaVersion": 1,
  "eventId": "55c42ae2-865b-47b8-bc4b-c166c0344c5c",
  "occurredAt": "2026-09-28T12:00:00Z",
  "action": "ban",
  "ip": "203.0.113.42",
  "jail": "sshd",
  "failures": 7
}
```

This documentation IP is deliberately non-public and must not become a real map location. No caller-supplied server identity or GeoIP data is accepted. A credential binds to exactly one configured instance ID and `event:ingest` privilege; credential rotation retains that instance ID.

Validation: body at most 16 KiB; exactly supported version/action; eventId UUID; IP literal only (no DNS lookup, hostname, zone ID or CIDR); nonempty jail at most 128 characters without control characters; failures optional integer 0–2,147,483,647. Reject unknown input fields. Require timezone-aware timestamp, reject more than five minutes in the future or more than seven days old. Perform authentication before expensive work. Limits are configurable with documented defaults.

New commit returns `201` with `{id, sequence, duplicate:false, geoStatus}`. Identical retry returns `200` with the original id and sequence and `duplicate:true`, without new enrichment or publication. Receipt deduplication is evaluated before timestamp-age rejection for a previously accepted event. Malformed JSON/values: `400`; missing/invalid credential: `401`; insufficient privilege: `403`; changed payload for same key: `409`; body too large: `413`; wrong media type: `415`; configured capacity/rate limit: `429` with `Retry-After`; persistence unavailable: `503`. All failures return `{error:{code,message,requestId}}`; omit tokens, stack traces and sensitive details.

Default per-instance token-bucket limit: 10 events/second, burst 100; tune during capacity verification. Tokens are cryptographically random, at least 256 bits, provisioned through protected configuration with hashed verifiers, never committed or logged. Rate limits and body bounds also apply to unauthenticated traffic via global connection/request limits.

### Fail2ban integration

Ship a small Python 3 standard-library helper and action template. Add the notification action alongside the host's existing ban action; preserve its firewall behavior. Only `actionban` notifies. Pass documented Fail2ban values as arguments without shell evaluation; encode JSON with a serializer. Keep token in a root-readable file, never in action arguments, URLs or source control. Verify TLS certificates and set finite connect/read timeouts.

Generate eventId and timestamp once per invocation; reuse the exact payload for every retry. Retry network failures, `429`, and `5xx` at most three total attempts with jitter and an overall 15-second deadline; respect Retry-After within that deadline. Do not retry other `4xx`. Notification failure must not prevent the normal ban action. Log a concise local failure. A persistent offline queue and guaranteed delivery through long outages are deferred: events can be lost after bounded retries. Document this limitation prominently.

## 5. MaxMind enrichment

Use local City MMDB files, loaded through MaxMind's Java database reader. Provision/update downloads externally with `geoipupdate` and protected MaxMind credentials; there is no per-event external lookup or database download. City enrichment is sufficient for MVP; ASN requires a separate dataset and is deferred. See [Java API](https://maxmind.github.io/GeoIP2-java/) and [database updates](https://dev.maxmind.com/geoip/updating-databases/).

Reuse a reader across lookups. Never overwrite an actively mapped database in place. Download to a staging path, validate it, atomically switch the configured file, then replace the reader while allowing existing lookups to finish. A failed update retains the last working reader; startup with no usable database enters degraded mode and still accepts events as `UNAVAILABLE`. Corrupt/read failures yield `ERROR` and diagnostics, not failed ingestion. Classify non-global/private/reserved addresses using a documented tested IPv4/IPv6 policy; return `NON_PUBLIC` without lookup. Missing records yield `NOT_FOUND`. Partial records may be `FOUND` without coordinates and remain unmapped.

Store the enrichment snapshot and database build metadata on each event. Missing coordinates remain null, never `(0,0)`. Do not exclude legitimate zero coordinates. GeoIP is approximate IP-network location, not a person's physical location. Display this explanation and dataset/map attribution. Deployments obtain their own licensed database; do not commit or redistribute MMDB files. Track last successful load and database age; default stale warning after 14 days. Updating the database does not rewrite event history.

## 6. Browser authentication and read API

Use Strolch-backed viewer sessions and privileges, isolated from machine tokens. Implement same-origin session login/logout using the pinned project's session convention; expose it through `/api/v1/session` if no existing project route is available. Require `event:read` for history and WebSocket connections. Cookies: HttpOnly, Secure in production, SameSite=Strict, restricted path. Use CSRF protection for session-changing requests and Origin checks. No token in localStorage or WebSocket URLs. Provision viewer users through protected Strolch configuration; no signup or user-management UI. Session expiry/logout closes active sockets and returns the UI to login.

`GET /api/v1/events/snapshot?limit=1000` returns latest events ascending by sequence, `cursor` (last committed sequence at snapshot), `minAfter`, and `truncated`. Default/max limit 1000; bounds and capture are consistent under the ingestion/retention lock. Each event uses the canonical DTO above. Snapshot cursor is a high-water mark even when older events were omitted. `minAfter` is the lowest accepted exclusive replay cursor; it is `0` before anything is removed. This endpoint is the initial map bootstrap, not a full-history download.

`GET /api/v1/events?after=<sequence>&limit=500` returns ascending `items`, `nextAfter`, `hasMore`, and fixed `through` high-water mark. Subsequent pages include `through` to finish the same catch-up window. Default 500, max 1000. `after` is exclusive; no offsets. Beyond-current/malformed cursor: `400`; a cursor below `minAfter`: `410 resync_required`. No general search/filter engine in MVP.

## 7. WebSocket protocol and recovery

Endpoint `/api/v1/events/stream?after=<sequence>`. Authenticate the session and validate exact allowed Origin before upgrade; require the same read privilege as REST. Cursor is not a credential. Version every frame with `version:1`.

Server sends `hello` containing `head` and `minAfter`, then committed event frames ascending by sequence:

```json
{"version":1,"type":"event.created","sequence":"42","event":{"schemaVersion":1,"id":"<uuid>","sequence":"42"}}
```

The example omits the remaining event fields for brevity; actual frames include the full canonical event DTO. Client messages do not implement application commands. Server also supports `resync_required` and non-sensitive `error` frames. Use transport ping/pong and close dead connections; reconnect with jittered exponential delay from 1 to 30 seconds. Default per-connection outbound queue 1000 frames, message cap 64 KiB, send timeout 10 seconds; disconnect a slow consumer instead of dropping individual events silently or blocking ingestion.

Bootstrap: load snapshot, render it without new-event animation, retain its cursor, then open stream after that cursor. The server replays retained events after the cursor and continues tailing the same ordered durable log; never subscribe only to future transient notifications. Capture/retention and replay reads coordinate so deleted ranges produce an explicit resync, not silent gaps. A reconnect resumes after the last fully applied sequence. Deduplicate by sequence/event ID. Duplicates do not highlight twice. Replayed events missing from the browser do highlight. Update the cursor only after applying each event to the browser model.

An expired cursor triggers a fresh snapshot and a visible “Some older live updates are no longer available” notice; do not imply gap-free history beyond retention. On restart, persisted sequences and events support recovery. When the browser is hidden or animations are throttled, retain data and show a new-events count on return; do not claim background rendering guarantees.

## 8. World-map UI

Use Vanilla JS ES modules and a pinned, locally packaged Leaflet build. Use a locally packaged, licensed simplified world-boundary GeoJSON layer as the default basemap, avoiding mandatory third-party tile traffic and secrets. Retain provenance and attribution for the chosen boundary dataset. No map build service is required.

Show a full-world map, recent-event list, logged-in state, live/reconnecting/offline indicator, unmapped count, and degraded GeoIP notice. Each marker popup safely renders IP, server, jail, occurred/received time, location and accuracy when available. Use text nodes rather than injecting event HTML. Provide keyboard-accessible list entries and readable contrast.

On every newly applied geolocated event, immediately add its marker and start a conspicuous three-second pulse. Several events at identical coordinates retain independent identities and pulses; use staggered/concentric rings and a count at the shared location. Do not cluster away the new-event indication. Do not recenter the user's map on every event. Off-screen arrivals get an immediate list highlight and a “show on map” action. Under prefers-reduced-motion use a three-second high-contrast static halo instead of animation.

Unmapped events enter the list immediately with a reason and increment the unmapped count; never manufacture a map point. Counts describe the current visible window only. Keep at most 1000 recent events/markers and evict the oldest by sequence. Snapshot and live paths use the same render model. Show an explicit rolling-window label; this is not an all-time dashboard. At visible foreground baseline load, target receipt-to-highlight <=100 ms at p95 and commit-to-highlight <=1 second at p95; these are acceptance targets, not measured claims.

## 9. Storage, configuration and operations

Default event retention: 30 days and maximum 100,000 events, whichever bound is reached first. A scheduled daily age sweep and admission-time capacity pruning remove only the oldest contiguous sequence prefix. Advance `minAfter` atomically with removal. Receipts survive at least 30 days from ingestion, independently of event capacity pruning; a duplicate whose event was pruned still returns the original id/sequence. After receipt expiry, the old producer timestamp is outside the seven-day acceptance window and is rejected. Bound receipt storage at 1,000,000 records; if full after expired-receipt cleanup, reject new ingestion with `429` rather than evicting unexpired receipts. Reassess defaults during capacity testing.

Configure DB connection, realm, bind/proxy/TLS settings, server credential mappings, viewer users, GeoIP path, time/size/rate/retention/queue limits through documented external configuration. Reject unsafe/malformed settings at startup. Never bundle production credentials. Production schema/model changes have versioned Strolch migrations and a documented backup/restore procedure.

`/health/live` exposes only process liveness; `/health/ready` reports readiness without sensitive data. DB failure makes readiness fail; unavailable GeoIP is degraded but ingestion-ready. Authenticated operator diagnostics show last successful GeoIP load, build age, ingest failures/duplicates, stream connections and queue disconnects. Use structured request IDs and bounded logs, excluding tokens and raw payloads. IPs appear only in protected event data and explicitly enabled restricted diagnostics. Do not expose generic Strolch write/management endpoints to viewers.

Graceful shutdown stops accepting writes, completes or rolls back in-flight transactions, closes sockets and components, and closes GeoIP readers. PostgreSQL unavailable means no successful acknowledgement and no live event. Crash after commit before push is repaired by durable replay. Backup/restore preserves events, receipts and stream state together; restoring an older backup requires clients to resnapshot when their cursor exceeds head.

## 10. Verification and release gate

Unit tests cover normalization, schema validation, timestamps, GeoIP partial/missing results, digest equivalence and authorization decisions. Use deterministic enrichment fixtures and licensed/test MMDB fixtures; tests never need live MaxMind credentials.

Integration tests use actual Strolch persistence and embedded Jetty: concurrent duplicate requests, conflict, rollback, restart, retained receipts, strict read privileges, Origin rejection, session expiry, ordered replay, slow clients, and retention resync. A crash-window test commits without a dispatcher wakeup and proves catch-up delivery.

Browser end-to-end test: two server credentials submit distinct public-IP fixture events; each produces exactly one persisted event and visible highlight without refresh; a repeated retry does not produce another pulse. Test overlapping points, IPv6, unmapped addresses, reconnect between snapshot and stream, reduced motion, and payload text that resembles HTML. Test foreground latency with 10 events/second sustained for five minutes, 100-event burst, two browsers and 100,000 retained events; record hardware, browser and measured p95. Backpressure must preserve durable events even if a browser needs to reconnect.

Release requires tasks 001–022 complete, successful clean Maven verification, restart/backup/restore evidence, reproducible deployment, documented credentials/database provisioning, and explicit notification-delivery limitations. This specification does not claim implementation or tests already exist.

## 11. Reference basis and unresolved implementation details

The user has fixed the scope and stack. This document selects concrete MVP defaults for persistence, retention, transport and UI to remove ambiguity. Task 001 verifies exact versions and integration APIs against an available sibling project or upstream sample; task 003 fixes the exact Strolch parameter mapping. Neither task may broaden scope silently.

Strolch's [architecture](https://strolch.li/documentation/architecture/) separates REST, services/commands, searches and components. Its [transaction documentation](https://strolch.li/documentation/transactions/) describes transaction outcomes and explicit locking, and [realm documentation](https://strolch.li/documentation/realms/) describes PostgreSQL persistence configuration. Consult the pinned release when implementing those mechanisms. Jetty's [WebSocket server documentation](https://jetty.org/docs/jetty/12/programming-guide/server/websocket.html) provides the integration reference if a compatible Jetty 12 line is selected. These references were checked on 2026-09-28; they do not select dependency versions for the project.
