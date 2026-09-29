# Intruvia — backlog status

Updated: 2026-09-29 · Implementation state: task 004 complete

Links: [specification](INTRUVIA_SPECIFICATION.md) · [numbered backlog](INTRUVIA_BACKLOG.md)

## Execution state

- Current task: **004 — Configure durable Strolch persistence (DONE)**.
- Next task: **005 — Implement machine credential authentication (TODO)**; dependency 002 is DONE.
- Completed: **4 / 22**. TODO: **18**. IN_PROGRESS: **0**. BLOCKED: **0**.
- Foundational decisions specified: MVP boundary; Strolch/Java/JAX-RS/embedded Jetty/Vanilla JS stack; module boundaries; generic model; local MaxMind enrichment; authenticated ingestion/viewing; durable replay; map highlighting. These are design inputs, not completed implementation tasks.
- Exact dependency versions and integration conventions are verified by task 001; the task 002 application skeleton and task 003 model contracts are verified; task 004 persistence is verified with the local snapshot override; ingestion, viewer and deployment remain pending.

## Status conventions

`TODO` = not started; `IN_PROGRESS` = the single active task; `BLOCKED` = cannot proceed, with a recorded reason and next action; `DONE` = acceptance criteria passed and evidence recorded. At most one task may be IN_PROGRESS. Change the summary counts, current/next task and update date whenever status changes.

## Task ledger

| Task | Title | Dependencies | Status | Evidence / blocker |
|---|---|---|---|---|
| 001 | Verify and pin the Strolch runtime baseline | — | DONE | [Decision](architecture/001-runtime-baseline.md), [probe](../probes/runtime-baseline/pom.xml), [verification](verification/001-resume-verification.txt), [checksums](verification/001-dependencies.sha256). Clean verify: 2 tests passed; REST/WebSocket/lifecycle and Jakarta namespace verified. |
| 002 | Create the Maven modules and application skeleton | 001 | DONE | [Architecture](architecture/002-application-skeleton.md), [clean verify](verification/002-clean-verify.txt), [process smoke](verification/002-application-smoke.txt). Four modules; 2 integration tests passed; packaged page/health and Strolch/Jetty shutdown verified. |
| 003 | Define event DTOs and Strolch model mapping | 002 | DONE | [Mapping](architecture/003-event-model.md), [schema](api/v1.schema.json), [fixture](api/event-v1.json), [clean verify](verification/003-clean-verify.txt). 7 contract tests and 2 existing integration tests passed. |
| 004 | Configure durable Strolch persistence | 003 | DONE | [Local snapshot verification](verification/004-local-verify.txt), [binary checksums](verification/004-local-strolch.sha256), [design](architecture/004-persistence.md). 7 contract tests and 4 PostgreSQL integration tests passed; restart, rollback, migration, isolation and configuration guards verified. |
| 005 | Implement machine credential authentication | 002 | TODO | — |
| 006 | Implement Fail2ban validation and normalization | 003 | TODO | — |
| 007 | Implement local GeoIP enrichment | 003 | TODO | — |
| 008 | Add safe GeoIP database replacement | 007 | TODO | — |
| 009 | Implement atomic ingestion and deduplication | 004, 006, 007 | TODO | — |
| 010 | Expose the authenticated ingestion endpoint | 005, 009 | TODO | — |
| 011 | Add authenticated viewer sessions | 002, 005 | TODO | — |
| 012 | Implement bounded snapshots and cursor history | 004, 009, 011 | TODO | — |
| 013 | Implement bounded retention and receipt cleanup | 009, 012 | TODO | — |
| 014 | Implement the ordered WebSocket event stream | 010, 011, 012, 013 | TODO | — |
| 015 | Build the viewer shell and initial map | 011, 012 | TODO | — |
| 016 | Add immediate per-event visual highlighting | 014, 015 | TODO | — |
| 017 | Implement browser reconnect and resynchronization | 016 | TODO | — |
| 018 | Ship the Fail2ban action and notification helper | 010 | TODO | — |
| 019 | Complete runtime configuration and diagnostics | 008, 010, 013, 014 | TODO | — |
| 020 | Verify the full MVP and capacity targets | 017, 018, 019 | TODO | — |
| 021 | Document and verify deployment and recovery | 018, 019 | TODO | — |
| 022 | Perform the MVP release review | 020, 021 | TODO | — |

## Execution log

