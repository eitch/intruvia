# Task 004 — durable Strolch persistence

The application uses the task 001 pinned `strolch-persistence-postgresql` and JDBC
42.7.12 artifacts. Production selects one `defaultRealm` in `CACHED` mode: the
in-memory model is backed by PostgreSQL transactions, not file persistence.
`PersistenceConfiguration` rejects another store/realm/provider and schema-drop
configuration before initialization. HTTP starts only after Strolch and model migration
complete. There is no development/transient production switch.

The external runtime template requires DB_URL, DB_USERNAME and DB_PASSWORD through
Strolch's `db.useEnv` convention. PostgreSQL schema creation and migration default to
false; schema drop is forbidden. Explicit first-start creation uses the schema scripts
inside the pinned Strolch artifact. No custom schema or ORM is introduced. The pool
uses Strolch/Hikari defaults; broader operational limits belong to task 019.

The packaged privilege files are examples only, with placeholders for secrets and a
SYSTEM agent (no interactive viewer). Their Apache-2.0 notices and license are retained
from the baseline fixture. Copy/configure these files as described in README.md.
Viewer and machine authentication remain tasks 005 and 011.

`InitialModelMigration` is a Strolch CodeMigration version 0.0.1 run synchronously by
`MigrationsHandler` from `IntruviaComponent.start()`. A single rollback-on-failure
transaction adds the zero stream state and migration marker. Subsequent startups skip
it and verify the existing stream state. An unversioned pre-existing state fails instead
of being reset. The migration does not populate event/receipt fixture data. Domain
Resources are created from the task 003 classpath templates by EventModelMapper.

`EventRepository` accepts an existing StrolchTransaction for every operation. Reads
return detached immutable DTOs; searches use ResourceSearch (requiring its Strolch
privilege); updates lock stream state before modifying it. The caller owns transaction
scope and commit. The internal event list bounds returned items to 1000; its underlying
search sorts the realm's matching resources. Cursor reads and scalable replay belong
to task 012. This boundary deliberately does not allocate sequences or deduplicate:
atomic ingestion logic belongs to task 009.

## Verification

The 2026-09-29 acceptance run uses locally installed `2.8.0-SNAPSHOT` via
`-Dstrolch.version=2.8.0-SNAPSHOT -o`. The default timestamped agent/service artifacts
were unavailable from the configured mirror. This run verifies the local snapshot,
not binary equivalence with the task 001 pin. See `../verification/004-local-verify.txt`
and `../verification/004-local-strolch.sha256` for results and the actual packaged binaries.

Run `JAVA_HOME=/path/to/java25 PATH=/path/to/java25/bin:$PATH scripts/verify-postgresql.sh`.
The script uses Docker only to provide PostgreSQL; no test framework dependency was
added. It binds an ephemeral port to loopback and removes its own container on exit.
Each test creates a randomly named database and drops it afterwards. For a supplied
test server use the three INTRUVIA_TEST_DB_* environment variables documented in README.
Missing variables fail tests instead of skipping the durability gate. JDBC calls in
DatabaseFixture only provision/drop databases and inject a CHECK constraint failure;
all application fixture reads/writes go through Strolch transactions.

PersistenceIT commits an IPv6/null-location event, receipt and stream state, closes
Strolch/Jetty, reopens against the same database with schema creation disabled, and
checks all DTOs and migration version. An injected database constraint violation during
commit must throw StrolchTransactionException; event/receipt/state remain unchanged in
the live model and on a second restart. Configuration rejection covers transient mode,
schema drop and a non-default realm. ApplicationIT retains the packaged HTTP/shutdown
smoke and lifecycle checks, now against PostgreSQL; the packaged process exercises
production DB environment variables.

The initial test attempts are retained under docs/verification/004-*.txt: a test helper
missing `throws Exception`, JDBC registration after Strolch deregisters the driver on
shutdown, and missing system-agent search privilege / an overly specific exception
assertion were corrected. The first complete pass is 004-fourth-verify.txt; the fifth
run strengthens rollback to a real PostgreSQL failure. Final evidence is recorded in
the status ledger. Expected logs include schema-version-table absence during explicit
first creation and the deliberately injected constraint violation.

Not claimed here: DB-server crash/recovery, ingestion concurrency, HTTP ingestion,
retention/replay, production provisioning rehearsal, backup/restore or browser behavior.
These have dedicated later tasks. PostgreSQL write failure diagnostics currently come
from Strolch; task 019 must enforce the full production logging contract.
