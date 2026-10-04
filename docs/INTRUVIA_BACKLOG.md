# Intruvia — numbered implementation backlog

Version: 1.1 · Date: 2026-09-29

Authoritative contract: [implementation specification](INTRUVIA_SPECIFICATION.md). Execution ledger: [backlog status](INTRUVIA_BACKLOG_STATUS.md).

## Agent execution rules

1. Read the specification and status before changing code. Work on **one numbered task at a time**. Follow ascending task order by default; never start with unmet dependencies.
2. Set that row to `IN_PROGRESS` before editing. Keep changes cohesive and within the task's stated scope. Do not implement deferred features.
3. Run the acceptance checks for that task. Record exact commands/results and a commit or artifact reference in the status ledger. Planning alone does not complete an implementation task.
4. Mark `DONE` only after all acceptance criteria pass. If blocked, set `BLOCKED`, record the concrete cause and next required action, and stop dependent work. Do not invent unavailable Strolch APIs or claim unrun tests passed.
5. Update specification and backlog together if evidence requires a design change. Keep task numbers stable; append new tasks rather than renumbering. Any scope expansion requires an explicit user decision.
6. If a task proves too large, add separately numbered follow-ups with explicit dependencies and leave the parent incomplete until its acceptance criteria are satisfied. Do not quietly turn it into a broad refactor.

The foundational product and stack decisions are already captured in the specification. Every item below produces or verifies implementation work, so all start as TODO. Dependencies are minimum prerequisites, not an instruction to run tasks in parallel.

## 001 — Verify and pin the Strolch runtime baseline

**Dependencies:** None

**Scope:** Inspect an available sibling Strolch project or upstream sample and select compatible Java LTS, Strolch, JAX-RS provider, Jetty, WebSocket and PostgreSQL driver versions. Record bootstrap, session, privilege and persistence conventions.

**Acceptance criteria:** Record exact versions and source references in a short architecture decision; compile and start a minimal Strolch/Jetty compatibility probe; prove the selected API namespace is consistent. If sibling code is unavailable, record that limitation and use a verified upstream baseline.

**Completion evidence:** Version decision and probe output.

## 002 — Create the Maven modules and application skeleton

**Dependencies:** 001

**Scope:** Create the root POM and core/rest/web/app modules from specification section 2, packaging a minimal static page and health route.

**Acceptance criteria:** Clean Maven verify succeeds; assembled application starts embedded Jetty and Strolch, serves the local page, then shuts down cleanly; module dependencies have no cycles and contain no frontend framework.

**Completion evidence:** Build command, result and startup smoke evidence.

## 003 — Define event DTOs and Strolch model mapping

**Dependencies:** 002

**Scope:** Implement the canonical envelope, Fail2ban request/response DTOs, GeoIP status vocabulary, and typed Strolch model templates for event, receipt and stream state.

**Acceptance criteria:** All section 3 fields have documented storage types/nullability; sequence serializes as a decimal string; round-trip tests cover IPv6, null coordinates, optional fields and namespaced attributes; publish a v1 API schema and complete event fixture.

**Completion evidence:** Model mapping, schemas and round-trip test results.

## 004 — Configure durable Strolch persistence

**Dependencies:** 003

**Scope:** Wire the selected PostgreSQL persistence, model initialization/migration and repository/search boundary. Keep access behind Strolch transactions.

**Acceptance criteria:** Real-database integration test writes and reads a model fixture across restart; failed transaction leaves no partial state; production configuration cannot accidentally use a transient store; tests isolate their data.

**Completion evidence:** Migration and restart/rollback results.

## 005 — Integrate Strolch PAT machine authentication

**Dependencies:** 002

**Scope:** Reopened by the 2026-09-29 design revision. Replace the custom `MachineCredentials` verifier store and authentication wiring with Strolch's PAT infrastructure. Inspect the actual resolved framework APIs and existing PAT tests; use one technical user per reporting server, a protected stable instance-ID mapping and narrowly scoped ingestion privileges. Delegate verification and token lifecycle to Strolch; adapt only HTTP errors, PAT-only endpoint enforcement and application identity mapping. Remove obsolete TSV loading/provisioning and custom verification code/tests, update the architecture decision and README, and preserve historical verification evidence as superseded. Do not implement the ingestion endpoint (010) or viewer sessions (011) in this task.

**Acceptance criteria:** Real Strolch-issued PATs authenticate through the framework and yield its certificate. Missing/malformed/invalid/expired/not-yet-valid/revoked PATs and disabled owners yield 401; valid PATs without ingestion privilege or a server mapping yield 403. Duplicate Authorization headers and viewer-session credentials cannot bypass the PAT-only boundary. Two technical users resolve distinct configured identities; caller data cannot spoof identity. Overlapping rotation PATs for one technical user preserve identity; revocation and expiry reject a previously cached PAT without an Intruvia credential-file reload/restart. Certificates have only intended ingestion privileges and no viewer/admin access; actual viewer-route isolation is verified in 011/014. No custom verifier store or token cryptography remains and no secret is logged or returned. Review framework verification/security behavior against the resolved version and record/resolve any gap rather than silently weakening requirements or adding a parallel verifier.

