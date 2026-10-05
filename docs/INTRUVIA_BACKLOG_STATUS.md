# Intruvia — backlog status

Updated: 2026-10-05 · Implementation state: tasks 001–009 complete; task 010 next

Links: [specification](INTRUVIA_SPECIFICATION.md) · [numbered backlog](INTRUVIA_BACKLOG.md)

## Execution state

- Current task: **009 — Implement atomic ingestion and deduplication (DONE)**.
- Next task: **010 — Expose the authenticated ingestion endpoint (TODO)**; dependencies 005 and 009 are DONE.
- Completed: **9 / 22**. TODO: **13**. IN_PROGRESS: **0**. BLOCKED: **0**.
- Strolch PAT integration replaces the custom verifier. Revised task 005 acceptance passed against the fixed local framework snapshot; historical custom-token results remain superseded. Task 006 validation is complete; task 007 enrichment is complete; task 008 replacement is complete; task 009 atomic ingestion is complete; tasks 010–022 remain TODO.
- Foundational decisions specified: MVP boundary; Strolch/Java/JAX-RS/embedded Jetty/Vanilla JS stack; module boundaries; generic model; local MaxMind enrichment; authenticated ingestion/viewing; durable replay; map highlighting. These are design inputs, not completed implementation tasks.
- Exact dependency versions and integration conventions are verified by task 001; the task 002 application skeleton and task 003 model contracts are verified; task 004 persistence is verified with the local snapshot override; the ingestion HTTP endpoint, viewer and deployment remain pending.

## Status conventions

`TODO` = not started; `IN_PROGRESS` = the single active task; `BLOCKED` = cannot proceed, with a recorded reason and next action; `DONE` = acceptance criteria passed and evidence recorded. At most one task may be IN_PROGRESS. Change the summary counts, current/next task and update date whenever status changes.

## Task ledger

| Task | Title | Dependencies | Status | Evidence / blocker |
|---|---|---|---|---|
| 001 | Verify and pin the Strolch runtime baseline | — | DONE | [Decision](architecture/001-runtime-baseline.md), [probe](../probes/runtime-baseline/pom.xml), [verification](verification/001-resume-verification.txt), [checksums](verification/001-dependencies.sha256). Clean verify: 2 tests passed; REST/WebSocket/lifecycle and Jakarta namespace verified. |
| 002 | Create the Maven modules and application skeleton | 001 | DONE | [Architecture](architecture/002-application-skeleton.md), [clean verify](verification/002-clean-verify.txt), [process smoke](verification/002-application-smoke.txt). Four modules; 2 integration tests passed; packaged page/health and Strolch/Jetty shutdown verified. |
| 003 | Define event DTOs and Strolch model mapping | 002 | DONE | [Mapping](architecture/003-event-model.md), [schema](api/v1.schema.json), [fixture](api/event-v1.json), [clean verify](verification/003-clean-verify.txt). 7 contract tests and 2 existing integration tests passed. |
| 004 | Configure durable Strolch persistence | 003 | DONE | [Local snapshot verification](verification/004-local-verify.txt), [binary checksums](verification/004-local-strolch.sha256), [design](architecture/004-persistence.md). 7 contract tests and 4 PostgreSQL integration tests passed; restart, rollback, migration, isolation and configuration guards verified. |
| 005 | Integrate Strolch PAT machine authentication | 002 | DONE | [Design/provisioning](architecture/005-machine-authentication.md), [real-PAT and full regression](verification/005-resume-acceptance.txt), [lifecycle probe](verification/005-resume-pat-probe.txt), [source review](verification/005-resume-source-review.txt), [hashes](verification/005-resume-strolch.sha256). 9 unit/HTTP + 4 integration + 2 probe tests pass; historical race resolved upstream. |
| 006 | Implement Fail2ban validation and normalization | 003 | DONE | [Validation table/design](architecture/006-fail2ban-validation.md), [full verify](verification/006-clean-verify.txt), [framework hashes](verification/006-strolch.sha256). Strict bounded parsing, canonical IP/time/digest, trusted envelope mapping and separate age policy; 15 unit/HTTP + 4 PostgreSQL integration tests pass. |
| 007 | Implement local GeoIP enrichment | 003 | DONE | [Design/fixture provenance](architecture/007-geoip-enrichment.md), [full acceptance](verification/007-acceptance.txt), [review](verification/007-review.txt), [artifact hashes](verification/007-artifacts.sha256). Real MaxMind reader, lifecycle/configuration, address policy and safe result mapping; 24 unit/HTTP + 4 PostgreSQL/application tests passed. |
| 008 | Add safe GeoIP database replacement | 007 | DONE | [Design/update procedure](architecture/008-geoip-replacement.md), [full verification](verification/008-final-review-acceptance.txt), [review](verification/008-review.txt), [hashes](verification/008-artifacts.sha256). Validated staged atomic replacement, concurrent lookup safety, diagnostics and external update example; 30 unit/HTTP + 4 integration tests passed. |
| 009 | Implement atomic ingestion and deduplication | 004, 006, 007 | DONE | [Design](architecture/009-atomic-ingestion.md), [full verification](verification/009-final-verify.txt), [review](verification/009-review.txt), [hashes](verification/009-artifacts.sha256). Serialized Strolch service/command with receipt recheck and atomic sequence; 30 unit/HTTP + 10 integration tests passed. |
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

### 2026-09-29 — task 005 started

- Agent: Codex. TODO → IN_PROGRESS. Verified 22 ledger rows, no active task, dependency 002 DONE; selected lowest eligible TODO 005. Initial working tree clean. Read all required guidelines (Vanilla JavaScript in CODE_STYLE.md), specification, backlog and ledger. Scope limited to machine authentication; no other task started.
- Counts: DONE 4, TODO 17, IN_PROGRESS 1, BLOCKED 0. Next task 006.

