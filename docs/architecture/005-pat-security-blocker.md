# Task 005: Strolch PAT revocation blocker

Historical report dated 2026-09-29. Resolved on 2026-10-01 against the fixed local
framework; see [current design and evidence](005-machine-authentication.md). The
following failure analysis and original results are preserved as historical evidence.

## Verified dependency

The isolated [probe](../../probes/pat-security/pom.xml) resolves the installed
`li.strolch:strolch-privilege:2.8.0-SNAPSHOT` offline. The source inspected is
`/home/eitch/src/git/atx-dev/strolch/`, clean at commit
`888f0880321ca9bba4274765d55cf85c4e07a96a`. The installed classes for
`DefaultPrivilegeHandler`, `PrivilegeCrudHandler`, `DefaultEncryptionHandler` and
`XmlPersistenceHandler` equal the framework's existing target classes byte for byte.
The PAT authentication bytecode was also inspected against source. This establishes
the behavior of these installed binaries, not equivalence to the historical timestamped baseline.
The workspace's pre-existing POM already selects `2.8.0-SNAPSHOT`; it was not edited.

Evidence: [dependency tree](../verification/005-pat-dependencies.txt),
[JAR checksums](../verification/005-pat-strolch.sha256),
[class/source identities](../verification/005-pat-binary-source-review.txt),
[authentication bytecode](../verification/005-pat-authentication-bytecode.txt).

## Reproduced failure

`DefaultPrivilegeHandler.authenticatePersonalAccessToken()` reads a PAT, verifies it,
then updates `lastUsed` by calling `removeAccessToken()` followed by `addAccessToken()`
(source lines 515–593, particularly 581–583). In parallel,
`PrivilegeCrudHandler.removePersonalAccessToken()` removes the PAT and cache entry
(lines 243–257). Neither operation shares a lifecycle lock. A valid interleaving is:

1. Authentication reads the existing token.
2. Revocation completes; listing tokens confirms it is gone.
3. Authentication resumes, verifies its previously read token, and re-adds it.
4. A fresh authentication request accepts the revoked token.

[PatRevocationTest](../../probes/pat-security/src/test/java/li/intruvia/probe/PatRevocationTest.java)
reproduces this deterministically with latches around an actual XML persistence read.
Its test-only persistence subclass changes scheduling only; issuance, hashing,
verification, certificates, caching and revocation run through the real Strolch handlers.
No production credentials, replacement verifier or application lifecycle workaround are used.
The assertion concerns a **new request after both overlapping operations finish**, not
whether a request already in progress may complete during revocation.

```bash
JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem \
PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH \
mvn -B -o -f probes/pat-security/pom.xml -Dstrolch.version=2.8.0-SNAPSHOT clean test
```

Result: exit 1; 2 tests, 1 passed, 1 failed, no errors or skips.
Sequential cached revocation and the exact `event:ingest` certificate privilege set pass.
Concurrent revocation fails because a fresh request accepts the restored token.
[Full output](../verification/005-pat-revocation-probe.txt).
The initial fixture run failed with 2 setup errors because enabled users require names;
that fixture was corrected. [Initial output](../verification/005-pat-probe-fixture-failure.txt).
The probe is intentionally separate from the application reactor and remains failing
until the framework regression is fixed; it is not a passing task-005 acceptance suite.

## Review observations and required resolution

The reviewed implementation generates 32 random bytes through `SecureRandom`, stores
PBKDF2 hashes, and checks secret, token existence, validity and enabled owner on cache hits.
The positive test confirms sequential cache invalidation. However, these checks do not
prevent the reproduced restoration. No existing configuration switch or shared PAT lock
was found in the reviewed authentication/CRUD paths. Synchronizing only the Intruvia HTTP
adapter cannot coordinate with supported framework revocation services. A private
revocation registry or copied token lifecycle would violate the task's integration contract.

Required next action: fix the framework lifecycle so authentication cannot recreate a
revoked token, with regression coverage for concurrent authentication/revocation and
last-used updates, then install the required framework reactor modules and rerun this
probe. Any supported framework-level integration alternative must cover all issuance,
authentication and revocation entry points without introducing a parallel lifecycle.
The framework source is outside this session's writable roots and approval is unavailable;
no upstream files were changed, no patch was installed and no external issue was sent.
Rebuilding unchanged source would retain the demonstrated bug. Artifact resolution itself
succeeded, so this is **not** a Maven-resolution blocker.

After the framework prerequisite is resolved, explicitly resume 005, replace the legacy
custom verifier, add the complete real-PAT HTTP/identity/lifecycle acceptance suite, and
run the full PostgreSQL/application regression. Those checks are unrun in this attempt;
no claim is made for expiry, disabled owners, identity mapping, rotation or HTTP isolation.
The earlier custom-token implementation and its historical results remain superseded.
