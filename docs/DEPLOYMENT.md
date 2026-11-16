# Intruvia installation and recovery

This guide is for administrators installing, operating, and recovering Intruvia.
Developers preparing a release should use the [developer deployment guide](DEVELOPER_DEPLOYMENT.md).

**Choose your installation path:**

- **Administrator:** [install a supplied Compose bundle](#compose-deployment); no Java, Maven or PostgreSQL installation is needed on the host.
  - For later changes, jump to [public-origin changes](#change-the-public-origin), [offline users and PATs](#offline-users-and-pats), [backup and restore](#backup-and-restore), or [failed-start recovery](#recover-a-failed-first-start).
- **Standalone operator:** continue to [standalone installation](#standalone-installation).

## Compose deployment

This is the administrator's container path. Use Docker Engine and Compose on a trusted
host. Get the exact bundle version and GitHub release tag from the release owner. The
checksum detects transfer errors; it does not authenticate the publisher. Release scripts
target `repo.strolch.li/intruvia` and the root POM version, but registry publication,
image pull and public-CA validation are not assumed to have passed.

### Download, verify and unpack the bundle

Replace both values with those supplied by the release owner. Run this in a directory
where the new download directory does not already exist:

```bash
(
  set -eu
  VERSION='<bundle-version>'
  RELEASE_TAG='<GitHub-release-tag>'
  ARCHIVE="intruvia-deployment-${VERSION}.tar.gz"
  RELEASE_URL="https://github.com/eitch/intruvia/releases/download/${RELEASE_TAG}"

  mkdir -m 0700 "intruvia-download-${VERSION}"
  cd "intruvia-download-${VERSION}"
  curl -fL "$RELEASE_URL/$ARCHIVE" -o "$ARCHIVE"
  curl -fL "$RELEASE_URL/$ARCHIVE.sha256" -o "$ARCHIVE.sha256"
  sha256sum -c "$ARCHIVE.sha256"
  mkdir -m 0700 bundle
  tar -xpzf "$ARCHIVE" -C bundle
  chmod 0700 bundle
  cd bundle
)
```

Preserving the archive modes (`-p`) keeps the read-only templates and database
initializer readable by the container users even if your shell uses `umask 077`. Do not
continue if either download or checksum verification fails.

### Configure and start

Do these five steps in order:

1. Generate the two database passwords in the supplied `.env`. This command refuses to
   replace non-empty passwords, generates both values before changing the file, and
   atomically replaces `.env` with a mode-`0600` file. It requires Python 3 and OpenSSL.
   If it fails, `.env` contents are unchanged; fix the reported issue before retrying.

   ```bash
   python3 - <<'PY'
   import os
   from pathlib import Path
   import re
   import subprocess
   import tempfile

   path = Path('.env')
   if path.is_symlink() or not path.is_file():
       raise SystemExit('.env must be a regular file')
   lines = path.read_text().splitlines(keepends=True)
   keys = ('POSTGRES_ADMIN_PASSWORD', 'INTRUVIA_DB_PASSWORD')
   positions = {}
   for index, line in enumerate(lines):
       key, separator, value = line.rstrip('\r\n').partition('=')
       if separator and key in keys:
           if key in positions or value:
               raise SystemExit(f'{key} must occur once and be blank; refusing to overwrite')
           positions[key] = index
   if set(positions) != set(keys):
       raise SystemExit('Both blank database password settings must exist in .env')

   passwords = {}
   for key in keys:
       result = subprocess.run(
           ['openssl', 'rand', '-hex', '32'], check=True, capture_output=True, text=True
       )
       value = result.stdout.strip()
       if not re.fullmatch(r'[0-9a-f]{64}', value):
           raise SystemExit('OpenSSL returned an invalid random password')
       passwords[key] = value
   if passwords[keys[0]] == passwords[keys[1]]:
       raise SystemExit('OpenSSL returned duplicate passwords; run the command again')

   for key, value in passwords.items():
       index = positions[key]
       ending = '\r\n' if lines[index].endswith('\r\n') else '\n' if lines[index].endswith('\n') else ''
       lines[index] = f'{key}={value}{ending}'
   descriptor, temporary = tempfile.mkstemp(prefix='.env.', dir=path.parent)
   try:
       with os.fdopen(descriptor, 'w') as output:
           os.fchmod(output.fileno(), 0o600)
           output.writelines(lines)
           output.flush()
           os.fsync(output.fileno())
       os.replace(temporary, path)
   except BaseException:
       try:
           os.unlink(temporary)
       except FileNotFoundError:
           pass
       raise
   PY
   ```

2. Set `INTRUVIA_PUBLIC_ORIGIN` to the exact public HTTPS origin (no path or trailing
   slash) using a protected editor. The supplied `.env` already contains the versioned
   `INTRUVIA_IMAGE` reference. Then check the file permissions:

   ```bash
   sudoedit .env && chmod 0600 .env
   ```

   Generated passwords are distinct 64-character hexadecimal values and meet the
   Compose password requirements. Database and role names must start with a lowercase
   letter, use only lowercase letters, digits, or underscores, be at most 63 characters,
   and not start with `pg_`; roles must differ and the database cannot be `postgres`,
   `template0`, or `template1`. Treat `.env` and container environments as sensitive:
   do not print resolved Compose configuration or expose Docker access to untrusted
  users. Ensure shell environment variables do not override `.env` values.

3. Create the initial `viewer` password file in `secrets/`. Run the following commands
   separately from the bundle directory. The first command creates an empty file only
   when it does not already exist; if it fails, stop and do not replace the existing
   file. Then enter a strong password without trailing whitespace in the protected
   editor. Never put it in command arguments, environment variables, shell history, or
   logs.

   ```bash
   (umask 077; set -C; : > secrets/viewer-password)
   ```

   ```bash
   sudoedit secrets/viewer-password
   ```

   Set the directory and file to container UID/GID `10001:10001`; the directory must
   be mode `0700` and the regular file mode `0600` (or `0400`). Run these only after
   the password file exists and has been saved:

   ```bash
   sudo chown 10001:10001 secrets secrets/viewer-password &&
   sudo chmod 0700 secrets &&
   sudo chmod 0600 secrets/viewer-password
   ```

   With rootless Docker or user namespaces, use the host IDs mapped to container
   `10001:10001`. The mounted directory is read-write so initialization can remove
   the password file.
4. Configure DNS and firewall for the exact HTTPS origin. You own DNS, certificate
   acquisition/renewal, proxy configuration, and verification. Only Caddy publishes
   ports 80 and 443; app and PostgreSQL publish none. Trust the Docker host and proxy/
   database networks: the app binds `0.0.0.0` inside its network. Restrict Docker and
   network access; do not attach untrusted containers. The database network is internal.
   Caddy blocks public health paths; check readiness from the trusted network. Protect
   proxy state/TLS keys and review proxy logs.
5. If required, authenticate to the registry using the release owner's instructions.
   Then pull and start services:

```bash
   docker compose pull && docker compose up -d
```

   PostgreSQL readiness gates app startup; app readiness gates Caddy. On first start,
   Intruvia initializes the empty database/runtime, creates the enabled `viewer`, and
   removes the password file. Sign in over HTTPS and check WSS. No producer mapping or
   token operator is provisioned by default. Editing `.env` or adding another password
   file does not change existing users/passwords.

### After startup

Named volumes retain `postgres_data`, `runtime`, `app_logs`, `caddy_data` and
`caddy_config` across restarts and image replacement. Protect Docker volumes and the
host, including logs, runtime privilege files and proxy certificate state. Do not use
`docker compose down --volumes` on an installation you intend to retain.

### Operate the Compose deployment

#### Change the public origin

Changing `.env` alone does **not** update the persistent viewer origin. Keep ingress
paused until the new origin is verified.

1. Stop the app and confirm no app or offline privilege writer is running:

   ```bash
   docker compose stop app
   ```

2. With a trusted maintenance environment, set the same exact HTTPS origin (no path or
   trailing slash) in both files:

   ```properties
   # /var/lib/intruvia/runtime/config/viewer.properties
   origin=https://intruvia.example.org

   # .env
   INTRUVIA_PUBLIC_ORIGIN=https://intruvia.example.org
   ```

   Preserve runtime file owner `10001:10001`, mode `0600`, private directory modes,
   and `.env` mode `0600`.
3. Coordinate DNS, certificates, and proxy configuration. The browser Origin and the
   configured value must match exactly, including any port.
4. Recreate services and verify HTTPS, viewer login, and WSS before reopening ingress:

   ```bash
   docker compose up -d
   ```

This does not rotate existing users or credentials.

#### Offline users and PATs

Before offline commands or runtime edits:

1. Pause ingress, stop the app, and confirm no app, editor, or privilege writer uses
   the runtime. The container locks the runtime for offline commands; manual edits
   also require all writers stopped.
2. Back up the matching database and runtime.
3. Use separate owner-only password files in `secrets/`, owned by `10001:10001`.
   Never put passwords in command arguments or environment variables. Output files
   must not already exist.

Generate a password hash for a new operator or producer:

```bash
docker compose run --rm --no-deps app offline hash /run/secrets/new-password /run/secrets/new-password.hash
```

The initial `viewer` cannot administer tokens. Follow the [standalone role/user
procedure](#provision-the-external-runtime-while-stopped), adapting paths to the runtime
volume. While stopped:

1. Provision a trusted `token-admin` and one ingestion-only user per producer.
2. Preserve the existing viewer, SYSTEM agent, generated secrets, and other users;
   never copy fresh templates over them. Add the `Producer` and `TokenOperator` roles
   and use the generated hashes as password attributes.
3. Set stable username-to-instance mappings in `machine-identities.conf`, for example:

   ```text
   producer-a=server-a
   ```

Keep the runtime owned by UID/GID `10001:10001`, with owner-only files and private
directories. The source-mode tool is not in the JAR-only Compose image; use the
container's offline commands. The source tool and architecture files may only be in
the source/Java distribution, not in the bundle.

Create a separate protected `secrets/operator-password` for the operator. Replace the
example UTC validity dates with the current validity window in your policy:

```bash
docker compose run --rm --no-deps app offline issue token-admin /run/secrets/operator-password \
  producer-a initial 2026-11-12T00:00:00Z 2026-12-12T00:00:00Z /run/secrets/producer.pat
```

Issuing writes a one-time `tokenId:tokenValue` to a new mode-0600 file and persists a
scoped `event:ingest` token. Transfer the PAT securely to the reporting host as a
root-owned mode-0600 file; never expose its contents in commands or logs. Revoke by
the old token ID (before `:`), never by the secret:

```bash
docker compose run --rm --no-deps app offline revoke token-admin /run/secrets/operator-password OLD_TOKEN_ID
```

For rotation, issue a second token for the same owner, restart, replace the producer
file securely, and verify delivery. Stop the app, revoke the old ID, then restart.
Remove temporary password, hash, and PAT files when no longer needed. A failed issue
may persist an orphan token before output fails; inspect/revoke it with trusted Strolch
tooling before retrying. Never erase privilege files.

#### Backup and restore

##### Create a coordinated backup

1. Pause producer ingress, stop the app gracefully, and verify it has exited. Stop all
   other database and privilege-file writers.
2. Set `umask 077` and use a private, encrypted backup location.
3. Back up the whole application database and matching runtime, not selected tables.
   This example omits object ownership and ACLs; PostgreSQL cluster roles/settings are
   also excluded. Separately back up site-specific roles, grants, ownership, and
   database settings. If the app role cannot read every object, use a privileged backup
   role and verify the restore.

```bash
umask 077
docker compose exec -T db sh -c 'export PGPASSWORD="$INTRUVIA_DB_PASSWORD"; \
  exec pg_dump -h 127.0.0.1 -U "$INTRUVIA_DB_USERNAME" -d "$POSTGRES_DB" \
  --format=custom --no-owner --no-acl' > /protected/backup/database.dump
docker compose run --rm --no-deps -T --entrypoint tar app \
  -C /var/lib/intruvia -czf - runtime > /protected/backup/runtime.tar.gz
sha256sum /protected/backup/database.dump /protected/backup/runtime.tar.gz > /protected/backup/SHA256SUMS
```

Check every exit status. Keep `.env`, proxy settings/state, and bundle/image identity
separately in the encrypted backup set. Record time, release/JAR hashes, and
`snapshot cursor/minAfter`. Protect IP history, password/PAT hashes, and privilege
secrets. Back up or reprovision licensed MMDB files. Restart the app and reopen ingress
only after readiness.

##### Restore into a fresh project

Restore into a **fresh, isolated** Compose project with new empty database and runtime
volumes, matching release/bundle, and protected `.env`. Keep the old installation and
all writers stopped. Verify checksums, then start only the new `db`:

```bash
docker compose up -d db
```

Wait for the database health check. Confirm the new runtime volume is empty and owned
by `10001:10001`. Restore the whole dump as the application role into its empty database,
then extract the matching runtime. Run each command separately; stop if one fails:

```bash
docker compose exec -T db sh -c 'export PGPASSWORD="$INTRUVIA_DB_PASSWORD"; \
  exec pg_restore -h 127.0.0.1 -U "$INTRUVIA_DB_USERNAME" -d "$POSTGRES_DB" \
  --exit-on-error --single-transaction --no-owner --no-acl' < /protected/backup/database.dump
docker compose run --rm --no-deps -T --entrypoint tar app \
  -C /var/lib/intruvia/runtime --strip-components=1 --no-same-owner -xzf - < /protected/backup/runtime.tar.gz
```

Check both exit statuses. Preserve private modes and initialization markers; never merge
with an existing runtime or database. Restore proxy/configuration state as needed and
match database credentials and public origin to the runtime. Then start one app and
verify readiness, login, snapshot IDs/cursors, and identical-retry behavior. Reconcile
revocations before reopening ingress.

An older backup may lose acknowledged events/receipts, resurrect revoked PATs, and
reuse sequence numbers. Rotate potentially resurrected credentials and require viewers
to reload and sign in; even a cursor at or below the restored head may represent a
different history. Backups cannot recover later events, and Fail2ban has no persistent
notification queue.

#### Rotate database and application credentials

Bootstrap values apply only to a new PostgreSQL data directory; editing `.env` does
**not** rotate existing roles. During backed-up maintenance:

1. Change each role password through protected PostgreSQL administration; `psql`
   prompts for the new value:

   ```text
   \password ADMIN_ROLE
   \password APPLICATION_ROLE
   ```

2. Update the matching `.env` values with distinct passwords within the initialization
   constraints.
3. Recreate services and verify both credentials and readiness:

   ```bash
   docker compose up -d
   ```

Manage viewer passwords, users, and PATs through protected Strolch procedures, not
bootstrap environment values.

#### Upgrade the application image

For an image upgrade:

1. Take and rehearse a matching backup; review release notes and schema compatibility.
2. Set `INTRUVIA_IMAGE` to the supplied versioned reference in `.env`.
3. Pull and recreate:

   ```bash
   docker compose pull
   docker compose up -d
   ```

Preserve runtime and volumes; never overwrite runtime with templates. Perform
migrations only as documented maintenance. Roll back image, database, and runtime as
a matching set; do not run old code against an arbitrarily migrated schema.

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

Install and configure these host prerequisites using your OS procedures:

- Java 25; PostgreSQL 18 server/client tools; nginx with TLS support.
- Python 3 and Fail2ban on reporting hosts; Maven only on the build machine.
- A certificate for the real DNS name; keep its private key readable only by nginx.
- A private PostgreSQL connection using a dedicated role/database; use TLS with server
  verification for a remote database.

Create the service account and install host services using your OS procedures; this
guide does not install them. The browser needs no Node build or internet map provider.
The local rehearsal uses isolated containers.

From the source root, with Java 25 and Maven on PATH:

```bash
scripts/verify-postgresql.sh -o -Dstrolch.version=2.8.0-SNAPSHOT
```

Docker is needed for that disposable test database. For dependency cache recovery,
follow [README local Strolch instructions](../README.md#locally-installed-strolch-artifacts).
Archive the complete `intruvia-app/target/intruvia/` directory and SHA-256 manifest;
keep its JAR, `lib/`, runtime template, integrations, and `docs/` together. Local
snapshot hashes identify binaries but do not establish historical timestamped provenance.

For a new installation, copy the release and runtime template separately:

```bash
sudo install -d -o root -g root -m 0755 /opt/intruvia
sudo cp -a intruvia-app/target/intruvia/. /opt/intruvia/
sudo install -d -o intruvia -g intruvia -m 0700 /var/lib/intruvia/runtime
sudo cp -a /opt/intruvia/runtime/. /var/lib/intruvia/runtime/
sudo chown -R intruvia:intruvia /var/lib/intruvia
```

Keep `/var/lib/intruvia` owned by the service account with mode `0700`; release files
can be read-only.

As a PostgreSQL administrator, create a LOGIN role without superuser, CREATEDB, or
CREATEROLE; set its password interactively. Create the database owned by that role.
Restrict `pg_hba.conf` to the service host and role/database; do not grant PUBLIC access
to a sensitive database.

```text
CREATE ROLE intruvia LOGIN;
\password intruvia
CREATE DATABASE intruvia OWNER intruvia;
```

Create `/etc/intruvia/database.env` as root-owned mode `0600`, then edit it:

```bash
sudo install -d -o root -g root -m 0755 /etc/intruvia
sudo install -o root -g root -m 0600 /dev/null /etc/intruvia/database.env
sudoedit /etc/intruvia/database.env
```

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

On the dedicated empty database, set only `allowSchemaCreation` to `true` in
`runtime/config/StrolchConfiguration.xml`. Keep schema drop, data initialization, and
schema migration disabled:

```xml
<allowSchemaCreation>true</allowSchemaCreation>
<allowSchemaMigration>false</allowSchemaMigration>
<allowSchemaDrop>false</allowSchemaDrop>
<allowDataInitOnSchemaCreate>false</allowDataInitOnSchemaCreate>
```

Start as the service user and verify readiness. Then stop cleanly, set
`allowSchemaCreation` back to `false`, start again, and verify readiness:

```bash
sudo systemctl start intruvia
curl --fail http://127.0.0.1:8080/health/ready
sudo systemctl stop intruvia
# Set allowSchemaCreation to false in StrolchConfiguration.xml before starting again.
sudo systemctl start intruvia
curl --fail http://127.0.0.1:8080/health/ready
```

Strolch creates its schema and Intruvia migration `0.0.1`; no manual model import is
needed.

Before enabling the [systemd example](deployment/intruvia.service.example):

- Adjust Java, release, and runtime paths; it sets the umask, restart policy, and
  120-second shutdown budget.
- Configure bounded journal retention. The application rotates logs at 10 MB, five
  days, or 50 MB total; do not enable framework SQL/request logging in production.

After the first-start checks pass, enable the unit:

```bash
sudo systemctl enable intruvia
```

Adapt [nginx.conf.example](deployment/nginx.conf.example) for the hostname, TLS files,
and loopback app port. Jetty binds `127.0.0.1`, so nginx must share its host/network
namespace. Permit inbound 443 only; keep 8080 and PostgreSQL private. Serve `/` and
`/api/v1` on the configured origin (no URL prefix).

The proxy preserves Origin/Cookie/Authorization, forwards WSS upgrade headers, disables
buffering, and allows 75-second heartbeat traffic. Review [nginx WebSocket proxying](https://nginx.org/en/docs/http/websocket.html).
Health is loopback-only. Proxy 413/429/502 responses may differ from the app's JSON
contract; proxy limits supplement, not replace, app rate limits. Review local logging
overrides: the example suppresses access logs and limits error logs to avoid secrets.

Test and reload nginx:

```bash
sudo nginx -t -c /absolute/config
sudo systemctl reload nginx
```

Verify the deployment:

1. Sign in at the HTTPS origin. Confirm the Secure/HttpOnly/SameSite=Strict cookie
   uses path `/api/v1`; check WSS returns a 101 upgrade and `hello` frame.
2. With a protected client that reads the PAT file, POST one event (201), then repeat
   the identical body (200 duplicate, same ID/sequence). Never put Authorization in
   a shell command.
3. Confirm the browser contains no machine tokens and stores no session credentials
   in `localStorage`.

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
   umask 077
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