### 2026-09-29 — task 005 completed

- Agent: Codex. IN_PROGRESS → DONE. Implemented exactly task 005; no commit, push, deployment or subsequent task started. Specification/backlog unchanged.
- Changes: immutable protected TSV verifier loader, stable machine principals and ingestion privilege, name-bound JAX-RS authentication filter, API servlet wiring, rotation/revocation and failure tests. README documents provisioning and restart-based rotation. Missing file disables credentials; invalid existing file fails startup. No active token or verifier bundled.
- Sources: `intruvia-core/src/main/java/li/intruvia/core/auth/MachineCredentials.java`, `intruvia-rest/src/main/java/li/intruvia/rest/auth/`, `intruvia-app/src/test/java/li/intruvia/app/MachineAuthenticationTest.java`; [design](architecture/005-machine-authentication.md), [application hashes](verification/005-artifacts.sha256).
- Command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH mvn -B -o -Dstrolch.version=2.8.0-SNAPSHOT clean test` — exit 0, 9 tests passed; [output](verification/005-unit-http.txt).
- Full acceptance/regression command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH INTRUVIA_TEST_POSTGRES_IMAGE=postgres:18-trixie scripts/verify-postgresql.sh -o -Dstrolch.version=2.8.0-SNAPSHOT` — exit 0 twice, before and after import cleanup. 9 unit/HTTP tests plus 4 PostgreSQL/application integration tests; zero failures/errors/skips, all five reactor projects SUCCESS. [First full output](verification/005-clean-verify.txt), [final output](verification/005-final-verify.txt). Disposable database cleanup performed by script.
- Authentication evidence: missing/invalid/malformed/duplicate credentials return 401 with challenge; valid credential without ingestion privilege returns 403; both rotation tokens resolve server-a despite spoofed query identity; removed token fails after reload. Principal has no event:read role. Unsafe file permissions, malformed rows, unknown privileges and duplicate verifiers fail. Error bodies do not echo credentials. Code review confirms no authentication logging and all fixed-size SHA-256 verifiers compared with MessageDigest.isEqual without early match exit; no wall-clock constant-time claim.
- Actual framework version: locally installed `2.8.0-SNAPSHOT`, also declared by `/home/eitch/src/git/atx-dev/strolch/pom.xml`; local agent JAR inspected. Default timestamped pin unchanged. `sha256sum intruvia-app/target/intruvia/lib/strolch-*.jar` — exit 0, [hashes](verification/005-local-strolch.sha256). These results do not establish timestamped-baseline equivalence. Framework rebuild unnecessary.
- Inspection failure: `rg` unavailable (exit 127); used grep/Python/find. No failed tests. `git diff --check` and new Java line-length inspection passed. Unrun/out of scope: real ingestion endpoint (010), viewer sessions (011), browser checks, TLS proxy rehearsal and production credential provisioning. No new dependencies/toolchains.
- Counts: DONE 5, TODO 17, IN_PROGRESS 0, BLOCKED 0. Next eligible task **006**, dependency 003 DONE; remains TODO.

### 2026-09-29 — task 005 reopened for Strolch PAT infrastructure

- Agent: Codex. Transition: 005 DONE → TODO, explicitly requested by the user. Documentation/planning change only; no implementation task started. Existing staged and unstaged implementation changes and all historical evidence preserved.
- Specification/backlog advanced to v1.1: Strolch owns PAT generation, verification, storage and lifecycle; dedicated technical users map to stable server identities; scoped certificates authorize ingestion; endpoint enforcement separates PATs and viewer sessions. Remove the custom verifier/TSV mechanism during revised task 005, verify actual framework APIs/artifacts and record any framework security gap instead of introducing custom cryptography.
- Updated task 005 replacement scope and acceptance criteria, plus downstream contracts in 010, 011, 014, 018 and 021. These downstream tasks were already TODO and remain so. Tasks 001–004 remain DONE; their acceptance evidence is unaffected. Tasks depending on 005 cannot start until its revised acceptance criteria pass.
- Artifacts: [specification](INTRUVIA_SPECIFICATION.md), [backlog](INTRUVIA_BACKLOG.md), this ledger, superseded notice in [prior design](architecture/005-machine-authentication.md) and README. Prior task 005 logs/hashes remain historical evidence for the custom implementation only, not evidence of PAT integration.
- Checks: `python3 /tmp/intruvia-pat-docs.py` — exit 0; guarded documentation replacements succeeded. `git diff --check` — exit 0. Inline `python3` ledger/version assertions — exit 0: 22 task rows; DONE 4, TODO 18, IN_PROGRESS 0, BLOCKED 0; 005 TODO with dependency 002 DONE; specification and backlog both v1.1. Manual review confirmed existing implementation files were not edited by this revision.
- Unrun: Maven, runtime, PAT authentication/lifecycle, PostgreSQL and browser tests; documentation-only revision, all revised task 005 implementation checks remain pending. No failed checks or blocker. No commit, push or deployment.
- Next execution: select **005**, set it IN_PROGRESS, inspect Strolch PAT infrastructure and replace the superseded implementation according to the revised backlog; do not skip to 006 based on old completion evidence.

### 2026-09-29 — task 005 PAT integration started

- Agent: Codex. TODO → IN_PROGRESS. Verified all 22 rows: no active task; 005 is lowest eligible TODO, dependency 002 DONE. Summary agrees with ledger.
- Read both AGENTS.md files, all referenced framework guidelines including Vanilla JavaScript in CODE_STYLE.md and STROLCH_SPECIFICATION.md, and all three project planning documents. `rg` unavailable (exit 127); using find/grep/direct reads.
- Existing staged and unstaged task 005 implementation and documentation changes preserved. Scope limited to revised 005; no commit, push, deployment or other task.
- Counts: DONE 4, TODO 17, IN_PROGRESS 1, BLOCKED 0. Next task 006 remains TODO.