2026-09-28 (UTC) — Task 001, Codex: TODO → IN_PROGRESS. Verified the ledger has no active or blocked task; task 001 has no dependencies. Read project and Strolch guidelines (including Vanilla JavaScript), specification, backlog and status. Initial `git status --short` was clean. Scope: baseline decision and isolated compatibility probe only.

Append one entry for each task attempt:

```text
Date/time (UTC):
Task number:
Agent/owner:
Status transition:
Change summary:
Files and commit/artifact reference:
Acceptance checks (exact command or manual procedure):
Results (including failures and unrun checks):
Blocker and required next action, if any:
Specification/backlog changes, if any:
Next eligible task:
```

A failed check keeps the task incomplete. If a previously completed task needs repair, reopen it and record why; reassess affected downstream evidence. Never erase failed-check history or replace evidence with an unsupported success claim.

### 2026-09-28 (UTC) — task 001 blocked

- Agent: Codex. Transition: IN_PROGRESS → BLOCKED.
- Changes: recorded sibling/framework versions and source references, available Java LTS,
  bootstrap/session/privilege/persistence observations and dependency-access failure.
- Artifacts: [baseline investigation](architecture/001-runtime-baseline.md) and
  [exact verification commands/results](verification/001-baseline-attempt.txt). No commit.
- Results: local source and Java 25 available; Jetty WebSocket implementation absent from
  inspected cache; Maven Central request failed with curl exit 6 (DNS resolution).
- Unrun: probe compile/start/stop and API namespace/runtime checks. Acceptance criteria
  remain incomplete; no final version decision or compatibility success claimed.
- Blocker/next action: restore repository access or provide the complete dependencies
  in an accessible cache; explicitly resume task 001. No permission escalation is
  available in this session. No dependent implementation started.
- Specification/backlog requirements unchanged. Counts: DONE 0, TODO 21,
  IN_PROGRESS 0, BLOCKED 1. Next eligible TODO: none; task 002 awaits 001.
- Final documentation check: `git diff --check` passed (exit 0).

### 2026-09-28T06:41:38Z — task 001 authorized retry

- Agent: Codex. Transition: BLOCKED → IN_PROGRESS. No other IN_PROGRESS task; task 001 has no dependencies.
- Read all required project/framework guidelines, including Vanilla JavaScript and the linked Strolch specification, and all three project documents.
- Blocker recheck: the recorded `curl -I --connect-timeout 10 --max-time 15` Maven Central URL returned HTTP 200 (exit 0); `rg --files /home/eitch/.m2/repository/org/eclipse/jetty | rg 'websocket.*jar$'` still found no JARs (exit 1). Dependency downloads are now accessible.
- Existing changes to README.md, this ledger, docs/TASK_RUNNER.md, next-task.sh, docs/architecture/ and docs/verification/ will be preserved. Scope remains task 001 only.

### 2026-09-28T10:53:01Z — task 001 completed

