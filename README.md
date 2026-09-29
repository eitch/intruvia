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

## Build and run the application

Use Java 25 and Maven (verified with Maven 3.9.16). From the repository root:

```bash
JAVA_HOME=/path/to/jdk-25 PATH=/path/to/jdk-25/bin:$PATH scripts/verify-postgresql.sh
# Configure an external runtime and PostgreSQL as described below, then:
/path/to/jdk-25/bin/java -jar intruvia-app/target/intruvia/intruvia-app-0.0.1.jar /path/to/runtime 8080
```

Open `http://127.0.0.1:8080/` for the packaged local landing page.
`http://127.0.0.1:8080/health/live` returns `{"status":"UP"}`.
Stop with Ctrl+C or SIGTERM; Jetty stops before Strolch is stopped and destroyed.
The port is optional (default 8080); `0` selects an ephemeral port printed in the log.
The runtime path is required and can be absolute. Keep the application JAR, `lib/`
and `runtime/` together when copying the assembled directory.

### Locally installed Strolch artifacts

The default POM pins a timestamped Strolch snapshot. Maven cannot satisfy that exact
coordinate with an installed `2.8.0-SNAPSHOT`, even when its JARs are present.
For development with the local framework referenced by AGENTS.md, use the existing
version property override (offline mode uses the installed artifacts without a mirror):

```bash
scripts/verify-postgresql.sh -o -Dstrolch.version=2.8.0-SNAPSHOT
```

Use Java 25 as above. If the local artifacts need rebuilding, install the required
framework modules and their reactor dependencies first:

```bash
mvn -f /home/eitch/src/git/atx-dev/strolch/pom.xml \
    -pl strolch-service,strolch-persistence-postgresql -am install -DskipTests
```

The bootstrap command skips framework tests; Intruvia's full verification still runs.
Omit `-o` when other dependencies need downloading. Record the override and artifact
checksums with verification evidence: local snapshot results do not verify the original
timestamped binaries. Do not rename local JARs to impersonate timestamped artifacts.

The application binds only to loopback and now requires durable PostgreSQL storage.
Ingestion, viewer authentication and readiness endpoints remain pending. The packaged
`production` environment rejects transient stores; there is no memory-only fallback.

`mvn verify` requires a disposable PostgreSQL service. `scripts/verify-postgresql.sh`
starts a loopback Docker PostgreSQL container, runs `mvn -B clean verify`, and removes
the container on exit. Each test provisions and drops a unique database. Docker and
Java 25 must already be installed. The default image is `postgres:18-bookworm`; set
`INTRUVIA_TEST_POSTGRES_IMAGE` to a local image or immutable digest when needed.
Alternatively, supply `INTRUVIA_TEST_DB_URL`, `INTRUVIA_TEST_DB_USERNAME` and
`INTRUVIA_TEST_DB_PASSWORD` for a dedicated test PostgreSQL role with CREATEDB and
run `mvn -B clean verify`. Never point tests at production. No browser/Node build is required.

For a persistent runtime, copy the packaged `runtime/` outside `target/`, copy its
`config/Privilege*.xml.example` files to the corresponding `.xml` names, and replace
both `CHANGE-ME` values in `PrivilegeConfig.xml` with independently generated secrets.
Keep runtime configuration and the process environment protected; example roles contain
only a system agent and provide no viewer login. Set `DB_URL` (a PostgreSQL JDBC URL),
`DB_USERNAME` and `DB_PASSWORD` through protected service configuration, not command-line
arguments. Use a dedicated empty database owned by the application role.

On the first start only, set `allowSchemaCreation` to `true` in
`config/StrolchConfiguration.xml`; after successful startup, stop the application and
restore it to `false`. Strolch installs its pinned schema and the application runs model
migration `0.0.1`. Keep `allowSchemaDrop` and `allowDataInitOnSchemaCreate` false.
Schema upgrades require a backup and an explicit maintenance change to
`allowSchemaMigration`; it defaults to false. Startup fails on an absent/incompatible
schema when creation/migration is disabled. Model migration versions, events, receipts
and stream state survive restart. See [persistence design and checks](docs/architecture/004-persistence.md).

## Event contracts (v1)

The core module now provides immutable event, GeoIP, receipt and stream-state contracts
and a typed Strolch Resource mapper. See the [field mapping](docs/architecture/003-event-model.md),
[v1 API schema](docs/api/v1.schema.json) and [complete synthetic fixture](docs/api/event-v1.json).
Sequences are decimal strings on the wire; absent coordinates remain null. The fixture
uses a documentation IPv6 address with invented geography solely for serialization tests;
real ingestion must classify that address as NON_PUBLIC. Ingestion remains pending. Run `scripts/verify-postgresql.sh` with Java 25 to verify JSON and
Strolch XML round trips, including IPv6, partial locations and 64-bit sequence precision.

## Machine ingestion credentials

**Design revision (2026-09-29):** Task 005 is reopened to replace the custom credentials
below with Strolch PATs for per-server technical users. These instructions describe
the current, superseded implementation; PAT integration is not yet implemented.
The next task execution must follow [revised task 005](docs/INTRUVIA_BACKLOG.md)
and update this section with verified Strolch provisioning instructions.

Task 005 provides the machine authentication boundary; the ingestion endpoint itself
is still pending. Use HTTPS through your TLS proxy. Protect the external runtime and
its parent directories from other users. The optional `config/machine-credentials.tsv`
file must be a regular, non-symlink POSIX file with mode `0600` (or `0400`). Without
it, all machine credentials are disabled. Invalid or insecure configuration fails startup.

Provision each server with 32 cryptographically random bytes encoded as unpadded
base64url (43 characters). Keep the token only in that server's protected token file.
Compute SHA-256 over the ASCII token, without a trailing newline. Store only the
lowercase hex digest in Intruvia's verifier file. Each line has three TAB-separated
fields (the notation below describes fields, not a usable credential):

```text
stable-server-id<TAB>event:ingest<TAB>64-character-lowercase-sha256-verifier
```

Use a trusted provisioning process with owner-only output files; never put tokens in
command arguments, shell history, logs or source control. Human-chosen passwords are
not valid substitutes for generated tokens. `none` is the only other accepted privilege
and deliberately yields 403. Blank lines and lines beginning with `#` are allowed.

For rotation, add a new verifier with the **same stable server ID**, atomically replace
the file while preserving owner-only permissions, and restart Intruvia. Switch the
producer token, then remove the old verifier and restart to revoke it. Other machine
IDs and viewer accounts remain separate. See [authentication design](docs/architecture/005-machine-authentication.md).
