# Intruvia developer reference

This document collects contributor-focused build, verification, architecture, and
implementation notes. For a product overview, see the [root README](../README.md);
for installation and operations, see the [administrator guide](./DEPLOYMENT.md).
The [implementation specification](./INTRUVIA_SPECIFICATION.md), [backlog](./INTRUVIA_BACKLOG.md),
and [status ledger](./INTRUVIA_BACKLOG_STATUS.md) track requirements and progress.

## Deployment

Administrators should follow the [installation and recovery guide](./DEPLOYMENT.md)
for Compose or standalone setup, credentials, upgrades, backups and recovery.
For the combined versioned image, administrator bundle, Git tag and GitHub release,
see the [release procedure](./RELEASE.md). The separate
[GitHub source-release procedure](./GITHUB_RELEASE.md) remains available for a
source-only release and Mastodon announcement.

## Run the next implementation task

The runner needs Bash, `flock`, and an authenticated Codex CLI. It finds Codex on
PATH or uses the desktop app's bundled `/usr/lib/chatgpt/resources/codex` executable.
To select another installation, set `CODEX_BIN=/full/path/to/codex`.
If authentication is needed, run the selected executable with `login` (for example,
`/usr/lib/chatgpt/resources/codex login`). From the project directory, run:

```bash
./next-task.sh --dry-run
./next-task.sh
```

You can also invoke the script by absolute path from any directory. Each invocation
starts a fresh Codex CLI session using your configured model and authentication.
It instructs Codex to resume an in-progress task or choose the first eligible TODO,
implement and verify it, and update the ledger. Run it again for the following task.
This does not continue the desktop conversation; project files provide its context.

The run can edit project files and execute checks in the workspace-write sandbox.
If permissions or prerequisites prevent completion, the agent should record the
blocker. Review its final report and the status ledger: a successful CLI exit alone
does not prove that the task is DONE. Avoid editing the same task concurrently in
another session. The script lock prevents overlapping invocations of this script.

