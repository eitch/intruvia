# Project Development Guidelines for Agents

This document contains specific details for developers and agents working on the `Intruvia` project.
The guidelines should be taken from the Strolch Framework Source Code:

# Strolch Framework Source Code

- **Local Path**: `/home/eitch/src/git/atx-dev/strolch/`
- **Repository**: `https://github.com/strolch-li/strolch`
- **Website**: `https://strolch.li]`
  Read the AGENTS.md file from Strolch Framework Source Code

**If you cannot find the guidelines, then STOP work and inform the user!**

## Prerequisites

- **Java**: JDK 25 or higher (modern Strolch projects target Java 24/25).
- **Maven**: Maven 3.6+ (e.g. Maven 3.10+).

Before building or testing, use Bash and verify both `java -version` and `mvn -version`. Maven must report a JDK 25 or
newer. Non-interactive shells may not load `~/.bashrc` or initialize SDKMAN automatically. If Java or Maven is missing
or
the versions are unsuitable, initialize SDKMAN when available:

```bash
source "${SDKMAN_DIR:-$HOME/.sdkman}/bin/sdkman-init.sh"
sdk list java
```

Select an installed JDK 25 or newer with `sdk use java <version>` (for example, `sdk use java 25.0.5.0+8-tem` if
installed). If needed, select an installed Maven version with `sdk use maven <version>`. Verify the versions again
before running Maven. SDKMAN selections apply only to the current shell, so initialize and select the versions in the
same shell as each build or test command. If the required tools are unavailable, report the environment issue rather
than assuming the build or tests cannot be run without checking SDKMAN.

## Project Ownership and License

Intruvia is the project owner's personal code. Company copyright-header requirements
in the shared guidelines do not apply to Intruvia. Do not add company attribution
or invent a copyright holder name.
The project is licensed under `AGPL-3.0-only`; see [LICENSE](LICENSE). Preserve
existing third-party copyright and license notices when incorporating external code.

## Intruvia Specification and Open Work

Before starting implementation, read these project documents:

- [Implementation specification](docs/INTRUVIA_SPECIFICATION.md): authoritative product requirements, MVP scope,
  architecture, and technical contracts.
- [Numbered implementation backlog](docs/INTRUVIA_BACKLOG.md): task scope, dependencies, acceptance criteria, and agent
  execution rules.
- [Backlog status](docs/INTRUVIA_BACKLOG_STATUS.md): current and next task, task statuses, completion evidence,
  blockers, and execution history.

Use the backlog status ledger to identify unfinished work (`TODO`, `IN_PROGRESS`, or `BLOCKED`), then consult the
numbered backlog for its requirements and dependencies. Follow ascending task order by default, work on one numbered
task at a time, and never start a task with unmet dependencies.

Set the selected task to `IN_PROGRESS` before editing implementation files. Mark it `DONE` only after all acceptance
criteria pass and evidence is recorded. For blocked work, record the cause and required next action and stop dependent
work.

Keep the status ledger, summary counts, current/next task, update date, and execution log up to date whenever task
status changes. Record exact checks and results, including failures and unrun checks. Planning or a specification alone
does not count as completed implementation.
