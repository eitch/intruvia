# MVP and capacity validation

Task 020 exercises the packaged application, real Strolch PostgreSQL persistence,
PAT-authenticated HTTP ingestion, cookie-authenticated WebSockets and locally
packaged browser assets. It introduces no production dependencies or frontend build.
The acceptance record in `docs/verification/020-acceptance.txt` is authoritative
for results and limitations; a harness alone is not a passing capacity gate.

`CapacityBrowserFixture` creates a disposable database/runtime, seeds 100,000
invented events in 1,000-event Strolch transactions, and uses two real framework
PAT owners mapped to server-a/server-b. The measured workload is 3,000 new HTTP
requests scheduled every 100 ms, followed by 100 requests submitted concurrently
to a 100-worker producer pool. No rate, retention, queue or polling defaults are
relaxed. The capacity fixture uses the production environment-mode Hikari defaults:
ten connections, 30000 ms acquisition and 5000 ms validation timeout. The existing
small XML-credential integration fixture supplies two connections and a 3000/2000 ms
timeout pair; these must be overridden here. Production environment-mode pool
properties come from environment variables, not the XML settings (see task 019).
The fixture retains isolated XML credentials while matching those effective pool values.
The seed contains events only, not historical receipts; live requests create real
receipts and exercise capacity prefix pruning at the 100,000-event boundary.

`SyntheticCityDatabase` generates an invented City MMDB for public IPv4/IPv6 at
the same zero coordinates. The production GeoIP reader enriches live HTTP events
from that file. No licensed database or real credentials are redistributed.

`020-capacity.cjs` opens two isolated authenticated Chromium contexts with a
1440×1000 viewport, disables Chromium background throttling, and measures each
unique ordered event to the next animation frame with that event's highlighted
row and halo in the DOM. `occurredAt` is set immediately before HTTP send on the
same host; elapsed time therefore includes HTTP/commit/stream/browser work and
conservatively bounds commit-to-frame time. A second measurement starts at browser
WebSocket receipt. These are frame-opportunity measurements, not physical display
scanout or a guarantee for background/suspended browsers. Both contexts must report
`document.hidden === false`; raw per-event data and gate failures are retained.
No event-handling code is replaced by the measuring listener.

Run from the repository root after clean Maven verification with Java 25:

```bash
scripts/verify-postgresql.sh -o -Dstrolch.version=2.8.0-SNAPSHOT
bash docs/verification/020-run-browser.sh
bash docs/verification/020-run-regressions.sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s integrations/fail2ban/tests -v
```

The browser scripts use the already installed Node/Playwright/Chromium paths listed
in `020-environment.txt`. Edit those environment paths for another workstation;
there is no npm install. They create and remove dedicated loopback PostgreSQL
containers. Never supply a production database. `CAPACITY_EVENTS` is a diagnostic
short-run override; only the default 3,000 sustained events meets the five-minute
workload. A failed or shortened run must never be presented as that acceptance run.

The serial regression runner reuses the established snapshot/live/recovery suites
with new evidence prefixes. Maven covers authorization, persistence, retries,
restart, rollback, durable catch-up without a commit wakeup, GeoIP errors,
retention, slow consumers and runtime shutdown. Durable catch-up fault injection
is not an OS power-loss simulation. Deployment/TLS/backup rehearsal belongs to 021;
release audit belongs to 022.

## Retention correction found by capacity validation

The first production-default diagnostic took roughly ten seconds at p95, despite
fast browser receipt-to-frame indication. Admission sorted every retained resource
and parsed every receive timestamp under the stream lock. The task-013 retention
implementation now selects the oldest excess candidates with a bounded max-heap;
typical capacity admission selects one candidate in a linear scan. Age expiry finds
the greatest expired sequence and removes the complete prefix through the larger
of the age/capacity thresholds. Mutations and `minAfter` still commit atomically
with ingestion, and receipt lifetime/capacity rules remain independent.

Canonical UTC dates on different days can be compared without parsing each instant.
Same-day timestamps (including fractional-second boundaries) and extended years
still use exact `Instant` ordering. Focused cutoff tests compare the shortcut with
`Instant` across year/day/leap/fraction boundaries. Real-runtime retention tests
cover capacity reduction across several unordered resources, exact age, reversed
clock, receipt survival, rollback, restart and concurrent reads/admission.
This correction is part of task 020's defect remediation; no additional backlog
task or architectural dependency is introduced.

The report includes the combined workload p95 and separate sustained/burst p95s;
phase measurements must not be hidden by the larger sustained sample population.
Server `receivedAt` to frame is also measured: it includes precommit work and
conservatively bounds commit-to-frame latency. Producer HTTP-send timestamps share
the server/browser host clock; raw timestamps allow auditing actual send span.