- Agent: Codex. Resumed the single IN_PROGRESS task; no dependencies. Transition: IN_PROGRESS → DONE.
- Read AGENTS.md, all referenced Strolch guidelines including Vanilla JavaScript and Strolch specification, and all three backlog/specification documents.
- Preserved existing probe, checksum inventory, prior evidence and unrelated docs/TASK_RUNNER.md changes. Reviewed and verified the existing probe; finalized the baseline decision and README usage instructions. No commit, push or deployment.
- Artifacts: [decision](architecture/001-runtime-baseline.md), [probe source](../probes/runtime-baseline/src/test/java/li/intruvia/probe/RuntimeBaselineTest.java), [exact commands/output](verification/001-resume-verification.txt).
- Acceptance command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH mvn -B -f probes/runtime-baseline/pom.xml clean verify dependency:tree dependency:build-classpath -Dmdep.outputFile=target/classpath.txt` — exit 0; 2 tests, 0 failures/errors/skips. Start/stop, HTTP response, WebSocket exchange/close, namespace and driver loading passed. Empty probe JAR warning expected.
- Integrity command (cwd `/home/eitch/.m2/repository`): `sha256sum -c /home/eitch/src/git/intruvia/docs/verification/001-dependencies.sha256` — exit 0, all 150 entries OK.
- Minor failed inspection: relative classpath path used from Maven-cache cwd; corrected from workspace root successfully. Web reader metadata request failed; Maven and checksum evidence succeeded. Earlier dependency-access failure history preserved.
- Unrun: actual PostgreSQL durability, viewer authentication, browser and application distribution checks; belong to subsequent tasks. No remaining task 001 blocker. Specification/backlog scope unchanged.
- Counts: DONE 1, TODO 21, IN_PROGRESS 0, BLOCKED 0. Next eligible task: **002**; not started.
- Final checks: `git diff --check` — exit 0; Python ledger assertion — 22 rows, DONE 1/TODO 21, task 001 DONE and task 002 TODO, passed.

### 2026-09-28 — task 002 started

- Agent: Codex. Transition: TODO → IN_PROGRESS. No active task; task 002 is the lowest eligible TODO and dependency 001 is DONE.
- Read project and framework guidelines, including Vanilla JavaScript and the linked Strolch specification, plus specification, backlog and ledger.
- Preserving existing changes to this ledger and untracked docs/architecture/, docs/verification/ and probes/. Scope: four Maven modules, minimal page/health and assembled lifecycle verification only.
- Counts: DONE 1, TODO 20, IN_PROGRESS 1, BLOCKED 0. Next task 003 awaits 002.

### 2026-09-28 — task 002 initial verification

- Command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH mvn -B clean verify` — exit 1.
- Evidence: [initial build output](verification/002-build-attempt.txt). Two integration tests ran: assembled-process HTTP/shutdown passed; lifecycle test failed on expected closed-port sentinel -1 versus actual -2. Corrected test to assert a negative port (not listening). No application lifecycle failure observed. Task remains IN_PROGRESS pending a clean rerun.
- Inspection failure: guessed framework `DefaultComponentContainer.java` path did not exist; `rg --files` located `ComponentContainerImpl.java`. No required guideline was missing.

### 2026-09-28T11:03:51+00:00 — task 002 completed

