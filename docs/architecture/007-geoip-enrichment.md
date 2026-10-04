# Task 007 — local GeoIP enrichment

Implemented 2026-10-04. `GeoIpComponent` is a Strolch lifecycle component in core,
registered in the application's production configuration. It returns the existing
immutable `SecurityEvent.Geo` snapshot. Task 009 will invoke it before the ingestion
write lock; this task does not add an ingestion endpoint or persistence operation.

## Configuration and lifecycle

In the external runtime's `config/StrolchConfiguration.xml`, set the `GeoIp`
component's `databasePath` property to an absolute path to an operator-provided
GeoLite2 City or GeoIP2 City MMDB. Empty configuration is supported. Relative paths
fail configuration validation; missing, unreadable, corrupt or wrong-edition files
leave the component STARTED with enrichment UNAVAILABLE. The rest of the application
can start. Operators must provide their own licensed database. No download or
external lookup takes place in this component.

One `DatabaseReader` is opened on start and reused for all lookups. MEMORY mode
holds a snapshot of the file in heap (budget approximately the database size plus
reader overhead). It avoids an active file mapping, but operators must still avoid
in-place writes while a reader is loading. Lookups share a read lock; stop/destroy
hold the write lock, wait for active lookups and close the reader. Stop then start
loads a fresh reader. Replacement without restart, staging/validation/swap and
staleness diagnostics are task 008, not implemented here.

The required MaxMind Java dependency is pinned to `com.maxmind.geoip2:geoip2:5.2.0`
in root dependency management. Its reader dependency resolves to
`com.maxmind.db:maxmind-db:4.1.0`. The API and implementation were checked against
[MaxMind's Java documentation](https://maxmind.github.io/GeoIP2-java/) and
[the versioned reader source](https://github.com/maxmind/GeoIP2-java/blob/v5.2.0/src/main/java/com/maxmind/geoip2/DatabaseReader.java),
then verified with actual JARs. No alternative GeoIP provider or new test toolchain
was added. Dependency licenses remain in their JARs; MaxMind data is not bundled.

## Results and provenance

Input must be a validated IP literal. The existing literal-only normalizer is
reused; no DNS API is used. Invalid caller input remains a validation error.

| Condition | Result | Provenance |
|---|---|---|
| Excluded/non-public literal | NON_PUBLIC; no reader lookup | Lookup decision time; provider/edition/build null |
| No usable reader, including failed startup load | UNAVAILABLE | Decision time; provider/edition/build null |
| Public literal absent from the database | NOT_FOUND | maxmind, edition, database build time, lookup time |
| Database record present, including partial/empty record | FOUND | Same complete database provenance |
| Decode/read failure or invalid finite/range contract | ERROR; no location or coordinates | Same database provenance |

Country code, English country name, most-specific subdivision name, city name,
coordinates and accuracy radius are copied without invented fallback locations.
A partial coordinate pair is discarded as a pair; missing coordinates are null.
Both zero coordinates and a zero latitude/longitude paired with a nonzero value
are legitimate. Invalid complete coordinates or accuracy fail safely with ERROR.
Database build time comes from MMDB metadata, not file modification time. Lookup
time comes from an injectable UTC clock. Updating/restarting does not mutate an
already returned snapshot or stored history.

Reader IO/GeoIP exceptions, malformed-record class casts, decoder deserialization
exceptions and invalid value mappings are converted at the reader boundary.
Load failures produce a fixed warning. Lookup failures produce one fixed warning
per lifecycle, preventing repetitive error logs; no IP, database path, record,
exception message or credential is logged by this component. Unavailable GeoIP
does not represent a persistence failure or reject an otherwise valid event.

## Address classification policy

This is a conservative **geographic unicast eligibility policy**, not a routing
oracle. Basis inspected 2026-10-04: [IANA IPv4 special-purpose registry](https://www.iana.org/assignments/iana-ipv4-special-registry/)
and [IANA IPv6 special-purpose registry](https://www.iana.org/assignments/iana-ipv6-special-registry/).
It intentionally excludes some globally reachable protocol/transition addresses
because treating them as an ordinary source location would be misleading.
No online registry consultation occurs during lookups; policy changes require a
reviewed code/test change.

IPv4 excludes `0/8`, `10/8`, `100.64/10`, `127/8`, `169.254/16`, `172.16/12`,
`192.0.0/24`, `192.0.2/24`, `192.88.99/24`, `192.168/16`, `198.18/15`,
`198.51.100/24`, `203.0.113/24`, and `224/3` (multicast plus reserved/broadcast).
This deliberately excludes the whole protocol-assignment and deprecated relay
blocks, including special anycast exceptions. Other IPv4 literals are eligible
for lookup, which can still yield NOT_FOUND.

IPv6 permits only `2000::/3`, excluding `2001::/23` (protocol assignments),
`2001:db8::/32` and `3fff::/20` (documentation), and `2002::/16` (6to4).
This also excludes non-global unicast, multicast, local/private, unspecified,
loopback, translation, discard and currently reserved space outside that range.
Protocol-specific global exceptions within `2001::/23` are deliberately excluded.
IPv4-mapped IPv6 first normalizes to IPv4 and follows the IPv4 policy.

## Fixture provenance and verification

`SyntheticCityDatabase.java` is original Intruvia test-only code implementing the
small subset of the [MMDB 2.0 format](https://maxmind.github.io/MaxMind-DB/)
needed by these tests. Each test generates its binary file in a temporary folder
under `intruvia-core/target`, opens it with the real MaxMind reader, and removes it
through JUnit cleanup. No downloaded MMDB, encoded database blob, production data
or MaxMind credentials are committed. All names, country associations, coordinates
and accuracy values are invented test data; public IP literals identify fixture
keys only, with no network requests or real-world geolocation assertion.

The encoder creates an IPv6 search tree with IPv4 leaves beneath 96 leading zero
bits, typed metadata, a fixed build epoch 1700000000, and controlled record data.
Tests include complete IPv4/IPv6 records, zero coordinates, missing records,
partial/empty records, non-public addresses with deliberately matching records,
wrong record type, non-finite/out-of-range numbers, an invalid radius, corrupt
file, wrong edition, missing file and absent configuration. The address policy
tests check first/last excluded addresses and neighboring permitted ranges.

Lifecycle tests hold the actual reader reference: deleting the source file does
not interrupt repeated lookups or create another reader; stop closes that reader
(actual subsequent reader lookup throws), restart loads a new edition, and destroy
closes it. ApplicationIT verifies Strolch starts the registered degraded component
and destroys it during application shutdown. Existing PostgreSQL restart/rollback
and packaged HTTP tests run as regression coverage.

Focused checks (Java 25):

```bash
mvn -B -o -Dstrolch.version=2.8.0-SNAPSHOT -pl intruvia-core -am test
```

Full acceptance command and failed-attempt history are in the
[status ledger](../INTRUVIA_BACKLOG_STATUS.md). Final output:
[007-acceptance.txt](../verification/007-acceptance.txt).
No real licensed City dataset accuracy audit, hot replacement/concurrency test,
geoipupdate deployment, ingestion API or browser check is claimed by task 007.
