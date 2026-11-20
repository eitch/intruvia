# Intruvia deployment and recovery

Follow the [Compose deployment](#compose-deployment) steps in order, then
[connect a Fail2ban host](#connect-a-fail2ban-host). The standalone procedure is an
alternative to Compose. Operational exceptions and
[troubleshooting and recovery](#troubleshooting-and-recovery) are at the end.

## Compose deployment

Set `INTRUVIA_VERSION` to the release version to deploy. Run these commands in the
directory where you want a new `intruvia/` folder; the archive includes the matching
Compose configuration and application image reference.

```bash
export INTRUVIA_VERSION=XXX
wget https://github.com/eitch/intruvia/releases/download/v$INTRUVIA_VERSION/intruvia-deployment-$INTRUVIA_VERSION.tar.gz
wget https://github.com/eitch/intruvia/releases/download/v$INTRUVIA_VERSION/intruvia-deployment-$INTRUVIA_VERSION.tar.gz.sha256
sha256sum -c "intruvia-deployment-$INTRUVIA_VERSION.tar.gz.sha256"

tar -xpzf intruvia-deployment-$INTRUVIA_VERSION.tar.gz --one-top-level=intruvia
chmod 0700 intruvia
cd intruvia

# Run only once for a fresh bundle; OpenSSL is included with Git for Windows and standard on macOS/Linux.
sed -i "s/POSTGRES_ADMIN_PASSWORD=/POSTGRES_ADMIN_PASSWORD=$(openssl rand -hex 16)/" .env
sed -i "s/INTRUVIA_DB_PASSWORD=/INTRUVIA_DB_PASSWORD=$(openssl rand -hex 16)/" .env
```

The two `sed` commands are for a fresh bundle whose database password fields are empty;
do not rerun them or use them on an existing deployment. Keep `.env` private. Do not
extract over existing data; see [backup and restore](#backup-and-restore) before replacing
or migrating a deployment.

Edit the existing `INTRUVIA_PUBLIC_ORIGIN=` line in `.env` to the exact origin, such as
`https://intruvia.example.org` (public service) or `http://localhost` (local use). Use no
credentials, path, trailing slash, query or fragment. For public HTTPS, point DNS at this
host and allow inbound ports 80 and 443 for the bundled proxy. Never expose plain HTTP to
an untrusted network. Protect the terminal and `.env`; do not print resolved Compose
configuration or grant Docker access to unauthorized users. Then pull the image paired
with this release and prepare the database:

```bash
vi .env
chmod 0600 .env
docker compose pull
docker compose up -d --wait db
```

Run the first start attached to the terminal. It initializes the empty runtime and
database, prints the initial `admin` password once, and exits successfully; save that
password in a protected password manager before continuing. Docker does not retain app
stdout/stderr, so `docker compose logs` cannot recover the password. If this command
fails, or its output is missed, use the [troubleshooting and recovery](#troubleshooting-and-recovery)
instructions rather than deleting runtime or database state.

```bash
docker compose run --rm --no-deps -T app
```

Start the full deployment:

```bash
docker compose up -d
```

Sign in at the configured origin with the saved administrator password. Only the proxy
publishes ports 80 and 443; the app and PostgreSQL stay on private Docker networks. Use
**Manage access** to create viewer accounts, add machines and stable instance IDs, issue
scoped ingestion tokens, and rotate or revoke them. Each new token is displayed once.
See [troubleshooting and recovery](#troubleshooting-and-recovery) before changing
credentials on an initialized database.

### Connect a Fail2ban host

After the first administrator login, use **Manage access** to create a machine for each
reporting server and assign its stable instance ID. Copy the displayed installation
command to the Fail2ban host, run it in Bash, and paste the one-time token when prompted.
Enter the already enabled jail name and confirm its standard firewall action. The host
needs Bash, curl, Python 3, sudo and an HTTPS connection to Intruvia; the token must be
transferred securely and never entered into a command argument, URL or shell history.

The command downloads the installer directly from Intruvia, protects the token file,
preserves the firewall action, validates and reloads Fail2ban. It refuses existing files
and nonstandard configurations. For the command and custom-action or dedicated-user
alternatives, see the [Fail2ban integration guide](../integrations/fail2ban/README.md).
Notifications are best-effort and have no offline queue.

#### Test ingestion connectivity

After creating the machine in **Manage access**, run this Bash example on a host with
Python 3 and curl. Replace the example origin with the exact configured public origin
(no path prefix or trailing slash). Paste the machine's one-time `tokenId:tokenValue`
when prompted; it is read silently and passed to curl through its config input, not a
command argument or shell history. Keep shell tracing disabled, and do not use `curl -k`:
curl must verify the server's TLS certificate.

```bash
(
  set +x
  INTRUVIA_ORIGIN=https://intruvia.example.org
  read -r -s -p 'Machine token (tokenId:tokenValue): ' INTRUVIA_TOKEN
  printf '\n'
  EVENT_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
  OCCURRED_AT=$(date -u +'%Y-%m-%dT%H:%M:%SZ')
  PAYLOAD=$(printf '{"schemaVersion":1,"eventId":"%s","occurredAt":"%s","action":"ban","ip":"203.0.113.42","jail":"intruvia-connectivity-test"}' "$EVENT_ID" "$OCCURRED_AT")
  printf 'header = "Authorization: Bearer %s"\n' "$INTRUVIA_TOKEN" |
    curl --config - --silent --show-error --fail-with-body \
      --header 'Content-Type: application/json' \
      --data-binary "$PAYLOAD" \
      --write-out '\nHTTP %{http_code}\n' \
      "$INTRUVIA_ORIGIN/api/v1/fail2ban/events"
)
```

A successful new event returns `201` and its event ID and sequence. Each run generates a
new UUID, so it creates another persistent event that may appear in the viewer. The
reserved `203.0.113.42` documentation address should not produce a real map location.
Intruvia attributes the event to the machine ID mapped to the token, not to an identity
in the request. Keep the host clock synchronized: timestamps more than five minutes in
the future or seven days old are rejected.

### Configure the MaxMind GeoIP database

Compose does not include a database. Obtain your own licensed GeoLite2 City or GeoIP2
City MMDB. After first start, stop the app and place it at
`state/runtime/geoip/GeoLite2-City.mmdb` on the host. Ensure the directory and file are
accessible to app UID `10001`; the app needs write access to the directory for staged
updates. In `state/runtime/config/StrolchConfiguration.xml`, set the `GeoIp` component's
`databasePath` to its container path and restart the app:

```xml
<databasePath>/var/lib/intruvia/runtime/geoip/GeoLite2-City.mmdb</databasePath>
```

Without a usable database, the app remains ready and accepts events, but GeoIP enrichment
is unavailable. For later updates, follow the [staged update procedure](architecture/008-geoip-replacement.md);
never overwrite the active database in place.

### Persistent data

The bundle includes these persistent, bundle-relative directories before first start:
`state/postgres`, `state/runtime`, `state/app-logs`, `state/caddy-data` and
`state/caddy-config`. Compose uses bind mounts only; it does not create Docker named
volumes. Keep the whole bundle directory on protected storage and include these paths
in backups. The one-shot `prepare-state` service sets `state/runtime`, `state/app-logs`
and `secrets/` to owner `10001:10001` and mode `0700` before PostgreSQL starts; it
changes only the directories themselves, not their contents. PostgreSQL prepares its
own data directory. Do not move or delete the bundle while services use it.

### Operate the Compose deployment

#### Change the public origin

Changing `.env` alone does **not** update the persistent viewer origin. Keep ingress
paused until the new origin is verified.

1. Stop the app and confirm no app or offline privilege writer is running:

   ```bash
   docker compose stop app
   ```

2. While the app is stopped, set the same exact HTTP or HTTPS origin (no credentials,
   path, trailing slash, query or fragment) in both files. Use HTTPS for exposed services;
   HTTP remains suitable for certificate-free local access only:

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

#### Manage viewer users, machines, and tokens

For fresh installations, use **Manage access** in the browser. Create viewer accounts,
set or reset their passwords, enable or disable accounts, and remove accounts that are
no longer needed. Add each machine with a stable, human-readable machine ID; by default
it becomes both the unique Strolch username and the instance ID, while the token name uses
the first 64 characters. Optional advanced overrides let you change those values
independently. Keep the machine ID and instance mapping stable: instance IDs attribute
events and participate in retry deduplication. The application creates an ingestion-only
Strolch user and issues a token limited to `event:ingest`. The mapping is saved
immediately, so no runtime file edit or restart is required.

Tokens can be valid for 1–3650 days. Enter `0` for practical non-expiry: Strolch requires
an expiry, so Intruvia stores the finite sentinel `9999-12-31 23:59:59 UTC`, not a truly
infinite lifetime. Long-lived tokens increase exposure if a host is compromised and
remain revocable; securely transfer each newly displayed token, and revoke old tokens
after a replacement is installed. Plaintext PAT values are shown only once and are never
included in the machine list.

The browser administration endpoints require the initial administrator session and
the same exact-origin/CSRF checks as other session-changing requests. Viewer-only
accounts cannot manage users, machines, or tokens. The `admin` account created on a
fresh install also has viewer access, so it can immediately sign in to the map.

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

Restore into a **fresh, isolated** Compose project with empty `state/postgres` and
`state/runtime` directories, matching release/bundle, and protected `.env`. Keep the old
installation and all writers stopped. Verify checksums, then start only the new `db`:

```bash
docker compose up -d db
```

Wait for the database health check. Confirm the new `state/runtime` directory is empty
and owned by `10001:10001`. Restore the whole dump as the application role into its empty
database, then extract the matching runtime. Run each command separately; stop if one fails:

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

Manage viewer passwords, users, machines, and PATs through the browser administration
panel or a protected offline Strolch procedure, not bootstrap environment values.

#### Optional GeoIP and Fail2ban

For optional GeoIP, provide a licensed MMDB through a protected readable mount and
configure the runtime; use the staged update procedure in the source/Java distribution.
Without one, GeoIP remains degraded but ingestion is ready. Install the bundled
`integrations/fail2ban/` helper on each reporting host as an additive action; preserve
the existing firewall action. Delivery is best effort (three attempts within 15 seconds,
no persistent queue). Review the packaged integration guide before use.

## Standalone deployment (advanced)

Use this path to run one Intruvia process with PostgreSQL and a same-host TLS reverse
proxy, without Compose. Run it as a dedicated unprivileged `intruvia` account.
Paths and `intruvia.example.org` are examples; secrets below are placeholders, never usable
credentials. For the standard first-start path, use [Compose deployment](#compose-deployment).

### Prepare the distribution and database

Install and configure these host prerequisites using your OS procedures:

- Java 25; PostgreSQL 18 server/client tools; nginx with TLS support.
- Python 3 and Fail2ban on reporting hosts; Maven only on the build machine.
- A certificate for the real DNS name; keep its private key readable only by nginx.
- A private PostgreSQL connection using a dedicated role/database; use TLS with server
  verification for a remote database.

Create the service account and install host services using your OS procedures; this
guide does not install them. The browser needs no Node build or internet map provider.
Build the application distribution from the project root with JDK 25+ and Maven 3.10+:

```bash
mvn -B clean package
```

Copy the complete `intruvia-app/target/intruvia/` directory to the host; keep its JAR,
`lib/`, runtime template, integrations and documentation together. On the host, create
the service account and install the distribution into a protected application directory.
Use an authenticated transfer and verify the transferred files before starting services.

For a new standalone deployment, prepare the build output and runtime template:

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
vi /etc/intruvia/database.env
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
agent or operator roles to producers. The token operator is an offline credential
administrator, never a browser user or PAT owner for ingestion.

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
orphan through authorized Strolch tooling before retrying. Never erase privilege
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
  producer-a initial REPLACE_WITH_START_UTC REPLACE_WITH_EXPIRY_UTC /protected/producer.pat
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

- Adjust Java, application, and runtime paths; it sets the umask, restart policy, and
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

## Troubleshooting and recovery

| Symptom | Check / action |
|---|---|
| Startup fails | Inspect `/var/log/intruvia/startup-failures.log` in `state/app-logs` for the failed stage and safe stack details; verify HTTP/HTTPS origin syntax, required XML/privilege settings, DB health and schema flags without deleting runtime or database |
| Ready 503 | DB network/authentication, finite pool acquisition/validation budget; retry a busy probe with a monitor timeout above 35 seconds for defaults |
| Viewer 403 / WebSocket refused | Exact configured HTTP/HTTPS origin including port, Origin/CSRF headers, Viewer role, cookie Secure flag for HTTPS, proxy upgrade headers; PATs cannot authenticate viewers |
| Ingestion 401 / 403 | PAT validity/owner enabled/revocation, exact event:ingest scope, stable mapping; viewer cookies never ingest |
| 409 | Same producer UUID used with changed normalized payload; retries must reuse exact bytes |
| 429 | Per-instance/global/proxy rate limit or receipt_capacity; honor Retry-After; do not delete unexpired receipts |
| No mapped point | Stored geo status, MMDB permissions/City edition/age, non-public or missing IP; never invent coordinates |
| Stream disconnect / gap | Session expiry, queue/deadline, retention or restored head; reauthenticate/resnapshot and inspect operator diagnostics |
| No notifications | Confirm additive action, private token file, HTTPS trust and helper logs; no replay queue exists |

### Inspect startup diagnostics

Startup and first-start provisioning failures are written to the protected persistent
file `/var/log/intruvia/startup-failures.log` in `state/app-logs`. It records the failed
stage, safe diagnostic details and stack locations; it omits arbitrary exception messages
and credentials. Docker's `none` logging driver discards app stdout/stderr. Read the log
without starting the app or its dependencies:

```bash
docker compose run --rm --no-deps -T --entrypoint sh app -c \
  'cat /var/log/intruvia/startup-failures.log'
```

Keep the database and runtime intact while investigating. In particular,
`.intruvia-initializing` means initialization was interrupted or is ambiguous; never
delete markers, staging data or schema objects to force a retry. Follow the bundled
[initialization and recovery guide](deployment/compose-initialization-recovery.md),
restore a matching database/runtime backup, or deliberately create new empty state only
for a disposable new deployment.

### Recover a missed initial administrator password

The initial `admin` password cannot be recovered from the initialized runtime or Docker
logs. Stop the app before resetting it; the reset command takes the runtime lock and
fails without changing credentials if the app still owns it. Protect the one-time output:

```bash
docker compose stop app
docker compose run --rm --no-deps -T --entrypoint /usr/local/bin/intruvia-reset-viewer-password app
docker compose up -d app caddy
```

The reset tool atomically replaces the initial login account's password hash and prints
the replacement once. The previous password stops working. A missing or ambiguous account
is rejected; do not edit or delete the runtime marker to bypass that failure.

### Repair database credentials

Bootstrap values apply only to a new PostgreSQL data directory; editing `.env` does not
rotate initialized roles. Preserve `state/postgres` and use the bundled
[database credential recovery procedure](deployment/compose-initialization-recovery.md#repair-a-database-credential-mismatch).

### Offline credential recovery (advanced)

Use offline commands only for break-glass recovery or planned maintenance; routine user,
machine, mapping and token changes belong in **Manage access**. Pause ingress, stop the
app, back up the matching database and runtime, and use an owner-only password file in
`secrets/` owned by `10001:10001`. Never put passwords in command arguments or environment
variables. Do not edit initialized privilege files; the initial administrator has the
`TokenOperator` role.

With a protected password file for an enabled token operator, issue a scoped token to a
new protected file or revoke it by token ID (the portion before `:`):

```bash
docker compose run --rm --no-deps app offline issue admin /run/secrets/admin-password \
  producer-a fail2ban REPLACE_WITH_START_UTC REPLACE_WITH_EXPIRY_UTC /run/secrets/producer.pat
docker compose run --rm --no-deps app offline revoke admin /run/secrets/admin-password OLD_TOKEN_ID
```

Replace the UTC values with dates required by your policy. The one-time `tokenId:tokenValue`
must be transferred securely and never exposed in commands or logs. A failed issue may
persist an orphan token before output fails; inspect/revoke it through authorized Strolch
tooling before retrying. Offline token commands do not configure a machine mapping; use
the UI for that. Never copy fresh templates over an initialized runtime or erase
privilege files.
