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

## 023 — Package a Compose deployment bundle

**Dependencies:** 022

**Scope:** Add a published-image Docker Compose deployment bundle and sanitized versioned runtime tarball for administrators. Support first-start runtime and schema provisioning from protected templates without replacing existing state; isolate the private app/database networks; retain a replaceable HTTPS/WSS proxy; and document lifecycle, credentials, offline PAT operations, upgrades and coordinated recovery. Preserve the Java distribution and standalone deployment path. Provide a no-argument build-and-push workflow for the changeable `repo.strolch.li/intruvia` image constant, deriving the image and bundle version from the root Maven POM; registry login remains a prerequisite.

**Acceptance criteria:** Jetty uses a validated `INTRUVIA_BIND_ADDRESS` with loopback default; a Java 25 unprivileged image copies only the prebuilt application and dependency JARs, with offline privilege tooling packaged in the application JAR. The bundle supplies allowlisted runtime templates separately. A fresh runtime and verified-empty database initialize exactly once with independent privilege secrets and an enabled Viewer using an owner-only password file; interrupted, unsafe, partial or existing state fails closed without leaking secrets or replacing data. Compose uses PostgreSQL 18 with persistent storage, readiness-gated startup, separate database administrator/application credentials and no published app/database ports. The bundle preserves TLS/WSS through a minimal replaceable proxy, protects persistent runtime/log/proxy state, and survives restart and image upgrade without replacing existing state. No-argument release scripts derive the version from the root POM and use the single changeable `repo.strolch.li/intruvia` constant; image building/pushing runs `mvn clean package -DskipTests` by default, while `--with-tests` opts into the PostgreSQL-backed Maven verification, and prior registry login is required. Skipping tests is not release acceptance: full `scripts/verify-postgresql.sh` and required acceptance checks must pass before handoff. The tarball excludes deployed data/secrets, preserves executable modes and licenses, and emits a checksum; explicit references remain available for isolated tests. The admin guide covers setup, proxy/origin, DB credential rotation limits, stopped-app PAT management, optional post-install tasks, upgrades and matching DB/runtime backup/restore. JUnit and `scripts/verify-postgresql.sh` pass; image contents, archive, Compose configuration, clean local-image startup, fresh/restart/failure-injection, HTTPS/WSS, viewer login, port isolation, PAT and restore checks are recorded, with unavailable publication/public-CA checks stated as blockers or unrun.

**Completion evidence:** Exact test/build/rehearsal commands and outputs, image identity, sanitized archive/checksum audit, and explicit release blockers or unrun checks in the status ledger.

## 024 — Automate GitHub source releases and announcements

**Dependencies:** 022

**Scope:** Add an explicitly approved release script that updates all five reactor POM versions to the selected release version, verifies the release revision, commits and creates an annotated tag, pushes the branch and tag, creates the GitHub release, advances and pushes the next SNAPSHOT version, and publishes a Mastodon announcement. Document prerequisites, credentials, verified gates, separation from task 023 deployment publication, and recovery from partial external failures.

**Acceptance criteria:** For the separate source-only workflow, the script requires explicit release and next-SNAPSHOT versions, reviewed GitHub release notes and Mastodon text; validates the configured branch/remote, clean synchronized worktree and absent tag before editing; and requires explicit release-gate approval before remote publication. It verifies all five POM versions and rebuilds the release distribution with `mvn clean package -DskipTests` by default; `--with-tests` opts into the existing PostgreSQL-backed verification against the release version before creating an annotated tag and non-forced pushes. Skipping tests does not itself constitute release acceptance: required product and operational release gates must still pass before handoff. It uses authenticated GitHub tooling and a Mastodon token that is never placed in command arguments; stops on the first failure without retrying, rolling back or duplicating a public action; reports completed actions and manual recovery steps; supports an empty release commit when the release version equals the current version. Isolated tests cover successful order, both build modes, invalid arguments and failure gates without real GitHub/Mastodon publication. Documentation states that task 023's Docker image/bundle checks are separate and unresolved blockers are not release approval.

**Unified release follow-up:** For `patch`, a current `MAJOR.MINOR.PATCH-SNAPSHOT` releases as the same numeric version; a plain version increments its patch. `major` and `minor` retain their component increments and lower-component resets. After release, the workflow prepares the following patch `-SNAPSHOT`, so consecutive patch releases advance one version at a time. Before changing POMs, it displays the build mode, version, Git branch/tag pushes, image tags, GitHub assets and next-SNAPSHOT transition, then asks once for release-gate approval using `[Y/n]` (Enter accepts). `no`, invalid input and EOF stop without changing POMs or Git refs. Isolated tests cover snapshot/plain/major/minor versions, consecutive patches, Enter/yes approval, cancellation and preflight failures without external publication.

**Completion evidence:** Exact isolated test, syntax, whitespace and Maven verification commands/results; reviewed release instructions and explicitly listed unrun external publication checks.

## 025 — Generate and recover initial account passwords without plaintext files

**Dependencies:** 023