- Agent: Codex. Transition: IN_PROGRESS → DONE. All task 002 acceptance criteria passed; no other task started.
- Changes: root dependency/plugin management with baseline pins; core/rest/web/app modules; explicit Jersey liveness resource; local static page; loopback Jetty/Strolch lifecycle; executable directory distribution; packaged-process and lifecycle integration tests; README build/run instructions.
- Sources: [root POM](../pom.xml), [bootstrap](../intruvia-app/src/main/java/li/intruvia/app/IntruviaApplication.java), [tests](../intruvia-app/src/test/java/li/intruvia/app/ApplicationIT.java), [architecture](architecture/002-application-skeleton.md). Generated artifact: `intruvia-app/target/intruvia/intruvia-app-0.0.1.jar` plus `lib/` and `runtime/`; [artifact hashes](verification/002-artifacts.sha256).
- Acceptance command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH mvn -B clean verify dependency:tree` — exit 0; five reactor projects SUCCESS; 2 integration tests, 0 failures/errors/skips. [Full output](verification/002-clean-verify.txt), [test results](verification/002-test-results.txt), [packaged process log](verification/002-application-smoke.txt).
- Packaged Java process starts Strolch/Jetty, serves local `/` and `/index.html` and JSON `/health/live`, returns 404 for absent event/readiness/config paths, and shuts down within 15 seconds after termination signal. Verified STOPPED/DESTROYED and repeat-close behavior. Initial failed assertion and exact failed command remain recorded above.
- Dependency/source review: `app → rest → core` and `app → web`, no cycles; resource-only frontend with no framework, Node or bundler. Static HTML HTTP delivery tested; visual browser checks not run or required for this minimal static page.
- Integrity command (cwd `/home/eitch/.m2/repository`): `sha256sum -c /home/eitch/src/git/intruvia/docs/verification/001-dependencies.sha256` — exit 0; all 150 entries OK, [output](verification/002-baseline-integrity.txt). Baseline probe/evidence and initial user changes preserved.
- Artifact command: `sha256sum intruvia-app/target/intruvia/intruvia-app-0.0.1.jar intruvia-app/target/intruvia/lib/intruvia-{core,rest,web}-0.0.1.jar` — exit 0.
- Final review: `git diff --check` passed; changed source/XML/HTML line lengths under 160 columns. No credentials, realm or event storage bundled. Database/authentication/WebSocket/browser-capacity checks unrun and outside task 002.
- No blocker. Specification and backlog requirements unchanged. No commit, push or deployment.
- Counts: DONE 2, TODO 20, IN_PROGRESS 0, BLOCKED 0. Next eligible task: **003**; remains TODO.

### 2026-09-28 — task 003 started

- Agent: Codex. Transition: TODO → IN_PROGRESS. Ledger verified: no active tasks; lowest eligible TODO is 003, dependency 002 DONE. Initial `git status --short` clean.
- Read project/framework guidelines, Vanilla JavaScript rules, linked framework specification and all three project planning documents. Scope limited to task 003. No commit, push or deployment.
- Counts: DONE 2, TODO 19, IN_PROGRESS 1, BLOCKED 0. Next task 004 awaits 003.

### 2026-09-28 — task 003 completed

- Agent: Codex. Transition: IN_PROGRESS → DONE. Implemented only task 003; no commit, push or deployment.
- Changes: immutable event/GeoIP/receipt/stream records; browser-safe decimal string sequences; typed Strolch XML templates and detached bidirectional mapper; Fail2ban request/response and error DTOs; shared Gson codec using the existing dependency; v1 JSON Schema and complete synthetic IPv6 event fixture. All section 3 storage types/nullability documented. README updated.
- Sources/artifacts: `intruvia-core/src/main/java/li/intruvia/core/model/`, `intruvia-core/src/main/resources/model/templates.xml`, `intruvia-rest/src/main/java/li/intruvia/rest/dto/`, [mapping](architecture/003-event-model.md), [schema](api/v1.schema.json), [fixture](api/event-v1.json), [JAR hashes](verification/003-artifacts.sha256).
- Acceptance command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH mvn -B clean verify` — exit 0. Initial run: 6 contract tests plus 2 integration tests passed ([output](verification/003-initial-verify.txt)). Final run after partial-location/text coverage and response invariant: 7 contract tests plus 2 integration tests, zero failures/errors/skips; all reactor modules SUCCESS ([output](verification/003-clean-verify.txt)).
- Round trips exercise actual pinned Strolch XML serialization/reload and JSON: IPv6, namespaced attributes and escaped text, missing/null optional values, all five GeoIP statuses, partial FOUND records, real zero coordinates, timestamp nanoseconds and UTC conversion, receipt/state Long.MAX_VALUE and sequence above JavaScript safe integer range. Unsupported schema versions and invalid coordinates/cursors fail.
- Artifact sanity command: `python3 docs/verification/003-contract-check.py` — exit 0 ([output](verification/003-contract-check.txt)); JSON parses, local schema references resolve, complete envelope keys and decimal sequence boundaries pass, Java lines remain under 160 characters. This is not a general JSON Schema validation run. Optional Python `jsonschema` module inspection found it unavailable; no dependency was added and no full schema-validator result is claimed.
- Artifact command: `sha256sum intruvia-core/target/intruvia-core-0.0.1.jar intruvia-rest/target/intruvia-rest-0.0.1.jar` — exit 0. `git diff --check` — exit 0.
- Unrun/out of scope: PostgreSQL persistence/restart, strict inbound validation/normalization, HTTP ingestion and browser checks; assigned to subsequent tasks. No failed builds or remaining task 003 blockers. Specification/backlog requirements unchanged.
- Counts: DONE 3, TODO 19, IN_PROGRESS 0, BLOCKED 0. Next eligible task **004**; remains TODO. Task 005 is also dependency-eligible but follows 004 in ascending order.

### 2026-09-28 — task 004 started

- Agent: Codex. Transition: TODO → IN_PROGRESS. No active task; task 004 is the lowest eligible TODO, dependency 003 DONE. Current/next summary verified against all 22 ledger rows. Initial `git status --short` clean.
- Read project/framework guidelines, including Vanilla JavaScript and linked framework specification, and all three project planning documents. Scope limited to task 004; no commit, push or deployment.
- Checking availability of real PostgreSQL before implementation. Counts: DONE 3, TODO 18, IN_PROGRESS 1, BLOCKED 0. Next eligible task after this attempt: 005.

### 2026-09-28 — task 004 resume blocked by missing guidelines

