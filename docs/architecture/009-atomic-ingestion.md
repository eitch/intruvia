# 009 — Atomic ingestion and deduplication

The application registers Strolch `DefaultServiceHandler`. Construct a fresh
`IngestService` with the protected `MachineIdentities` loaded by the application,
and invoke `ServiceHandler.doService(certificate, service, new IngestArgument(payload))`.
`payload` is the validated task 006 `Fail2banEvent`. The service resolves the stable
instance from the certificate username; its argument contains no instance identity.
It rejects an explicit realm override and uses the authenticated user's realm.
The production configuration enforces one realm and one application process.

The service overrides Strolch's `Restrictable` privilege name and value with
`event:ingest`. The framework handler validates the actual caller certificate and
this privilege, so existing ingestion-only PATs require no added broad service or
model privileges. No agent impersonation is used for ingestion. HTTP PAT-only
checks and endpoint wiring remain task 010; call through the service handler,
not `doService` directly. Constructor dependencies are trusted application wiring,
not request parameters. The overload accepting a clock, age policy and enrichment
function supports deterministic tests and later configuration wiring.

## Transaction protocol

1. Open a read-only Strolch transaction, lock the `EventStreamState/event-stream`
   Resource locator, then look up the receipt. Return the original ID/sequence for
   an equal digest; report conflict for a changed digest. Only an absent receipt
   reaches the age check. Close the transaction and release its lock.
2. Enrich the validated IP through the managed GeoIP component, with no transaction
   or stream lock held. Concurrent requests that initially see no receipt may both
   perform this speculative lookup. A retry with an already committed receipt
   never enriches again.
3. Open a rollback-on-failure write transaction and acquire the same locator lock
   **before reading** receipt or state. Recheck the receipt, then age against the
   current server time. Allocate the next sequence with checked long arithmetic.
4. Register `IngestCommand`. At commit it asserts lock ownership, adds the event
   and receipt, and advances the stream state, preserving `minAfter`. PostgreSQL
   commits all three together; transaction close releases the lock. Only after
   successful close does the service return a created result.

This uses the framework's supported per-realm element lock; no parallel database
connection, ORM, in-memory sequence counter, global static mutex or custom
persistence mechanism is introduced. Future retention and snapshot operations
must coordinate on the same locator, acquire it before reading, and retain it
through transaction close. This is single-process serialization, not a database
advisory lock or a clustering protocol.

Receipt IDs are `instanceId:canonical-producer-UUID`; the protected instance syntax
excludes the delimiter, making the key unambiguous. Receipts contain the normalized
payload digest, original server ID/sequence and expiry 30 days after acceptance.
The timestamp used for `receivedAt` is captured under the final write lock.
Receipts remain independent of event existence. Duplicate results have no geography;
there is no database lookup or invented status after an event is pruned. New results
include `geoStatus`. The task 010 HTTP adapter must serialize duplicates using the
specified original `id`, string `sequence` and `duplicate:true` contract. The earlier
new-event response DTO is not a requirement to reconstruct deleted geography.

Receipt expiry/removal, storage capacity and retention scheduling remain task 013.
Task 009 recognizes receipts while present; task 013 must remove expired receipts
under this same lock before admission. Once removed, an old producer timestamp
fails the normal age policy. No publication is implemented here; task 014 tails
committed storage and adds a post-commit wakeup.

## Failure behavior and framework review

Conflict and invalid age perform no writes. Persistence/locking failure and sequence
exhaustion return a fixed unavailable outcome without an accepted result or attached
persistence exception. Callers must inspect `isOk()` and the outcome, never assume
that a returned Strolch result implies success. Framework authorization failures
retain their framework result state. HTTP status/error formatting remains task 010.

Inspected local `2.8.0-SNAPSHOT` sources: `DefaultServiceHandler` validates the
certificate and `Restrictable` before execution; `AbstractTransaction` runs registered
commands, flushes persistence, commits, then updates the cached model and releases
locks at close. PostgreSQL rollback leaves the cached model unchanged, verified
by querying immediately and after restart. No local artifact is relabeled as the
historical timestamped baseline.

The local framework's `ElementLock` restores the interrupt flag and returns when
interrupted during acquisition. The service checks interruption before and after
locking and refuses to proceed. Lock timeout is also an unavailable result.
Framework transaction logging remains framework-owned; the service neither logs
producer payloads nor passes database errors into its result. Global operational
logging configuration is task 019.

## Verification

`IngestionIT` uses isolated real PostgreSQL databases and actual framework-issued
PATs containing only `event:ingest`. It exercises concurrent equal and conflicting
submissions, concurrent distinct sequences, durable rollback/restart, independent
receipt survival after simulated event pruning, retries beyond the acceptance age,
certificate authorization and server mapping. A latch suspends enrichment while
another submission commits, proving enrichment does not hold the stream lock and
that the suspended request rechecks the winning receipt.

See the task 009 ledger entry for exact commands, failures, final results and hashes.
No ingestion endpoint, retention worker or WebSocket behavior is claimed by these tests.
