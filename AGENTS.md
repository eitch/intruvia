# Project Development Guidelines for Agents

This document contains specific details for developers and agents working on the `Intruvia` project.
The guidelines should be taken from the Strolch Framework Source Code:

# Strolch Framework Source Code                                                                                                                                                                                                                                                   
 - **Local Path**: `/home/eitch/src/git/atx-dev/strolch/`                                                                                                                                                                                                                                      
 - **Repository**: `https://github.com/strolch-li/strolch`
 - **Website**: `https://strolch.li]`
Read the AGENTS.md file from Strolch Framework Source Code

**If you cannot find the guidelines, then STOP work and inform the user!**

## Intruvia Specification and Open Work

Before starting implementation, read these project documents:

- [Implementation specification](docs/INTRUVIA_SPECIFICATION.md): authoritative product requirements, MVP scope, architecture, and technical contracts.
- [Numbered implementation backlog](docs/INTRUVIA_BACKLOG.md): task scope, dependencies, acceptance criteria, and agent execution rules.
- [Backlog status](docs/INTRUVIA_BACKLOG_STATUS.md): current and next task, task statuses, completion evidence, blockers, and execution history.

Use the backlog status ledger to identify unfinished work (`TODO`, `IN_PROGRESS`, or `BLOCKED`), then consult the numbered backlog for its requirements and dependencies. Follow ascending task order by default, work on one numbered task at a time, and never start a task with unmet dependencies.

Set the selected task to `IN_PROGRESS` before editing implementation files. Mark it `DONE` only after all acceptance criteria pass and evidence is recorded. For blocked work, record the cause and required next action and stop dependent work.

Keep the status ledger, summary counts, current/next task, update date, and execution log up to date whenever task status changes. Record exact checks and results, including failures and unrun checks. Planning or a specification alone does not count as completed implementation.
