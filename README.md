<h1 align="center">Intruvia</h1>
<p align="center"><strong>A clear, live view of Fail2ban activity across your servers.</strong></p>
<p align="center">
  Intruvia brings ban notifications together in an authenticated map and recent-events timeline.<br>
  Deploy Intruvia on your own infrastructure and keep event history and optional GeoIP data under your control.
</p>
<p align="center">
  <a href="docs/DEPLOYMENT.md"><img src="https://img.shields.io/badge/Self--hosted-Deployment%20guide-0f766e?style=flat-square" alt="Self-hosted deployment guide"></a>
  <a href="#scope-and-limits"><img src="https://img.shields.io/badge/Mode-Read--only-2563eb?style=flat-square" alt="Read-only viewer"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-AGPL--3.0--only-7c3aed?style=flat-square" alt="AGPL-3.0-only license"></a>
</p>
<p align="center">
  <a href="docs/DEPLOYMENT.md"><strong>Deploy Intruvia →</strong></a>
  &nbsp;·&nbsp;
  <a href="integrations/fail2ban/README.md">Connect Fail2ban</a>
</p>
<p align="center">
  <img src="docs/verification/020-capacity-1.png" alt="Intruvia map and recent-events dashboard displaying synthetic capacity-test data" width="100%">
</p>
<p align="center"><sub>Viewer preview from a capacity test. The displayed events and locations are synthetic test data.</sub></p>

## A shared view of security events

| Live at a glance | Multiple servers | Local by design |
| --- | --- | --- |
| See new ban notifications on a world map and in a recent-events list. | Bring reporting servers together in one authenticated viewer. | Store history in PostgreSQL and optionally enrich IPs with a locally supplied MaxMind City database. |

Map assets are bundled, so the viewer does not require an external tile service, browser API key, or frontend CDN.

## How it works

1. An additive Fail2ban action sends a notification when a new ban occurs.
2. Intruvia authenticates the reporting server, records the event, and attempts local IP geolocation.
3. Signed-in viewers see the event and any available location details.

## Scope and limits

- **Read-only by design.** Intruvia displays events; it does not issue ban or unban commands, manage firewall rules, or report current firewall state.
- **Best-effort notifications.** The Fail2ban integration makes bounded retries but has no persistent offline queue. Events can be lost during outages; notification failure does not stop the normal ban action.
- **Approximate locations.** GeoIP estimates a network location, not a person or precise address. Some IPs have no location, and the viewer works when GeoIP data is unavailable.
- **Bounded history.** Events are retained for up to 30 days and 100,000 records, whichever limit is reached first; older history is removed as limits are reached.

## Get started

Follow the [deployment guide](docs/DEPLOYMENT.md) to download and start the release bundle with Compose.
Compose administrators can select a non-root application UID/GID in `.env`; both default to `10001`. Changing the
identity for an initialized bundle requires a stopped, backed-up ownership migration described in the deployment guide.
After the first administrator login, use **Manage access** to create a machine and issue
its one-time, `event:ingest` token. Enter a stable machine ID; it defaults the username and
instance ID, with optional advanced overrides. Tokens may last up to 3650 days; `0` uses
the practical finite expiry `9999-12-31 23:59:59 UTC` and remains revocable. Copy the
displayed installation command and run it on the Fail2ban host, then paste the token at
the prompt. See the [Fail2ban integration guide](integrations/fail2ban/README.md) for
custom configurations. Never replace the jail's existing firewall action or put the
token in a command argument or URL.

## Project links

[Release notes](docs/RELEASE_NOTES.md) · [Developer reference](docs/DEVELOPMENT.md) ·
[License](LICENSE) — GNU Affero General Public License v3.0 only (`AGPL-3.0-only`)
