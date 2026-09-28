# Task 003 — v1 contracts and Strolch mapping

`EventModelMapper` maps immutable records to detached Strolch Resources using packaged
`model/templates.xml`. It does not initialize a realm or persist data (task 004).
The templates are structural prototypes, not valid event instances. Optional prototype
parameters are removed when mapping nulls; absence on read restores null, never zero.

| Canonical field | Bag / parameter | Strolch type | Nullable |
|---|---|---|---|
| id | Resource Id | UUID text | No |
| schemaVersion | `envelope/schemaVersion` | Integer | No |
| sequence | `envelope/sequence` | Long | No |
| category | `envelope/category` | String | No |
| action | `envelope/action` | String | No |
| occurredAt | `envelope/occurredAt` | String | No |
| receivedAt | `envelope/receivedAt` | String | No |
| source.type | `source/type` | String | No |
| source.instanceId | `source/instanceId` | String | No |
| source.eventId | `source/eventId` | String | No |
| subject.ip | `subject/ip` | String | No |
| observer.id | `observer/id` | String | No |
| attributes.fail2ban.jail | `attributes.fail2ban/jail` | String | No |
| attributes.fail2ban.failures | `attributes.fail2ban/failures` | Integer | Yes |
| geo.status | `geo/status` | String | No |
| geo.countryCode | `geo/countryCode` | String | Yes |
| geo.countryName | `geo/countryName` | String | Yes |
| geo.region | `geo/region` | String | Yes |
| geo.city | `geo/city` | String | Yes |
| geo.latitude | `geo/latitude` | Float | Yes |
| geo.longitude | `geo/longitude` | Float | Yes |
| geo.accuracyRadiusKm | `geo/accuracyRadiusKm` | Float | Yes |
| geo.provider | `geo/provider` | String | Yes |
| geo.databaseEdition | `geo/databaseEdition` | String | Yes |
| geo.databaseBuildAt | `geo/databaseBuildAt` | String | Yes |
| geo.lookedUpAt | `geo/lookedUpAt` | String | Yes |

Strolch `Float` stores Java double precision. Timestamp strings contain canonical UTC
RFC 3339 (`Instant`) text; using String preserves nanoseconds through the pinned XML
serializer, avoiding Date epoch-millisecond precision loss. UUIDs use canonical text.
Sequence is Long in storage and a validated decimal String in every DTO; no floating
point conversion occurs. Events and receipts require positive sequences; stream cursors
permit zero and minAfter cannot exceed head. Unknown event schema versions fail clearly.

Attributes v1 is a closed typed namespace: `attributes.fail2ban` contains jail (1–128
UTF-16 code units, no control characters) and optional nonnegative 32-bit failures.
It cannot hold arbitrary JSON. Its serialized content is bounded by those fields
(at most 2 KiB of escaped UTF-8 JSON); no raw log or credentials fields exist.
A future namespace requires an explicit schema/model change, not a plugin framework.

## Other resources

| Resource | ID | parameters bag |
|---|---|---|
| IngestReceipt | Internal caller-assigned receipt ID | instanceId String; sourceEventId UUID String; payloadDigest String; eventId UUID String; sequence Long; expiresAt UTC String |
| EventStreamState | `event-stream` | lastCommittedSequence Long; minAfter Long |

All receipt/state fields are required. Receipt eventId is deliberately a scalar rather
than a required relation: receipts must survive event pruning. Receipt-key construction,
digest computation and transaction atomicity belong to task 009; no enrichment or server
timestamp is included in the future canonical payload digest. State starts at 0/0.

## API artifacts

See `docs/api/v1.schema.json` (JSON Schema 2020-12, named definitions for event,
Fail2ban request/response and errors) and `docs/api/event-v1.json` (complete synthetic
IPv6 fixture with all provenance fields). The schema describes contracts, not live routes.
Request DTOs intentionally retain input strings; strict unknown-field rejection, input
limits, age policy and IP normalization are task 006/010. `EventJson` is the shared
record codec, not a request validator or registered JAX-RS provider.

Mapping tests serialize actual pinned Strolch XML and reload it, plus JSON round trips.
No PostgreSQL or browser behavior is claimed by this task.
