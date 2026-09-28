# Task 001 — runtime baseline decision

Date: 2026-09-28. Status: **accepted; compatibility probe passed**.

## Available source evidence

The local sibling `/home/eitch/src/git/Chronivaro` exists at revision
`860facd75f8abab4405e3adfd01060ee932121cb`. Its root `pom.xml` declares
Strolch `2.8.0-SNAPSHOT`, Jersey `3.1.11`, Jakarta REST API `4.0.0`, and
Jetty `12.0.16` with EE10 servlet/webapp modules. It targets Java 26, so copying
its configuration would not satisfy Intruvia's Java LTS requirement.
These are observed sibling versions, not a proven compatible Intruvia selection.

The local framework `/home/eitch/src/git/atx-dev/strolch` reports
`git describe --tags --always` as `1.2.0-2889-g072f959aa`; its working tree is clean.
Its root `pom.xml` declares Java 25, Strolch `2.8.0-SNAPSHOT`, PostgreSQL JDBC
`42.7.12`, Jersey `3.1.11`, Jakarta REST `4.0.0`, Servlet `6.1.0`, and
WebSocket API `2.2.0`. Snapshot coordinates alone are not an immutable pin.
Do not assume these API versions match Jetty EE10 merely because all use Jakarta.

Temurin `25.0.4+7-LTS` is available at
`/home/eitch/.sdkman/candidates/java/25.0.4-tem`; this is the candidate LTS runtime
for the eventual probe. The shell defaults to Zulu Java 27. Maven is `3.9.16`.

## Observed integration conventions

- Bootstrap: sibling `chronivaro-web/src/main/java/ch/eitchnet/chronivaro/web/StartupListener.java`
  uses `StrolchBootstrapper.trySetupByEnvironment`, falling back to
  `setupByBootstrapFile`, then agent `initialize()` and `start()`, followed by
  `RestfulStrolchComponent` initialization. Shutdown calls `stop()` and `destroy()`.
- REST: sibling `chronivaro-web/src/main/java/ch/eitchnet/chronivaro/web/RestfulApplication.java`
  extends Jersey `ResourceConfig` and registers resource/provider classes explicitly.
  Imports use `jakarta.servlet` and `jakarta.ws.rs`.
- Sessions: framework
  `strolch-web-rest/src/main/java/li/strolch/rest/filters/AuthenticationRequestFilter.java`
  consults `StrolchSessionHandler`, supports cookie session lookup and certificate
  validation, and also supports authorization headers. Intruvia must still implement
  and verify its stricter viewer/machine separation, cookie flags and CSRF contract.
- Privileges: framework `guidelines/STROLCH.md` documents service/search authorization
  through `PrivilegeRoles.xml`, with transaction-level assertions for data operations.
- Persistence: framework `strolch-persistence-postgresql/src/main/java/li/strolch/persistence/postgresql/`
  contains `PostgreSqlPersistenceHandler`, `PostgreSqlStrolchTransaction` and schema
  initialization classes. Detailed configuration and runtime behavior remain unverified.

## Selected baseline

| Component | Exact selection |
|---|---|
| Java | Temurin 25.0.4+7-LTS; compiler release 25 |
| Strolch | `2.8.0-20260927.120822-2` for all used Strolch modules |
| Jersey | 3.1.11 |
| Jakarta REST | 3.1.0 |
| Jetty server, EE10 servlet and EE10 Jakarta WebSocket implementation | 12.0.16 |
| Jakarta Servlet | 6.0.0 |
| Jakarta WebSocket server/client APIs | 2.1.1 |
| PostgreSQL JDBC | 42.7.12 |
| Maven / JUnit | 3.9.16 / 4.13.2 |

The executable pin is [the isolated probe POM](../../probes/runtime-baseline/pom.xml).
Use the sibling's Jetty/Jersey combination with EE10 API versions, overriding the
framework's newer API declarations. The Java 25 probe validates this combination;
it is a compatibility baseline, not a claim that these are the newest releases.
The initializer and EE10 WebSocket choice follow the
[Jetty integration documentation](https://jetty.org/docs/jetty/12/programming-guide/server/websocket.html).

Strolch is fetched from `https://repo.strolch.li/repository/strolch-snapshots/`
using the timestamped version, never a moving version in dependency management.
Maven displays and caches these artifacts under `2.8.0-SNAPSHOT`; the
[SHA-256 inventory](../verification/001-dependencies.sha256) records both the
resolved JARs and timestamped Strolch JARs/POMs, including the parent POM.
All 150 entries were rechecked successfully. Preserve these checksums when carrying
this baseline into the application build; a timestamped snapshot still depends on
repository retention. The published artifacts do not establish a source commit;
the local source revisions are integration references, not asserted binary provenance.
The framework checkout observed on this continuation is
`4c86d8d6d2062ab4e9e6c924c470e080464cffc7`; the earlier observation above is historical.

## Persistence and fixture conventions

The framework's `strolch-persistence-postgresql/src/test/resources/cachedRuntime/config/StrolchConfiguration.xml`
wires `RealmHandler` to `PersistenceHandler`, uses `dataStoreMode=CACHED`, and
selects `li.strolch.persistence.postgresql.PostgreSqlPersistenceHandler` with
`db.url`, `db.username`, `db.password` and pool settings. Schema creation/drop
flags in that test fixture are test-only examples, not production defaults.
Use Strolch transactions for reads/writes and explicit rollback/commit and locking
as documented in `guidelines/STROLCH.md`. Task 004 must verify actual PostgreSQL
restart/rollback behavior and choose safe production schema configuration.

The probe's runtime XML retains Strolch's Apache-2.0 notices and the accompanying
`LICENSE-Strolch`. Its modified fixture uses an EMPTY realm and only a system user;
placeholder crypto settings and permissive fixture roles are not deployable defaults.
No database server, real viewer session, or production credential is used.

## Verification and limitations

[RuntimeBaselineTest](../../probes/runtime-baseline/src/test/java/li/intruvia/probe/RuntimeBaselineTest.java)
starts Strolch and embedded Jetty on an ephemeral loopback port, checks JAX-RS
HTTP 200/body, exchanges a Jakarta WebSocket text frame and closes normally,
then asserts Jetty stopped and Strolch destroyed. A second test loads the Strolch
REST/persistence classes and PostgreSQL driver, identifies Jakarta API JARs, and
asserts legacy `javax.servlet`, `javax.ws.rs` and `javax.websocket` classes are absent.
This restriction concerns web APIs, not Java SE packages such as `javax.crypto`.

The clean Java 25 verification passed: 2 tests, 0 failures/errors/skips. The empty
JAR warning is expected because this project contains only probe tests.
[Continuation evidence](../verification/001-resume-verification.txt) records exact
commands and output; [earlier successful output](../verification/001-probe-success.txt)
and [initial dependency failure](../verification/001-baseline-attempt.txt) are preserved.
The web reader could not fetch repository metadata; Maven resolution and local
artifact checks succeeded. A real database test, application packaging and browser
checks were not run and are not task 001 acceptance checks. No application modules
or later backlog tasks were implemented.
