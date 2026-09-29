# Backlog task runner

`next-task.sh` resolves the workspace relative to its own location, verifies the
four required project documents, and sends a fixed prompt to `codex exec` via stdin.
Task selection is performed by the agent from the current Markdown ledger, not by
a separate parser. The prompt limits each invocation to one task and requires
dependency checks, acceptance evidence, and ledger maintenance.
For Maven resolution failures, the prompt requires checking locally installed Strolch
artifacts and the referenced framework source before declaring a blocker. README.md
documents the explicit snapshot override and source-install command. Verification must
record the version actually used rather than claiming timestamped-baseline equivalence.

The runner uses `workspace-write` and permits a workspace without Git through
`--skip-git-repo-check`. It inherits the configured Codex model and authentication.
Executable resolution uses an explicit `CODEX_BIN` first, then PATH, then the Linux
desktop bundle at `/usr/lib/chatgpt/resources/codex`. An invalid override fails.
It enables outbound network access inside the sandbox and grants write access to
`~/.m2/repository` for Maven downloads (override with an absolute `MAVEN_REPOSITORY`
path matching your Maven settings). It does not bypass filesystem sandboxing. The shell
propagates CLI failures using `pipefail`; task completion remains a ledger outcome,
not an interpretation of the process exit code.

An exclusive nonblocking `flock` on `.next-task.lock` lasts for the run and is
released when the processes exit. The empty lock file remains intentionally.
This coordinates script invocations only, not other editors or agent sessions.
`--dry-run` prints the workspace, resolved executable, and prompt without starting
Codex or taking a lock.

After an interrupted run, inspect the changes and ledger before invoking the
script again. It requests resumption of the single IN_PROGRESS task; BLOCKED tasks
can be retried explicitly with `--retry-blocked`. This selects the lowest-numbered
eligible BLOCKED task, rechecks its blocker, and resumes only if it is resolved.
The previous failure evidence is preserved. Combine with `--dry-run` to preview.
