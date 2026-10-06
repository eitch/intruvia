# Task 011 — authenticated viewer sessions

Date: 2026-10-06. The application exposes `/api/v1/session`; the viewer UI,
history routes and WebSocket endpoint remain tasks 015, 012 and 014 respectively.

## Framework integration and lifetime

The registered `DefaultStrolchSessionHandler` authenticates a password with
`Usage.ANY`, issues the framework's random `sessionId:tokenValue`, validates it
through the framework's hashed-token/session cache, and invalidates it on logout.
There is no Intruvia credential verifier, token cryptography, or session registry.
The shared `ViewerSessions` validator requires an ordinary non-system certificate
and the `event:read` privilege/policy value on every call, honoring explicit denies. API/PAT and password-change certificates fail.
The framework certificate is exposed to protected REST handlers as
`ViewerAuthenticationFilter.Identity`; annotate read handlers with `@ViewerRead`.
No generic framework management resources are registered.

The framework session handler periodically expires idle sessions (30 minutes by
default). Because validation updates framework last-access time before the next
periodic sweep, Intruvia also enforces an absolute maximum lifetime synchronously:
`session.maxKeepAlive.minutes`, default 30 minutes, from the certificate login time.
At or beyond that boundary the framework session is invalidated. Requests do not
extend this absolute deadline. Login always issues a fresh credential and
invalidates a supplied previous valid viewer session. No refresh route exists.

Task 014 must call `validateUpgrade(headers)` before upgrade, retain the credential
only server-side, revalidate it through `validateToken` during socket lifetime,
and close the socket when validation fails. This task verifies the shared
validator after logout/expiry; it does not implement or claim socket closure yet.
A restart follows framework session persistence settings; without persisted
sessions browsers must log in again. Never persist browser credentials in
localStorage, sessionStorage, URLs or JavaScript application state.

Inspected framework sources at
`/home/eitch/src/git/atx-dev/strolch`, HEAD
`e34b772f9d58f053a0272457708658d0dceadf76`:

- `strolch-agent/.../runtime/sessions/{StrolchSessionHandler,DefaultStrolchSessionHandler}.java`
- `strolch-privilege/.../handler/DefaultPrivilegeHandler.java`: token validation,
  password authentication, cache removal and invalidation.
- `strolch-web-rest/.../endpoint/AuthenticationResource.java`: `Usage.ANY`,
  `Certificate.getAuthToken()` and framework cookie convention. The stock resource
  supports broader behaviors, so Intruvia exposes only its bounded adapter.

Actual installed/packaged version: `2.8.0-SNAPSHOT`. Source/target/JAR comparisons,
checksums and verification outputs are in `docs/verification/011-*`. These local
snapshot checks do not verify the historical timestamped baseline. Dependencies
resolved offline; no framework rebuild or dependency/toolchain change was needed.

## External configuration and provisioning

Copy `runtime/config/viewer.properties.example` to `viewer.properties` in the
external runtime and set its sole property:

```properties
origin=https://intruvia.example.org
```

Use exactly the browser's public origin, including a non-default port if applicable,
with no trailing slash, path, query, fragment, user information or wildcard.
Missing configuration disables viewer access with 503; malformed configuration
fails application construction. HTTPS is required except for explicitly configured
`http://localhost`, `http://127.0.0.1` or `http://[::1]` development origins.
The server remains on loopback behind the TLS proxy. Neither Host nor forwarded
headers choose the trusted Origin or cookie security mode. Proxy all `/api/v1`
paths under the same public origin and do not add permissive CORS headers.

The updated `StrolchConfiguration.xml` registers the SessionHandler with dependency
on PrivilegeHandler and 30-minute idle/maximum settings. Existing external runtimes
must copy this component and add the agent's narrow `PrivilegeAction` /
`GetCertificates` permission from the updated role example (session restoration).
Do not replace existing operator users or roles wholesale.

The role example now includes `Viewer` with only `event:read`. Provision a dedicated
ENABLED human user in protected external `PrivilegeUsers.xml` using this role:

```xml
<User userId="viewer-alice" username="alice" password="REPLACE-WITH-STROLCH-PASSWORD-HASH">
    <Firstname>Alice</Firstname><Lastname>Viewer</Lastname><Locale>en-GB</Locale>
    <State>ENABLED</State><Roles><Role>Viewer</Role></Roles>
</User>
```

Generate the password hash using the supported Strolch `DefaultEncryptionHandler`,
initialized with the external PrivilegeConfig's encryption parameters, and
`hashPassword(passwordChars, nextSalt()).toString()`. Enter the password through a
protected prompt/management process; do not put it in shell arguments or source.
Store only the hash in the XML. Protect configuration/parent directories and back
up privilege data. Restart after offline configuration edits. No signup,
password-reset UI or user-management endpoint is provided. Keep technical
producer accounts ingestion-only; do not add Viewer or administration roles.

## HTTP and browser contract

All application session responses use `Cache-Control: no-store`. Errors have only
`{error:{code,message,requestId}}`, with fixed text and a generated UUID. Login JSON
is strict UTF-8, at most 4096 bytes, with exactly two string fields: username
(nonblank, at most 128 UTF-16 units) and password (1–1024 UTF-16 units). Unknown or
duplicate fields, malformed JSON/UTF-8, trailing data and non-string values fail.
Bodies are bounded before parsing; compressed input is unsupported. Existing
global API request and Jetty connection limits also cover session requests.

- `POST /api/v1/session`: JSON `{username,password}`; requires
  `Content-Type: application/json`, exact `Origin` and `X-Intruvia-CSRF: 1`.
  Returns 200 `{username}` and the session cookie. Invalid credentials return 401;
  valid credentials without `event:read` return 403 and their new session is revoked.
- `GET /api/v1/session`: cookie-only authenticated/read-authorized session check;
  returns 200 `{username}`, otherwise 401/403. No event payload is exposed here.
- `DELETE /api/v1/session`: requires the cookie, exact Origin and CSRF header;
  invalidates the framework session, returns 204 and deletes the cookie. A stale
  cookie receives 401; the client should return to login.

Cookie: `IntruviaSession`, HttpOnly, SameSite=Strict, Path=/api/v1, no Domain,
Secure for HTTPS, browser-session lifetime. HTTP loopback development explicitly
omits Secure. Login never returns the credential in its JSON body. Browser fetches
use same-origin cookies; no JavaScript credential storage is needed.

Every viewer request rejects Authorization headers, including Bearer PATs even
alongside a valid cookie. Duplicate session cookies fail. Mutation Origin and
CSRF headers must each have exactly one expected value. For GET, Origin may be
absent (normal same-origin fetch behavior); if present it must match exactly.
`Sec-Fetch-Site`, when present, must be `same-origin`. CSRF uses the custom-header
pattern together with exact Origin validation, strict JSON and no CORS permission;
there is no synchronizer-token bootstrap to persist in the browser. A cross-origin
form cannot set the header, and a cross-origin fetch gets no preflight permission.
Cookie flags are additional protections, not the only CSRF check.

## Verification and limits

`ViewerSessionIT` exercises production Jetty/Jersey/Strolch wiring against the
isolated PostgreSQL fixture: successful/failed/disabled/unauthorized logins,
strict input and bounds, secure/development cookies, missing/wrong/duplicate Origin
and CSRF headers, denied CORS preflight, duplicate cookies, token tampering,
real read-scoped PATs and API session credentials, Bearer rejection, viewer-to-ingest
isolation, password-session read denial, rotation, logout and controlled-clock
expiry exactly at 30 minutes. After logout/expiry both HTTP and the shared validator
reject the credential and the underlying framework certificate is invalid.
`ViewerConfigurationTest` covers origin parsing and unknown/missing settings.

No frontend code changed, and no browser visual test was required/run. Actual TLS
proxy deployment, production provisioning, idle scheduler timing, session
persistence across restart and WebSocket lifetime behavior were not acceptance
checks in this task. The HTTP tests inspect the Secure flag through loopback
transport; they do not claim to test a browser's TLS cookie handling. Task 014
owns actual sockets and task 015 owns the login UI.
