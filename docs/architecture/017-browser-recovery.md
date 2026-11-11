# Browser reconnect and resynchronization

Task 017 extends the existing snapshot/live viewer, without a frontend dependency
or build. `ViewerPage` owns model/application and resnapshot rendering;
`pages/viewer/Recovery.js` owns one socket, reconnect timer, session probe and
request deadlines. `api/stream.js` remains a one-socket transport adapter.

The page-memory decimal-string cursor advances only after map/list application.
The existing pure candidate model ignores sequences at/below that cursor, checks
event identity, and bounds the window at 1000. The socket always resumes with this
cursor. Missing retained replay events highlight; duplicates do not. No browser
storage is used for cursors or credentials. Snapshot recovery replaces the view
without new-event animation.

On close, cancel socket callbacks, timers and requests before scheduling one retry.
Equal jitter uses [1,2], [2,4], [4,8], [8,16], then [15,30] seconds. Progress through
an event resets backoff; a successful hello alone does not. Each retry checks the
real session before creating a socket. A hello deadline of ten seconds bounds a
stalled upgrade. The browser handles WebSocket ping/pong automatically; server
transport heartbeat/dead-connection detection remains task 014. No client heartbeat
command or new protocol frame is introduced. A fifteen-second session probe with
a ten-second request deadline also checks idle connections and lost cookies.
401/403 returns to login and destroys the page; transient failures reconnect.
Timer scheduling may be delayed while a tab is suspended.

`resync_required`, inconsistent hello bounds (including ahead cursor), or an
unapplicable frame stops the old connection and schedules a snapshot in 1–2
seconds. The persistent notice says “Some older live updates are no longer
available”; it remains alongside the live status and any hidden-tab count.
Failed automatic snapshots leave the previous model visible and retry with the
same bounded exponential policy. Snapshot reads have a ten-second deadline.
Refresh cancels automatic work before starting another snapshot. Logout, page
removal and pagehide destroy recovery, timers, requests, listeners, map and list.
Late responses/socket callbacks are ignored through abort/generation guards.

On visibilitychange to hidden, reset the count for that absence. Newly applied
unique events increment it while hidden, with normal bounded eviction. On return,
display the count, including unmapped arrivals. Do not defer data application or
promise animation/rendering while a browser throttles/suspends its page. Snapshot
resync explicitly discloses unavailable history rather than claiming a full count
of events beyond retention.

Verification uses the existing installed Playwright/Chromium and real
Jetty/Strolch/PostgreSQL fixture. The fixture alone supports synthetic event
append, atomic prefix pruning, and advancing the injected validator clock to the
actual maximum session lifetime. No production debug routes are added. Browser
harness: `docs/verification/017-browser.cjs`; exact commands, outputs, fault
injection boundaries and limitations: `017-acceptance.txt` in that directory.