See [runner details](./TASK_RUNNER.md) and the official
[Codex non-interactive documentation](https://developers.openai.com/codex/noninteractive).

## License

Intruvia is licensed under the GNU Affero General Public License, version 3.0
(SPDX: `AGPL-3.0-only`). See [LICENSE](../LICENSE) for the full terms.
Third-party components retain their respective licenses.

## Verified runtime baseline

The build pins Java 25, Strolch, Jersey and Jetty; an isolated compatibility probe
verifies their integration. See the [baseline decision](./architecture/001-runtime-baseline.md) for exact
versions, integration conventions and verification limits. Run it with Java 25:

```bash
JAVA_HOME=/path/to/jdk-25 PATH=/path/to/jdk-25/bin:$PATH \
  mvn -B -f probes/runtime-baseline/pom.xml clean verify
```

The probe starts Strolch/Jetty on loopback, verifies REST and WebSocket exchange,
and shuts both down. It needs Maven dependency access but no PostgreSQL server.
The complete MVP viewer is available below.

To retry a blocked task after fixing its environment:

```bash
./next-task.sh --retry-blocked
```

The runner enables outbound network access for dependency downloads and permits
writes to `~/.m2/repository` while retaining the workspace filesystem sandbox.
If Maven uses a custom local repository, set `MAVEN_REPOSITORY` to that absolute path.
The retry checks the blocker again and preserves previous failure evidence.

## Development build and checks

Use Java 25 and Maven from the repository root. For build, release and local run
instructions, see the [release procedure](./RELEASE.md).
For host installation and runtime provisioning, see the
[administrator guide](./DEPLOYMENT.md#standalone-installation).

Run the PostgreSQL-backed verification with:

```bash
scripts/verify-postgresql.sh
```

### Strolch framework version

Intruvia pins Strolch `2.8.0-1` and resolves the published artifacts from
`https://repo.strolch.li/repository/strolch-releases/`. Normal verification uses the
POM's release coordinate; no local snapshot installation or version override is needed.
The published release and checksums used in the PostgreSQL validation are recorded in
the [Strolch 2.8.0-1 validation report](./verification/023-acceptance.md#strolch-280-1-release-validation).
If your Maven cache previously received a locally installed framework artifact under
the same release coordinate, use a fresh local repository for provenance checks, for
example `scripts/verify-postgresql.sh -Dmaven.repo.local=/path/to/empty/repository`;
a version string alone does not guarantee that cached bytes came from the release repo.

`mvn verify` requires a disposable PostgreSQL service. `scripts/verify-postgresql.sh`
starts a loopback Docker PostgreSQL container, runs `mvn -B clean verify`, and removes
the container on exit. Each test provisions and drops a unique database. Docker and
Java 25 must already be installed. The default image is `postgres:18-bookworm`; set
`INTRUVIA_TEST_POSTGRES_IMAGE` to a local image or immutable digest when needed.
Alternatively, supply `INTRUVIA_TEST_DB_URL`, `INTRUVIA_TEST_DB_USERNAME` and
`INTRUVIA_TEST_DB_PASSWORD` for a dedicated test PostgreSQL role with CREATEDB and
run `mvn -B clean verify`. Never point tests at production. No browser/Node build is required.

`mvn verify` and the application-level tests do not install or configure a persistent
runtime. Follow the [administrator guide](./DEPLOYMENT.md) for runtime setup and
first start.

## Event contracts (v1)

The core module now provides immutable event, GeoIP, receipt and stream-state contracts
and a typed Strolch Resource mapper. See the [field mapping](./architecture/003-event-model.md),
[v1 API schema](./api/v1.schema.json) and [complete synthetic fixture](./api/event-v1.json).
Sequences are decimal strings on the wire; absent coordinates remain null. The fixture
uses a documentation IPv6 address with invented geography solely for serialization tests;
real ingestion classifies that address as NON_PUBLIC. The ingestion HTTP endpoint is available below. Run `scripts/verify-postgresql.sh` with Java 25 to verify JSON and
Strolch XML round trips, including IPv6, partial locations and 64-bit sequence precision.

## Machine ingestion credentials

Machine authentication uses Strolch Personal Access Tokens (PATs). The ingestion
endpoint is `POST /api/v1/fail2ban/events`. Send `Authorization: Bearer <tokenId>:<tokenValue>` over
HTTPS. Tokens must have exactly the `event:ingest` privilege; viewer/session credentials
and broad PATs are rejected.

The authenticated Strolch username maps to a stable server identity; callers cannot
choose that identity in the request. Revocation, expiry and disabled-user state are
checked for cached PATs, without an Intruvia restart. No public token-administration
endpoint is exposed. See the [administrator guide](./DEPLOYMENT.md#offline-users-and-pats)
for provisioning and rotation, and the [verified framework API and implementation
details](./architecture/005-machine-authentication.md).

The pinned Strolch release includes the upstream PAT persistence fixes. Its runtime and
database compatibility results are recorded in the
[Strolch 2.8.0-1 validation report](./verification/023-acceptance.md#strolch-280-1-release-validation).

## Fail2ban input validation

The public ingestion endpoint uses a strict, bounded JSON parser and canonical
Fail2ban adapter. Inputs are limited to
16 KiB by default; unknown/duplicate fields, invalid literals and malformed values
are rejected. IPv4-mapped IPv6 becomes IPv4, timestamps become UTC, and equivalent
payloads share a stable digest. Jail text remains plain data, with a 128-character
Unicode limit. Accepted retries can be checked before the separate seven-day age /
five-minute future policy. See the [validation table and integration contract](./architecture/006-fail2ban-validation.md)
for limits, constructor configuration, digest format and trusted envelope mapping.

Run focused validation and contract tests without PostgreSQL using Java 25:

```bash
mvn -B -pl intruvia-rest -am clean test
```

## Local GeoIP enrichment

GeoIP enrichment uses a lifecycle-managed reader for a locally supplied MaxMind City
database. Missing or unreadable data leaves GeoIP degraded (`UNAVAILABLE`) while the
application starts; private/reserved addresses yield `NON_PUBLIC`, and missing records
and lookup failures have distinct statuses. Partial locations remain unmapped, and
legitimate zero coordinates are preserved. GeoIP is approximate network location, not
a person's physical location. See the [administrator guide](./DEPLOYMENT.md#geoip-fail2ban-and-retention)
for provisioning and updates, and the [enrichment policy and fixtures](./architecture/007-geoip-enrichment.md).

## Atomic ingestion service

Validated Fail2ban events are processed by a Strolch service. Application code invokes
a fresh `IngestService` through the registered `ServiceHandler`, using the
producer's framework certificate and the protected identity mapping. It requires
`event:ingest`; identity comes from the certificate username. GeoIP runs before the
write transaction, and event, receipt and sequence commit atomically. Equal retries
return the original ID/sequence, including after event pruning; changed payloads
conflict. An accepted retry bypasses timestamp-age rejection and new enrichment.

The public POST endpoint delegates to this service. See the [service contract and locking
protocol](./architecture/009-atomic-ingestion.md) for result handling and later
retention integration. Run `scripts/verify-postgresql.sh` with Java 25 for the
real-database concurrency, rollback and retry checks.

## Fail2ban ingestion HTTP endpoint

Send the v1 JSON payload from the [specification](./INTRUVIA_SPECIFICATION.md#4-fail2ban-api)
to `POST /api/v1/fail2ban/events`, with `Content-Type: application/json` and the
Strolch PAT Authorization header described above. The request body must contain only
producer fields, never a server identity or geography. The certificate and protected
mapping select the server identity, and the same certificate authorizes the service.

A committed event returns `201` with `id`, decimal-string `sequence`, `duplicate:false`
and `geoStatus`. An identical retry returns `200` with the original `id`, `sequence`
and `duplicate:true`; it omits `geoStatus` because a retained receipt may outlive its
event. Changed retries return `409`. Validation, authentication, privilege, size and
media failures return `400`, `401`, `403`, `413` and `415`; database failures return
`503` without acknowledging success. Errors have `{error:{code,message,requestId}}`.
Responses use `Cache-Control: no-store` and do not echo payloads or credentials.

Defaults are a 16 KiB UTF-8 body, seven-day maximum event age, five-minute future
allowance, and 10 requests/second with burst 100 per configured instance. All
authenticated attempts, including retries and rejected payloads, consume that
instance's bucket. PAT rotation shares the bucket. A separate global bucket allows
100 API requests/second with burst 1000 before authentication; Jetty caps connections
at 256 with a 30-second idle timeout. Request-rate rejection returns `429` with
integer `Retry-After` seconds; connection saturation pauses accepts. No request queues
or rate state are persisted; restart resets buckets. Compressed request bodies are
unsupported. See [runtime settings and bounds](./architecture/010-ingestion-http.md)
for configuration details.

See [HTTP design, setting bounds and checks](./architecture/010-ingestion-http.md).
Verify with Java 25 and `scripts/verify-postgresql.sh`.
Receipt-capacity admission and ordered WebSocket delivery are available.

## Viewer sessions

Viewer sessions require the configured exact origin; missing configuration disables
them. HTTP is allowed only for explicit loopback development origins. HTTPS origins
receive Secure, HttpOnly, SameSite=Strict cookies restricted to `/api/v1`; no token
belongs in browser storage. See the [administrator guide](./DEPLOYMENT.md#provision-the-external-runtime-while-stopped)
for user/runtime provisioning and the [session contract](./architecture/011-viewer-sessions.md).

The same-origin client logs in with `POST /api/v1/session`, JSON `{username,password}`,
`Content-Type: application/json` and `X-Intruvia-CSRF: 1`; the browser sends Origin.
`GET /api/v1/session` checks the cookie session. `DELETE /api/v1/session` logs out
with the same CSRF header and Origin. Both login and session checks return only
`{username}`. Sessions expire after at most 30 minutes by default, and logout
invalidates the shared REST/future WebSocket validator immediately. Authorization
headers and machine PATs cannot authenticate viewer routes.

The login/map snapshot UI is available at `/`; the WebSocket endpoint is described below.
Verify sessions and the full regression suite with Java 25 and
`scripts/verify-postgresql.sh`.

## Viewer snapshots and history

Use the viewer session cookie from login to request `GET /api/v1/events/snapshot`.
It returns `{items,cursor,minAfter,truncated}` with the latest 1000 events in ascending
sequence order. Set `limit=1` through `1000` for a smaller window. `cursor` captures
the committed head, including events omitted from the window; it is the future stream
bootstrap cursor. `minAfter` is the oldest accepted exclusive history cursor.

For history, request `GET /api/v1/events?after=0&limit=500`. It returns
`{items,nextAfter,hasMore,through}`. Pass both `after=nextAfter` and the first page's
`through` on every following page until `hasMore` is false. New commits do not change
that catch-up window. A new request without `through` captures a new head. Limits are
1–1000, default 500. All sequence values are decimal strings; keep them as strings
in browser code. `after` is required and exclusive; there are no offset/search filters.

Malformed or ahead-of-head cursors and invalid limits return `400`. A cursor below
`minAfter` returns `410` with `error.code=resync_required`; load a new snapshot.
These routes require a valid viewer cookie and `event:read`; machine PATs and Bearer
headers cannot authenticate reads. Responses are uncached. Automatic retention is available as described below. See [read contracts and consistency](./architecture/012-event-reads.md)
and [v1 schemas](./api/v1.schema.json). Verify with Java 25 and
`scripts/verify-postgresql.sh`.


## Bounded event retention

Events are retained for 30 days from ingestion, with a maximum of 100,000 events.
A daily sweep removes expired events; new ingestion also prunes the oldest prefix
when needed. Snapshot `minAfter` advances atomically with removal. History below
that cursor returns `410 resync_required`; load a fresh snapshot.

Receipts remain independently for at least 30 days, so an identical retry can still
return the original ID/sequence after its event is pruned. Receipt storage is capped
at 1,000,000 records. When no slot remains after expiry cleanup, new ingestion returns
`429` with `error.code=receipt_capacity` and `Retry-After: 86400`. Unexpired receipts
are preserved. The delay recommends when to retry; it does not guarantee available
capacity. Ordinary request-rate limits also remain in effect.

Receipt lifetime cannot be less than 30 days. See the [administrator guide](./DEPLOYMENT.md)
for runtime setup, and [retention settings, transaction semantics and verification](./architecture/013-retention.md).
Run the full PostgreSQL suite with Java 25 and `scripts/verify-postgresql.sh`.


## Ordered live event stream

After loading the viewer snapshot, connect a same-origin browser WebSocket to
`/api/v1/events/stream?after=<snapshot.cursor>` using its viewer session cookie.
Use WSS through the TLS reverse proxy. The server requires the exact configured
viewer Origin and `event:read`; machine PATs and Authorization headers are rejected.
Only the canonical decimal cursor is accepted in the query string.

The server sends a version-1 `hello` with string `head`/`minAfter`, replays retained
`event.created` frames in ascending sequence, and continues reading committed events.
Frames include the full canonical event. Reconnect after the last event your client
fully applied, deduplicating sequence/event IDs. An expired or ahead cursor produces
`resync_required` and closes the socket; obtain a new snapshot. Session logout or
expiry closes active sockets. Text/binary client commands are unsupported and close
the connection.

Each connection is bounded to 1000 pending/in-flight frames, 64 KiB per message and a
10-second send deadline; slow consumers disconnect and recover through replay.
Ping/pong detects dead connections. A 250 ms durable poll recovers a missed post-commit
wakeup. Upgrades share the REST global request bucket and Jetty connection cap.

See the [protocol, lifecycle and recovery contract](./architecture/014-event-stream.md).
Verify with Java 25 and `scripts/verify-postgresql.sh`.
Live browser highlighting and automatic recovery are available below.

## Snapshot viewer

Open `/` on the configured viewer origin and sign in with a provisioned Viewer
account. The map and keyboard-accessible recent list show a rolling window of at
most 1000 events. Use **Refresh snapshot** to update it, **Event details** for full
metadata, and **Show on map** for mapped events. Logout clears the displayed events.
Existing sessions are restored after reload; expired sessions return to login on
the next read. Counts describe the visible window, not all-time activity.

Initial snapshots have no live pulses. After loading a snapshot, the page connects
its authenticated live stream. Each new mapped event gets an independent three-second
pulse; shared locations show a count and concentric/staggered rings. With reduced
motion, the indication is a static high-contrast halo. New list rows highlight for
three seconds, including unmapped events. Arrivals preserve the map view; use
**Show on map** to find an off-screen event. Oldest events and their markers are
evicted to keep the rolling window at 1000. Repeated frames do not highlight twice.
Disconnected streams reconnect automatically using jittered exponential delays from
1–2 seconds up to 15–30 seconds, resuming after the last fully applied event.
Session expiry returns to login. An expired or ahead cursor reloads the snapshot
and leaves a visible “Some older live updates are no longer available” notice;
recovery snapshots have no pulses and cannot recover history beyond retention.
Failed recovery reads preserve the previous view and retry. **Refresh snapshot**
can also restart recovery manually. Events received while hidden remain in the
bounded model; on return, a new-events count appears. Background animation timing
is browser-controlled and is not guaranteed. See the [recovery contract and browser
checks](./architecture/017-browser-recovery.md). See [highlight design and measured browser checks](./architecture/016-live-highlights.md).
Missing locations remain in the list with a reason. A degraded GeoIP notice refers
to those stored event results, rather than current database-reader health. GeoIP
is an approximate network location, not a person's physical location.

Leaflet 1.9.4 (BSD-2-Clause) and Natural Earth v5.1.2 public-domain 1:110m boundaries
are packaged locally, with attribution below the map. No tile provider, API key,
CDN or frontend build is needed. See the [viewer design, provenance and browser
verification procedure](./architecture/015-snapshot-viewer.md).

## Fail2ban notification action

The distribution includes `integrations/fail2ban/`: a Python 3 standard-library
HTTPS notification helper and an additive Fail2ban Python action. Only new ban
callbacks notify; unban/start/stop do not. See the
[administrator guide](./DEPLOYMENT.md#geoip-fail2ban-and-retention) and
[integration guide](../integrations/fail2ban/README.md) for installation.

**Delivery is best effort: events can be lost after three attempts within 15
seconds during outages. There is no persistent offline queue.** Failed notification
does not fail the normal ban action. TLS certificates are verified, and retries
reuse the same event UUID/timestamp and JSON bytes. Tokens stay in the private file.

## Runtime health and operator diagnostics

`GET /health/live` reports process liveness; `GET /health/ready` returns only
`{"status":"UP"}` (200) or `{"status":"DOWN"}` (503). Readiness checks the actual
Strolch PostgreSQL pool, fails during database loss or shutdown, and stays UP
when GeoIP is unavailable. Only one readiness probe runs at a time; overlapping
probes return DOWN. Monitor with a finite timeout and retry rather than treating
one busy probe as a permanent outage.

The diagnostics route requires an account with both the `Viewer` and supplied
`Operator` roles. See the [administrator guide](./DEPLOYMENT.md#provision-the-external-runtime-while-stopped)
for provisioning. After ordinary cookie login,
`GET /api/v1/diagnostics` reports process-lifetime ingestion created/duplicate/failure
counters, active stream connections, slow-consumer disconnects, GeoIP load/build/age
and stale/update status, and effective stream bounds. Ordinary viewers and machine
PATs cannot read diagnostics. Responses are uncached and contain no credentials,
IPs, instance names, payloads or filesystem paths. Counters reset on restart.

The administrator guide covers required runtime files and deployment configuration.
Missing optional identity/viewer files disable the corresponding access; missing
GeoIP remains degraded. EventStream defaults are 1000 queued frames, 65536 bytes,
10 seconds to send, and a 250 ms poll. Session TTL/maximum lifetime are bounded to
1–1440 minutes.
See the [complete configuration and diagnostic contract](./architecture/019-runtime-operations.md)
for ranges, database pool timeout settings and shutdown behavior.

Each API response carries a server-generated `X-Request-ID`; API error JSON uses
the same ID. Incoming IDs are ignored. Default logs contain only fixed operational
messages and at most one request-failure summary per second, with status/ID only.
Framework/Jersey logging is disabled because it can contain connection or model
values. Replacing the logging configuration can re-enable sensitive framework logs;
review it before use. See the [administrator guide](./DEPLOYMENT.md) for log and
service-manager configuration.

On SIGTERM/Ctrl+C, new API work is rejected, readiness fails, active API calls drain,
then the stream, Jetty, Strolch jobs, GeoIP readers and persistence close. The
administrator guide covers service-manager timeout configuration. If request drain
exceeds 30 seconds, shutdown reports failure and leaves persistence running for that
call; it does not destroy storage under active writes. Forced termination still relies
on PostgreSQL transaction atomicity and durable replay. No delivery through a long
producer outage is promised by the notification helper.

### Full MVP and capacity verification

The optional [MVP validation harness](./architecture/020-mvp-validation.md)
uses the existing browser tooling and disposable PostgreSQL. It seeds 100,000
retained events, submits events under two server identities, and measures two
browser contexts during a five-minute 10 events/second workload plus a 100-event
burst. The [capacity test report](./verification/020-acceptance.txt) records the
measured results and limits; the existence of this harness does not establish a
passing capacity or release gate.

The capacity fixture matches production environment-mode pool defaults: 10
connections, 30,000 ms acquisition and 5,000 ms validation timeout. XML-credential
runtimes instead use their XML pool settings; the template's 3,000 ms acquisition
budget can reject requests during a concurrent burst. See the [administrator
guide](./DEPLOYMENT.md) and [runtime operations
contract](./architecture/019-runtime-operations.md) for pool and readiness-monitor
configuration. The validation report lists separate burst latency and failures.

The capacity test measured combined-workload p95 of 951/961 ms across the two browsers;
receipt-to-frame p95 was 29 ms. All 3,100 events committed and appeared. The burst
phase alone had about 9.2 seconds p95, so this is not a one-second guarantee for
each phase or request. See the [capacity test report](./verification/020-acceptance.txt) for
raw measurements, failed attempts, hardware and receipt-population limits.

## Release evidence

See the [local HTTPS/WSS and restore rehearsal report](./verification/021-acceptance.txt)
for measured results and limits. See the [0.0.1 MVP release review](./RELEASE_NOTES.md)
and [0.0.1 release acceptance checklist](./verification/022-acceptance.md) for the reviewed
artifact identity, verification and operational limits.
