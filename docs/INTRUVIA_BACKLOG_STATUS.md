# Intruvia — backlog status

Updated: 2026-09-28 · Implementation state: not started

Links: [specification](INTRUVIA_SPECIFICATION.md) · [numbered backlog](INTRUVIA_BACKLOG.md)

## Execution state

- Current task: none.
- Next task: **001 — Verify and pin the Strolch runtime baseline**.
- Completed: **0 / 22**. TODO: **22**. IN_PROGRESS: **0**. BLOCKED: **0**.
- Foundational decisions specified: MVP boundary; Strolch/Java/JAX-RS/embedded Jetty/Vanilla JS stack; module boundaries; generic model; local MaxMind enrichment; authenticated ingestion/viewing; durable replay; map highlighting. These are design inputs, not completed implementation tasks.
- Exact dependency versions and sibling-project integration conventions remain to be verified in task 001. No code, test, deployment or compatibility result is claimed by these documents.

## Status conventions

`TODO` = not started; `IN_PROGRESS` = the single active task; `BLOCKED` = cannot proceed, with a recorded reason and next action; `DONE` = acceptance criteria passed and evidence recorded. At most one task may be IN_PROGRESS. Change the summary counts, current/next task and update date whenever status changes.

## Task ledger

| Task | Title | Dependencies | Status | Evidence / blocker |
|---|---|---|---|---|
| 001 | Verify and pin the Strolch runtime baseline | — | TODO | — |
| 002 | Create the Maven modules and application skeleton | 001 | TODO | — |
| 003 | Define event DTOs and Strolch model mapping | 002 | TODO | — |
| 004 | Configure durable Strolch persistence | 003 | TODO | — |
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

No implementation execution recorded.

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
