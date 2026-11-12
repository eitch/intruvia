# Task 019 — runtime operations

The application retains the existing one-process, loopback-only Jetty/Strolch design.
TLS/WSS terminates at the configured same-origin proxy; forwarded Host/identity headers
are not trusted. No management/write API is added. No dependency or toolchain is added.

## Configuration

Copy the packaged runtime outside target and provision the four canonical config XML
files. `RuntimeConfiguration` rejects missing files, malformed privilege XML, missing,
blank, duplicate or placeholder secretKey/secretSalt; XML external entities/DOCTYPE are
disabled during this check. Errors expose fixed setting/file names only. Existing
Strolch configuration and schema validation still run. `PersistenceConfiguration`
requires the existing one-CACHED-PostgreSQL-realm contract and nonempty DB URL/username/
password. Only PostgreSQL JDBC URLs are accepted. Startup errors at the executable
boundary omit underlying exception causes, which may contain connection values.

| Configuration | Default and permitted bounds |
|---|---|
| DB | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` when db.useEnv=true; otherwise corresponding protected db.* XML properties; required |
| Binding | 127.0.0.1; port argument default 8080, 0–65535 (0 is ephemeral) |
| Proxy/TLS | external TLS/WSS proxy; exact viewer.properties origin, HTTPS except explicit loopback development |
| Machine/viewer identities | protected machine-identities.conf and Strolch users/roles; existing PAT provisioning; absent mapping/origin disables access |
| SessionHandler | session.ttl.minutes/session.maxKeepAlive.minutes: 30 each, 1–1440 |
| GeoIp | databasePath empty; optional absolute City MMDB path; staleAfterSeconds 1209600, 1–315360000; updateIntervalSeconds 60, 1–86400 |
| EventStream | queueFrames 1000, 1–1000; messageBytes 65536, 1024–65536; sendSeconds 10, 1–30; pollMillis 250, 10–1000 |
| Ingestion/retention | unchanged finite settings in [010](010-ingestion-http.md) and [013](013-retention.md) |

Unknown stream and GeoIP properties fail startup. Lowering messageBytes can disconnect
clients whose canonical events exceed that bound; durable replay/snapshot remains the
recovery path. Frame capacity includes the outstanding async send. Overflow and send
timeout close with 1013 and count once; ordinary closes do not increase that counter.
Heartbeat stays at 15 seconds and transport idle at 45 seconds. The dispatcher maintains
one periodic task and a coalesced wakeup, independent of request volume.

The framework's `DbConnectionBuilder` selects pool properties from the same source as
credentials: XML db.pool.connectionTimeout=3000/db.pool.validationTimeout=2000 are used
when db.useEnv=false. For db.useEnv=true set environment `DB_POOL_connectionTimeout=3000`
and `DB_POOL_validationTimeout=2000` (case of the final Hikari property is significant).
Without these environment overrides Hikari's existing 30000/5000 ms defaults apply;
XML pool properties are not read in environment mode. Preflight bounds maximumPoolSize
(default 10) to 1–256, connectionTimeout to 250–30000 ms, and validationTimeout to
250–5000 ms; zero/unbounded timeouts are rejected. Readiness uses this existing pool
and `Connection.isValid(2)`, with one concurrent probe and no executor/queue. Budget pool
acquisition plus validation in the monitor timeout. It checks connectivity, not capacity
or a synthetic write transaction; transient pool saturation may report DOWN.

## Health, counters and authentication

Only /health/live and /health/ready are health resources. They return uncached JSON UP/
DOWN and no version, realm, exception, DB location or GeoIP path. GeoIP degradation is
independent of DB readiness. No health resource is registered on the API application.

GET /api/v1/diagnostics uses the existing same-origin cookie session validator including
expiry, CSRF/Origin policy and PAT exclusion. It additionally calls the real framework
privilege context for runtime:diagnostics. An operator needs event:read as well; give the
user Viewer and Operator roles. Generic Strolch management routes remain unavailable.

The response has ingestion.{created,duplicates,failures}, streamConnections,
queueDisconnects, geoIp.{available,databaseEdition,databaseBuildAt,lastSuccessfulLoadAt,
databaseAgeSeconds,stale,updateFailed}, and streamLimits. Missing GeoIP metadata is null.
Counters use constant-space saturating AtomicLongs, reset on restart, and have no client
labels. Ingestion counters describe completed HTTP requests on the ingestion path:
201 created, 200 duplicate, everything else failed (including auth/rate rejection).
A committed request whose client loses the response may still increment created;
these are operational counts, not transactional accounting. Snapshots of counters and
connections are approximate across concurrent operations. No IP diagnostics are enabled.

A servlet filter generates UUID request IDs before API auth and upgrade handling.
X-Request-ID and error.requestId match, including session/read/stream errors and shutdown
rejections. Incoming IDs are never echoed. MDC is restored/removed after completion.
The filter has no async request lifecycle; the supported REST calls are synchronous,
and WebSocket admission ends when the upgrade returns.

## Logs and shutdown

The shipped synchronous Logback configuration enables only Intruvia operational messages;
framework logging is OFF, and bootstrap resets java.util.logging to OFF before framework
setup. This is deliberate: framework exceptions and SQL may expose credentials/payloads.
Fixed application messages contain no throwable details. Request summaries contain only
the generated ID and status, rate-limited to one/second per process. There is no async
log-event queue. Logback rolls at 10 MB, retains five days with a 50 MB archive cap;
active file, compression and cleanup temporarily add overhead. The console stream is
retained by the service manager, whose retention must also be bounded. Restricted file
permissions belong to the service umask/directory; no public log endpoint exists.

Close marks API admission stopping, makes readiness DOWN, and waits up to 30 seconds for
accepted calls before touching components. New API calls get a fixed uncached 503 error.
An active transaction may commit and return its actual response while draining. If the
drain times out, close throws with persistence still alive and admission still closed;
an embedding caller can retry close after requests finish. The executable's SIGTERM
hook reports a fixed failure; an external hard kill still closes DB sessions and relies
on database rollback. This is not a guarantee that a hung request can finish.

After draining, quiesce stops the dispatcher, closes sockets with 1001, and clears queues;
then Jetty stops, followed by Strolch dependency-ordered stop/destroy. Existing retention
shutdown waits for its worker and GeoIP lifecycle locking prevents close during lookup/
replacement. Configure the service manager for at least 90 seconds (request/dispatcher/
retention deadlines can each take 30 seconds, plus transport overhead). An ordinary
shutdown test blocks a real SQL insert, proves close waits while rejecting a new write,
releases the insert, receives 201, restarts and finds the committed sequence. Existing
rollback fault tests prove failed commits leave no partial event/receipt/head.

Evidence and exact failures/limits are in [acceptance](../verification/019-acceptance.txt).
Capacity/browser release verification and deployment/recovery remain tasks 020/021.