### 2026-09-29 — task 005 blocked by reproduced framework PAT revocation race

- Agent: Codex. IN_PROGRESS → BLOCKED. Exactly one task attempted; no other task started. Counts: DONE 4, TODO 17, IN_PROGRESS 0, BLOCKED 1. Next eligible TODO: **006**, dependency 003 DONE. Tasks 010/011 still await 005.
- Changes: isolated real-framework PAT security probe, source/binary audit, checksums, blocker report and documentation/status updates. Existing staged/unstaged implementation and planning changes preserved; no application implementation, POM, upstream source, commit, push or deployment changes in this attempt.
- Artifacts: [probe POM](../probes/pat-security/pom.xml), [test](../probes/pat-security/src/test/java/li/intruvia/probe/PatRevocationTest.java), [blocker and API/source review](architecture/005-pat-security-blocker.md). Framework clean HEAD `888f0880321ca9bba4274765d55cf85c4e07a96a`.
- Exact probe command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH mvn -B -o -f probes/pat-security/pom.xml -Dstrolch.version=2.8.0-SNAPSHOT clean test` — exit 1; 2 tests, 1 failure, 0 errors/skips. Sequential cached revocation and scoped API certificate passed; concurrent authentication restored a revoked token, and a subsequent fresh authentication was wrongly accepted. [Output](verification/005-pat-revocation-probe.txt). Initial same-command run had 2 fixture errors for missing user names; corrected before the meaningful run, [initial failure preserved](verification/005-pat-probe-fixture-failure.txt).
- Exact resolution command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH mvn -B -o -f probes/pat-security/pom.xml -Dstrolch.version=2.8.0-SNAPSHOT dependency:tree` — exit 0, [tree](verification/005-pat-dependencies.txt). Local repository and framework source inspected; installed artifacts suffice. Actual version is `2.8.0-SNAPSHOT`, not the historical timestamped baseline. Existing workspace POM already has this version; preserved.
- Binary inspection: `/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin/javap -c -p -classpath /home/eitch/.m2/repository/li/strolch/strolch-privilege/2.8.0-SNAPSHOT/strolch-privilege-2.8.0-SNAPSHOT.jar li.strolch.privilege.handler.DefaultPrivilegeHandler` — exit 0; [PAT method bytecode](verification/005-pat-authentication-bytecode.txt). Python zipfile/hashlib comparison verified four relevant installed classes equal framework target classes and recorded source SHA-256 values in [review](verification/005-pat-binary-source-review.txt).
- Integrity: `sha256sum -c docs/verification/005-pat-strolch.sha256` — exit 0, privilege/utils JARs OK. No relabeling or source rebuild; rebuilding unchanged source cannot fix the reproduced lifecycle defect. This is not an artifact-resolution blocker.
- Blocker/required action: upstream framework lifecycle fix and rebuilt artifact, then explicitly resume 005. Framework source `/home/eitch/src/git/atx-dev/strolch/` is outside writable roots; approval unavailable. No supported existing PAT configuration lock found; an HTTP-only lock cannot coordinate framework revocation. No replacement verifier or private lifecycle added.
- Unrun: revised real-PAT HTTP suite, identity/rotation/expiry/disabled-owner cases, full Maven/PostgreSQL regression, production provisioning and browser checks. Existing custom-token code was not replaced and task 005 remains incomplete. Specification/backlog requirements unchanged.
- Inspection failures: `rg` unavailable; one guessed CRUD source filename and one guessed test configuration path did not exist, then actual paths located. Required guidelines all available.
- Final checks: `git diff --check` — exit 0; Python ledger assertions — 22 rows, DONE 4/TODO 17/BLOCKED 1/IN_PROGRESS 0, next eligible 006; new probe Java lines below 160 characters, passed.

### 2026-09-29 — task 005 authorized retry after upstream fix

