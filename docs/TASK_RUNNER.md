# Backlog task runner

`next-task.sh` resolves the workspace relative to its own location, verifies the
four required project documents, and sends a fixed prompt to `codex exec` via stdin.
Task selection is performed by the agent from the current Markdown ledger, not by
a separate parser. The prompt limits each invocation to one task and requires
dependency checks, acceptance evidence, and ledger maintenance.

The runner uses `workspace-write` and permits a workspace without Git through
`--skip-git-repo-check`. It inherits the configured Codex model and authentication.
Executable resolution uses an explicit `CODEX_BIN` first, then PATH, then the Linux
desktop bundle at `/usr/lib/chatgpt/resources/codex`. An invalid override fails.
It does not bypass sandboxing or grant extra writable directories. The shell
propagates CLI failures using `pipefail`; task completion remains a ledger outcome,
not an interpretation of the process exit code.

An exclusive nonblocking `flock` on `.next-task.lock` lasts for the run and is
released when the processes exit. The empty lock file remains intentionally.
This coordinates script invocations only, not other editors or agent sessions.
`--dry-run` prints the workspace, resolved executable, and prompt without starting
Codex or taking a lock.

After an interrupted run, inspect the changes and ledger before invoking the
script again. It requests resumption of the single IN_PROGRESS task; BLOCKED tasks
require the blocker to be resolved and their status deliberately updated before retry.
