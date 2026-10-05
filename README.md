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

The historical baseline uses a timestamped Strolch snapshot; the current POM selects
`2.8.0-SNAPSHOT` for the required PAT lifecycle fix. Maven cannot satisfy an exact
timestamped coordinate with an installed `2.8.0-SNAPSHOT`, even when its JARs are present.
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
The ingestion HTTP endpoint, viewer authentication and readiness endpoints remain pending. The packaged
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
real ingestion classifies that address as NON_PUBLIC. The ingestion HTTP endpoint remains pending. Run `scripts/verify-postgresql.sh` with Java 25 to verify JSON and
Strolch XML round trips, including IPv6, partial locations and 64-bit sequence precision.

## Machine ingestion credentials

Machine authentication uses Strolch Personal Access Tokens (PATs). The ingestion
endpoint remains task 010. Send `Authorization: Bearer <tokenId>:<tokenValue>` over
HTTPS. Tokens must have exactly the `event:ingest` privilege; viewer/session credentials
and broad PATs are rejected.

Provision one ENABLED Strolch technical user per server, with an ingestion-only role.
Use supported Strolch management APIs to issue a scoped PAT and persist it. Store the
returned secret directly in a protected producer file; never put it in arguments,
logs or source control. See the [verified APIs, role configuration and rotation
procedure](docs/architecture/005-machine-authentication.md).

Create the optional external `runtime/config/machine-identities.conf` with mode `0600`
(or `0400`), containing one username-to-stable-instance mapping per line:

```text
producer-a=server-a
producer-b=server-b
```

It contains identities only, no credentials. Both names must match
`[A-Za-z0-9][A-Za-z0-9._-]{0,127}`; duplicate users or instance IDs are rejected.
Blank lines and `#` comments are allowed. Protect the runtime and parent directories.
Missing mapping disables machine access; malformed/insecure files fail startup.
Restart to change mappings.

Rotate by issuing a replacement PAT for the same technical user, persisting it,
switching the producer file, then revoking and persisting removal of the old PAT.
The stable server ID is unchanged. Revocation, expiry and disabled owners are checked
even for cached PATs, without an Intruvia restart. The obsolete custom TSV verifier
is no longer loaded. No public token-administration endpoint is exposed.

Task 005 was verified using local Strolch `2.8.0-SNAPSHOT` with the upstream revocation
fix; see [source/binary identities](docs/verification/005-resume-source-review.txt)
and [full regression results](docs/verification/005-resume-acceptance.txt).

## Fail2ban input validation

Task 006 adds a strict, bounded JSON parser and canonical Fail2ban adapter. The
public ingestion endpoint is still pending (task 010). Inputs are limited to
16 KiB by default; unknown/duplicate fields, invalid literals and malformed values
are rejected. IPv4-mapped IPv6 becomes IPv4, timestamps become UTC, and equivalent
payloads share a stable digest. Jail text remains plain data, with a 128-character
Unicode limit. Accepted retries can be checked before the separate seven-day age /
five-minute future policy. See the [validation table and integration contract](docs/architecture/006-fail2ban-validation.md)
for limits, constructor configuration, digest format and trusted envelope mapping.

Run focused validation and contract tests without PostgreSQL using Java 25:

```bash
mvn -B -o -Dstrolch.version=2.8.0-SNAPSHOT -pl intruvia-rest -am clean test
```

## Local GeoIP enrichment

Task 007 provides a lifecycle-managed local MaxMind City reader. In your external
`runtime/config/StrolchConfiguration.xml`, set the `GeoIp` component property:

```xml
<Properties><databasePath>/srv/intruvia/geoip/GeoLite2-City.mmdb</databasePath></Properties>
```

Supply your own licensed GeoLite2 City or GeoIP2 City database and keep it readable
by the service account. No database, license key, download or per-event network
lookup is bundled. The default empty path, missing file or failed load leaves
GeoIP degraded (`UNAVAILABLE`) while the application starts. Private/reserved
addresses yield `NON_PUBLIC`; missing records and lookup failures have distinct
statuses. Partial locations remain unmapped, and legitimate zero coordinates are
preserved. GeoIP is approximate network location, not a person's physical location.

The reader keeps a reusable in-memory snapshot; budget heap for the database size.
For live replacement, publish a complete file by atomic rename to
`<databasePath>.staged`. Intruvia checks it every 60 seconds, validates every record,
and atomically replaces the working database and reader. Failed updates retain the
working reader; lookups continue during validation. Build/load time, age and the
default 14-day stale flag are available through the component diagnostics API.
Budget roughly three database sizes of heap during validation. See the
[update procedure and external geoipupdate example](docs/architecture/008-geoip-replacement.md)
for directory permissions, configuration, recovery and shutdown behavior.
Enrichment is ready for the ingestion service; the public ingestion endpoint remains pending.
See [policy, configuration, provenance and synthetic test fixtures](docs/architecture/007-geoip-enrichment.md).

## Atomic ingestion service

Task 009 provides a Strolch service for validated Fail2ban events. Application code
invokes a fresh `IngestService` through the registered `ServiceHandler`, using the
producer's framework certificate and the protected identity mapping. It requires
`event:ingest`; identity comes from the certificate username. GeoIP runs before the
write transaction, and event, receipt and sequence commit atomically. Equal retries
return the original ID/sequence, including after event pruning; changed payloads
conflict. An accepted retry bypasses timestamp-age rejection and new enrichment.

The public POST endpoint remains task 010. See the [service contract and locking
protocol](docs/architecture/009-atomic-ingestion.md) for result handling and later
retention integration. Run `scripts/verify-postgresql.sh -o -Dstrolch.version=2.8.0-SNAPSHOT`
with Java 25 for the real-database concurrency, rollback and retry checks.
