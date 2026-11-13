# Intruvia installation and recovery

This guide covers both preparing an administrator handoff and installing or operating
Intruvia. The standalone Java installation remains supported for hosts that do not use
Compose.

**Choose your path:**

- **Release owner:** [build and package the versioned image and deployment bundle](#release-owner-build-and-package-a-deployment).
- **Administrator:** [install a supplied Compose bundle](#compose-deployment); no Java, Maven or PostgreSQL installation is needed on the host.
  - For later changes, jump to [public-origin changes](#change-the-public-origin), [offline users and PATs](#offline-users-and-pats), [backup and restore](#backup-and-restore), or [failed-start recovery](#recover-a-failed-first-start).
- **Standalone operator:** continue to [standalone installation](#standalone-installation).

## Release owner: build and package a deployment

The administrator receives a tarball and its SHA-256 file; the tarball refers to a
published image and contains the Compose configuration, protected environment file,
allowlisted runtime templates and support documentation. The image itself contains only
the prebuilt application and dependency JARs. Release scripts use the changeable
`repo.strolch.li/intruvia` repository constant and derive the matching version from the
root Maven POM, so the image and bundle stay aligned without operator-supplied variables.

> **Release prerequisites:** task 023 still leaves Strolch snapshot provenance, registry
> publication and pulling the published image unverified. Verify the approved Strolch
> artifacts and their provenance, authenticate to `repo.strolch.li`, and complete the
> registry pull and release acceptance checks before describing a bundle as deployable.

The release commands require no image/version/output assignments. The root POM is the
single source for the Intruvia release version; keep the POM and the repository constant
in `scripts/release-config.sh` under review when preparing a release.

### 1. Assemble the Maven distribution

Use Java 25, Maven, Docker, Python 3 and reviewed Strolch artifacts available from the
configured Maven repositories or local cache. By default, `./build-and-push-docker.sh`
runs a clean Maven package with tests skipped, then assembles the image from
`intruvia-app/target/intruvia`. This is a quicker packaging path, not release acceptance.

For full PostgreSQL-backed Maven verification before building and pushing the image, use
the opt-in flag:

```bash
./build-and-push-docker.sh --with-tests
```

This runs `scripts/verify-postgresql.sh` and its clean Maven `verify` against a disposable
PostgreSQL container. Review dependency sources and the build result, and record exact
Strolch artifact hashes; hashes identify the built JARs but do not by themselves
establish their source provenance. Run full verification and all release acceptance
checks before handoff even if the image was initially built with tests skipped.

### 2. Build and publish the versioned image

Authenticate to the registry using the release owner's approved credentials. The
script derives `repo.strolch.li/intruvia:<root-POM-version>`, builds and pushes that
versioned reference, and prints SHA-256 hashes for the Strolch JARs used. The default
command skips tests; use `--with-tests` to run the PostgreSQL-backed verification before
the image build:

```bash
docker login repo.strolch.li
./build-and-push-docker.sh
# Or, for full PostgreSQL-backed verification before building and pushing:
./build-and-push-docker.sh --with-tests
```

Retain the command output and confirm the image can be pulled by its versioned
reference before proceeding. The image is assembled from JARs, not the application
source tree; runtime templates are provided separately in the bundle.

### 3. Create and hand off the administrator bundle

After the image is published, create the administrator bundle with the matching POM
version and image reference. The builder writes into
`target/deployment-release/`, refuses to overwrite existing release files, and emits
both the archive and its checksum:

```bash
scripts/build-deployment-tarball.sh
```

Run the deployment image, PostgreSQL, tarball and Compose acceptance checks before
handoff; their commands and limits are recorded in the source repository's
`docs/verification/023-acceptance.md` (that report is not part of the administrator bundle).
Then give the administrator the `intruvia-deployment-<root-POM-version>.tar.gz` file,
its matching `.sha256` file, the exact image reference and registry access instructions. Do not
include a live `.env`, runtime directory, database, passwords or deployed data. The
archive builder includes only reviewed distributable files and creates a protected
`.env` containing `repo.strolch.li/intruvia:<root-POM-version>`.

## Compose deployment

This is the administrator's container path. Use Docker Engine and Compose on a trusted
host. Obtain the versioned deployment tarball and its `.sha256` from the release owner.
Release scripts target `repo.strolch.li/intruvia` and the root POM version, but registry
publication, image pull and public-CA validation are not assumed to have passed.

### Verify and unpack the bundle

Run these commands from a directory containing exactly one supplied archive/checksum
pair:

```bash
sha256sum -c intruvia-deployment-*.tar.gz.sha256 &&
mkdir -m 0700 intruvia-deployment &&
tar -xpzf intruvia-deployment-*.tar.gz -C intruvia-deployment &&
chmod 0700 intruvia-deployment &&
cd intruvia-deployment
```

Preserving the archive modes (`-p`) keeps the read-only templates and database
initializer readable by the container users even if your shell uses `umask 077`.

### Configure and start

Complete the following four steps in order. Protect `.env` with mode `0600`. In a
protected editor, check `INTRUVIA_IMAGE` is
   the supplied explicit versioned image reference and set `INTRUVIA_PUBLIC_ORIGIN`
   to the exact public HTTPS origin, without a path or trailing slash. Set independent
   `POSTGRES_ADMIN_PASSWORD` and `INTRUVIA_DB_PASSWORD`: each must be 24–128 characters
   from `[A-Za-z0-9_-]` and they must differ. Database and role names in `.env` must
   start with a lowercase letter, contain only lowercase letters, digits or underscores,
   be at most 63 characters, and not start with `pg_`; the application and admin roles
   must differ and the database must not be `postgres`, `template0` or `template1`.
   Treat `.env` and the containers' environments as sensitive; do not print resolved
   Compose configuration or expose Docker access to untrusted users. Ensure shell
   environment variables do not override the corresponding `.env` values in Compose.
2. Ensure `secrets/` is owned by container UID/GID `10001:10001` and has mode `0700`.
   With a protected editor create `secrets/viewer-password` as a regular file owned by
   `10001:10001`, mode `0600` or `0400`, containing a strong password for the initial
   `viewer` account. Avoid trailing whitespace. Never put this password in command
   arguments, environment variables, shell history or logs. With rootless Docker or
   user namespaces, adjust host ownership for the container's mapped UID/GID. The
   directory is mounted read-write so successful initialization can remove the file.
   For a fresh extraction on rootful Docker, one Bash example is:

   ```bash
   if (
     set +x
     set -C
     umask 077
     test ! -e secrets/viewer-password || exit 1
     test ! -L secrets/viewer-password || exit 1
     unset initial_viewer_password
     IFS= read -r -s -p 'Initial viewer password: ' initial_viewer_password || exit 1
     printf '\n'
     test -n "$initial_viewer_password" || exit 1
     printf '%s\n' "$initial_viewer_password" > secrets/viewer-password || exit 1
     unset initial_viewer_password
   ); then
     sudo chown 10001:10001 secrets secrets/viewer-password &&
       sudo chmod 0700 secrets && sudo chmod 0600 secrets/viewer-password
   else
     printf 'Password file was not created; ownership is unchanged.\n' >&2
   fi
   ```

   Run the ownership commands only after the password-file creation succeeds. The
   prompt is not echoed; do not enable shell tracing while handling the password.
3. Configure DNS and firewall for the host and the exact HTTPS origin. The supplied
   Caddy configuration is a simple replaceable HTTPS/WSS reverse proxy with automatic
   TLS; the administrator owns DNS, certificate acquisition/renewal, proxy configuration
   and verification. Only Caddy publishes ports 80 and 443; app and PostgreSQL publish
   none. Compose's proxy and database networks and the Docker host are trusted: the
   app binds `0.0.0.0` inside its network, and network peers can reach it. Restrict
   Docker/network access and do not attach untrusted containers. The database network
   is internal. Caddy blocks public health paths; monitor readiness from the trusted
   network. Review proxy access/error logging and protect proxy state and TLS keys.
4. If registry authentication is required, first run `docker login repo.strolch.li`
   on the deployment host using the access instructions from the release owner. Pull
   the images and start the services:

   ```bash
   docker compose pull
   docker compose up -d
   ```

   PostgreSQL readiness gates app startup, and app readiness gates Caddy. On first
   start the application initializes a new empty database and runtime with independent
   privilege secrets, creates the enabled `viewer`, and removes the password file.
   Sign in at the HTTPS origin and check WSS. The default has no producer mapping or
   privileged token operator. Changing `.env` or supplying another viewer-password
   file on restart does not replace existing users or passwords.

### After startup

Named volumes retain `postgres_data`, `runtime`, `app_logs`, `caddy_data` and
`caddy_config` across restarts and image replacement. Protect Docker volumes and the
host, including logs, runtime privilege files and proxy certificate state. Do not use
`docker compose down --volumes` on an installation you intend to retain.

### Operate the Compose deployment

#### Change the public origin

After initialization, changing `INTRUVIA_PUBLIC_ORIGIN` in `.env` alone does **not**
rewrite the persistent `runtime/config/viewer.properties`. Pause ingress, stop the
app with `docker compose stop app`, and confirm no app or offline privilege writer is
running. Using a trusted maintenance environment with access to the runtime volume,
edit `/var/lib/intruvia/runtime/config/viewer.properties` to set `origin` to the exact
new HTTPS origin (no path or trailing slash), and update `.env` to the same value.
Preserve owner `10001:10001`, mode `0600` for the runtime file, private runtime
directories and mode `0600` for `.env`. Coordinate DNS, certificates and proxy
configuration for the new name before restarting; the browser Origin and this value
must match exactly, including any port. Use `docker compose up -d` to recreate services
with the changed environment, then verify HTTPS, viewer login and WSS before reopening
ingress. Neither this change nor a new environment value rotates existing users or
credentials.

#### Offline users and PATs

Pause ingress and stop the app (`docker compose stop app`) before any runtime edit or
offline operation. Ensure no other application, editor or privilege writer uses the
runtime. The container entrypoint takes the same runtime lock for each offline command;
manual edits must also be performed only while all writers are stopped. Back up the
matching database and runtime first. `secrets/` is the shared protected workspace:
create separate owner-only password files there, owned by `10001:10001`, without
placing passwords in arguments or environment. Outputs must not already exist. For
example, generate a hash for a new operator or producer password with:

```bash
docker compose run --rm --no-deps app offline hash /run/secrets/new-password /run/secrets/new-password.hash
```

The initial `viewer` has only Viewer privileges, not token administration. Separately
provision a trusted `token-admin` and one ingestion-only technical user per producer
in the existing runtime privilege files using the [standalone Strolch role and user
procedure](#provision-the-external-runtime-while-stopped), adapting its paths to the
runtime volume. Preserve the existing viewer, SYSTEM agent, generated secrets and
other users; do not copy fresh templates over them. Add the documented `Producer`
and `TokenOperator` roles and use the generated hashes as password attributes. Set
`machine-identities.conf` to the stable username-to-instance mapping, e.g.
`producer-a=server-a`. Edit with a trusted maintenance environment while stopped;
retain UID/GID `10001:10001`, owner-only file modes and private directories. The
standalone source-mode tool is not inside the JAR-only image; use the container's
offline commands instead. The referenced standalone source and architecture files
are available in the source/Java distribution, not necessarily in the Compose bundle.

Create a separate protected `secrets/operator-password` for the provisioned operator.
Replace the example UTC validity dates with your policy's current window:

```bash
docker compose run --rm --no-deps app offline issue token-admin /run/secrets/operator-password \
  producer-a initial 2026-11-12T00:00:00Z 2026-12-12T00:00:00Z /run/secrets/producer.pat
docker compose run --rm --no-deps app offline revoke token-admin /run/secrets/operator-password OLD_TOKEN_ID
```

Issue creates a new mode-0600 file containing the one-time `tokenId:tokenValue` and
persists the scoped `event:ingest` token. Transfer the PAT securely to the reporting
host as a root-owned 0600 file; do not expose its contents in commands or logs. Revoke
only an old token ID, the part before `:`, never the secret. For rotation issue a
second token to a new file for the same owner, restart, securely replace the producer
file and verify delivery; stop again, revoke the old ID and restart. Remove temporary
password, hash and PAT files securely when no longer needed. A failed issue can have
persisted an orphan token before output failed: inspect/revoke it with trusted Strolch
tooling before retrying; never erase privilege files. Restart with `docker compose up -d`.

#### Backup and restore

##### Create a coordinated backup

Pause producer ingress, stop the app gracefully and verify it has exited; keep all
other database and privilege-file writers stopped. Use `umask 077` and a private,
encrypted backup location. The following captures the whole application database
schema and data and its matching runtime, not selected tables, without putting passwords
in arguments. This example deliberately omits object ownership and ACLs; PostgreSQL
cluster-global roles and settings are not included. Separately back up and restore any
site-specific roles, grants, ownership and database settings using administrator procedures.
If the application role cannot read every database object, use an appropriately privileged
backup role instead and verify the resulting restore:

```bash
docker compose exec -T db sh -c 'export PGPASSWORD="$INTRUVIA_DB_PASSWORD"; \
  exec pg_dump -h 127.0.0.1 -U "$INTRUVIA_DB_USERNAME" -d "$POSTGRES_DB" \
  --format=custom --no-owner --no-acl' > /protected/backup/database.dump
docker compose run --rm --no-deps -T --entrypoint tar app \
  -C /var/lib/intruvia -czf - runtime > /protected/backup/runtime.tar.gz
sha256sum /protected/backup/database.dump /protected/backup/runtime.tar.gz > /protected/backup/SHA256SUMS
```

Check every command's exit status; retain `.env`, proxy settings/state and the exact
bundle/image identity separately in the encrypted backup set, and record time,
release/JAR hashes and snapshot cursor/minAfter. Protect backups containing IP history,
password/PAT hashes and privilege secrets. Back up or reprovision external MMDB files
according to their license. Restart the app and reopen ingress only after readiness.

##### Restore into a fresh project

Restore into a **fresh, isolated** Compose project with new empty database and runtime
volumes, matching release/bundle and protected `.env`; keep the old installation and
all writers stopped. Verify checksums. Start only the new `db`:

```bash
docker compose up -d db
```

Wait for its health check. Confirm the fresh runtime volume is empty and owned by
`10001:10001` before extracting the trusted archive. Then restore the whole dump as
the application role into its empty database and extract the matching runtime. Run
each command separately and stop if it fails:

```bash
docker compose exec -T db sh -c 'export PGPASSWORD="$INTRUVIA_DB_PASSWORD"; \
  exec pg_restore -h 127.0.0.1 -U "$INTRUVIA_DB_USERNAME" -d "$POSTGRES_DB" \
  --exit-on-error --single-transaction --no-owner --no-acl' < /protected/backup/database.dump
docker compose run --rm --no-deps -T --entrypoint tar app \
  -C /var/lib/intruvia/runtime --strip-components=1 --no-same-owner -xzf - < /protected/backup/runtime.tar.gz
```

Check both command exit statuses; preserve private modes and matching initialization
markers. Never merge with an existing runtime or database. Restore configuration/proxy state as appropriate and
ensure database credentials and public origin match the restored runtime. Start one
app, verify readiness, login, snapshot IDs/cursors and an identical retry, then reconcile
revocations and only reopen ingress after verification. An older backup may lose
acknowledged events and receipts, resurrect revoked PATs and reuse sequences. Rotate
potentially resurrected credentials and require all viewers to reload and sign in;
even a cursor at or below restored head may follow a different history. No backup can
recover later lost events, and Fail2ban has no persistent notification queue.

#### Rotate database and application credentials

PostgreSQL initialization environment values apply only to a new data directory:
editing `.env` does **not** rotate existing database roles. During maintenance, use
protected PostgreSQL administrative access and interactive `psql` `\password` for
each role (using the actual admin and application role names), then update the
corresponding `.env` values consistently and recreate the services; verify both
credentials and readiness. Keep the two new passwords distinct and within the
initialization constraints. Coordinate rotations with backups.
Likewise, manage viewer passwords, users and PATs explicitly through protected Strolch
procedures, not by changing bootstrap environment values.

#### Upgrade the application image

For an image upgrade, take and rehearse a matching backup, review release notes and
schema compatibility, set the supplied new versioned `INTRUVIA_IMAGE`, then pull and
recreate with `docker compose pull` and `docker compose up -d`. Preserve runtime and
volumes; do not overwrite runtime with new templates. Perform any migration only as
an explicitly documented maintenance operation. Roll back image, database and runtime
as a matching set, not old code against an arbitrarily migrated schema.

#### Optional GeoIP and Fail2ban

For optional GeoIP, provide a licensed MMDB through a protected readable mount and
configure the runtime; use the staged update procedure in the source/Java distribution.
Without one, GeoIP remains degraded but ingestion is ready. Install the bundled
`integrations/fail2ban/` helper on each reporting host as an additive action; preserve
the existing firewall action. Delivery is best effort (three attempts within 15 seconds,
no persistent queue). Review the packaged integration guide before use.

#### Recover a failed first start

For initialization failures, preserve the entire database, runtime, logs and remaining
password file. In particular, `.intruvia-initializing` means an interrupted or ambiguous
initialization: never delete markers, staging data or schema objects to force a retry.
Follow the bundled [initialization and recovery guide](deployment/compose-initialization-recovery.md);
restore matching backups or, only for a disposable new installation, deliberately use
a new empty database and runtime. Never reset an existing schema.

## Standalone installation

This guide describes one Intruvia process, one PostgreSQL database and a same-host
TLS reverse proxy. Run as a dedicated unprivileged `intruvia` account. Paths and
`intruvia.example.org` are examples; secrets below are placeholders, never usable
credentials. Task 022 records the standalone MVP release review; the Compose bundle
also requires task 023 acceptance and a published image. The [task 021 rehearsal record](verification/021-acceptance.txt)
states exactly what has been exercised locally.

### Prepare the distribution and database

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

### Provision the external runtime while stopped

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

### First start and TLS/WSS

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

### GeoIP, Fail2ban and retention

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

### Consistent backup and restore

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
