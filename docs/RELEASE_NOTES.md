# Intruvia 0.0.1 — MVP release review

Review date: 2026-11-12. Application version: `0.0.1`.
Source base: `d0e4d5a9f00a57b8399859c3bdd4a559892bbc09`, plus the uncommitted
release-review documentation, packaging and verification files listed by task 022.
No release commit or tag was created. The final acceptance checklist is
[task 022](verification/022-acceptance.md); the source, dependency and distribution
SHA-256 inventories in `docs/verification/022-*` identify the reviewed bytes.

The MVP accepts Fail2ban ban observations using scoped Strolch PATs, commits events,
receipts and ordered sequences to PostgreSQL, enriches with a local City MMDB, and
serves a cookie-authenticated map with independent live highlights and durable replay.
It includes bounded retention, operator diagnostics, an additive notification action,
and deployment/restore instructions. It does not manage firewalls or current ban state.
See [deployment](DEPLOYMENT.md) before provisioning an external runtime.

## Runtime and reproduction

Verified with Temurin Java 25.0.5+8 and Maven 3.10.0. Dependency pins include
Strolch **2.8.0-SNAPSHOT**, Jetty 12.0.16, Jersey 3.1.11, PostgreSQL JDBC 42.7.12,
MaxMind GeoIP2 5.2.0 and local Leaflet 1.9.4/Natural Earth v5.1.2.
The complete resolved JAR inventory records transitive versions and checksums.
The framework source inspected was clean at
`f8353080a3c8b140accbff6ee171c73deabb0f17`. The six packaged framework JARs
match the installed Maven cache. A mutable snapshot version alone cannot reproduce
these bytes: retain the checked artifacts/cache. No equivalence to the historical
timestamped baseline is claimed; no local artifact was relabelled.

From the reviewed source and cached dependencies, with Java 25/Maven on PATH:

```bash
scripts/verify-postgresql.sh -o -Dstrolch.version=2.8.0-SNAPSHOT
bash docs/verification/022-run-browser.sh
python3 docs/verification/022-package.py
sha256sum -c docs/verification/022-artifacts.sha256
sha256sum -c docs/verification/022-archive.sha256
```

The browser runner uses existing workstation paths documented in its source;
no frontend build or npm installation is required. Tests require disposable Docker
PostgreSQL, never production. The assembled directory is
`intruvia-app/target/intruvia/`; the normalized archive is
`intruvia-app/target/intruvia-0.0.1-mvp.tar.gz`. Packaging is deterministic for
identical input bytes, not a claim of bit-identical Maven recompilation across
machines/JDKs. Each build must regenerate and verify its own inventory.

## Operational limits

- The measured task-020 workload used 100,000 retained events, two browsers/server
  identities, 10 events/s for five minutes and a concurrent 100-event burst. All
  3,100 events committed and appeared. Combined send-to-frame p95 was 951/961 ms;
  sustained-only 845/861 ms; **burst-only 9171/9163 ms**. There is no one-second
  per-event or per-phase guarantee. The slower combined result has only 39 ms margin.
  The seed had no historical receipts; this does not characterize one million receipts.
- The notification helper can lose events after three attempts within 15 seconds.
  It has no persistent offline queue. Keep the existing firewall action and append
  notification last. Intruvia observations do not prove an attack or current ban state.
- Default history is 30 days/100,000 events; receipts last at least 30 days with a
  1,000,000 cap. Full receipt capacity rejects new ingestion with 429. Browser state
  is a rolling 1,000-event window; expired replay cursors visibly resnapshot.
- Production environment-mode pool defaults are ten connections, 30 s acquisition
  and 5 s validation. XML-credential settings can differ; a 3 s acquisition budget
  rejected 32 requests in an earlier burst run. Size monitor and proxy timeouts for
  configured budgets. Readiness probes overlap by returning DOWN, and GeoIP loss
  leaves readiness UP with degraded enrichment.
- GeoIP is approximate network location. Obtain a licensed City database externally;
  none is distributed. Missing/partial results remain unmapped; updates do not rewrite
  history. The packaged basemap requires no external tile requests.
- One process/realm, no HA or distributed ordering. Durable replay repairs missed
  notifications; it does not promise exactly-once network delivery or power-loss recovery.
  Browser frame opportunities are measured, not physical scanout/background rendering.
- Restoring an older backup can lose acknowledged events, resurrect revoked PATs and
  reuse sequences. Pause ingress, reconcile credentials and force viewer reload/login
  before reopening. At/below-head alternate history is not universally detected.
  Sessions do not survive restart. No zero-loss or recovery-time guarantee is made.
- Allow at least 90 s for service shutdown. A drain timeout reports failure and keeps
  persistence alive under active writes. Protect external configuration/logs/backups;
  default framework logs are disabled, counters reset on restart, and console retention
  belongs to the service manager.

Public deployment, host systemd/firewall installation, real DNS/CA issuance, remote DB
TLS, licensed MaxMind download, large-database restore timing and OS power-loss tests
were not performed. The isolated HTTPS/WSS/restore rehearsal and prior failures remain
in task 021 evidence. No dependency vulnerability certification is claimed by this
functional release review. Deferred features remain those in specification section 1.
