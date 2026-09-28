# Intruvia

Intruvia is a planned self-hosted security-event viewer. See the
[specification](docs/INTRUVIA_SPECIFICATION.md), [backlog](docs/INTRUVIA_BACKLOG.md),
and [status ledger](docs/INTRUVIA_BACKLOG_STATUS.md) for scope and implementation progress.

## Run the next implementation task

The runner needs Bash, `flock`, and an authenticated Codex CLI. It finds Codex on
PATH or uses the desktop app's bundled `/usr/lib/chatgpt/resources/codex` executable.
To select another installation, set `CODEX_BIN=/full/path/to/codex`.
If authentication is needed, run the selected executable with `login` (for example,
`/usr/lib/chatgpt/resources/codex login`). From the project directory, run:

```bash
./next-task.sh --dry-run
./next-task.sh
```

You can also invoke the script by absolute path from any directory. Each invocation
starts a fresh Codex CLI session using your configured model and authentication.
It instructs Codex to resume an in-progress task or choose the first eligible TODO,
implement and verify it, and update the ledger. Run it again for the following task.
This does not continue the desktop conversation; project files provide its context.

The run can edit project files and execute checks in the workspace-write sandbox.
If permissions or prerequisites prevent completion, the agent should record the
blocker. Review its final report and the status ledger: a successful CLI exit alone
does not prove that the task is DONE. Avoid editing the same task concurrently in
another session. The script lock prevents overlapping invocations of this script.

See [runner details](docs/TASK_RUNNER.md) and the official
[Codex non-interactive documentation](https://developers.openai.com/codex/noninteractive).

## License

Intruvia is licensed under the GNU Affero General Public License, version 3.0
(SPDX: `AGPL-3.0-only`). See [LICENSE](LICENSE) for the full terms.
Third-party components retain their respective licenses.

## Runtime baseline investigation

Task 001 is blocked on build dependency access. See the
[baseline findings](docs/architecture/001-runtime-baseline.md) and
[verification record](docs/verification/001-baseline-attempt.txt) for observed
versions, source references and the requirements for resuming verification.
No application build or verified runtime baseline is available yet.

To retry a blocked task after fixing its environment:

```bash
./next-task.sh --retry-blocked
```

The runner enables outbound network access for dependency downloads and permits
writes to `~/.m2/repository` while retaining the workspace filesystem sandbox.
If Maven uses a custom local repository, set `MAVEN_REPOSITORY` to that absolute path.
The retry checks the blocker again and preserves previous failure evidence.
