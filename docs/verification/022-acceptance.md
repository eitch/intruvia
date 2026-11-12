# Task 022 — MVP release acceptance checklist

Review date: 2026-11-12. Source base revision:
`d0e4d5a9f00a57b8399859c3bdd4a559892bbc09`, with uncommitted task-022
release notes, README/ledger updates and verification/packaging files.
No commit, push, tag, deployment or other backlog task was performed.
Initial workspace was clean. Dependencies 020/021 were DONE; no active task existed.
Only task 022 was set IN_PROGRESS before changes. Required guidelines were available,
including Strolch CODE_STYLE.md's Vanilla JavaScript section and STROLCH_SPECIFICATION.md.

## Specification audit

Paths below are relative to the repository; source inventories bind the reviewed bytes.
“Pass” means the identified implementation and evidence meet the specified MVP contract,
subject to the explicit operational limits in the release notes. Historical failures are
retained in their original records. No broader product feature or dependency was added.

| Specification section | Implementation and evidence reviewed | Finding |
|---|---|---|
| 1 — Purpose/boundary | Four Maven modules; registered resources in `IntruviaRestApplication` and bootstrap; `integrations/fail2ban`; README/deployment guide | Pass: read-only viewer and ban observations; no firewall control, signup, generic ingestion, analytics, broker or HA interface. |
| 2 — Architecture | Root/module POMs, `IntruviaApplication`, `EventRepository`, commands/services/searches, production `StrolchConfiguration.xml`; tasks 001/002/004 | Pass: Java 25/Jakarta/Jersey/Jetty, PostgreSQL CACHED realm, supported Strolch lifecycle/transactions; no parallel ORM or frontend build. Dependency flow app → REST/web → core is acyclic. |
| 3 — Model/invariants | `SecurityEvent`, `EventModelMapper`, `EventJson`, model templates, `docs/api/v1.schema.json`; `IngestService`/`IngestCommand`, `EventStreamLock`; model/ingestion tests | Pass: typed model/provenance/nullability, string sequences, canonical digest and atomic event/receipt/head; enrichment before write lock; duplicate/conflict/rollback/restart covered. |
| 4 — Fail2ban/API | `MachineAuthenticationFilter`, `MachineIdentities`, `Fail2banRequestParser`, `IngestionLimits`, `Fail2banResource`; authentication and HTTP tests; helper/action evidence 018/020 | Pass: actual Strolch PAT verification and scoped certificate, protected identity mapping, bounded validation/admission, specified statuses, safe errors; stable retry payload and bounded HTTPS helper. Earlier custom verifier evidence is superseded by revised 005. |
| 5 — GeoIP | `GeoIpComponent`, `PublicAddressPolicy`, synthetic fixture provenance, GeoIP tests and architecture 007/008 | Pass: local reused City reader, IPv4/IPv6/non-public/partial/zero results, staged validation/atomic replacement and lifecycle close; unavailable DB degrades ingestion. No production MMDB/credentials bundled. |
| 6 — Browser auth/reads | `ViewerSessions`, `ViewerConfiguration`, `SessionResource`, `EventsResource`, `EventReadService`/search; session/read ITs | Pass: same-origin CSRF/cookie sessions, PAT separation, privileges, locked bounded snapshot/fixed-through history, malformed/ahead/expired cursors. |
| 7 — Stream/recovery | `EventStreamServlet`, component, `StreamConnection`, notifications; browser `Recovery.js`, `live.js`, `ViewerPage`; stream ITs and browser recovery suite | Pass: ordered durable replay/polling, origin/session checks, finite send bounds, logout/expiry, cursor updated after application, dedup and visible resnapshot. Crash-window evidence means missed wakeup, not power loss. |
| 8 — Map/UI | Native ES modules under web resources; EventMap/EventList/eventDetails; local vendor/data notices; snapshot/live browser suites | Pass: local map, attribution, safe text, keyboard list/details, 1000 bound, partial/unmapped/degraded state, independent three-second indicators, reduced motion/off-screen behavior and duplicate suppression. |
| 9 — Operations | Retention policy/command, runtime configuration, health/diagnostics, request lifecycle, logback; retention/runtime ITs; deployment/restore 021 | Pass: atomic prefix pruning and receipt preservation/capacity; protected diagnostics, bounded logs/queues, shutdown and whole-database/runtime backup. Defaults crosschecked below. |
| 10 — Verification/gate | Fresh 022 Maven and browser/helper outputs; 020 capacity raw-data audit; 021 restore/TLS/WSS records; 022 source/dependency/distribution manifests | Pass: functional gate and measured combined-workload p95; phase/population and environment limits remain explicit. |
| 11 — Reference basis | 001 baseline decision, revised 005 framework source/API review, actual snapshot cache/source inspection; 021 provisioning recipe | Pass: implementation verified against actual local framework, not inferred sibling compatibility or relabelled timestamp binaries. |

The specification's version-1.1 introductory “PAT integration pending” sentence is
historical; its authoritative task-005 ledger and current implementation/evidence
show the revision complete. No contract or numbered backlog requirement was changed.

## Defaults and frontend review

Crosschecked source `IngestionLimits`, `EventAgePolicy`, `StreamLimits`,
`RetentionPolicy`, GeoIP configuration and packaged XML/examples against README and
architecture 007/008/010/011/013/014/019:

- Body 16384 bytes; event age 604800 s; future allowance 300 s; per-instance 10/s,
  burst 100; global 100/s, burst 1000; connections 256; idle timeout 30 s.
