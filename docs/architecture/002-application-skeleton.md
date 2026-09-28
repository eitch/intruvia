# Task 002 — Maven application skeleton

Date: 2026-09-28. Scope: specification section 2 and task 002 only.

## Modules and distribution

The root POM manages the exact task 001 Strolch and Jakarta pins, Jetty 12.0.16,
Jersey 3.1.11, Java release 25, JUnit 4.13.2 and Maven plugin versions.
Failsafe 3.5.2 matches Surefire and runs integration tests after packaging.
The [baseline checksum inventory](../verification/001-dependencies.sha256) is preserved.

Dependency direction is acyclic: `app → rest → core`, plus `app → web`.
Core depends on Strolch; REST owns explicit Jersey resource registration; web is
a resource-only JAR containing `META-INF/resources/index.html`; app owns embedded
Jetty, lifecycle coordination and distribution. No frontend framework, JavaScript,
Node build, generic management resources or automatic resource scanning is included.

`mvn clean verify` produces `intruvia-app/target/intruvia/` with the executable
`intruvia-app-0.0.1.jar`, runtime dependencies in `lib/`, and Strolch `runtime/`.
The JAR manifest uses relative dependency paths, so the entire directory is movable.
The integration test launches that JAR from the distribution directory without a
Maven/test classpath, exercising the actual packaged web module and REST resource.
Third-party dependencies remain separate original JARs with their embedded notices.

## Runtime contract

`java -jar intruvia-app-0.0.1.jar <runtime-directory> [port]` starts on 127.0.0.1;
port defaults to 8080 and accepts 0 for an ephemeral test port. The required runtime
path is passed to StrolchBootstrapper.setupByRoot with environment `skeleton`.
Strolch initializes and starts the core IntruviaComponent before Jetty opens HTTP.
The bootstrap contains no credentials, realm or event storage. Task 004 will add
verified durable storage; this does not claim a usable production event runtime.

`GET /` and `/index.html` serve the packaged static page. The servlet allows only
these paths; runtime files and arbitrary classpath resources are never served.
The static page uses a restrictive CSP and contains no remote assets.
`GET /health/live` is a JAX-RS JSON process-liveness route returning status UP.
It does not claim persistence readiness. `/health/ready` and event routes return
404 until their owning tasks implement them.

The shutdown hook and AutoCloseable path stop/destroy Jetty, then stop/destroy
Strolch, including nested cleanup on failure. Repeated close is safe. The completion
log is emitted only after verifying Jetty STOPPED and Strolch DESTROYED. Full
write/socket draining belongs to task 019 once those features exist.

## Acceptance evidence

Exact final command from repository root:

```bash
JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.4-tem PATH=/home/eitch/.sdkman/candidates/java/25.0.4-tem/bin:$PATH mvn -B clean verify dependency:tree
```

Result: exit 0, all five reactor projects successful; ApplicationIT ran 2 tests,
0 failures, 0 errors, 0 skipped. The tests verify packaged startup, HTTP 200 for
page/index/liveness and content types/bodies, 404 for absent routes and configuration
paths, process termination within 15 seconds after SIGTERM, both final lifecycle
states, and repeated in-process close. Maven reactor ordering and the recorded
dependency trees establish no module cycles; source/POM review confirms no frontend
framework or toolchain. Static content was verified over HTTP, not visually in a browser.

- [Final build and dependency tree](../verification/002-clean-verify.txt)
- [Packaged process lifecycle log](../verification/002-application-smoke.txt)
- [Failsafe results](../verification/002-test-results.txt)
- [Initial failed build](../verification/002-build-attempt.txt): the packaged-process
  test passed; the in-process test incorrectly expected closed connector port -1,
  while Jetty reports -2. The corrected assertion tests a negative (non-listening)
  port. The failed run is retained rather than overwritten.

Database, authentication, WebSocket/event behavior, browser interactions and capacity
checks remain unimplemented/unrun and belong to subsequent tasks. No deployment,
commit, push or second backlog task was performed.