- Agent: Codex. Transition: IN_PROGRESS → BLOCKED. Verified all 22 ledger rows: 004 was the sole active task and dependency 003 is DONE. No other task started.
- Read project AGENTS.md, `/home/eitch/src/git/atx-dev/strolch/AGENTS.md`, its six referenced guidelines and linked STROLCH_SPECIFICATION.md, plus all three Intruvia planning documents.
- Required Vanilla JavaScript guidelines could not be found. The referenced directory is `/home/eitch/src/git/atx-dev/strolch/guidelines/`; no exact Vanilla JavaScript filename is supplied by the available AGENTS.md. Required next action: provide or restore the guidelines and explicitly resume task 004.
- Checks: `git status --short` — exit 0, clean before this attempt. `rg` discovery failed with exit 127 (`rg: command not found`); used `find` instead. `find /home/eitch/src/git/atx-dev/strolch/guidelines -type f` listed seven Markdown files, none for JavaScript. `find /home/eitch/src/git -iname '*vanilla*' -o -iname '*javascript*guid*'` — exit 0, no matches. `find . -name AGENTS.md` found only the root AGENTS.md.
- Preliminary environment inspection: `command -v postgres initdb psql pg_isready docker podman` found only `/usr/bin/docker`; `/usr/lib/postgresql` was absent. Database availability is not established; no database was started or modified.
- Changes/artifact: this status ledger only. No implementation changes, commit, push or deployment. Specification/backlog requirements unchanged.
- Unrun: Maven verification and all task 004 real-database restart, rollback, migration, isolation and production-configuration acceptance checks. Task remains incomplete.
- Counts: DONE 3, TODO 18, IN_PROGRESS 0, BLOCKED 1. Next eligible TODO: **005**, dependency 002 DONE; not started because this invocation is limited to one task.

### 2026-09-28T18:55:39+00:00 — task 004 authorized blocker recheck

- Agent: Codex. Status: BLOCKED → BLOCKED. Explicit retry authorization selects 004, the lowest eligible blocked task; dependency 003 is DONE and no task is IN_PROGRESS. Summary and all 22 ledger rows agree.
- Rechecked the missing-guidelines blocker after reading both AGENTS.md files, available framework guidelines and linked specification, and all three Intruvia planning documents. Vanilla JavaScript guidelines remain unavailable; no exact filename is specified. Missing required document location: `/home/eitch/src/git/atx-dev/strolch/guidelines/` (Vanilla JavaScript guidelines).
- Commands/results: `command -v rg` produced no path (unavailable); fallback `find /home/eitch/src/git/atx-dev/strolch/guidelines -type f` — exit 0, seven Markdown files, none for JavaScript. `find /home/eitch/src/git -iname '*vanilla*' -o -iname '*javascript*guid*'` — exit 0, no matches. `git status --short` — exit 0, existing modification only to this ledger; preserved. `git diff -- docs/INTRUVIA_BACKLOG_STATUS.md` — exit 0, reviewed existing changes before appending this entry.
- Changes/artifact: this ledger only; existing history preserved. No implementation, commit, push, deployment or other task started. Specification/backlog unchanged.
- Unrun: Maven and all task 004 real-database restart, rollback, migration, isolation and production-configuration acceptance checks. Database availability was not rechecked because required guidelines remain missing.
- Required next action: restore the Vanilla JavaScript guidelines or supply their actual path, then resume task 004. Current task remains 004 BLOCKED; next eligible TODO is 005, not started under this retry-only authorization.
- Counts unchanged: DONE 3, TODO 18, IN_PROGRESS 0, BLOCKED 1. Python ledger assertions passed: 22 rows, no active task, only eligible blocked task 004, expected status counts.

### 2026-09-28 — task 004 authorized retry; guidelines located

- Agent: Codex. Transition: BLOCKED → IN_PROGRESS. Only eligible blocked task is 004, dependency 003 DONE; no active task. Read both AGENTS.md files, all seven framework guideline documents (including Vanilla JavaScript within CODE_STYLE.md), and all three project planning documents. Earlier missing-file reports are superseded by this content inspection.
- Existing ledger changes preserved. `rg` unavailable (exit 127); used `find` and direct reads. `find . -name AGENTS.md` found only root guidelines. `docker info --format '{{.ServerVersion}}'` — exit 0, server 29.8.1. PostgreSQL prerequisite check continues.
- Counts: DONE 3, TODO 18, IN_PROGRESS 1, BLOCKED 0. Next TODO 005; no other task started.

### 2026-09-28T19:00:16.418123+00:00 — task 004 blocked by dependency resolution

