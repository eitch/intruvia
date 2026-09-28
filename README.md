# Intruvia

Intruvia is a self-hosted security-event viewer under development. See the
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

## Verified runtime baseline

Task 001 pins Java 25, Strolch, Jersey and Jetty in an isolated compatibility probe.
See the [baseline decision](docs/architecture/001-runtime-baseline.md) for exact
versions, integration conventions and verification limits. Run it with Java 25:

```bash
JAVA_HOME=/path/to/jdk-25 PATH=/path/to/jdk-25/bin:$PATH \
  mvn -B -f probes/runtime-baseline/pom.xml clean verify
```

The probe starts Strolch/Jetty on loopback, verifies REST and WebSocket exchange,
and shuts both down. It needs Maven dependency access but no PostgreSQL server.
The application skeleton is available below; the event viewer remains pending.

To retry a blocked task after fixing its environment:

```bash
./next-task.sh --retry-blocked
```

The runner enables outbound network access for dependency downloads and permits
writes to `~/.m2/repository` while retaining the workspace filesystem sandbox.
If Maven uses a custom local repository, set `MAVEN_REPOSITORY` to that absolute path.
The retry checks the blocker again and preserves previous failure evidence.

## Build and run the application skeleton

Use Java 25 and Maven (verified with Maven 3.9.16). From the repository root:

```bash
JAVA_HOME=/path/to/jdk-25 PATH=/path/to/jdk-25/bin:$PATH mvn -B clean verify
cd intruvia-app/target/intruvia
/path/to/jdk-25/bin/java -jar intruvia-app-0.0.1.jar runtime 8080
```

Open `http://127.0.0.1:8080/` for the packaged local landing page.
`http://127.0.0.1:8080/health/live` returns `{"status":"UP"}`.
Stop with Ctrl+C or SIGTERM; Jetty stops before Strolch is stopped and destroyed.
The port is optional (default 8080); `0` selects an ephemeral port printed in the log.
The runtime path is required and can be absolute. Keep the application JAR, `lib/`
and `runtime/` together when copying the assembled directory.

This skeleton binds only to loopback and has no event ingestion, viewer authentication,
database, or readiness endpoint yet. The supplied `skeleton` Strolch environment
contains no credentials or event store. It must not be used as a production event
service. Later backlog tasks implement persistence, authentication and the viewer.

`mvn verify` runs the packaged-process HTTP/start/stop smoke test and an in-process
lifecycle test; it needs no database, browser, Node, frontend framework or bundler.
See [skeleton architecture and verification](docs/architecture/002-application-skeleton.md).
