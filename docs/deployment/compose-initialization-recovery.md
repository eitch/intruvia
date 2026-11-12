# Compose initialization and recovery

First start requires a dedicated, empty PostgreSQL database directly owned by the application role. The role must not
have administrative, replication or row-security-bypass privileges, or membership in privileged roles. Keep all other
database writers stopped during initialization: the emptiness check is a point-in-time check, not a database-wide lock
against unrelated clients.

The runtime directory must be empty and owned by the container user (`10001:10001`) with mode `0700`. Put the initial
viewer password in a regular owner-only file (`0400` or `0600`) inside a directory owned by the same user with mode
`0700`; mount the directory read-write, not the individual file. Supply its path, not its contents, to the container. A
successful first initialization removes that file. A later restart never reads or removes a newly created file at the
same path and never replaces an existing viewer.

The application holds `.intruvia.lock` for its entire lifetime and offline privilege operations take the same lock. Do
not edit the privilege files or run another writer while either is active. The normal runtime keeps schema creation,
migration and drop disabled; a separate protected temporary runtime is used for the one-time schema bootstrap.

### If initialization fails

1. Stop the application and all other database and privilege-file writers. Preserve the entire database, runtime, logs
   and any remaining password file before investigating. Treat them as sensitive. The protected `.intruvia-staging` and
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

If initialization reached `.intruvia-initialized` and the in-progress marker is absent but password-file removal failed,
inspect the protected file and remove it manually when appropriate. Keep the matching database and runtime together:
restoring either alone can roll back privilege secrets, users or event cursors independently of the other.