- Agent: Codex. BLOCKED → IN_PROGRESS. Only eligible blocked task 005, dependency 002 DONE; no active task. Required guidelines including Vanilla JavaScript and all planning documents read. Existing staged/unstaged changes preserved.
- Framework clean HEAD now `b5e5ef3fe19f538d73a94a6c39aa9fc1039b39ac` adds PAT locking and atomic last-used updates; installed snapshot hashes changed. Historical checksum verification failed as expected for rebuilt artifacts.
- Original probe rerun: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH mvn -B -o -f probes/pat-security/pom.xml -Dstrolch.version=2.8.0-SNAPSHOT clean test` — exit 1, 2 tests, 0 failures, 1 lock-timeout error. [Output](verification/005-pat-retry-probe.txt). Its scheduling assumes revocation completes before releasing authentication; adapting it for serialization before integration.
- Counts: DONE 4, TODO 17, IN_PROGRESS 1, BLOCKED 0. Next task 006 remains TODO.

### 2026-10-01 — task 005 resumed

- Agent: Codex. IN_PROGRESS → IN_PROGRESS. Verified all 22 ledger rows: 005 is the only active task, dependency 002 DONE; resume without starting 006. Counts remain DONE 4, TODO 17, IN_PROGRESS 1, BLOCKED 0.
- Read project/framework AGENTS.md, all seven referenced guidelines including Vanilla JavaScript in CODE_STYLE.md, specification, backlog and ledger. Existing staged/unstaged implementation and evidence preserved. `rg` unavailable; used direct reads/find/grep.
- Reviewing the existing partial PAT implementation against current local framework and rerunning acceptance checks. No commit, push or deployment authorized or performed.

### 2026-10-01 — task 005 PAT integration completed

- Agent: Codex. IN_PROGRESS → DONE. Resumed exactly the single active task; dependency 002 DONE. Counts: DONE 5, TODO 17, IN_PROGRESS 0, BLOCKED 0. Next eligible task **006**, dependency 003 DONE; not started.
- Completed existing partial replacement: Strolch PAT verification/certificate wiring, protected non-secret technical-user mapping, narrow ingestion authorization and real-PAT HTTP tests. Fixed two existing test fixture defects and added wrong-secret rejection after caching. Updated README and architecture/provisioning instructions; historical blocker report marked resolved without erasing its evidence. Existing unrelated/staged changes preserved; no commit, push, deployment, new dependency/toolchain or upstream modification.
- Sources: `intruvia-core/src/main/java/li/intruvia/core/auth/MachineIdentities.java`, `intruvia-rest/src/main/java/li/intruvia/rest/auth/`, `intruvia-rest/src/main/java/li/intruvia/rest/IntruviaRestApplication.java`, application wiring and `intruvia-app/src/test/java/li/intruvia/app/MachineAuthenticationTest.java`; [design](architecture/005-machine-authentication.md). Custom `MachineCredentials.java` remains deleted; no production custom verifier references remain.
- Framework inspection: clean source HEAD `4d2a4b8d8db4fbd2c254910431e8284edc15c7d8`; installed version `2.8.0-SNAPSHOT`. Read actual authentication, issuance, subsetting, revocation, persistence and upstream PAT test sources. Python zipfile/hashlib comparison passed for four installed handler classes against framework target classes; [source/class identities](verification/005-resume-source-review.txt). Installed dependencies suffice offline; no rebuild needed, no artifacts relabeled as the timestamped baseline. Existing POM version preserved.
- Exact probe command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH mvn -B -o -f probes/pat-security/pom.xml -Dstrolch.version=2.8.0-SNAPSHOT clean test` — exit 0, 2 tests, zero failures/errors/skips; [output](verification/005-resume-pat-probe.txt). Adapted scheduling accommodates upstream locking; new authentication after completed revocation fails.
- Exact full acceptance command (three attempts): `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH INTRUVIA_TEST_POSTGRES_IMAGE=postgres:18-trixie scripts/verify-postgresql.sh -o -Dstrolch.version=2.8.0-SNAPSHOT`.
  - Attempt 1 exit 1: 7 contract tests passed; HTTP suite 2 tests/1 failure because the leak assertion searched for malformed input `:` in JSON punctuation. [Output](verification/005-resume-full-verify.txt). Corrected by checking exact safe error fields/messages and checking realistic secret strings separately.
  - Attempt 2 exit 1: HTTP suite 2 tests/1 error; disabled-owner fixture used DefaultPrivilege for a tuple-valued user-state operation. [Output](verification/005-resume-final-verify.txt). Corrected to framework UserAccessPrivilege.
  - Final exit 0: all five reactor projects SUCCESS; 9 unit/HTTP and 4 PostgreSQL/application integration tests, zero failures/errors/skips. [Output and PostgreSQL image identity](verification/005-resume-acceptance.txt). Verifies persisted model restart/rollback and packaged application lifecycle as regression coverage.
- Acceptance: real issued PATs return API certificates; missing/malformed/invalid/expired/future/revoked tokens and disabled owners yield 401; missing scope/mapping and broad certificates yield 403; duplicate headers, session Bearer/cookie credentials rejected; two mapped users have distinct identities despite spoofed query; rotation retains identity; cached revocation/expiry rejected without reload. Framework certificate privilege set is exactly ingestion-only. No application secret logging; fixed HTTP errors do not echo credentials. Viewer route/WebSocket isolation remains explicitly in 011/014.
- Integrity commands: `sha256sum intruvia-app/target/intruvia/lib/strolch-*.jar > docs/verification/005-resume-strolch.sha256`; `sha256sum intruvia-app/target/intruvia/intruvia-app-0.0.1.jar intruvia-app/target/intruvia/lib/intruvia-*.jar > docs/verification/005-resume-artifacts.sha256`; both `sha256sum -c` commands exit 0, all files OK. [Application hashes](verification/005-resume-artifacts.sha256).
- Final checks: `git diff --check` exit 0; Python changed-Java line-length and custom-verifier-removal assertions pass. Script cleanup ran for this invocation's disposable databases/containers. A separately existing container `intruvia-verify-3-10496` was left untouched. Initial guessed resource path was absent; actual `src/main/runtime/config/PrivilegeConfig.xml.example` located and read. `rg` unavailable; fallback reads used.
- Unrun/out of scope: upstream framework test suite/rebuild, production user/token provisioning, TLS proxy rehearsal, browser/viewer routes and actual ingestion endpoint. No constant-time cryptographic audit claimed. Specification/backlog requirements unchanged; all revised 005 acceptance criteria pass using local snapshot artifacts.

### 2026-10-04 — task 006 started

- Agent: Codex. TODO → IN_PROGRESS. Verified all 22 ledger rows: no active task; lowest eligible TODO 006, dependency 003 DONE. Summary agrees.
- Read both AGENTS.md files, all referenced framework guidelines including Vanilla JavaScript in CODE_STYLE.md and STROLCH_SPECIFICATION.md, specification, backlog and ledger. `rg` unavailable (exit 127); using find/direct reads.
- Existing staged/deleted `intruvia-core/src/main/java/li/intruvia/core/auth/MachineCredentials.java` preserved. Scope is validation, canonical payload digest and envelope mapping only; no endpoint, persistence or GeoIP implementation.
- Counts: DONE 5, TODO 16, IN_PROGRESS 1, BLOCKED 0. Next task 007 remains TODO. No commit, push or deployment.

### 2026-10-04 — task 006 completed

