# Task 005 — Strolch PAT machine authentication

Date: 2026-10-01. Replaces the superseded custom-token design; historical logs remain
in the verification directory and ledger. No Intruvia verifier or token lifecycle remains.

## Boundary and identity

`@MachineIngest` binds `MachineAuthenticationFilter` to machine resources. It accepts
one bounded `Authorization: Bearer <tokenId>:<tokenValue>` header, delegates to
`PrivilegeHandler.authenticatePersonalAccessToken`, then validates the returned framework
certificate. Missing/malformed/invalid credentials receive 401 with a Bearer challenge.
Cookies and session IDs cannot authenticate this boundary. Error messages are fixed,
include a generated request ID, and use `Cache-Control: no-store`; credentials are not logged.

The accepted API certificate must contain exactly `event:ingest` and pass the framework
policy check for that action. Broad/viewer/admin privilege sets receive 403. Task 010
must explicitly extend this allowlist with its actual service privilege and use the
certificate in the service call. No ingestion or viewer endpoint is implemented here.
Tasks 011/014 must reject PAT authentication on viewer REST/WebSocket routes.

`Identity` carries the framework certificate and mapped stable instance ID. The protected
`config/machine-identities.conf` maps `username=instance-id`; no secret or token ID belongs
in it. Missing file means no mapped machines (valid PATs receive 403). IDs use
`[A-Za-z0-9][A-Za-z0-9._-]{0,127}`. Duplicate users or instance IDs, malformed rows,
symlinks, non-regular files, non-owner permissions and files over 64 KiB fail startup.
Blank lines and lines starting with `#` are ignored. Protect parent directories too.
Mapping changes require restart; PAT rotation/revocation/expiry do not. Caller query or
body fields, display names and token IDs cannot override identity.

## Framework review

Verified local version: `2.8.0-SNAPSHOT`, source HEAD
`4d2a4b8d8db4fbd2c254910431e8284edc15c7d8` in `/home/eitch/src/git/atx-dev/strolch`.
The installed privilege JAR's four relevant handler classes match framework target
classes; source and class hashes are in [source review](../verification/005-resume-source-review.txt).
This does not establish equivalence with the original timestamped baseline.

Reviewed `DefaultPrivilegeHandler` authentication/removal/validation,
`PrivilegeCrudHandler` issuance/subsetting/removal, `DefaultEncryptionHandler`,
`XmlPersistenceHandler` and upstream `PersonalAccessTokenTest`. Strolch generates
32 SecureRandom bytes, returns the secret once, stores salted PBKDF2 hashes and caches
a scoped API context. Cache hits check secret, existence, validity and enabled owner.
Token privileges are a snapshot at issuance: revoke/reissue when changing permissions.
Intruvia always authenticates the PAT on each HTTP request, rather than treating a
previously returned certificate as proof of current token validity.

The [historical revocation race](005-pat-security-blocker.md) is resolved in these
binaries: authentication/removal share a token lock, and last-used updates do not
reinsert removed tokens. The adapted probe allows serialized operations to finish
before checking that a fresh authentication fails. It passes both tests. The original
probe schedule deadlocked against the new lock; that failed retry remains historical evidence.
Framework digest comparisons use `Arrays.equals`; no constant-time or side-channel audit
claim is made. Intruvia adds no cryptographic implementation or workaround.

## Protected provisioning and rotation

Use the supported Java `PrivilegeHandler` management API from trusted local Strolch
operator tooling, connected to the same running privilege handler; no management REST
resource is registered by Intruvia. Do not edit privilege XML concurrently with the runtime.
For initial offline user provisioning, stop the application and use protected Strolch
configuration/management tooling with the configured encryption handler for password hashes.

1. Create one dedicated ENABLED technical user per server (not a SYSTEM user). Its role
   needs only the following ingestion privilege; it needs no viewer/admin privileges:

   ```xml
   <Privilege name="event:ingest" policy="DefaultPrivilege">
       <Allow>event:ingest</Allow>
   </Privilege>
   ```

2. Configure the protected username-to-instance mapping and restart for mapping changes.
   Authenticate a trusted operator through Strolch. That operator needs
   `PrivilegePersonalAccessToken` and, for another user's tokens,
   `PrivilegePersonalAccessTokenUser` with `UserAccessPrivilege` and
   `<AllAllowed>true</AllAllowed>` as in the upstream operator role. The inspected policy
   does not implement per-username PAT restrictions; reserve this broad grant for a trusted operator. Use `PrivilegeAction` / `Persist`
   for explicit durable persistence. These privileges belong to the operator, never the PAT.
3. Issue through the actual API (variables are local to protected operator tooling):

   ```java
   String token = handler.createPersonalAccessToken(operatorCertificate, "producer-a",
           "rotation-2026-10", validFrom, validTo, Set.of(), Set.of("event:ingest"));
   handler.persist(operatorCertificate);
   ```

   Choose explicit validity dates. Do not pass two empty scope sets: Strolch interprets
   that as all current privileges. Write the returned string directly to an owner-only
   producer token file, without printing it or putting it in arguments/history/source.
   Check persistence success/errors before distributing the token; XML storage is
   `PrivilegeTokens.xml` beside the other protected privilege files.
4. For rotation issue a second PAT for the same username, persist, switch the producer
   file, then call `handler.removePersonalAccessToken(operatorCertificate, oldTokenId)`
   and `handler.persist(operatorCertificate)`. Both tokens map to the same instance
   during overlap. Fresh requests reject the revoked token immediately, including
   previously cached tokens, with no Intruvia restart. An already accepted request may finish.
   `getPersonalAccessTokens(operatorCertificate, username)` lists metadata, not secrets.

Use HTTPS through the TLS proxy and exclude Authorization headers from proxy/access logs.
Remove obsolete custom credential files securely; they are no longer read.

## Verification and limits

[HTTP and full regression](../verification/005-resume-acceptance.txt) covers real issued
PATs, 401/403 responses, distinct users, spoofed identity, narrow certificates, rotation,
cached wrong-secret rejection, cached revocation/expiry, disabled owners, duplicate
headers, session credentials and protected mapping validation. The fixture intentionally
has broad owner privileges to prove PAT scope reduction and rejection of broad PATs.
[Concurrency probe](../verification/005-resume-pat-probe.txt) exercises the framework
lifecycle. PostgreSQL integration verifies existing persistence and packaged app behavior.

No production users/tokens were provisioned. Browser/viewer isolation, ingestion, TLS
proxy rehearsal and deployment tooling remain in their numbered tasks. The API instructions
above reference inspected supported methods; no production provisioning rehearsal is claimed.
