# Compose initialization and recovery

First start requires a dedicated, empty PostgreSQL database directly owned by the application role. The role must not
have administrative, replication or row-security-bypass privileges, or membership in privileged roles. Keep all other
database writers stopped during initialization: the emptiness check is a point-in-time check, not a database-wide lock
against unrelated clients.

The runtime directory must be empty and owned by the container user (`10001:10001`) with mode `0700`. Images with
in-memory viewer-password generation need no viewer password file or password-file environment variable: they generate
the initial credential in memory, store only the configured Strolch hash, and display the password once after successful
initialization. Start the app with an attached `docker compose run --rm --no-deps -T app` after the database is ready;
the Docker app logging driver is `none`, so Docker logs cannot recover the output. A later restart neither changes the
viewer nor redisplays the password. The published
`0.0.2` image predates this behavior and must not be used with these file-free instructions.

`INTRUVIA_PUBLIC_ORIGIN` accepts an exact HTTP or HTTPS origin with an optional port and no credentials, path, query,
fragment or trailing slash. Use `http://localhost` for certificate-free local testing; HTTPS is recommended for exposed
services because HTTP does not encrypt viewer credentials or session cookies. TLS termination is the operator's
responsibility. Startup/provisioning diagnostics are kept in `/var/log/intruvia/startup-failures.log` on the persistent
`app_logs` volume; the log records a stage and safe stack details, never arbitrary exception messages or credentials.
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

### Repair a database credential mismatch

Compose's `db` healthcheck connects to hostname `db` over the private database network using `INTRUVIA_DB_USERNAME` and
`INTRUVIA_DB_PASSWORD`, the same authentication route used by the app. If that check fails, dependent `docker compose up`
startup remains blocked. Commands using `docker compose run --no-deps app` bypass the dependency health gate, but the app
still authenticates to `db` and will fail startup until the mismatch is repaired. PostgreSQL's `POSTGRES_*` values and
the bootstrap use of `INTRUVIA_DB_*` initialize a **new** data volume only; `INTRUVIA_DB_*` still configure app and
healthcheck connections. Editing `INTRUVIA_DB_PASSWORD` does not change the role password in an existing
`postgres_data` volume. Do not remove or recreate that volume to clear an authentication failure.
Already extracted bundles and published or cached images are not changed by this source update. The corrected healthcheck
requires the updated Compose file, and the actionable app diagnostic requires an image built with this code.

If the value was changed accidentally, restore the existing password from the protected credential record. To
intentionally set a new application-role password, stop app writers and connect as an authorized PostgreSQL administrator:

```bash
docker compose stop app
docker compose exec db sh -c 'exec psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

At the interactive `psql` prompt, use `\password APPLICATION_ROLE`, replacing `APPLICATION_ROLE` with the configured
`INTRUVIA_DB_USERNAME`. Enter the new password at the prompts; do not put it in a command, shell history, or log. Update
`INTRUVIA_DB_PASSWORD` in the protected `.env` to the same value, then recreate the DB container so its healthcheck
receives the updated environment while retaining the existing volume:

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