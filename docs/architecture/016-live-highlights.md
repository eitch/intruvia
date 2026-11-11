# 016 — Immediate event highlighting

The viewer loads and renders the bounded snapshot without animation, then opens
one same-origin authenticated WebSocket after its captured cursor. The existing
server supplies retained replay followed by durable live events. The page owns the
connection and closes it before refresh, page removal or logout. Connection and
protocol failures show an explicit manual-refresh status. Automatic reconnect,
session-expiry navigation, retention resnapshot and hidden-tab counts remain 017.

`api/stream.js` owns the URL and transport; it carries no credentials in the URL or
browser storage. `utils/live.js` validates event-frame version/sequence identity
and returns a candidate bounded model. Decimal strings compare using BigInt.
Frames at or below the applied cursor are ignored, including frames whose events
have already left the window. Reusing an event ID with a new sequence is a protocol
error. The page advances its in-memory cursor only after map and list application.
The snapshot and live paths share the same event shape, page model and components.

Map/list updates are incremental. A new row is prepended and receives a three-second
high-contrast background. Each mapped event receives its own SVG ring and timer;
concentric radii and staggered animation phases distinguish overlapping arrivals.
Ring radii repeat after eight levels and phases after six, keeping the visual
footprint bounded even at one busy location. Identities, popups and ring lifetimes
remain independent, and a permanent count shows the number of events at a shared
coordinate in the visible window. Each list row offers its own keyboard-accessible
Show on map action, including when markers overlap or lie outside the viewport.
Arrivals never pan or zoom the map or close an existing popup. Reduced-motion CSS
replaces the pulse with a static high-contrast halo for the same three seconds.

Unmapped events only enter the list and visible-window counts; zero coordinates
remain legitimate. Oldest-by-sequence eviction keeps at most 1000 events and mapped
markers. Eviction also cancels that event's highlight timers, removes its ring/row,
and updates the shared-location count. Snapshot replacement and component removal
cancel every outstanding timer. All server values and overlap counts use DOM text;
number formatting remains in the shared formatter. The UI remains English only.
No dependency, bundler, framework or frontend build was added.

## Verification

Exact commands, failures and environment are recorded in
[acceptance](../verification/016-acceptance.txt). The optional existing-Playwright
[harness](../verification/016-browser.cjs) uses real Jetty/Strolch/PostgreSQL,
real authentication/snapshot/WebSocket and synthetic stored events. The existing
browser fixture accepts stdin lines: `mapped` appends a synthetic mapped event at
zero coordinates, `unmapped` appends NON_PUBLIC without coordinates. These writes
use real transactions but deliberately bypass ingestion/GeoIP and post-commit
notification; production durable polling delivers them. No production endpoint or
new runtime toolchain was introduced for testing.

The harness injects duplicates and an unsupported-version frame at the browser
message boundary; these alone are synthetic transport frames. It checks independent
ring identities, a shared count, no duplicate repulse, actual three-second duration,
unmapped counts, off-screen arrival without recentering, keyboard navigation,
reduced motion, window eviction, full-width sequence precision, repeated refresh,
manual error/offline recovery, mobile and logout cleanup. Snapshot regression also
covers real denied roles, cookie loss, inert HTML-like values and empty/error states.

Foreground timing observes the WebSocket message before the application handler,
then measures to the next requestAnimationFrame with the highlighted row in the
DOM, for 30 events at 10/s in Chromium. This includes application/render scheduling
and is a next-frame opportunity measurement, not instrumented display scanout or
commit-to-highlight latency. It verifies the 100 ms task-016 target in this local
run; the five-minute/two-browser/100,000-event workload remains task 020. No
background rendering, reconnect, production capacity, TLS or release claim is made.