- Agent: Codex. IN_PROGRESS → DONE. Executed exactly task 006; dependency 003 DONE. Counts: DONE 6, TODO 16, IN_PROGRESS 0, BLOCKED 0. Next eligible task **007**, dependency 003 DONE; remains TODO.
- Changes: strict streaming JSON shape/type validation with bounded UTF-8 input and safe errors; literal-only IPv4/IPv6 normalization (mapped IPv6 becomes IPv4); UUID/time normalization; stable versioned SHA-256 payload digest; trusted generic envelope mapping; separately callable configurable timestamp-age policy. Jail length now counts Unicode code points consistently with the schema. No persistence/ingestion endpoint or GeoIP implementation added.
- Sources: `intruvia-core/src/main/java/li/intruvia/core/ingest/`, `intruvia-rest/src/main/java/li/intruvia/rest/ingest/`, `intruvia-rest/src/test/java/li/intruvia/rest/ingest/Fail2banValidationTest.java`, small model/DTO updates. [Validation table and design](architecture/006-fail2ban-validation.md), README usage, [application hashes](verification/006-artifacts.sha256). Existing staged/deleted MachineCredentials.java state preserved. No commit, push, deployment, dependency or toolchain changes. Specification/backlog requirements unchanged.
- Initial command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH mvn -B -o -Dstrolch.version=2.8.0-SNAPSHOT -pl intruvia-rest -am clean test` — exit 1; 13 tests, one failure. [Output](verification/006-unit-tests.txt). Invalid-surrogate fixture was reserialized and UTF-8 encoded as `?`, so it no longer contained the invalid input. Corrected the test to send the raw JSON escape directly; production code unchanged by that correction.
- Full acceptance/regression command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH INTRUVIA_TEST_POSTGRES_IMAGE=postgres:18-trixie scripts/verify-postgresql.sh -o -Dstrolch.version=2.8.0-SNAPSHOT` — exit 0; all five reactor projects SUCCESS, 15 unit/HTTP tests and 4 PostgreSQL/application integration tests, zero failures/errors/skips. [Output and database image identity](verification/006-clean-verify.txt). Script cleaned up its disposable container/databases.
- Acceptance coverage: strict/duplicate/unknown fields and types, exact byte bound/one-byte overflow/endless stream bound, malformed UTF-8 and JSON, UUID/jail/failures boundaries, invalid surrogate, IPv4/IPv6 canonical forms and invalid names/zones/CIDR, timezone normalization, exact age/skew bounds and one-nanosecond violations. Parsing old accepted payloads/digest computation is independent of age rejection. Equivalent ordering/escaping/number/UUID/IP/time forms produce equal digests; each changed producer field differs; missing/null failures agree and zero differs. Envelope identity comes only from the separate authenticated mapping argument. No name-service API or raw-payload logging in validation code.
- Framework/artifact verification: actual installed and packaged version `2.8.0-SNAPSHOT`; offline Maven succeeded. Python XML/hashlib inspection verified framework source version and each packaged Strolch JAR equals its local Maven-cache JAR; [review](verification/006-review.txt). No rebuild needed; no artifact relabeled or claim of timestamped-baseline equivalence. `sha256sum intruvia-app/target/intruvia/lib/strolch-*.jar > docs/verification/006-strolch.sha256` and `sha256sum intruvia-app/target/intruvia/intruvia-app-0.0.1.jar intruvia-app/target/intruvia/lib/intruvia-*.jar > docs/verification/006-artifacts.sha256` succeeded; both corresponding `sha256sum -c` commands exited 0, all entries OK.
- Minor inspection failure: checksum commands first ran before the in-flight clean build assembled JARs, failing with missing files/empty checksum list (exit 1). Reran successfully after build completion; final checksum files contain verified artifacts. `rg` unavailable; direct reads/find used.
- Final checks: `git diff --check` exit 0; Python Java length/tab review passed; ledger assertions verify 22 rows, DONE 6/TODO 16/IN_PROGRESS 0/BLOCKED 0 and next eligible 007. Unrun/out of scope: actual ingestion HTTP status/media/rate wiring (010), deduplication persistence (009), GeoIP (007), runtime setting wiring (019), browser checks, framework rebuild/tests. No remaining task 006 blocker.

### 2026-10-04 — task 007 started

- Agent: Codex. TODO → IN_PROGRESS. Verified 22 rows, no active task; lowest eligible TODO 007, dependency 003 DONE. Summary agrees. Counts: DONE 6, TODO 15, IN_PROGRESS 1, BLOCKED 0. Next 008 awaits 007.
- Read both AGENTS.md files, all seven framework guideline files including Vanilla JavaScript, and specification/backlog/status. Required guidelines available; `rg` unavailable (exit 127), using find/grep/direct reads.
- Preserve existing staged/deleted MachineCredentials.java. Scope limited to lifecycle-managed local MaxMind enrichment and fixtures; no task 008 replacement work, commit, push or deployment.

### 2026-10-04 — task 007 completed

