# Bounded retention and receipts

Task 013 adds a Strolch-owned `RetentionComponent`, maintenance service, scoped
search and transaction command. The existing model remains unchanged; no migration
or new library is required.

## Configuration

In the external `runtime/config/StrolchConfiguration.xml`, copy the Retention
component from the packaged example. It depends on Intruvia (model initialization)
and ServiceHandler. Add `event:retention` to the protected agent role as shown in
`PrivilegeRoles.xml.example`; do not grant it to producers or viewers.

| Property | Default | Accepted range |
|---|---:|---:|
| eventAgeSeconds | 2592000 (30 days) | 1–315360000 (3650 days) |
| eventCapacity | 100000 | 1–1000000 |
| receiptAgeSeconds | 2592000 (30 days) | 2592000–315360000 |
| receiptCapacity | 1000000 | 1–10000000 |
| sweepSeconds | 86400 (daily) | 1–86400 |

Only these properties are accepted. Values are canonical positive decimal integers;
empty values, leading zeros, unknown keys, invalid bounds and overflow fail component
initialization. The receipt lifetime cannot be less than 30 days. Restart to apply
changes. Existing receipts keep their original expiry; increasing the setting applies
to new receipts. Lowering capacity preserves unexpired receipts and blocks new events
until enough receipts expire.

## Transaction protocol

All callers acquire `EventStreamLock` before examining state, receipts or retained
events. New ingestion rechecks deduplication, then validates age, plans cleanup and
reserves one event slot only if a receipt slot is available. The command deletes
expired receipts and the oldest event prefix, advances minAfter, and then the ingest
command writes the new event/receipt and advances head. Commands execute on transaction
close, under the same lock. Persistence failure rolls back deletions, minimum, event,
receipt and head together; success is returned only after close completes.

Event age is measured from server `receivedAt`, not producer `occurredAt`. An event
expires at `receivedAt + eventAgeSeconds` (equality included). Count pruning removes
just enough oldest sequences to admit a new event within the configured bound. Age
pruning removes through the last expired sequence. Normally received times increase;
a clock reversal can put an expired event after a newer one, so that newer event is
also removed to preserve a contiguous prefix. This avoids retaining expired tail events
or creating replay holes. Sequences and head never decrease or reset. minAfter becomes
the highest removed sequence and never decreases.

Receipts expire independently at their persisted `expiresAt` (equality included).
Events can be pruned while receipts remain. Identical unexpired retries return the
original id/sequence without enrichment or new writes; changed payloads conflict.
The duplicate check ignores expired receipts even before physical cleanup. With the
default 30-day lifetime and seven-day input age, a normal expired retry is rejected
as too old. Expired keys can be reused only with an input that passes the current age
policy; the expired receipt is removed before a new receipt with that key is added.

A new event with no receipt slot after expired-receipt cleanup receives `429`, safe
`receipt_capacity` error and conservative `Retry-After: 86400`. It creates no event,
receipt or sequence. Maintenance deletions may commit on this path, but unexpired
receipts are never evicted. Retries and conflicts are evaluated before capacity.
The header is a polling recommendation, not a promise that capacity will be free
in one day. Existing global and per-instance rate limits still apply first.

## Scheduling, reads and shutdown

The single maintenance worker runs after each configured interval and performs the
same age/count and expired-receipt cleanup through ServiceHandler using the agent
certificate. First sweep occurs one interval after startup; idle data may exceed its
age by up to one interval, longer following a failed sweep. New admissions also apply
age/count cleanup. Failed sweeps report a fixed warning and retry on the next interval.
Stopping/destroying the component interrupts and joins the worker before its realm
dependencies stop. No cleanup endpoint is exposed.

Snapshot/history readers hold the same stream lock throughout capture and
materialization. A history page is either complete for its captured window or returns
EXPIRED/HTTP 410 when its exclusive cursor is below minAfter. A later page can expire
after a prior page succeeded; the viewer must resnapshot. Snapshots retain head even
when every event has been removed.

The cached-realm implementation scans receipts and sorts retained events under the
lock. Temporary storage is O(receipts + events), and event sorting is O(N log N).
There is no capacity/throughput benchmark claim; task 020 must assess this at the
specified production workload. Daily cleanup is a single atomic transaction, including
all deletions; operators should budget database, audit and heap capacity. These bounds
apply to event/receipt resources, not PostgreSQL backups or audit history.

## Verification

`RetentionPolicyTest` checks defaults, unknown/blank/malformed settings and bounds.
`RetentionIT` uses actual PostgreSQL, the production Jetty routes and time-controlled
services to cover exact age/expiry, count bounds, clock reversal, retained retry and
conflict, restart, failed commit rollback, capacity races, 429/Retry-After, history 410,
snapshot consistency during pruning, scheduled execution, maintenance privilege
separation and worker shutdown. Existing ingestion/session/read tests remain in the
full reactor suite. See the task ledger for exact commands, failures and final results.