**Scope:** Replace the Compose first-start viewer password file with a high-entropy administrator password generated in process memory, hashed through Strolch's configured encryption handler without writing plaintext to disk or passing it in process arguments. Display it once on the attached console only after initialization succeeds. Add a packaged offline reset command that holds the runtime lock and atomically changes only the initial account password hash. Preserve already initialized installations, standalone deployment behavior, and any pre-existing legacy password files; do not delete legacy files automatically.

**Acceptance criteria:** A new Compose installation requires no viewer password file or password-file environment variable. The generated administrator password is not persisted in a plaintext file, environment, process arguments or Docker application logs; it is printed exactly once after the initialized marker is committed, and `admin` can authenticate with viewer and administrative roles. Restart does not generate or print another password and does not change existing privilege files. The reset command refuses to run while the application owns the runtime lock, rejects missing/ambiguous initial-account records, atomically replaces only the initial account hash, preserves all other users, and prints the replacement only after success; the new password works and the old one fails. Initialization/reset failures disclose no password and preserve fail-closed state. Compose foreground-start instructions, non-retention of the one-time output, reset/recovery, legacy-file handling and the limitation that published `0.0.2` images do not contain this change are documented. Unit, PostgreSQL-backed process and Compose startup checks cover success, restart, reset, lock contention, ambiguous users and failure paths; the Maven verification, script syntax, Compose smoke and whitespace results are recorded.

**Completion evidence:** Exact verification commands/results, startup/reset output and authentication evidence, plus explicit unrun checks and compatibility/release limitations in the status ledger.

## 026 — Allow HTTP origins and persist actionable startup diagnostics

**Dependencies:** 023, 025

**Scope:** Accept valid HTTP and HTTPS viewer origins so local and private-network deployments can be tested without a certificate; HTTPS remains recommended for exposed deployments, while TLS termination belongs to the operator. Preserve exact Origin/CSRF checks and set session-cookie Secure according to the configured scheme. Record actionable, secret-safe stage and failure details for provisioning and startup in the protected persistent application log, while keeping the one-time administrator password and Docker stdout/stderr out of persistent logs. Document origin syntax, HTTP's transport risk, and where to find diagnostics.

**Acceptance criteria:** HTTP and HTTPS origins with valid authorities are accepted; malformed origins, credentials, paths, query strings, fragments, and invalid ports are rejected with an explicit configuration diagnostic. HTTP cookies are not marked Secure and HTTPS cookies remain Secure; Origin/CSRF enforcement is unchanged. Initialization and startup failures include the failed stage and useful safe diagnostics in the protected application log without leaking passwords, database credentials, tokens, or arbitrary untrusted exception text; recovery remains fail-closed and existing initialized state is not silently changed. Compose HTTP testing and TLS risk, origin syntax, log location, and one-time-password non-retention are documented. Origin unit/session tests, provisioning diagnostics tests with secret canaries, targeted PostgreSQL-backed process/session tests, Maven/build verification, Compose HTTP configuration checks, and whitespace checks pass; exact results and unrun checks are recorded in the ledger.

**Completion evidence:** Exact test/build/Compose commands and results, diagnostics confidentiality evidence, HTTP/HTTPS authentication and cookie evidence, and explicit unrun checks in the status ledger.

**Local-storage follow-up:** The release tarball contains empty, protected `state/postgres`, `state/runtime`, `state/app-logs`, `state/caddy-data` and `state/caddy-config` directories. Compose uses only explicit bind mounts with missing-path creation disabled; app-owned paths are initialized before database/application startup without recursively changing existing contents. Archive tests verify entries and modes, Compose tests verify all mounts and fresh/restart/persistence behavior, and the guide documents a stopped, backed-up migration from the prior named volumes. Existing bundles and published images remain unchanged.

**First-install administration follow-up (supersedes the earlier viewer-only/manual-provisioning acceptance above):** A fresh Compose runtime provisions one high-entropy initial `admin` account with `Viewer`, `Admin` and `TokenOperator` roles; its password is displayed once after initialization. The authenticated browser UI creates, updates and removes viewer accounts, provisions Producer users with scoped `event:ingest` PATs, manages unique stable machine-to-instance mappings, and rotates/revokes tokens without direct runtime-file edits. Mutations require the admin privilege and exact-origin/CSRF checks; viewer-only sessions are denied. Identity changes are atomically persisted with owner-only permissions and immediately affect ingestion. Creation failures roll back partial user/token/mapping state where possible; existing initialized installations are never automatically elevated or overwritten. Integration tests cover administrator login, viewer denial, CSRF, account/password operations, narrowly scoped tokens, mapping persistence across restart, token revocation and cleanup; exact test and build evidence, including unavailable checks, is recorded in the ledger.

## 027 — Publish version-qualified Docker tags

**Dependencies:** 023, 024

**Scope:** Publish the application image under major, minor, and full patch-version tags in both the standalone image workflow and the unified release workflow. Preserve any version qualifier, including `-SNAPSHOT`, on all three version-derived tags. Also publish non-SNAPSHOT versions as `latest`; never publish a SNAPSHOT image as `latest`.

