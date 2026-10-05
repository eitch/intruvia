# Task 008 — safe local GeoIP database replacement

The Strolch-managed `GeoIpComponent` polls a sibling of its configured database:
`<databasePath>.staged`. The default poll interval is 60 seconds. An operator publishes
this file by atomic rename only after an external download is complete. Intruvia
makes no download or per-event network requests. Existing event snapshots are unchanged.

## Configuration and operator procedure

In the external runtime's GeoIp component, configure:

```xml
<Properties>
    <databasePath>/srv/intruvia/geoip/GeoLite2-City.mmdb</databasePath>
    <updateIntervalSeconds>60</updateIntervalSeconds>
    <staleAfterSeconds>1209600</staleAfterSeconds>
</Properties>
```

Both intervals must be positive integer seconds. The path must be absolute; empty
configuration disables the reader and updater. Missing/corrupt live files allow degraded
startup. A subsequently valid staged database recovers enrichment without restarting.
The application account needs read/write access to the live directory and files for
claiming and renaming candidates. Restrict this directory to the service and trusted
update operator; staged files must be regular files, not symlinks. Never mutate a live,
staged, or claimed candidate inode in place, including via a hard link.

Provision the external `geoipupdate` utility following
[MaxMind's update instructions](https://dev.maxmind.com/geoip/updating-databases/).
Use [GeoIP.conf.example](../examples/GeoIP.conf.example), replacing placeholders only
in a protected external configuration file (mode 0600). Download into a separate
operator-owned directory. Select GeoIP2-City instead if licensed for that edition.
Credentials and actual MMDB files are neither included nor redistributed.

Example after provisioning directories/permissions, using one serialized updater:

```bash
# External operator command; no credentials on the command line.
geoipupdate -f /etc/intruvia/GeoIP.conf
# Run only after geoipupdate succeeds. Keep a backup in a protected external location.
# The temporary file and staged destination MUST be on the same filesystem.
install -m 0640 /srv/intruvia/geoip-download/GeoLite2-City.mmdb \
    /srv/intruvia/geoip/GeoLite2-City.mmdb.part
# Ensure the service account can read this file (provision the operator/service group).
mv -f /srv/intruvia/geoip/GeoLite2-City.mmdb.part \
    /srv/intruvia/geoip/GeoLite2-City.mmdb.staged
```

Run the download and publication steps in a job that stops on failure and prevents
overlapping invocations (for example a systemd oneshot unit). The commands above are
instructions, not an installed scheduler. Only Intruvia replaces the live destination.
Its poller consumes the staged file and rejects invalid data. A rejected claimed
candidate is deleted; retain the download separately for diagnosis/retry. A staging
file rejected before it can be claimed remains for operator removal. Fix the cause
and republish a fresh file; do not repeatedly write to the same published inode.
There is no public reload/management endpoint. Protected HTTP diagnostics remain task 019.

## Swap and failure contract

Replacement calls are serialized with lifecycle transitions. Each candidate is claimed
by atomic rename to a unique sibling before opening it. This isolates validation from
a later staged publication. The candidate uses MaxMind MEMORY mode. A second temporary
reader traverses every network, decoding each City record and checking the same Geo
invariants as enrichment. Wrong edition, malformed metadata/tree/records, invalid
coordinates and I/O failures reject the update. This validates readability and record
shape, not authenticity, geographic accuracy or completeness of the licensed dataset.

Validation holds no lookup lock: lookups continue using the old reader. The final
write lock waits for active lookups, atomically renames the validated file over the live
path, closes the old reader and installs the new reader with matching provenance. Each
lookup sees one complete reader/metadata snapshot. No fallback to non-atomic replacement
is permitted; unsupported atomic moves and failed file switches retain the old reader.
Old on-disk content remains unchanged on invalid-candidate rejection. Successful swaps
survive process restart. A crash after rename loads the new valid database on restart;
a crash before rename retains the previous live file. Orphan `.candidate` files from
process termination can be removed by an operator while the application is stopped.
No power-loss/fsync durability guarantee is added; external database reprovisioning is
still possible and absence remains degraded.

Stop/destroy serialize with replacement, cancel the polling executor and close the
live reader under the lookup lock. Shutdown may wait for a validation already in
progress; dataset-size performance is not yet benchmarked. Budget memory for the old
snapshot, candidate snapshot and temporary validation snapshot (roughly three database
sizes during validation), plus decoding overhead. There is no lookup cache to invalidate.

## Diagnostics and verification

`diagnostics()` returns an immutable, lock-consistent snapshot: availability, edition,
build instant, last successful load instant, database age, stale flag and update-failure
flag. It includes no paths or exception details. Staleness means age strictly greater
than 14 days by default; no current database means unavailable with unknown age and
stale=false. Last successful load remains available after stop/load failure within the
same component instance. Update failures preserve the working build/load metadata;
a successful replacement clears the failure flag. Logging uses fixed messages and
suppresses repeated update warnings until a successful replacement.

Fixtures use task 007's original synthetic MMDB encoder, generated under `target/`;
no downloaded database or credentials are needed. Tests cover ten swaps alongside
20,000 lookups, a deterministic active-read-lock barrier, actual old-reader closure,
full record/tree validation failures, failed destination rename, symlink rejection,
polling, restart/degraded recovery, exact stale boundary and invalid interval settings.
See [full verification](../verification/008-final-review-acceptance.txt) and the status ledger for
exact commands, initial compilation failure and artifact identities. Production download,
licensed-data accuracy, unsupported-filesystem simulation and capacity measurements
were not run.