- Event age 2592000 s/capacity 100000; receipt age 2592000 s/capacity 1000000;
  daily sweep 86400 s. Receipt lifetime below 30 days is rejected.
- Stream queue 1000 frames; message 65536 bytes; send 10 s; durable poll 250 ms.
- Snapshot/browser maximum 1000; history default 500/max 1000; string cursors.
- Session TTL/max lifetime 30 minutes, configurable within 1–1440; exact configured
  HTTPS origin, optional loopback HTTP; cookie HttpOnly/SameSite Strict/path `/api/v1`.
- GeoIP staged-file checks every 60 s and stale warning after 14 days. Empty path
  disables reader, not persistence/readiness. No historical re-enrichment.
- Loopback bind/default port 8080; schema creation/migration/drop/init flags false.
  Pool environment-mode defaults 10/30000/5000 differ from XML 3000/2000 budgets;
  this distinction and burst failure history are documented, not silently unified.
- File rollover 10 MB, five days, 50 MB archive cap plus active/temporary files;
  synchronous operational messages with framework/Jersey logs disabled.

Frontend responsibilities are separated into entry coordination, page/recovery,
components, API/transport and pure shared format/model helpers. Inspected native
relative imports, dependency direction, textContent/DOM value rendering, Intl in
shared format helper, timer/request/listener cleanup and actual page destruction.
All application JS modules are below the 300–500 line guidance; no size exception.
English is the only supplied UI language; no new localization set or visible text
was added. Browser tests exercise interactions rather than relying on source assertions.

## Acceptance commands and results

All commands ran from the repository root, using existing installed tools only.
Java prefix for Maven:
`JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.5.0+8-tem`
and PATH prefixed by its `bin` and `/home/eitch/.sdkman/candidates/maven/3.10.0/bin`.

1. `scripts/verify-postgresql.sh -o -Dstrolch.version=2.8.0-SNAPSHOT > docs/verification/022-maven.txt 2>&1`
   — exit 0, clean verify; all five modules SUCCESS; **87 tests, zero failures/errors/skips**
   (46 unit/HTTP and 41 real PostgreSQL/application integration).
2. `bash docs/verification/022-run-browser.sh > docs/verification/022-browser.txt 2>&1`
   — exit 0; all three serial snapshot/live/recovery suites PASS on this build;
   live receipt-to-frame p95 25.7 ms over 30 samples, first highlight 2999.9 ms.
   Zero external asset requests or script errors. Per-suite results
   and screenshots use `022-snapshot`, `022-live`, `022-recovery` prefixes.
3. `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s integrations/fail2ban/tests -v > docs/verification/022-helper-tests.txt 2>&1`
   — exit 0, 10 tests pass. No live firewall/jail changed.
4. `python3 docs/verification/020-capacity-review.py > docs/verification/022-capacity-audit.txt 2>&1`
   — exit 0; re-audited retained task-020 raw measurements, exact workload/counts and
   combined/phase p95s. The five-minute capacity workload was not rerun in 022;
   production code is unchanged from the validated 020/021 revision.
5. `python3 docs/verification/022-review.py > docs/verification/022-review.txt 2>&1`
   — exit 0; all 87 report results, packaged class/resource/frontend/runtime bytes,
   actual framework/cache byte identity and clean framework HEAD checked;
   generated source/framework/resolved dependency inventories. No framework rebuild
   needed: offline cache sufficed. No timestamped-baseline equivalence claimed.
6. Documentation-only package refresh, deterministic archive creation, all checksum,
   ledger/whitespace/cleanup checks are recorded in `022-final-checks.txt`.
   `022-artifacts.sha256` inventories every distribution file; `022-archive.sha256`
   identifies the deliverable archive. The archive is reproduced twice from identical
   inputs and each archived file byte-compared. Maven cross-machine binary reproducibility
   is not claimed. No executable or configuration behavior changed in this task.

The task-021 `OperationalLoggingTest` archive-cleanup race failure is retained in
`021-final-maven.txt`; its fresh complete retry passed, and this fresh clean release
suite passed on its first attempt. No failure was erased or silently counted as success.
One exploratory read used nonexistent `IngestionConfiguration.java`; located the
actual `IngestionLimits.java`. One grouped `tail` inspection used invalid syntax;
neither was an acceptance check. No required guideline or dependency was unavailable.

## Prior evidence, scope and limits

All ledger rows 001–021 were checked DONE with nonempty evidence; local Markdown
references in those rows were checked to exist. Revision identity and exact delivered
files are captured without manufacturing a release commit. No unresolved MVP product
defect was found by this audit or fresh acceptance checks. No dependency/toolchain,
endpoint, schema or broader product feature was added. The archive helper and browser
runner are optional verification tools using existing Python/Bash/Docker/Node tooling.

Read [release notes](../RELEASE_NOTES.md) for the material latency, loss, retention,
restore, snapshot/version and deployment limits. Tasks 020/021 raw results remain the
source for capacity and restoration; release review does not grant a per-burst latency
promise or a production deployment/vulnerability certification. Framework test suite,
new five-minute capacity run, new full TLS/restore rehearsal, live MaxMind download,
public deployment, host system changes and OS power-loss checks were not run in 022.

Screenshots `022-live-overlap.png` and `022-recovery-recovered.png` visually inspected;
synthetic map/list highlights, attribution and recovered live state are readable.
Browser runner cleanup reported zero remaining test databases; `docker ps` showed
no running containers. All 001–021 ledger evidence paths exist.
