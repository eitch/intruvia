# Compose initialization and recovery

First start requires a dedicated, empty PostgreSQL database directly owned by the application role. The role must not
have administrative, replication or row-security-bypass privileges, or membership in privileged roles. Keep all other
database writers stopped during initialization: the emptiness check is a point-in-time check, not a database-wide lock
against unrelated clients.

The runtime directory must be empty and owned by the configured application UID/GID (defaults to `10001:10001`) with
mode `0700`. `INTRUVIA_UID` and `INTRUVIA_GID` in `.env` must be non-zero decimal IDs. The application generates the
initial viewer password in memory, stores only the configured Strolch hash, and displays the password once after
successful initialization. After the database is ready, run `docker compose run --rm --no-deps -T app` attached
to the terminal; it exits successfully after initialization and password output, releasing the runtime lock. Then start
the deployment with `docker compose up -d`. An initialized app start serves normally and never changes the viewer or
redisplays the password. The Docker app logging driver is `none`, so Docker logs cannot recover the output.

`INTRUVIA_PUBLIC_ORIGIN` accepts an exact HTTP or HTTPS origin with an optional port and no credentials, path, query,
fragment or trailing slash. Use `http://localhost` for certificate-free local testing; HTTPS is recommended for exposed
services because HTTP does not encrypt viewer credentials or session cookies. TLS termination is the operator's
responsibility. Startup/provisioning diagnostics are kept in `/var/log/intruvia/startup-failures.log`, backed by the
bundle's `state/app-logs` directory; the log records a stage and safe stack details, never arbitrary exception messages or credentials.
It does not contain the one-time viewer password.

If the output is missed, stop the app and run the packaged `/usr/local/bin/intruvia-reset-viewer-password` command via
`docker compose run --rm --no-deps -T --entrypoint ... app`. It takes the runtime lock, replaces only the existing
viewer hash atomically, and prints the replacement once to the attached console. It fails without changing credentials
if the app still owns the runtime lock or the viewer record is missing or ambiguous. Legacy password files from older
deployments are not read or deleted automatically; protect and remove them manually according to the site's secret
handling policy when no longer needed.

The application holds `.intruvia.lock` for its entire lifetime and offline privilege operations take the same lock. Do
not edit the privilege files or run another writer while either is active. The normal runtime keeps schema creation,
migration and drop disabled; a separate protected temporary runtime is used for the one-time schema bootstrap.

### Change the application UID and GID

For a fresh bundle, set `INTRUVIA_UID` and `INTRUVIA_GID` in `.env` before starting Compose; the default is
`10001:10001`. The one-shot `prepare-state` service sets ownership and mode `0700` on the runtime, app-log and secrets
directory roots only. It never recursively changes existing files. PostgreSQL and proxy identities are independent.

Changing identity on an initialized bundle requires an explicit offline migration. Do not start the app with the new IDs
until the existing app files have the matching numeric owner:

1. Pause ingress, stop the app and every offline privilege-file writer, and make and verify a coordinated database and
   runtime backup. Keep the backup private and do not modify it.
2. Set both non-zero decimal IDs in `.env`, then from the bundle root run `chown` on only the application-owned paths.
   Replace the example numbers with the exact values from `.env`; `chown` preserves file modes:

   ```bash
   INTRUVIA_UID=23456
   INTRUVIA_GID=34567
   sudo chown -R -- "$INTRUVIA_UID:$INTRUVIA_GID" state/runtime state/app-logs secrets
   ```

   Do not change ownership under `state/postgres`, `state/caddy-data` or `state/caddy-config`; those services use
   different identities. On rootless Docker or a host with user-namespace remapping, apply the equivalent ownership in
   the mapped host IDs.
3. Start the database and wait for it to become healthy; this also prepares the three application directory roots.
   Then start the app and proxy and verify readiness, login, offline operations, and that sensitive files remain mode
   `0600` inside mode-`0700` directories:

   ```bash
   docker compose up -d --wait db
   docker compose up -d --wait app caddy
   ```