- Agent: Codex. IN_PROGRESS → DONE. Executed exactly task 007; dependency 003 DONE. Counts: DONE 7, TODO 15, IN_PROGRESS 0, BLOCKED 0. Next eligible **008**, dependency 007 DONE; remains TODO. No commit, push, deployment or another task started.
- Changes: Strolch-managed reusable local MaxMind City reader; absolute external path configuration and degraded startup; documented conservative IPv4/IPv6 geographic-unicast classification; immutable result/provenance mapping; safe read/decode failure statuses and bounded fixed warnings; reader closure under lifecycle lock. Added original synthetic MMDB test encoder (generated files only under target), fixture/policy tests and application lifecycle assertions; README and [architecture/provenance](architecture/007-geoip-enrichment.md). Existing staged/deleted MachineCredentials.java state preserved. Specification/backlog requirements unchanged.
- Sources: `intruvia-core/src/main/java/li/intruvia/core/geo/`, `intruvia-core/src/test/java/li/intruvia/core/geo/`, root/core POMs, application StrolchConfiguration.xml and ApplicationIT.java. Only specification-required dependency added: `com.maxmind.geoip2:geoip2:5.2.0`, with reader `com.maxmind.db:maxmind-db:4.1.0`; no new toolchain or MMDB/credentials bundled. Actual APIs checked against versioned MaxMind source, Java docs and `javap`; IANA policy references and deliberate exclusions documented.
- Dependency command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH mvn -B -Dstrolch.version=2.8.0-SNAPSHOT -pl intruvia-core -am dependency:resolve` — exit 0; [output](verification/007-dependency-resolution.txt). New MaxMind dependencies downloaded through configured Maven repository. Subsequent verification succeeded offline.
- Focused command, two attempts: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH mvn -B -o -Dstrolch.version=2.8.0-SNAPSHOT -pl intruvia-core -am test`.
  - Initial exit 1: 12 tests, 6 fixture errors because JUnit's relative target directory produced relative DB paths. Corrected test root to absolute; [output](verification/007-initial-tests.txt).
  - Second exit 1: 14 tests, 1 error. Wrong-type synthetic record revealed MaxMind's unchecked ClassCastException; added narrow boundary handling so it returns ERROR. [Output](verification/007-fixture-tests.txt).
- Full acceptance command, three attempts: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH INTRUVIA_TEST_POSTGRES_IMAGE=postgres:18-trixie scripts/verify-postgresql.sh -o -Dstrolch.version=2.8.0-SNAPSHOT`.
  - First exit 1: 14 core tests, 1 error. Invalid radius record exposed unchecked MaxMind DeserializationException; confirmed its JAR hierarchy and added handling at load/lookup boundaries. [Output](verification/007-clean-verify.txt).
  - Second exit 1: all 24 unit/HTTP tests passed; 4 integration tests, 1 test error. Newly added shutdown assertion attempted container lookup after agent destruction cleared the container. Retained the component reference before close; no lifecycle implementation defect observed. [Output](verification/007-final-verify.txt).
  - Final exit 0: all five reactor projects SUCCESS; 24 unit/HTTP tests (including 9 new GeoIP/policy tests) and 4 PostgreSQL/application integration tests, zero failures/errors/skips. [Acceptance output and PostgreSQL image identity](verification/007-acceptance.txt). Script cleaned up its disposable databases/containers.
- Acceptance coverage: actual reader decodes synthetic IPv4/IPv6/City and GeoLite2 metadata; missing/partial records, complete zero coordinates, mapped IPv4, non-public records bypassed, invalid record shape/values, corrupt/missing/wrong-edition DB, missing configuration, safe ERROR/UNAVAILABLE results. Tests verify same reader survives file removal, actual close rejects further direct reader use, restart opens another reader, registered Strolch component starts degraded and is destroyed. Public-range boundary/adjacent tests pass. No external lookup, production dataset accuracy or ingestion endpoint claimed.
- Framework review: source `/home/eitch/src/git/atx-dev/strolch/` HEAD `e34b772f9d58f053a0272457708658d0dceadf76`, version `2.8.0-SNAPSHOT`. Local Maven repository inspected; installed artifacts suffice. `python3 docs/verification/007-review.py > docs/verification/007-review.txt` — exit 0: packaged/cache Strolch hashes match, all test reports pass, Java lines/tabs pass, source contains no MMDB, pre-existing staged/deleted file state preserved. No rebuild or relabeling; no timestamped-baseline equivalence claimed.
- Exact tree command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH mvn -B -o -Dstrolch.version=2.8.0-SNAPSHOT -pl intruvia-core -am dependency:tree` — exit 0; [tree](verification/007-dependency-tree.txt).
- Integrity commands: `sha256sum intruvia-app/target/intruvia/lib/strolch-*.jar > docs/verification/007-strolch.sha256`; `sha256sum intruvia-app/target/intruvia/intruvia-app-0.0.1.jar intruvia-app/target/intruvia/lib/intruvia-*.jar intruvia-app/target/intruvia/lib/geoip2-*.jar intruvia-app/target/intruvia/lib/maxmind-db-*.jar > docs/verification/007-artifacts.sha256` — exit 0. Both corresponding `sha256sum -c` commands exited 0, all entries OK.
- Inspection issues: `rg` unavailable; guessed framework Configuration.java path absent, actual ComponentConfiguration source read. An inspection accidentally printed Maven settings credentials into the tool transcript; no values copied into repository files. Owner informed that those credentials should be rotated. No credential changes made.
- Final checks: `git diff --check` exit 0; Python ledger assertions verify 22 rows, DONE 7/TODO 15/IN_PROGRESS 0/BLOCKED 0 and next eligible 008. Unrun/out of scope: production licensed-dataset accuracy, framework rebuild/tests, live DB replacement and concurrent replacement (008), ingestion endpoint/service (009/010), geoipupdate deployment, diagnostics/staleness and browser checks. No remaining task 007 blocker.

### 2026-10-05 — task 008 started

- Agent: Codex. TODO → IN_PROGRESS. Verified all 22 rows: no active task; lowest eligible TODO 008, dependency 007 DONE. Counts: DONE 7, TODO 14, IN_PROGRESS 1, BLOCKED 0. Next eligible 009; not started.
- Read project/framework guidelines including Vanilla JavaScript and planning documents. Required guidelines available. Preserve pre-existing staged/deleted MachineCredentials.java. No commit, push or deployment.

### 2026-10-05 — task 008 completed