**Acceptance criteria:** A valid `MAJOR.MINOR.PATCH` POM version produces major, major.minor, and full-version tags; a qualified version keeps its qualifier on each version-derived tag. A version without a `-SNAPSHOT` suffix also produces a `latest` tag, while SNAPSHOT suffix matching is case-insensitive and never produces `latest`. The image is built once and all applicable references are tagged and pushed. Invalid versions fail before build/publication. The unified release still checks that the immutable full-version image does not already exist, and reports successfully pushed tags if a later push fails. Isolated tests cover stable and SNAPSHOT versions, build/tag/push order, invalid versions and failure short-circuiting; release documentation and README describe the tag policy and mutable `latest` recovery. Record exact checks and unrun external publication checks in the status ledger.

**Completion evidence:** Exact isolated test, shell syntax and whitespace commands/results; explicit confirmation that no real image was published.

## 028 — Simplify deployment onboarding and Fail2ban setup

**Dependencies:** 018, 021

**Scope:** Make the deployment guide a concise release-bundle installation procedure: download and verify the versioned GitHub archive, unpack it, set fresh database passwords and the public origin, pull the paired image, initialize the database and administrator, then start all services. Make the attached first-start command exit successfully after printing the one-time administrator password, while initialized starts continue serving normally. Keep operational exceptions, recovery and troubleshooting after the happy path; retain the guarded Fail2ban setup and its packaged installer.

**Acceptance criteria:** Deployment documentation follows the user's release flow in order: download the versioned bundle and checksum, verify and unpack it, generate distinct PostgreSQL passwords with the simple documented OpenSSL commands, set `INTRUVIA_PUBLIC_ORIGIN`, pull the release image, run `docker compose up -d --wait db`, run `docker compose run --rm --no-deps -T app` to initialize and capture the one-time administrator password, and then run `docker compose up -d`. No build, Python setup script, or extra first-start command is required. The first-start app command exits zero only after successful provisioning and password output, releasing its runtime lock; initialized runs continue to serve normally and do not print another password. Tests verify first-run exit, one-time password, successful subsequent service startup/login, persisted state and recovery behavior. HTTPS and secret-handling cautions remain beside setup; existing Fail2ban guidance/installer, standalone deployment, backup/restore, operational exceptions and troubleshooting remain documented. The guarded Fail2ban setup preserves the default firewall action and refuses unsafe/pre-existing configuration without overwriting files or leaking secrets. Record exact checks and unrun checks in the status ledger.

**Completion evidence:** Exact first-start and PostgreSQL/Compose acceptance checks, Fail2ban tests and package/archive checks, documentation/link/shell-syntax checks, and confirmation that normal setup precedes troubleshooting and recovery guidance in the status ledger.

## 029 — Downloadable Fail2ban onboarding

**Dependencies:** 018, 023, 028

**Scope:** Serve a self-contained Fail2ban setup from the Intruvia host, authenticated by the machine's ingestion token, and show an installation command next to newly issued tokens in the administration UI. Document the same flow and retain manual setup for custom configurations.

**Acceptance criteria:** A scoped, mapped, active producer PAT can download the packaged installer; missing, invalid, insufficient and revoked credentials are rejected. The UI provides a copyable command without embedding the token, which prompts securely and downloads over verified HTTPS. The installer configures a standard jail without overwriting existing files or firewall actions, protects the token, validates and reloads Fail2ban, and reports failures safely. Cover endpoint authorization, bundled resources, command presentation, successful setup and failure/rollback paths. Record exact checks and unrun checks in the ledger.

**Completion evidence:** Focused HTTP, installer, UI and packaging checks and exact results in the status ledger.

## 030 — Machine-first provisioning and long-lived tokens

**Dependencies:** 005, 023, 029

**Scope:** Simplify machine creation to a required, administrator-chosen machine ID, defaulting the Strolch username and instance ID to that stable value and the token name to its first 64 characters. Keep optional overrides for all three derived values. Allow token issuance and rotation for 1–3650 days or explicit practical non-expiry, without changing existing API field names or mappings.

**Acceptance criteria:** The admin form needs only a valid machine ID by default; advanced optional overrides can be changed independently, retain entered values, and reset after successful creation. A human-readable machine ID is used as the default stable instance ID; it is not randomly regenerated and changing an existing mapping remains an explicit operation. Both token creation paths reject malformed/out-of-range values, accept 1–3650 days, and treat 0 as practical non-expiry using the documented 9999-12-31 UTC sentinel required by Strolch. Token listings clearly distinguish this sentinel from ordinary expiries. Tests verify default and override behavior, token metadata, authentication, persistence across restart, and revocation. Documentation explains stability, the sentinel's finite end date, and the increased exposure risk and continued revocability of long-lived tokens.

**Completion evidence:** Exact admin browser and PostgreSQL-backed token lifecycle checks, build/lint results, documentation checks, and any unrun checks in the status ledger.
