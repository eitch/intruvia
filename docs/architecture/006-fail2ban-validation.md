# Task 006 — Fail2ban validation and normalization

`Fail2banRequestParser` is the bounded REST input boundary. Call `parse(InputStream)`
**after authentication**; the caller owns and closes the stream. It reads at most
`maximumBodyBytes + 1` bytes, rejects oversized input before decoding, and returns
an immutable core `Fail2banEvent`. It neither persists events nor exposes an HTTP
endpoint. Do not use the general-purpose `EventJson.read` codec for ingestion.

`InvalidEventException` contains only a fixed safe message, no payload or parser
cause. `isOversized()` distinguishes the future 413 response from 400 validation
failures. Transport IOExceptions remain transport failures. HTTP error envelopes,
media checks, rate limits and authentication ordering are wired in task 010.

## Validation table

| Input | Accepted / normalized | Rejected |
|---|---|---|
| Body | UTF-8 JSON object, at most 16,384 bytes including whitespace | Oversize, malformed UTF-8/JSON, trailing document, comments, duplicate or unknown fields, nested field values |
| Required fields | schemaVersion, eventId, occurredAt, action, ip, jail | Missing/null required fields; string/boolean coercion |
| schemaVersion | Numeric integer 1 (including equivalent 1.0) | Other versions, fractions, numeric strings |
| eventId | Full 8-4-4-4-12 hexadecimal UUID, normalized lowercase | Shortened UUID syntax, invalid hexadecimal, whitespace |
| action | Exact string `ban` | Other actions/case |
| occurredAt | RFC 3339 offset date/time, UTC-normalized with nanosecond precision | Missing offset, invalid calendar/time, zone names, over-nine-digit precision, leap seconds |
| IP | Four-component decimal IPv4; IPv6 lowercase, leading zeros removed, longest first zero run compressed | DNS names, abbreviated/integer/octal/hex IPv4, leading-zero IPv4 components, zones, brackets, CIDR, whitespace |
| IPv4-mapped IPv6 | Both dotted and hexadecimal forms become IPv4 | Invalid embedded dotted IPv4 |
| jail | 1–128 Unicode code points; text preserved verbatim | Empty, C0/C1 controls including DEL, unpaired surrogates, over 128 code points |
| failures | Absent/null or mathematical integer 0–2,147,483,647; equivalent decimal/exponent numbers normalized | Negative, fractional, overflow, non-number |
| Age | Inclusive seven days old through five minutes future | Outside bounds, checked separately only for new events |

No trimming/case folding is performed on jail text. Whitespace and HTML-like text
are data and participate in the digest. Unicode code-point length matches JSON
Schema character length; the existing envelope validator now uses that same count.
IP syntax uses Java 25 `InetAddress.ofLiteral`, which does not resolve hostnames;
no `getByName` or name-service fallback exists. IPv4-compatible (not mapped) IPv6
remains IPv6. No public/reserved classification or GeoIP lookup occurs in this task.
Timestamp offsets follow Java's ISO offset support (up to ±18:00); lowercase t/z
are accepted. Calendar normalization never silently repairs invalid dates.

## Retry and mapping contract

1. Parse and normalize producer data without applying its age policy.
2. Task 009 looks up `(authenticated instance ID, eventId)` and compares the digest.
   An existing matching receipt returns the original result even when now too old;
   a changed payload conflicts.
3. Only for a new receipt, call `EventAgePolicy.validate(occurredAt, now)` with a
   server clock. Defaults are seven days and five minutes; constructor durations
   configure these limits and reject negatives.
4. Task 009 supplies the identity from the protected authentication mapping, a
   server UUID, positive sequence, receipt time and enrichment snapshot to
   `Fail2banEvent.toEvent`. Source/observer use the same authenticated identity.
   Producer JSON cannot supply these fields.

The parser constructor configures the body byte limit (default 16 KiB), requiring
a positive value with room for the overflow sentinel. Runtime configuration wiring
is task 019; this task adds no environment settings or new toolchain/dependency.

## Stable payload digest

SHA-256 covers these ordered normalized fields:
`intruvia.fail2ban.v1`, producer UUID, UTC instant, `ban`, canonical IP, jail, failures.
Each field is a signed four-byte big-endian UTF-8 byte length followed by those
bytes; absent failures use length -1 and no bytes. This avoids delimiter ambiguity.
The result is 64 lowercase hexadecimal characters. The versioned domain field
fixes schema/source semantics. Identity is the receipt-key namespace, not caller
data. Server UUID/sequence/receivedAt and enrichment never enter the digest.

JSON ordering, escaping, insignificant whitespace, UUID casing, equivalent UTC
instants, integral number spellings and mapped IP spellings yield the same digest.
Missing/null failures are equivalent; absent failures and zero differ. Nanosecond,
IP, producer UUID, jail or failures changes yield a different digest in the tests.
The digest format must stay stable for the lifetime of retained v1 receipts.

## Verification

`Fail2banValidationTest` covers the table, byte limits with a bounded endless input,
malformed UTF-8, safe errors, IO failures, configurable limits, exact time boundaries
and one-nanosecond violations, old payload parsing before age rejection, digest
comparisons and the complete envelope mapping. Existing model/REST/authentication
and real PostgreSQL restart/rollback tests run as regression coverage.

See [full verification](../verification/006-clean-verify.txt) and
[actual local Strolch JAR hashes](../verification/006-strolch.sha256).
The initial [unit run](../verification/006-unit-tests.txt) failed because the test
serializer converted an isolated surrogate into `?` before parsing. The corrected
fixture sends the escaped surrogate directly; no production workaround was needed.
