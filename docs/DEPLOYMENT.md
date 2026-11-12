# Intruvia installation and recovery

This guide describes one Intruvia process, one PostgreSQL database and a same-host
TLS reverse proxy. Run as a dedicated unprivileged `intruvia` account. Paths and
`intruvia.example.org` are examples; secrets below are placeholders, never usable
credentials. Task 022 remains the release gate. The [task 021 rehearsal record](verification/021-acceptance.txt)
states exactly what has been exercised locally.

## Prepare the distribution and database

Install Java 25, PostgreSQL 18 server/client tools, nginx with TLS support, Python 3
on reporting hosts and Fail2ban there. Maven is needed only on the build machine.
The browser needs no Node build or internet map provider. Obtain a certificate for
your real DNS name from your certificate authority and install its private key
readable only by the proxy. Keep PostgreSQL private; use authenticated connections,
TLS with server verification for a remote database, and a dedicated role/database.
Use your OS package/service instructions to create the service account, provision
PostgreSQL and install nginx. None of the guide's examples installs host services
automatically. The local rehearsal uses isolated containers instead.

From the source root, with Java 25 and Maven on PATH:

```bash
scripts/verify-postgresql.sh -o -Dstrolch.version=2.8.0-SNAPSHOT
```

Docker is needed for that disposable test database. For dependency cache recovery
follow [README local Strolch instructions](../README.md#locally-installed-strolch-artifacts).
Archive the complete `intruvia-app/target/intruvia/` directory and its SHA-256
manifest. Keep its JAR, `lib/`, runtime template, integrations and `docs/` together.
Local snapshot checksums identify the actual binaries; they do not establish the
historical timestamped baseline. Copy the distribution to `/opt/intruvia` and copy
its runtime template to `/var/lib/intruvia/runtime`, outside the release directory.
The service account owns `/var/lib/intruvia` (0700); release files can be read-only.

As a PostgreSQL administrator, create role `intruvia` with LOGIN (no superuser,
CREATEDB or CREATEROLE), set its password using psql's `\password intruvia` prompt,
and `CREATE DATABASE intruvia OWNER intruvia;`. Restrict pg_hba.conf to the service
host and this role/database. Do not grant PUBLIC access to a sensitive database.
Create `/etc/intruvia/database.env`, root-owned 0600, containing:

```text
DB_URL=jdbc:postgresql://127.0.0.1:5432/intruvia
DB_USERNAME=intruvia
DB_PASSWORD=REPLACE_WITH_DATABASE_SECRET
DB_POOL_maximumPoolSize=10
DB_POOL_connectionTimeout=30000
DB_POOL_validationTimeout=5000
```

Keep real secrets out of shell history and process arguments. Environment variables
are visible to the service owner/root; protect that account. For a remote database
use JDBC `sslmode=verify-full` and a configured trusted root certificate. The XML
pool values are ignored when `db.useEnv=true`; the environment names above are case
sensitive. [All settings and bounds](architecture/019-runtime-operations.md).

## Provision the external runtime while stopped

Use `umask 077`. Copy `PrivilegeConfig.xml.example`, `PrivilegeRoles.xml.example`
and `PrivilegeUsers.xml.example` in runtime/config to their `.xml` names. Retain
third-party notices. Replace the two `CHANGE-ME` values with two independent random
secrets (for example generated with `openssl rand -hex 32`), using a protected
editor. Preserve these values across upgrades/restores. Keep configuration files
0600 and directories 0700. XML values must be escaped when editing.

Add these roles inside `PrivilegeRoles.xml`'s existing `<Roles>` element:

```xml
<Role name="Producer">
  <Privilege name="event:ingest" policy="DefaultPrivilege"><Allow>event:ingest</Allow></Privilege>
</Role>
<Role name="TokenOperator">
  <Privilege name="PrivilegePersonalAccessToken" policy="DefaultPrivilege"><AllAllowed>true</AllAllowed></Privilege>
  <Privilege name="PrivilegePersonalAccessTokenUser" policy="UserAccessPrivilege"><AllAllowed>true</AllAllowed></Privilege>
  <Privilege name="PrivilegeAction" policy="DefaultPrivilege"><Allow>Persist</Allow></Privilege>
</Role>
```

Keep the supplied SYSTEM agent and its roles. Add ENABLED users with unique userId
and username: `viewer` with `Viewer`, optionally `Operator` for diagnostics;
`producer-a` with `Producer`; `token-admin` with `TokenOperator`. Do not assign viewer,
agent or operator roles to producers. The token operator is a trusted offline
credential administrator, never a browser user or PAT owner for ingestion.

The included source-mode Java [PrivilegeTool](deployment/PrivilegeTool.java) uses
the configured Strolch encryption and management APIs. It requires no compilation
or additional libraries beyond the distribution. **Stop Intruvia before running
it**: a second XML privilege handler must never write concurrently with a live one.
Run from the distribution root as the runtime owner. Create temporary password
files using a protected editor (one password, no trailing whitespace), mode 0600:

```bash
java --class-path 'lib/*' docs/deployment/PrivilegeTool.java \
  /var/lib/intruvia/runtime/config hash /protected/new-password /protected/password-hash
```

Put the generated hash in the user's password attribute; it is sensitive too:

```xml
<User userId="viewer-1" username="viewer" password="REPLACE_WITH_GENERATED_HASH">
  <Firstname>Local</Firstname><Lastname>Viewer</Lastname><Locale>en-GB</Locale>
  <State>ENABLED</State><Roles><Role>Viewer</Role></Roles>
</User>
```

Repeat hashing with distinct strong passwords for the technical user and token
operator, adjusting ID/name/role. Remove temporary plaintext password/hash files
after provisioning. The tool writes outputs with CREATE_NEW and mode 0600; it
never prints passwords or PATs. Protect its whole working directory. A failed
issuance may have persisted a token before output failed; inspect/revoke the
orphan through trusted Strolch tooling before retrying. Never erase privilege
files to recover from such a failure.

Copy viewer.properties.example to viewer.properties and set exactly
`origin=https://intruvia.example.org` (no slash). Add mode-0600
`config/machine-identities.conf`:

```text
producer-a=server-a
```

The stable instance ID is mapped from the authenticated username, never a caller
field or token label. No duplicate names/IDs; changes require restart. Rotation
keeps the same owner and mapping. Missing origin/mapping disables that access.

Issue a PAT while stopped, with explicit UTC validity dates appropriate to your
policy (the dates here are syntax examples, replace before running):

```bash
java --class-path 'lib/*' docs/deployment/PrivilegeTool.java \
  /var/lib/intruvia/runtime/config issue token-admin /protected/operator-password \
  producer-a initial 2026-11-12T00:00:00Z 2026-12-12T00:00:00Z /protected/producer.pat
```

The tool always requests only `event:ingest`, persists `PrivilegeTokens.xml` and
writes the one-time `tokenId:tokenValue` secret to the new protected file. Back up
all privilege files together. Install the file on its reporting host using an
authenticated secure channel with no console output; root owns it, mode 0600.
Neither the technical-user password nor operator password belongs on that host.

For rotation, stop Intruvia, issue a second PAT for the same owner to a different
output path, restart, replace the producer file atomically, and verify delivery.
Then stop, revoke the OLD token ID (the portion before `:`; do not pass the secret),
and restart:

```bash
java --class-path 'lib/*' docs/deployment/PrivilegeTool.java \
  /var/lib/intruvia/runtime/config revoke token-admin /protected/operator-password OLD_TOKEN_ID
```

This offline recipe entails maintenance downtime. Supported Strolch management
APIs on the same running handler can instead rotate/revoke without downtime;
Intruvia exposes no public management endpoint. Revocation/expiry is checked on
every request, including cached PATs. An already accepted request may finish.
Disabling a user blocks all its PATs; privilege scope changes require reissue.
Expired/revoked secrets cannot be recovered; issue replacements.

## First start and TLS/WSS

Set `allowSchemaCreation=true` for the first start against the dedicated empty
database. Keep allowSchemaDrop/allowDataInitOnSchemaCreate/allowSchemaMigration
false. Start the packaged JAR with the protected DB environment, as the service
user, and check `http://127.0.0.1:8080/health/ready` returns 200 UP. Stop cleanly,
set allowSchemaCreation=false, restart, and check again. Strolch creates its schema
and Intruvia migration 0.0.1; no manual model import is required.

The [systemd example](deployment/intruvia.service.example) supplies paths, umask,
restart and a 120-second shutdown budget. Adjust Java/release/runtime paths before
installing it. Enable only after a successful manual first-start check. Configure
bounded journal retention as well as the application's 10 MB/five-day/50 MB archive
log limits. Do not enable framework SQL/request logging in production.

Adapt [nginx.conf.example](deployment/nginx.conf.example) for your hostname,
certificate/key and loopback application port; run `nginx -t -c /absolute/config`
before reloading nginx. Permit 443 inbound, keep 8080 and PostgreSQL private. Use a
proxy on the same host/network namespace because Jetty binds 127.0.0.1 only.
The proxy preserves Origin, Cookie and Authorization, uses HTTP/1.1 and explicitly
forwards Upgrade/Connection for WSS, disables buffering and allows heartbeat
traffic for 75 seconds. This follows [nginx WebSocket proxying](https://nginx.org/en/docs/http/websocket.html).
No URL prefix is supported: serve `/` and `/api/v1` on the configured origin.
Health stays loopback-only. Proxy 413/429/502 errors may be nginx responses rather
than the application's JSON contract. Proxy limits are additional admission
controls and not replacements for the application's global/per-instance buckets.
The example disables access logs and uses critical-only error logging to avoid
credentials/payloads; review any local logging overrides.

Visit the HTTPS origin, log in, check the Secure/HttpOnly/SameSite=Strict cookie
with path `/api/v1`, and confirm a WebSocket 101 upgrade and `hello` frame.
A producer POST must create one event (201); repeating the same body must return
200 duplicate with the same ID/sequence. Test with a protected client that reads
the PAT file; do not put Authorization on a shell command line. The browser must
not contain machine tokens or store session credentials in localStorage.

## GeoIP, Fail2ban and retention

Obtain your own licensed MaxMind City MMDB externally. Empty/missing databasePath
keeps readiness UP but stores UNAVAILABLE geography. Configure an absolute readable
path in the GeoIp component. Follow the [staged update procedure](architecture/008-geoip-replacement.md):
external geoipupdate credentials stay protected; publish a complete file by atomic
rename to `<databasePath>.staged` on the same filesystem. The component validates
and replaces it; failed updates retain the working reader. Never overwrite the
active file in place. Diagnostics report load/build age and a 14-day stale flag.
Historical enrichment does not change. GeoIP is approximate network location.

Follow [Fail2ban installation](../integrations/fail2ban/README.md) to install the
packaged Python helper/action and append it LAST alongside each jail's existing
firewall action. Only new actionban callbacks notify; never replace the ban action.
Use the real HTTPS URL and the private PAT file. Test in an isolated jail first.
**Best effort only: three attempts within 15 seconds, no persistent offline queue;
events can be lost during outages or this guide's maintenance windows.** Failures
do not fail the normal firewall action. Monitor the concise local failure log.

Events default to 30 days/100,000 records, receipts at least 30 days/1,000,000.
Retention prunes an oldest prefix and advances minAfter; full unexpired receipt
storage rejects new events with 429/Retry-After instead of evicting receipts.
See [retention settings](architecture/013-retention.md); do not delete rows manually.
Readiness validates DB connectivity and may temporarily fail under pool saturation.
Operator diagnostics require Viewer+Operator; counters reset on restart.

## Consistent backup and restore

Use PostgreSQL 18 client tools, an owner-only .pgpass/PGPASSFILE for credentials,
and ordinary PGHOST/PGPORT/PGUSER/PGDATABASE environment settings. Backups contain
protected IP/history and password/PAT hashes. Encrypt them at rest, restrict access,
keep off-host copies and routinely restore to an isolated database. Preserve the
exact release tree and checksum manifest as part of the backup set.

1. Pause producer notification traffic at the proxy, stop Intruvia gracefully and
   verify process exit. Never start another process on the same realm. If shutdown
   fails, resolve in-flight work before backup; do not copy a live XML privilege set.
2. With umask 077, capture the **whole database**, not selected tables/resources:

   ```bash
   pg_dump --format=custom --no-owner --no-acl --file=/protected/backup/database.dump
   tar -C /var/lib/intruvia -czf /protected/backup/runtime.tar.gz runtime
   sha256sum /protected/backup/database.dump /protected/backup/runtime.tar.gz \
     > /protected/backup/SHA256SUMS
   ```

   Preserve protected DB/service/proxy settings, TLS key/certificate and the release
   separately in the encrypted backup set. runtime.tar.gz includes users/roles,
   PrivilegeConfig secretKey/secretSalt, PrivilegeTokens.xml, mappings, configuration,
   templates/migrations and any staged updates. An external MMDB must be backed up
   separately subject to its license or reprovisioned. Never commit these backups.
3. Record UTC time, release/JDK/Strolch binary hashes and the last observed
   snapshot cursor/minAfter with the backup. Restart, verify ready and unpause.
   PostgreSQL [pg_dump](https://www.postgresql.org/docs/18/app-pgdump.html) provides
   a consistent database export; stopping also coordinates the external XML files.
4. Restore with ingress paused and Intruvia stopped. Verify checksums. Create a NEW
   empty database owned by the application role; recreate that role/password via
   protected administration because pg_dump does not include cluster roles. Restore:

   ```bash
   pg_restore --exit-on-error --single-transaction --no-owner --no-acl \
     --dbname=intruvia_restored /protected/backup/database.dump
   ```

   Use [pg_restore](https://www.postgresql.org/docs/18/app-pgrestore.html), inspect its
   exit status, and keep a failed restore isolated. Extract the runtime archive into
   a new protected directory, preserve owner/modes, restore the matching release and
   privilege secrets, and point DB_URL at the new database. Keep all schema flags
   false; restoring does not require schema creation/drop/migration. Do not merge
   live and restored event tables or restore only events without receipts/state.
5. Start one application process. Verify ready, viewer login, snapshot IDs/sequences,
   cursor/minAfter, and identical ingestion retry returns its original ID/sequence.
   Test an intentional changed-payload conflict. A client with cursor greater than
   restored head must receive resync_required and load a new snapshot; UI recovery
   shows the data-gap notice. Reauthenticate after restart (sessions are not backups).
   Open browser pages may need reload/login before recovery; a snapshot cannot recover
   events absent from the backup. Only unpause producers after these checks.

RPO is the last successful backup, not zero: later acknowledged events/receipts and
revocations may be lost. Restoring can resurrect revoked PATs and reuse sequence
numbers assigned after the backup. Keep ingress paused, reconcile token revocations,
rotate potentially resurrected credentials, and require every viewer to reload and
sign in before reopening; a cursor at/below the restored head cannot detect all
alternate histories. Recovery time depends on database size and operator actions;
no RTO, point-in-time recovery or HA guarantee is provided. Long producer outages
lose notifications independently of backups. Retention also deliberately loses old
history. The [specification's deferred features](INTRUVIA_SPECIFICATION.md#explicitly-deferred)
include control/unban, offline queue, analytics, export, multi-tenancy and clustering.

## Troubleshooting and upgrades

| Symptom | Check / action |
|---|---|
| Startup fails | Four required XML files, two non-placeholder privilege secrets, Java 25, DB credentials, schema flags/version and protected mapping permissions; errors omit secret values |
| Ready 503 | DB network/authentication, finite pool acquisition/validation budget; retry a busy probe with a monitor timeout above 35 seconds for defaults |
| Viewer 403 / WSS refused | Exact HTTPS origin including port, Origin/CSRF headers, Viewer role, Secure cookie, proxy upgrade headers; PATs cannot authenticate viewers |
| Ingestion 401 / 403 | PAT validity/owner enabled/revocation, exact event:ingest scope, stable mapping; viewer cookies never ingest |
| 409 | Same producer UUID used with changed normalized payload; retries must reuse exact bytes |
| 429 | Per-instance/global/proxy rate limit or receipt_capacity; honor Retry-After; do not delete unexpired receipts |
| No mapped point | Stored geo status, MMDB permissions/City edition/age, non-public or missing IP; never invent coordinates |
| Stream disconnect / gap | Session expiry, queue/deadline, retention or restored head; reauthenticate/resnapshot and inspect operator diagnostics |
| No notifications | Confirm additive action, private token file, HTTPS trust and helper logs; no replay queue exists |

Before upgrades take and rehearse a full backup, preserve runtime/config rather
than overwriting it with templates, compare component changes, run migration only
as an explicit maintenance operation and keep its evidence. Roll back code and
database/runtime as a matching set; do not run old code on a migrated schema blindly.
Task 020 measured combined workload p95 below one second but burst-only p95 around
9.2 seconds; this is not a per-burst latency guarantee or release approval.