- Agent: Codex. Transition: IN_PROGRESS → BLOCKED. Existing persistence implementation, migration, repository, configuration guard, database fixtures and verification script were already present; reviewed without modifying them. Prior ledger changes and history preserved.
- Resolved prerequisite: Vanilla JavaScript guidelines read in `/home/eitch/src/git/atx-dev/strolch/guidelines/CODE_STYLE.md`. Docker PostgreSQL 18 started and passed `pg_isready` and `SELECT version()` in both attempts; each script removed its own disposable container. Existing user containers were not modified.
- Exact acceptance command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH INTRUVIA_TEST_POSTGRES_IMAGE=postgres:18-trixie scripts/verify-postgresql.sh` — exit 1 before compilation; cached missing dependencies. [Output](verification/004-retry-verify.txt).
- Forced recheck: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH INTRUVIA_TEST_POSTGRES_IMAGE=postgres:18-trixie scripts/verify-postgresql.sh -U` — exit 1; mirror `https://repo.atexxi.ch/repository/atx-all/` could not find `li.strolch:strolch-agent` and `li.strolch:strolch-service`, version `2.8.0-20260927.120822-2`. [Output and image digest](verification/004-retry-refresh-verify.txt).
- Unrun: compilation, model migration, real-database restart/rollback, isolation, production configuration rejection and packaged application acceptance tests. PostgreSQL startup alone does not satisfy task 004.
- Inspection failures: initially guessed runtime configuration and framework ComponentConfiguration source paths were absent; correct runtime file was located and read. No required guidelines remain missing.
- Blocker/next action: make the pinned Strolch artifacts available through the configured Maven repository/cache, then retry task 004. No dependency versions or toolchains changed.
- Changes: this ledger and two verification logs only. No implementation edits, commit, push, deployment or other task started. Specification/backlog unchanged. Counts: DONE 3, TODO 18, IN_PROGRESS 0, BLOCKED 1. Current task 004 BLOCKED; next eligible TODO 005 (not started).
- Final checks: `git diff --check` — exit 0; Python ledger assertions — 22 rows, DONE 3/TODO 18/BLOCKED 1 and no active task, passed. `docker ps --filter name=intruvia-verify --format '{{.Names}}'` — exit 0, no test containers remain. `git status --short` confirms only the ledger and two new evidence logs changed.

### 2026-09-29 — task 004 local-artifact recovery and completion

- Agent: Codex. Transitions: BLOCKED -> IN_PROGRESS -> DONE. User explicitly requested resolving the Maven blocker using available local artifacts/source. Dependency 003 DONE; no other active task. Existing ledger edits and retry logs preserved.
- Cause: the requested `2.8.0-20260927.120822-2` agent/service JARs are absent, while locally installed `2.8.0-SNAPSHOT` JARs exist. Maven does not substitute these coordinates. The framework source also declares `2.8.0-SNAPSHOT`; no rebuild was needed.
- Recovery: used the existing `strolch.version` property override and offline Maven resolution. Default timestamped POM pin retained. README and task-runner instructions now document local inspection, explicit override and source-install recovery before declaring a dependency blocker.
- Exact acceptance command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH INTRUVIA_TEST_POSTGRES_IMAGE=postgres:18-trixie scripts/verify-postgresql.sh -o -Dstrolch.version=2.8.0-SNAPSHOT` — exit 0, all five reactor projects SUCCESS; 7 contract tests and 4 integration tests, zero failures/errors/skips.
- Initial sandbox execution failed before Maven because Docker socket access was denied. Reran with approved elevated execution; disposable PostgreSQL container was removed by the script on exit. Full output/image identity: [verification](verification/004-local-verify.txt).
- Acceptance evidence: PersistenceIT verifies event/receipt/stream state and migration across restart, injected PostgreSQL commit failure with no partial state both live and after restart, and rejection of transient/destructive/wrong-realm configuration. DatabaseFixture isolates tests in randomly named databases and drops them. ApplicationIT verifies packaged HTTP and lifecycle/shutdown against PostgreSQL.
- Actual packaged Strolch JAR identities: `sha256sum intruvia-app/target/intruvia/lib/strolch-*.jar` — exit 0; [checksums](verification/004-local-strolch.sha256). This verifies local snapshot behavior, not equivalence with the original timestamped baseline.
- Unrun: framework source rebuild/tests (installed artifacts sufficient), DB crash recovery, backup/restore and later ingestion/browser requirements. No application implementation changes needed; no commit, push or deployment. Specification/backlog requirements unchanged.
- Counts: DONE 4, TODO 18, IN_PROGRESS 0, BLOCKED 0. Next task: 005; not started.
