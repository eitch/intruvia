# Task 012 — bounded viewer reads

The production JAX-RS routes delegate to `EventReadService` through Strolch's
`ServiceHandler` using the original cookie session certificate. The service and
`EventReadSearch` authorize `event:read` with value `event:read`, including its
configured policy. No agent elevation, generic model endpoint, caller-selected realm,
parallel storage or new dependency is introduced. The existing `ViewerRead` filter
rejects PAT/API certificates and Authorization headers, validates session expiry,
checks read privilege and rejects a supplied foreign Origin. Viewer configuration is
required; absent configuration returns 503 before query handling.

## HTTP contracts

All success/error responses use JSON and `Cache-Control: no-store`. Errors use
`{error:{code,message,requestId}}` with fixed safe text and a generated UUID request ID.

| Route | Parameters | Response |
|---|---|---|
| `GET /api/v1/events/snapshot` | `limit`: default/max 1000, minimum 1 | `{items,cursor,minAfter,truncated}` |
| `GET /api/v1/events` | required exclusive `after`; optional `through`; `limit`: default 500, max 1000, minimum 1 | `{items,nextAfter,hasMore,through}` |

`items` always contains canonical event DTOs in ascending sequence order. A snapshot
selects the latest retained events; `cursor` is the captured head even when the limit
omits older items or all events were removed. `truncated` means more retained events
existed at capture than fit in the requested window; it does not represent all-time
history loss. An empty fresh stream has `cursor="0"`, `minAfter="0"`, `items=[]`,
`truncated=false`. After removal, empty snapshots still expose the persisted head/minimum.

History captures the current head as `through` when omitted. Subsequent requests must
preserve that value and use the previous `nextAfter` as `after`. Reads include only
`after < sequence <= through`. `hasMore` uses a one-event lookahead within that same
window. `nextAfter` is the last returned sequence, or the supplied `after` for an empty
page. New ingestion beyond `through` is excluded until a new catch-up request.

Cursors use canonical nonnegative signed 64-bit decimal strings, including values
above JavaScript's safe integer range and Long.MAX_VALUE. Query parsing never uses
floating point. Empty/missing `after`, leading zeros, signs, whitespace, fractional
values, overflow, `after > head`, `through > head`, or `through < after` return 400
`invalid_request`. Limits outside 1–1000, duplicate parameters and unknown parameters
also return 400. Snapshot accepts only `limit`. `after < minAfter` returns 410
`resync_required`; `after == minAfter` is accepted. Syntax/ahead/window errors take
precedence over expiry. The client must resnapshot rather than advance across lost
history. Sequence/cursor fields in both responses are JSON strings; see the `snapshot`
and `historyPage` definitions in `docs/api/v1.schema.json`.

## Locking, storage and bounds

`EventStreamLock.acquire(tx)` locks the existing stream-state Resource locator before
any relevant read and holds it until transaction close. The extraction preserves the
ingestion service's existing pre/post-acquisition interruption checks. The framework
can restore an interruption without throwing during acquisition, so interrupted calls
must never continue. No independent lock or new consistency domain is created.

The read-only Strolch transaction acquires that lock before reading state, validating
the replay window, searching and materializing immutable events. Ingestion cannot
commit between capture of the head and capture of the items. Future retention must
acquire the same lock before prefix deletion/minAfter advancement. A fixed `through`
does not reserve data: if pruning invalidates a later page's `after`, that page explicitly
returns 410. Only the oldest contiguous prefix may be removed (task 013).

The scoped ResourceSearch filters by numeric sequence, scanning the cached realm
through the framework navigator. A priority queue keeps at most limit+1 candidates
(temporarily limit+2 during insertion); it selects the newest snapshot or earliest page
without sorting/materializing all retained events. Only selected events are mapped,
then sorted ascending. Responses contain at most 1000 events. Work is O(N log limit)
for N matching retained resources and bounded by the future configured retention
capacity; there is no new database/index implementation. The stream lock is held during
this scan. Capacity/load measurements remain task 020; no throughput claim is made.

Service transaction/persistence/locking failures return UNAVAILABLE, mapped to 503
`events_unavailable`, with no event data or persistence details. Framework service
access denial maps to 403. HTTP session failures reuse the existing session contract.

## Verification

`EventReadIT` exercises production Jetty/Jersey and real PostgreSQL:

- Empty snapshots/history, default/max/min limits, latest-window truncation, canonical
  DTOs with IPv6/null geography/inert-looking plain jail text, and strict cursor errors.
- Traversal of 1005 retained events in a fixed window while later events are added;
  no duplicate or skipped sequence, correct empty/end/hasMore behavior.
- Simulated locked prefix deletion and exact minAfter boundary, empty pruned stream,
  410 during later pages, and snapshot head independent of retained item count.
- Values above 2^53 across restart and Long.MAX_VALUE without loss or overflow.
- 80 real service ingestions concurrent with snapshot reads and seven-event pages;
  snapshots match their captured heads and fixed traversal stops at the first head.
- Deterministically held prefix-removal transaction: queued snapshot/history cannot
  finish while the lock is held; after commit the snapshot has coherent state/items
  and the older page returns EXPIRED. This is a test fixture, not retention scheduling.
- Viewer cookie authentication, missing credentials, read-scoped PAT/API-cookie and
  Bearer rejection, missing/explicitly denied read privilege, foreign Origin,
  logout and exact expiry; direct service invalid realm and interrupted-read guards.

`ApplicationIT` checks both read routes exist in the assembled process and return
503 when viewer configuration is absent, while lifecycle/page/health checks continue.
Full commands, failures and final results are recorded in the status ledger and
`docs/verification/012-*.txt`. Framework/cache/package identity and checksums are
recorded for actual local Strolch `2.8.0-SNAPSHOT`; no timestamped-baseline equivalence
is claimed. Automatic retention/capacity cleanup (013), WebSocket delivery (014),
browser UI (015), framework rebuild/tests and deployment are outside this task.