- Agent: Codex. IN_PROGRESS → DONE. Executed exactly task 008; dependency 007 DONE. Counts: DONE 8, TODO 14, IN_PROGRESS 0, BLOCKED 0. Next eligible **009**, dependencies 004/006/007 DONE; not started.
- Changes: serialized staged-file claim, complete City record validation while lookups continue, atomic live-file switch and reader swap under the lookup lock, old-reader closure, lifecycle-owned polling and cancellation guard, immutable build/load/age/stale/update-failure diagnostics. Added six replacement/failure/concurrency/configuration tests using the original synthetic fixture generator. README, external credential-placeholder configuration and [operator/design instructions](architecture/008-geoip-replacement.md) updated; ignored staging/candidate artifacts. No specification/backlog contract changes, dependencies, toolchains, commit, push or deployment.
- Sources: `intruvia-core/src/main/java/li/intruvia/core/geo/GeoIpComponent.java`, `intruvia-core/src/test/java/li/intruvia/core/geo/GeoIpComponentTest.java`, and test-isolation correction in `intruvia-app/src/test/java/li/intruvia/app/DatabaseFixture.java`. Existing staged/deleted MachineCredentials.java and the runtime template's configured GeoIP path preserved.
- Focused command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH mvn -B -o -Dstrolch.version=2.8.0-SNAPSHOT -pl intruvia-core -am test`.
  - First exit 1 at compilation: MaxMind networks() declares checked InvalidNetworkException; added explicit handling. [Output](verification/008-unit-tests.txt). No tests ran in this attempt.
  - Retry exit 0: 18 core tests, zero failures/errors/skips. [Output](verification/008-unit-retry.txt). Two additional failure/locking tests were added before full verification.
- Full command, three runs: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH INTRUVIA_TEST_POSTGRES_IMAGE=postgres:18-trixie scripts/verify-postgresql.sh -o -Dstrolch.version=2.8.0-SNAPSHOT`.
  - First exit 1: 30 unit/HTTP tests passed; 4 integration tests, one failure. ApplicationIT expected UNAVAILABLE but inherited the runtime template's real local database and got FOUND. Fixed the isolated test fixture to use its own missing database path, preventing tests from reading/updating operator data. [Failure output](verification/008-acceptance.txt).
  - Second exit 0: all five reactor projects SUCCESS; 30 unit/HTTP + 4 integration tests, zero failures/errors/skips. [Output](verification/008-final-acceptance.txt).
  - Final exit 0 after adding the interrupted-poller guard/test: all five reactor projects SUCCESS; same 34 tests, zero failures/errors/skips. [Final acceptance and PostgreSQL image identity](verification/008-final-review-acceptance.txt). Each invocation's disposable container/databases cleaned up by the script.
- Acceptance coverage: 20,000 parallel lookups across ten replacements return coherent old/new results; deterministic held read lock delays swap and old-reader closure. Invalid headers/edition/tree/records/geography retain the working reader and live bytes; failed destination rename and symlinks are rejected; successful swap survives restart; missing database restarts degraded and recovers by staging. Polling, actual reader close, stop/destroy, interrupted-call non-consumption, exact 14-day stale boundary and invalid interval settings pass. Diagnostics preserve successful load/build metadata on rejection. No actual database or license key added.
- Framework/API inspection: local source HEAD `e34b772f9d58f053a0272457708658d0dceadf76`, actual version `2.8.0-SNAPSHOT`; local Maven repository inspected. Installed artifacts suffice offline; no rebuild/relabeling or timestamped-baseline equivalence claimed. Inspected MaxMind 5.2.0/reader 4.1.0 installed APIs with `javap` and Networks.java source; checked official external update instructions (linked in design).
- Review command: `python3 docs/verification/007-review.py > docs/verification/008-review.txt` initially exited 1 because its indentation assertion rejected a pre-existing Javadoc leading space; partial output retained as [initial review](verification/008-review-initial.txt). Adapted task-specific review permits Javadoc lines. `python3 docs/verification/008-review.py > docs/verification/008-review.txt` — exit 0 after final build: framework/cache/package hashes match, all test reports pass, Java length/tab review passes, no source MMDB, pre-existing user change preserved.
- Integrity commands: `sha256sum intruvia-app/target/intruvia/lib/strolch-*.jar > docs/verification/008-strolch.sha256`; `sha256sum intruvia-app/target/intruvia/intruvia-app-0.0.1.jar intruvia-app/target/intruvia/lib/intruvia-*.jar intruvia-app/target/intruvia/lib/geoip2-*.jar intruvia-app/target/intruvia/lib/maxmind-db-*.jar > docs/verification/008-artifacts.sha256`; both corresponding `sha256sum -c` commands exit 0, all entries OK after final build.
- Inspection issues: `rg` unavailable (127), used find/direct reads; guessed GeoIp.java/GeoIpTest.java paths absent, actual component/test filenames located and read. Required guidelines were available.
- Unrun/out of scope: production geoipupdate download/deployment, licensed-dataset accuracy or size/performance measurements, unsupported atomic-filesystem simulation, power-loss durability, framework rebuild/tests, protected HTTP diagnostics (019) and browser checks. Shutdown may wait for current validation; transient heap needs roughly three database sizes. These operational limits are documented; no remaining task 008 blocker.
- Final checks: `git diff --check` exit 0; Python ledger assertions verify 22 rows, DONE 8/TODO 14/IN_PROGRESS 0/BLOCKED 0, next eligible 009. No other task started.

### 2026-10-05 — task 009 started

- Agent: Codex. TODO → IN_PROGRESS. Verified 22 ledger rows, no active task, lowest eligible TODO 009; dependencies 004/006/007 DONE. Counts: DONE 8, TODO 13, IN_PROGRESS 1, BLOCKED 0. Next 010 awaits 009.
- Read both AGENTS.md files, all referenced framework guidelines including Vanilla JavaScript and Strolch specification, project specification/backlog/status and README. Preserve pre-existing staged/deleted MachineCredentials.java. No other task, commit, push or deployment.