If the backup, current owners, or host UID mapping are uncertain, stop and resolve that before starting the application.
Changing `.env` alone is not an ownership migration and never triggers recursive ownership changes.

### Migrate an existing named-volume installation

The new bundle uses protected, bundle-relative directories and never discovers or copies an older Compose installation's
named volumes. Do not start it against the empty packaged directories until migration is complete. This migration must be
offline, backed up, and performed with all application and database writers stopped; keep the old volumes intact until
the new installation has been verified.

1. Make and verify a coordinated database/runtime backup as described in the
   [deployment guide](../DEPLOYMENT.md#backup-and-restore). In the old bundle, record the Compose project name and the
   actual volume names used for `/var/lib/postgresql`, `/var/lib/intruvia/runtime`, `/var/log/intruvia`, `/data` and
   `/config`. They are usually `<project>_postgres_data`, `<project>_runtime`, `<project>_app_logs`,
   `<project>_caddy_data` and `<project>_caddy_config`; inspect the old containers and `docker volume ls` rather than
   relying on these defaults.
2. Stop and remove the old containers without removing their volumes:

   ```bash
   docker compose stop app caddy db
   docker compose down
   ```

   Do not add `--volumes` or `-v`. Keep the old bundle directory and its protected configuration.
3. Extract the new archive into a separate protected directory. From that new bundle, carry over the existing public
   origin and database names/credentials into `.env`, but retain the new bundle's versioned `INTRUVIA_IMAGE` reference;
   set `.env` to mode `0600`. Merge any intentional old `Caddyfile` changes into the new bundle's `Caddyfile`. Copy the
   old bundle's `secrets/` contents to the new `secrets/` directory, preserving ownership and modes. For example, with
   `OLD_BUNDLE` set to the absolute old bundle path:

   ```bash
   set -euo pipefail
   OLD_BUNDLE=/absolute/path/to/old/bundle
   docker run --rm --user 0:0 \
     --mount "type=bind,source=$OLD_BUNDLE/secrets,target=/source,readonly" \
     --mount "type=bind,source=$PWD/secrets,target=/destination" \
     --entrypoint sh postgres:18-bookworm -ec 'cp -a /source/. /destination/'
   chmod 0600 .env
   ```

   Do not copy the old database or runtime over a running service.
4. Set the following variables to the **actual** old names. Run these commands from the new bundle root; the packaged
   target directories already exist. The copy container runs as root so `cp -a` preserves numeric ownership, modes and
   timestamps from the stopped volumes:

   ```bash
   set -euo pipefail
   OLD_POSTGRES_VOLUME=oldproject_postgres_data
   OLD_RUNTIME_VOLUME=oldproject_runtime
   OLD_APP_LOGS_VOLUME=oldproject_app_logs
   OLD_CADDY_DATA_VOLUME=oldproject_caddy_data
   OLD_CADDY_CONFIG_VOLUME=oldproject_caddy_config

   copy_volume() {
     docker run --rm --user 0:0 \
       --mount "type=volume,source=$1,target=/source,readonly" \
       --mount "type=bind,source=$PWD/$2,target=/destination" \
       --entrypoint sh postgres:18-bookworm -ec 'cp -a /source/. /destination/'
   }
   copy_volume "$OLD_POSTGRES_VOLUME" state/postgres
   copy_volume "$OLD_RUNTIME_VOLUME" state/runtime
   copy_volume "$OLD_APP_LOGS_VOLUME" state/app-logs
   copy_volume "$OLD_CADDY_DATA_VOLUME" state/caddy-data
   copy_volume "$OLD_CADDY_CONFIG_VOLUME" state/caddy-config
   ```

   Replace every example volume name before running. Stop if a source volume is missing or a copy command fails. Verify
   each source/destination pair's file contents and numeric owners/modes before starting Compose; keep the backup and old
   volumes unchanged.
5. From the new bundle, run `docker compose config --quiet`, start PostgreSQL and wait for its health check, then start
   the app and proxy. Verify application readiness, viewer login, expected history and proxy certificates. The new
   `prepare-state` service changes only the runtime, app-log and secrets directory ownership/mode to the application
   user; it does not recursively change copied files. Keep the previous named volumes until verification and a fresh
   backup are complete. Do not start the old stack again against a second copy of the data.

If the old volume names or data layout are uncertain, stop and restore from the verified backup into a fresh bundle
instead of guessing or deleting data.

### Repair a database credential mismatch

Compose's `db` healthcheck connects to hostname `db` over the private database network using `INTRUVIA_DB_USERNAME` and
`INTRUVIA_DB_PASSWORD`, the same authentication route used by the app. If that check fails, dependent `docker compose up`
startup remains blocked. Commands using `docker compose run --no-deps app` bypass the dependency health gate, but the app
still authenticates to `db` and will fail startup until the mismatch is repaired. PostgreSQL's `POSTGRES_*` values and
the bootstrap use of `INTRUVIA_DB_*` initialize a **new** `state/postgres` directory only; `INTRUVIA_DB_*` still configure app and
healthcheck connections. Editing `INTRUVIA_DB_PASSWORD` does not change the role password in an existing
database directory. Do not remove or recreate it to clear an authentication failure.
Use the Compose file and application image from the same deployment release. Do not mix files or images from different
releases when repairing a credential mismatch.

If the value was changed accidentally, restore the existing password from the protected credential record. To
intentionally set a new application-role password, stop app writers and connect as an authorized PostgreSQL administrator:

```bash
docker compose stop app
docker compose exec db sh -c 'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

At the interactive `psql` prompt, use `\password APPLICATION_ROLE`, replacing `APPLICATION_ROLE` with the configured
`INTRUVIA_DB_USERNAME`. Enter the new password at the prompts; do not put it in a command, shell history, or log. Update
`INTRUVIA_DB_PASSWORD` in the protected `.env` to the same value, then recreate the DB container so its healthcheck
receives the updated environment while retaining `state/postgres`:

```bash
docker compose up -d --force-recreate --wait db
docker compose up -d app caddy
```

Do not change `POSTGRES_ADMIN_PASSWORD` expecting it to rotate the initialized admin role either. If the role cannot be
reconciled safely, keep the database and runtime intact and use the coordinated backup/restore procedure.

### If initialization fails

1. Stop the application and all other database and privilege-file writers. Preserve the entire database, runtime, logs
   and any legacy password file before investigating. Treat them as sensitive. Inspect the persistent app log at
   `/var/log/intruvia/startup-failures.log` for the failed stage and safe stack details. The protected `.intruvia-staging`
   and `.intruvia-bootstrap` directories may also contain diagnostic logs and secrets.
2. If the runtime contains only `.intruvia.lock` and there is no `.intruvia-initializing` marker, correct the reported
   input, permission, connection or empty-database problem before retrying. Do not point initialization at an existing
   nonempty database or delete its contents.
3. If `.intruvia-initializing` exists, including when `.intruvia-initialized` also exists, initialization was
   interrupted or its completion is ambiguous. Do **not** remove markers, staging files, privilege files or database
   objects to force a retry. The schema may be partial even if some tables exist.
4. Recover by restoring a known matching, consistent database and runtime backup using the coordinated restore
   procedure. For a genuinely disposable new installation only, an administrator may instead retain the failed state
   separately and deliberately create a **new** empty database and **new** empty runtime with a new protected initial
   password. Never reset an existing installation automatically.

If initialization reached `.intruvia-initialized` and the in-progress marker is absent but startup output was missed, use
the stopped-app password reset described above. Keep any legacy password file protected and remove it manually when
appropriate. Keep the matching database and runtime together:
restoring either alone can roll back privilege secrets, users or event cursors independently of the other.