**Completion evidence:** Framework API/version references, real-PAT HTTP/privilege/lifecycle tests, provisioning/rotation instructions and fresh full regression results. Earlier custom-token results do not satisfy this revised task.

## 006 — Implement Fail2ban validation and normalization

**Dependencies:** 003

**Scope:** Validate request shape and limits and map it into the generic event envelope without trusting caller identity. Separate timestamp-age policy so accepted retries can be checked first.

**Acceptance criteria:** Section 4 valid/invalid boundary cases pass, including unknown fields, oversized input, IPv4/IPv6 canonicalization, no hostname resolution, timezones and jail bounds; semantic-equivalent payloads have the same digest; changed payload has a different digest.

**Completion evidence:** Validation table and unit results.

## 007 — Implement local GeoIP enrichment

**Dependencies:** 003

**Scope:** Create a lifecycle-managed MaxMind reader component with deterministic enrichment result mapping and provenance.

**Acceptance criteria:** Fixture tests cover IPv4/IPv6, non-public addresses, missing records, partial records, legitimate zero coordinates, missing/corrupt DB; enrichment failure returns a status instead of aborting ingestion; reader is reused and closed.

**Completion evidence:** Enrichment tests with fixture provenance.

## 008 — Add safe GeoIP database replacement

**Dependencies:** 007

**Scope:** Support staged MMDB replacement and reader swapping; add external geoipupdate configuration examples without credentials.

**Acceptance criteria:** Concurrent lookup during replacement is safe; invalid replacement retains the working reader; build/load time and stale state are available to diagnostics; restart without a database remains degraded but usable; no MMDB or license key is committed.

**Completion evidence:** Replacement/failure tests and update instructions.

## 009 — Implement atomic ingestion and deduplication

**Dependencies:** 004, 006, 007

**Scope:** Create the Strolch ingestion service and command using a serialized stream-state transaction, receipt digest check and monotonic committed sequence.

**Acceptance criteria:** Concurrent identical submissions produce one event and receipt; conflicts produce no writes; rollback does not advance committed state; old accepted retries bypass age rejection; retained receipts still resolve pruned events; enrichment occurs outside the write lock.

**Completion evidence:** Concurrency, rollback and retry integration results.

## 010 — Expose the authenticated ingestion endpoint

**Dependencies:** 005, 009

**Scope:** Wire POST /api/v1/fail2ban/events, body/media limits, rate limits and consistent error/response DTOs.

**Acceptance criteria:** HTTP tests demonstrate 201/200/400/401/403/409/413/415/429/503 as specified; a DB failure is never acknowledged as success; Retry-After is present for limits; identity is derived from the Strolch PAT certificate and configured technical-user mapping, the service uses that certificate for authorization, and errors contain no secrets.

**Completion evidence:** HTTP contract test results.

## 011 — Add authenticated viewer sessions

**Dependencies:** 002, 005

**Scope:** Integrate Strolch viewer authentication, cookie sessions, login/logout, CSRF protection and event:read authorization using the verified project convention.

**Acceptance criteria:** Session cookie flags and same-origin rules are verified; machine PATs cannot authenticate viewer routes even via Bearer headers; unauthorized users cannot view events; logout/expiry invalidates the shared session validation used by future sockets; no credential is stored in browser localStorage.

**Completion evidence:** Session, CSRF and authorization test results.

## 012 — Implement bounded snapshots and cursor history

**Dependencies:** 004, 009, 011

**Scope:** Add snapshot and paginated read endpoints with canonical event DTOs, stable high-water marks and sequence cursors.

**Acceptance criteria:** Snapshot is consistent during concurrent ingestion; page traversal has no duplicates or skipped retained events; limit bounds, malformed/ahead cursors and expired cursors return specified results; browser-safe string sequences are preserved.

**Completion evidence:** Concurrent snapshot and pagination results.

## 013 — Implement bounded retention and receipt cleanup

**Dependencies:** 009, 012

**Scope:** Add age/capacity pruning of a contiguous event prefix, atomic minAfter advancement, independent receipt expiry and receipt-capacity rejection.

**Acceptance criteria:** Time-controlled tests prove age and count bounds, replay 410 behavior, duplicate resolution after event pruning, unexpired receipt preservation, and 429 when receipt capacity is full; concurrent reads/pruning never silently skip deleted ranges.

**Completion evidence:** Retention and concurrency test results.

## 014 — Implement the ordered WebSocket event stream

**Dependencies:** 010, 011, 012, 013

**Scope:** Add authenticated Origin-checked stream endpoint, durable replay/tailing, commit wakeup plus catch-up polling, bounded outbound queues and session invalidation.

