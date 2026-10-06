# Task 010 — authenticated ingestion HTTP boundary

The production Jetty application exposes `POST /api/v1/fail2ban/events` through
Jersey. `Fail2banResource` composes the existing strict parser and a fresh
`IngestService`, invoked through Strolch `ServiceHandler` with the original PAT
certificate. It never opens a privileged agent transaction or trusts identity in
query parameters or payloads. Unknown body fields are rejected. The service
resolves the protected mapping again and authorizes `event:ingest` itself.

## Admission and errors

A pre-matching global token bucket bounds API work before PAT verification. The
existing name-bound PAT filter authenticates and enforces the narrow ingestion
privilege and configured identity before body parsing. A per-instance token bucket
then admits the authenticated request. Buckets exist only for configured identities;
arbitrary tokens/IPs cannot grow a rate-state map. Rotation PATs share their instance's
bucket. Synchronization and a monotonic nanosecond clock prevent concurrent overspend.
All attempts consume tokens, including duplicates and invalid bodies. Fractional refill
is capped at burst size. Rate state is local to this single process and resets on restart.

Jetty's lifecycle-managed `ConnectionLimit` pauses accepts at the configured bound.
The connector idle timeout limits idle sockets; a reverse proxy must apply its own
internet-facing body, connection and request limits. A connection-level reject is not
an HTTP 429. Request-rate rejects are JSON 429 with whole-second `Retry-After`.

The resource explicitly checks media type after authentication, allowing only
`application/json` with absent or UTF-8 charset and no Content-Encoding. The parser
reads at most bodyBytes + 1 bytes, including for chunked/unknown-length bodies. Missing
or wrong media returns 415; malformed JSON/UTF-8/values return 400; overflow returns 413.
No arbitrary inbound JSON survives parsing. Authentication failures do not parse the
body. Jetty handles malformed HTTP framing/header overflow at the transport layer;
these pre-JAX-RS errors do not use the application JSON envelope.

| Result | HTTP contract |
|---|---|
| Committed | 201 `{id,sequence,duplicate:false,geoStatus}` |
| Retained equal receipt | 200 `{id,sequence,duplicate:true}` |
| Invalid payload/age | 400 `invalid_event` |
| Missing/invalid PAT | 401 `unauthorized`, Bearer challenge |
| Invalid scope/mapping | 403 `forbidden` |
| Changed receipt payload | 409 `event_conflict` |
| Body overflow | 413 `body_too_large` |
| Unsupported media/encoding | 415 `unsupported_media_type` |
| Instance/global rate limit | 429 `rate_limited`, Retry-After |
| Persistence/service failure | 503 `unavailable` |

Every application error contains `{error:{code,message,requestId}}`, with a generated
UUID and fixed safe message. All responses have `Cache-Control: no-store`. The final
exception mapper masks framework diagnostics; it does not log request bodies or
credentials. Service outcomes are handled after transaction close. Duplicates omit
geography rather than inventing a value for an event that may have been pruned. The
v1 response schema now expresses that distinction, matching specification section 4.
Receipt capacity and retention remain task 013; publication remains task 014.

## Configuration

Copy the packaged `config/ingestion.properties.example` to the external runtime's
`config/ingestion.properties`. Absence uses defaults; unreadable files, unknown keys,
non-integer/out-of-range values fail construction before startup. Settings are read
once; restart after changes. All values are positive integers.

| Key | Default | Maximum |
|---|---:|---:|
| bodyBytes | 16384 | 1048576 |
| eventsPerSecond | 10 | 100000 |
| burst | 100 | 100000 |
| globalPerSecond | 100 | 100000 |
| globalBurst | 1000 | 100000 |
| connections | 256 | 10000 |
| idleSeconds | 30 | 300 |
| maximumAgeSeconds | 604800 | 2592000 |
| maximumFutureSeconds | 300 | 86400 |

Age policy remains in the service after receipt lookup, including its final locked
recheck. Defaults are the specification's seven days/five minutes. Raising age beyond
receipt lifetime requires operator care and is not a different deduplication guarantee.

## Verification and provenance

`IngestionHttpIT` runs production wiring on embedded Jetty, real Strolch-issued PATs
and isolated PostgreSQL databases. It covers every specified status, fixed-length and
chunked overflow, exactly 16 KiB, media/charset checks, authentication before body work,
unknown identity fields and query spoofing, two identities persisted from certificates,
revocation after use, 16 concurrent retries producing one commit, restart deduplication,
actual database constraint failure with no partial state/sequence, and successful retry.
It tests rate rejection/refill, rotation sharing the instance bucket, instance isolation,
and global admission before authentication. Existing task 005 tests retain expired,
future, disabled, broad, duplicate-header and session-credential coverage.

`AdmissionTest` deterministically tests concurrent burst exhaustion, fractional refill,
capped refill and external configuration validation. Existing service tests retain age
bypass/pruned-receipt, rollback/restart, and out-of-lock enrichment coverage. No browser
code, dependency or toolchain was introduced. No capacity benchmark or external proxy
rehearsal is claimed. Connection-limit behavior uses the pinned Jetty implementation;
a saturation load test was not run.

Verified actual local Strolch source/cache/package `2.8.0-SNAPSHOT`, source HEAD
`e34b772f9d58f053a0272457708658d0dceadf76`; inspected `DefaultServiceHandler` authorization
and result handling, PAT lifecycle source, and Jetty 12.0.16 `ConnectionLimit` API.
The installed cache suffices offline with `-Dstrolch.version=2.8.0-SNAPSHOT`; no framework
rebuild or artifact relabeling occurred. No timestamped-baseline equivalence is claimed.
Exact commands, failure history, final results and hashes are linked from the ledger.
