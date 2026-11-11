# 015 — Snapshot viewer and local world map

The root page now signs in using the existing Strolch-backed viewer session, loads
`/api/v1/events/snapshot?limit=1000`, and renders a rolling window of at most 1000
events. Session cookies remain HttpOnly and restricted to `/api/v1`; JavaScript
stores no credentials or cursor in browser storage. Login/logout use the shared
transport with the existing CSRF header and browser-generated Origin. Reload checks
the session before reading events. Logout and read-time 401 clear the rendered data.
An unsuccessful logout explicitly reports that the session may remain active.

The page reports snapshot state and explicitly says live updates are not connected.
Manual refresh replaces the window without animation, preserves the user's map view,
and retains the last good snapshot if a refresh fails. Live stream consumption,
pulses, overlap indications, reconnect and background behavior belong to 016/017.
Session expiry is detected on session checks or snapshot requests in this task;
there is no background session poll or WebSocket yet.

## Browser modules

- `js/app.js`: startup, login/logout and page ownership.
- `js/pages/ViewerPage.js`: abortable snapshot loading, map/list composition and state.
- `js/api/`: shared fetch transport, safe HTTP errors and endpoint contracts.
- `js/components/`: Leaflet map lifecycle, recent list and reusable safe event details.
- `js/utils/`: central locale-aware display formatting and pure bounded snapshot model.

Native ES modules have no frontend build step. The initial UI is English only;
number/date formatting follows the browser locale, with timestamps shown in the
browser's timezone. Sequences stay strings and sort using BigInt, avoiding Number
precision loss. Future schema versions fail the snapshot load instead of rendering
an unknown format. Page removal aborts reads and removes Leaflet/list state and
listeners. BFCache restore reloads the application after pagehide cleanup.

All event values enter the DOM through textContent. Popup contents are DOM nodes,
not HTML strings. Missing/partial coordinates produce a readable list reason;
zero coordinates remain valid. Marker identity is per event ID even at shared
coordinates. Keyboard-accessible list controls open details or show a marker popup.
The degraded notice describes enrichment snapshots in the visible window, not the
current operational health of the GeoIP reader.

## Pinned assets and licensing

Leaflet **1.9.4**, the specification's selected library, is packaged from
`https://registry.npmjs.org/leaflet/-/leaflet-1.9.4.tgz`. Its original distribution
JS/CSS/images and BSD-2-Clause LICENSE are retained without modification.

Natural Earth vector **v5.1.2**, **ne_110m_admin_0_countries**, is the 1:110 million
simplified world basemap. Source:
`https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.2/geojson/ne_110m_admin_0_countries.geojson`.
Natural Earth's [terms](https://www.naturalearthdata.com/about/terms-of-use/)
place its raster/vector data in the public domain, including modified distributions.
The packaged GeoJSON preserves all 177 feature geometries, removes unused properties,
and uses compact JSON. This is a visual backdrop, not a legal boundary authority.
See packaged `data/NOTICE.txt` for provenance and `015-assets.sha256` for downloaded
and packaged asset identities. No MMDB, tile service or runtime CDN is included.
Attribution and the GeoIP approximation notice are visible below the map.

The application servlet permits only explicit public web path patterns and serves
correct MIME types with nosniff, no-cache and a same-origin CSP. Scripts cannot run
inline; inline styles are allowed because Leaflet places map panes using styles.
Runtime files and arbitrary classpath resources remain inaccessible. There are no
third-party fetches or tiles; clicking the attribution link is ordinary navigation.

## Verification

See `docs/verification/015-final-review-verify.txt` for clean offline Maven verify
with local Strolch `2.8.0-SNAPSHOT`. This verifies local artifacts only, not the
historical timestamped baseline. `015-artifacts.sha256` records distribution hashes.

`ViewerBrowserFixture` is an optional test launcher using the existing database and
viewer fixtures. It seeds 1005 synthetic events in a unique disposable PostgreSQL
database, including zero coordinates, partial geography, each unmapped status and
HTML-like jail text. It serves the actual packaged UI/API on loopback. Its synthetic
geography for a documentation IPv6 address is deliberately test-only. Closing its
stdin shuts down Jetty/Strolch and drops the database.

The optional `docs/verification/015-browser-smoke.cjs` uses an already installed
Playwright and Chromium. No npm install, package manifest, build service or new
project test toolchain is required. It exercises real session login/reload/logout,
wrong credentials, denied roles, cookie loss, real snapshot and safe popups, all
unmapped reasons, keyboard action, mobile layout, repeated map creation, no initial
animations, no external network, and no browser credential storage. Empty and 503
snapshot responses alone are browser-route fixtures; authentication remains real.
Screenshots include login, populated popup, empty window and mobile view.

Reproduce after a clean Maven verify, with an isolated PostgreSQL test service:

```bash
# Set JAVA_HOME, INTRUVIA_TEST_DB_URL/USERNAME/PASSWORD for a disposable test DB role.
# Use an existing Playwright installation; do not install a project toolchain.
export PLAYWRIGHT_MODULE=/absolute/path/to/existing/node_modules/playwright
export JUNIT_JAR=/home/eitch/.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar
XDG_CONFIG_HOME=/tmp/intruvia-015/chromium-config \
XDG_CACHE_HOME=/tmp/intruvia-015/chromium-cache \
node docs/verification/015-browser-smoke.cjs
```

The script currently selects `/usr/bin/chromium`. Runtime configuration and synthetic
fixture logs belong only in protected test environments. The browser acceptance does
not measure live latency, reconnect, ingestion-to-highlight or production TLS.