### 2026-10-05 — task 009 completed

- Agent: Codex. IN_PROGRESS → DONE. Executed exactly task 009; dependencies 004/006/007 DONE. Counts: DONE 9, TODO 13, IN_PROGRESS 0, BLOCKED 0. Next eligible **010**, dependencies 005/009 DONE; remains TODO. No other task, commit, push or deployment.
- Changes: `IngestService`, `IngestCommand`, producer-only argument and typed result; certificate-derived protected identity, framework `event:ingest` authorization, stream-locator locking before receipt/state reads, unlocked GeoIP, locked receipt/age recheck, atomic event/receipt/state writes, checked sequence allocation, 30-day receipt expiry and safe failure outcomes. Registered framework ServiceHandler. Added six real-PostgreSQL integration tests and receipt-failure fixture; README and [design](architecture/009-atomic-ingestion.md). No dependencies/toolchains or specification/backlog requirements changed. Pre-existing staged/deleted MachineCredentials.java and operator GeoIP path preserved.
- Focused command: `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH mvn -B -o -Dstrolch.version=2.8.0-SNAPSHOT -pl intruvia-core -am test` — exit 0; 20 existing core tests, zero failures/errors/skips. [Output](verification/009-unit-tests.txt). New task acceptance is in the database tests below, not this compilation/regression check alone.
- Full command (four attempts): `JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH INTRUVIA_TEST_POSTGRES_IMAGE=postgres:18-trixie scripts/verify-postgresql.sh -o -Dstrolch.version=2.8.0-SNAPSHOT`.
  - Initial exit 1 at application test compilation: PAT fixture omitted the roles argument of `createPersonalAccessToken`; 28 core/REST tests passed, no app tests ran. [Output](verification/009-acceptance.txt).
  - Second exit 1: 30 unit/HTTP tests passed; 9 integration tests, 5 errors because the test fixture passed its scope in the roles slot. Verified the actual framework signature and corrected argument order. [Output](verification/009-acceptance-retry.txt).
  - Third exit 1: 30 unit/HTTP tests passed; 9 integration tests, 1 failure. Rollback returned a failed result, but the framework attached the database exception because `StrolchTransactionException` is not a `StrolchException`. Added explicit transaction/persistence exception handling; no accepted result is exposed. [Output](verification/009-full-verify.txt).
  - Final exit 0: all five reactor projects SUCCESS; 30 unit/HTTP tests and 10 PostgreSQL/application integration tests, zero failures/errors/skips. Added interrupted-admission and final age-recheck coverage before this run. [Final acceptance and PostgreSQL image identity](verification/009-final-verify.txt). Script removed each invocation's disposable container/databases.
- Acceptance evidence: 24 concurrent identical submissions produce one event/receipt and 23 duplicates with the same ID/sequence; 12 concurrent changed retries conflict without writes; 12 distinct concurrent submissions allocate contiguous sequences 2–13. Injected database receipt constraint failure rolls back all three models, verified immediately and after restart; retry then gets sequence 1. Simulated pruning removes the event and advances minAfter; retained receipt survives restart, returns original ID/sequence at day 8, and bypasses enrichment/age rejection. New old events fail; changed old retries conflict. A paused enrichment lets another request commit, then resolves that receipt on final recheck. Real ingestion-only PATs authenticate/authorize and produce distinct mapped instances; read-only scope and missing mapping fail. Explicit realm override, pre-interrupted admission and age advancing during enrichment produce no writes. GeoIP ERROR/UNAVAILABLE statuses still permit ingestion. No publication or HTTP ingestion behavior claimed.
- Framework inspection: actual local source/cache/package version `2.8.0-SNAPSHOT`, source HEAD `e34b772f9d58f053a0272457708658d0dceadf76`. Read `DefaultServiceHandler`, `AbstractService`, `Command`, `AbstractTransaction`, `PostgreSqlStrolchTransaction`, PAT signature and element-lock sources. The framework lock API returns with the interrupt flag set on interrupted acquisition; service guards before/after locking prevent writes in that case. Pre-interrupted admission tested; interruption during lock wait was not separately injected. Installed artifacts resolved offline; no rebuild/relabeling or timestamped-baseline equivalence claimed.
- Review command: `python3 docs/verification/009-review.py > docs/verification/009-review.txt` — exit 0: all packaged Strolch hashes equal local Maven cache, all test-report counts pass, Java length/tab review passes, no source MMDB, unrelated staged/deleted file preserved.
- Integrity commands: `sha256sum intruvia-app/target/intruvia/lib/strolch-*.jar > docs/verification/009-strolch.sha256`; `sha256sum intruvia-app/target/intruvia/intruvia-app-0.0.1.jar intruvia-app/target/intruvia/lib/intruvia-*.jar > docs/verification/009-artifacts.sha256`; both corresponding `sha256sum -c` commands exit 0, all entries OK. [Framework hashes](verification/009-strolch.sha256).
- Inspection failures: `rg` unavailable (127), used find/grep/Python. Guessed framework service/transaction and REST authentication-test paths absent; located actual agent-module sources and app-module test. Required guidelines available. Earlier failure logs retained; no failed result claimed as acceptance.
- Unrun/out of scope: framework rebuild/tests, live HTTP ingestion/rate limits (010), automatic receipt expiry/capacity/pruning (013), WebSocket publication (014), browser checks, deployment and power-loss tests. Present receipts deduplicate until task 013 removes expired receipts under the same lock. Duplicate core results intentionally omit geography because a receipt survives event pruning; task 010 must map the specified duplicate ID/sequence contract. No remaining task 009 blocker.
- Final checks: `git diff --check` exit 0; ledger assertions verify 22 rows, DONE 9/TODO 13/IN_PROGRESS 0/BLOCKED 0 and next eligible 010. No other task started.
