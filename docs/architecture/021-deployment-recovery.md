# Task 021 — deployment and recovery

The [operator guide](../DEPLOYMENT.md) describes the existing single-process,
loopback Jetty/PostgreSQL architecture. TLS/WSS terminates at a same-host nginx
proxy; no new application library, frontend build or public management API is added.
The distribution now carries the guide, related design documents, README/LICENSE,
service/proxy examples and an offline source-mode Java privilege recipe.

`PrivilegeTool.java` delegates hashing, PAT scope selection, persistence and
revocation to the configured Strolch privilege handler. It runs only while Intruvia
is stopped; concurrent offline XML handlers are unsupported. It reads bounded
owner-only password files, creates output files exclusively with 0600 permissions,
wipes the password char array and suppresses framework logs/errors at its boundary.
The only PAT scope is event:ingest. Operator privilege grants stay on the trusted
local user. Rehearsal exercises source-mode compilation, cross-user issuance,
rotation, overwrite rejection, persisted revocation and fresh application restarts.
It is a documented local recipe, not an additional authentication implementation.

Backup coordinates the whole database with external runtime/privilege XML during
maintenance. Restore targets a new database and matching runtime/release. Restoring
only events would violate receipt/sequence invariants. Restoring an older snapshot
can resurrect revoked credentials and reuse sequence numbers; the guide requires
revocation reconciliation and all viewers to reload/login before reopening ingress.
An ahead-of-head cursor causes explicit resnapshot; it cannot detect every alternate
history when a cursor is at/below the restored head. No zero-loss or HA claim is made.

The isolated [rehearsal](../verification/021-rehearsal.py) uses a freshly copied
packaged distribution and newly initialized PostgreSQL database owned by an
unprivileged application role. The admin role only provisions/dumps/inspects the
test databases; application persistence still uses Strolch transactions. It follows
the guide's XML, schema flags, source-mode privilege tool, proxy and backup/restore
recipes, compares every public table's count/content fingerprint immediately after
restore, and verifies the receipt through HTTP. A trusted disposable certificate
exercises HTTPS and WSS. The [browser check](../verification/021-browser.cjs)
logs in after restart and substitutes the pre-restore cursor on its first real WSS
connection; the ordinary UI then receives resync_required, resnapshots and becomes
live with a visible data-gap notice. This substitution is explicit test injection,
not a claim that sessions survive a backup/restore.

The fixture removes all temporary credentials, keys, archives and containers.
The guide's self-hosted OS account/package installation, public DNS/CA issuance,
systemd installation and licensed MaxMind download remain site responsibilities;
the rehearsal does not modify host services or deploy publicly. The existing task
018 Fail2ban and task 008 GeoIP checks cover those local integration mechanics.
Exact runs, failures, artifact identities and limitations are recorded in the
[acceptance record](../verification/021-acceptance.txt). Task 022 is not executed.
