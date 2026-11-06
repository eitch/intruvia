# Ordered viewer event stream

Task 014 implements `/api/v1/events/stream?after=<sequence>` using the already
pinned Jetty 12.0.16 Jakarta WebSocket server and Jakarta WebSocket 2.1.1 APIs.
It adds no new transport, frontend framework or build toolchain.

## Authentication and upgrade

The exact servlet mapping takes precedence over Jersey's `/api/v1/*` mapping.
The servlet and REST routes share the same configured global admission bucket;
rejected upgrades consume admission before session validation. Jetty's existing
connection cap also bounds sockets. Rate rejection is HTTP 429 with Retry-After.

Before upgrade, the shared `ViewerSessions` validator requires exactly the configured
Origin, a valid IntruviaSession cookie and `event:read`. It rejects Authorization,
foreign Sec-Fetch-Site, duplicate session cookies, machine PATs and API-usage
certificates. Header names are normalized; duplicate values are preserved for
validation. The sole query parameter is one canonical nonnegative signed-64-bit
`after` decimal. Missing, duplicate, unknown and malformed parameters return 400.
There is no credential or application command in the URL. Failure bodies contain
fixed safe text and request IDs, and responses use Cache-Control: no-store.
Each upgrade creates its own endpoint and configurator, with no shared mutable
handshake credential state. The origin also passes the container's check.

## Frames and recovery

All application frames carry `version:1`. The initial frame is:

```json
{"version":1,"type":"hello","head":"42","minAfter":"12"}
```

A subsequent frame contains the complete canonical event (abbreviated here):

```json
{"version":1,"type":"event.created","sequence":"43","event":{"schemaVersion":1,"sequence":"43"}}
```

Sequence values remain strings, including above 2^53. `hello` captures head and
minimum with the existing snapshot service and shared ingestion/retention lock.
The same authorized EventReadService then reads ascending pages of up to 1000
retained events after the connection's cursor, capturing a fresh head per page.
New commits between snapshot, upgrade, hello and any later page remain eligible.
No transient subscribe boundary exists. Each page materializes under the stream
lock, so pruning after capture cannot alter its detached events; pruning across
an unread cursor produces an explicit resync on the next read.

An expired or ahead-of-head cursor receives
`{"version":1,"type":"resync_required","code":"resync_required"}` followed by
close 1008. A read failure receives
`{"version":1,"type":"error","code":"events_unavailable"}` and close 1011.
Terminal frames follow already queued events. A transport failure can prevent
receipt of the terminal frame; the client must resume from its last applied
sequence or resnapshot. The server's enqueue cursor is not a client acknowledgement.

The frontend snapshot/bootstrap, deduplication, last-applied cursor, visible gap
notice, jittered 1–30-second reconnect and animations belong to tasks 015–017.
This task implements the server protocol only. No exactly-once network guarantee.
Text or binary client messages close with 1008 and never mutate the model.

## Lifecycle, wakeups and bounds

Add both `StreamNotifications` and `EventStream` components from the packaged
StrolchConfiguration.xml to existing external runtimes. EventStream depends on
Intruvia model initialization, ServiceHandler, SessionHandler and StreamNotifications.
The application injects the same session validator/clock used by REST after Strolch
initialization and before components start. Strolch starts/stops the dispatcher;
Jetty owns endpoint and transport shutdown. Shutdown removes sockets, cancels
scheduled work and waits for the worker before persistence dependencies stop.

After a new ingestion transaction closes successfully, a lightweight core component
signals the dispatcher. Duplicate, invalid, capacity-rejected and rolled-back writes
do not publish. The signal only schedules a coalesced job; it neither sends frames
nor reads persistence on the producer thread. Wakeup failure cannot reverse a
committed result. A 250 ms fixed-delay poll independently catches committed events,
including writes without a wakeup and restart recovery. Immediate wakeups accelerate
normal commits and multi-page replay. There is at most one queued wakeup plus the
periodic task. One worker reads connections fairly in bounded pages.

Each socket has at most **1000 total pending/in-flight frames**, one outstanding
asynchronous text send, a **64 KiB UTF-8 frame/message cap**, and a **10-second send
limit**. Overflow or send deadline closes with 1013; no individual event is silently
dropped while keeping the connection alive. Reconnect replays from durable storage.
Send failures close 1011; oversized frames close 1009. These are fixed defaults for
this task; broader external diagnostics/settings remain task 019.

Transport ping runs every 15 seconds; pong must arrive before the next ping period.
Missing pong closes 1001; the transport also has a 45-second idle limit. Every
active dispatcher pass revalidates the framework session and read privilege;
logout, expiry or lost authorization closes 1008, normally within one poll interval
plus dispatcher work. No cached certificate substitutes for validation. A frame
already in flight when a session ends cannot be retracted. Queued frames are cleared
on closure. JavaScript does not need an application heartbeat or any command API.

Single-worker reads scan retained events under the shared lock, as in task 012;
this is not a claim that production-sized capacity or latency targets have been
measured. Those checks belong to task 020. Queue bounds count frames, with the
64 KiB cap also bounding per-connection queued memory. Normal global HTTP connection
limits bound connection count. A slow socket never blocks an ingestion network send.

## Verification

Use Java 25 and the repository's disposable PostgreSQL script:

```bash
JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.5.0+8-tem \
PATH=/home/eitch/.sdkman/candidates/java/25.0.5.0+8-tem/bin:/home/eitch/.sdkman/candidates/maven/current/bin:$PATH \
  scripts/verify-postgresql.sh -o -Dstrolch.version=2.8.0-SNAPSHOT
```

`EventStreamIT` exercises real HTTP upgrades and Strolch PostgreSQL replay, live
commits, missed wakeups, duplicates, rollback, restart, retention crossing an unread
range, full-width sequences, Origin/session/PAT isolation, logout/expiry, command
rejection, shared admission and shutdown. For deterministic backpressure it also
attaches a transport whose asynchronous send never completes to the actual production
dispatcher and verifies overflow while ingestion succeeds. This avoids dependence
on OS socket buffers; it is not a measured real-network slow-client load test.
`StreamConnectionTest` independently verifies serialized callbacks, exact queue
capacity, terminal ordering, UTF-8 limits, send deadlines and ping/pong timeout using
a controlled monotonic clock. No browser UI changed; browser/TLS proxy/capacity
checks remain unrun and outside this task. Exact results, failures and artifact
identities are linked from the status ledger.