**Acceptance criteria:** Protocol tests prove ascending replay then live delivery across the snapshot connection gap; commit without wakeup still delivers; expired cursors request resync; slow-client overflow disconnects without blocking ingestion; invalid Origin and expired sessions are rejected/closed; machine PATs cannot authenticate a WebSocket connection; no client command changes server state.

**Completion evidence:** Protocol, crash-window and backpressure results.

## 015 — Build the viewer shell and initial map

**Dependencies:** 011, 012

**Scope:** Package pinned Leaflet and a licensed local world-boundary dataset; build login, world map, recent list, rolling-window labels, safe popups and unmapped states in Vanilla JS.

**Acceptance criteria:** Initial authenticated snapshot renders at most 1000 events without live pulses; partial/unmapped data is readable; malicious-looking text stays inert; dataset/library attribution is visible and documented; page works without external tile requests.

**Completion evidence:** Browser screenshots and snapshot/security smoke results.

## 016 — Add immediate per-event visual highlighting

**Dependencies:** 014, 015

**Scope:** Apply event.created frames to the shared model and display three-second marker/list highlights, overlap handling, off-screen action and reduced-motion behavior.

**Acceptance criteria:** Each unique geolocated event starts its own visible indication on arrival; repeated frames do not repulse; overlapping points retain event identities; unmapped events highlight in the list; eviction enforces 1000-event bound; foreground receipt-to-highlight p95 is measured against 100 ms.

**Completion evidence:** Browser tests/video or screenshots and latency measurements.

## 017 — Implement browser reconnect and resynchronization

**Dependencies:** 016

**Scope:** Persist cursor in page memory only after applying events; add heartbeat/disconnection handling, bounded jittered reconnect, replay deduplication and expired-cursor snapshot recovery.

**Acceptance criteria:** Browser tests inject disconnects before/after commit and between snapshot and stream without losing retained events; duplicates never animate twice; expired/ahead cursor forces visible resnapshot; logout stops reconnect; hidden-tab behavior and new-event count match section 7.

**Completion evidence:** Reconnect and session-expiry end-to-end results.

## 018 — Ship the Fail2ban action and notification helper

**Dependencies:** 010

**Scope:** Create the additive action template and Python helper with protected Strolch PAT-file loading and Bearer tokenId:tokenValue transmission, stable UUID/timestamp per invocation, JSON encoding, verified HTTPS and bounded retries.

**Acceptance criteria:** Mock-server tests prove same payload across retries, 429/5xx/network retry policy, no retry for other 4xx, overall deadline and safe argument encoding; action installation preserves existing ban action and only actionban notifies; long-outage loss limitation is explicit.

**Completion evidence:** Helper tests and isolated Fail2ban action smoke evidence.

## 019 — Complete runtime configuration and diagnostics

**Dependencies:** 008, 010, 013, 014

**Scope:** Add validated configuration defaults, safe health endpoints, protected diagnostic counters, request IDs and graceful shutdown behavior.

**Acceptance criteria:** Missing required settings fail clearly; GeoIP degraded mode remains ready; unavailable DB fails readiness; secrets/raw payloads never enter logs; shutdown commits or rolls back writes and closes sockets/readers; queues/logs stay bounded.

**Completion evidence:** Configuration, health and shutdown tests.

## 020 — Verify the full MVP and capacity targets

**Dependencies:** 017, 018, 019

**Scope:** Run the full integration/browser suite against real Strolch persistence and the release runtime; measure the stated baseline workload.

**Acceptance criteria:** Two server identities produce persisted events and immediate map highlights; retry/restart/crash/GeoIP failure/IPv6/unmapped/overlap/reduced-motion scenarios pass; run 10 events/s for five minutes plus 100-event burst with two browsers and 100,000 retained events; record p95 end-to-end <=1 s, hardware and any failures. Fix defects within their owning task before marking DONE.

**Completion evidence:** Reproducible validation report with measurements and limitations.

## 021 — Document and verify deployment and recovery

**Dependencies:** 018, 019

**Scope:** Write installation, TLS proxy/WSS, PostgreSQL, viewer and technical-user provisioning, scoped Strolch PAT issuance/expiry/rotation/revocation and stable instance-ID mappings, GeoIP updates, Fail2ban setup, retention, backup/restore and troubleshooting instructions.

**Acceptance criteria:** A clean-environment rehearsal follows the guide without hidden steps; secrets are placeholders; backup/restore preserves events/receipts/stream state; older-backup cursor recovery works; data-loss limits and deferred features are stated.

**Completion evidence:** Deployment and restore rehearsal records.

## 022 — Perform the MVP release review

**Dependencies:** 020, 021

**Scope:** Audit implementation against every specification section, dependency pins, documented defaults and backlog evidence; package the reproducible distribution.

**Acceptance criteria:** Clean Maven verify and documented browser suite pass on release revision; no unresolved MVP defects or silently added broader features; tasks 001–021 are DONE with evidence; release notes identify operational limits and exact artifact/version.

**Completion evidence:** Release revision, artifact checksums and final acceptance checklist.

