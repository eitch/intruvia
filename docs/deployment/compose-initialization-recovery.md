# Compose initialization and recovery

First start requires a dedicated, empty PostgreSQL database directly owned by the application role. The role must not
have administrative, replication or row-security-bypass privileges, or membership in privileged roles. Keep all other
database writers stopped during initialization: the emptiness check is a point-in-time check, not a database-wide lock
against unrelated clients.

The runtime directory must be empty and owned by the container user (`10001:10001`) with mode `0700`. New images
implementing task 025 need no viewer password file or password-file environment variable: they generate the initial
credential in memory, store only the configured Strolch hash, and display the password once after successful
initialization. Start the app with an attached `docker compose run --rm --no-deps -T app` after the database is ready;
the Docker app logging driver is `none`, so Docker logs cannot recover the output. A later restart neither changes the
viewer nor redisplays the password. The published
`0.0.2` image predates this behavior and must not be used with these file-free instructions.

If the output is missed, stop the app and run the packaged `/usr/local/bin/intruvia-reset-viewer-password` command via
`docker compose run --rm --no-deps -T --entrypoint ... app`. It takes the runtime lock, replaces only the existing
viewer hash atomically, and prints the replacement once to the attached console. It fails without changing credentials
if the app still owns the runtime lock or the viewer record is missing or ambiguous. Legacy password files from older
deployments are not read or deleted automatically; protect and remove them manually according to the site's secret
handling policy when no longer needed.

The application holds `.intruvia.lock` for its entire lifetime and offline privilege operations take the same lock. Do
not edit the privilege files or run another writer while either is active. The normal runtime keeps schema creation,
migration and drop disabled; a separate protected temporary runtime is used for the one-time schema bootstrap.

### If initialization fails

1. Stop the application and all other database and privilege-file writers. Preserve the entire database, runtime, logs
   and any legacy password file before investigating. Treat them as sensitive. The protected `.intruvia-staging` and
   `.intruvia-bootstrap` directories may contain diagnostic logs and secrets.
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