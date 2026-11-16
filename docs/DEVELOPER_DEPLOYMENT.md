# Intruvia developer deployment

This guide is for developers preparing a release and administrator handoff. For
installation, operation and recovery, use the [administrator guide](DEPLOYMENT.md).

The handoff is a tarball, its SHA-256 file, the image reference, and registry access
instructions. The bundle contains Compose configuration, a protected environment file,
allowlisted runtime templates, and support documentation; the image contains only the
prebuilt application and dependency JARs. Release scripts use `repo.strolch.li/intruvia`
and derive the matching version from the root Maven POM.

> **Release prerequisites:** Strolch `2.8.0-1` is pinned and validated from the
> published release repository, including its PAT persistence fixes. The published
> `repo.strolch.li/intruvia:0.0.2` image was pulled, audited and upgraded from `0.0.1`
> with state preserved; Task 023 acceptance is recorded in
> [the verification report](verification/023-acceptance.md). Public DNS/CA issuance,
> rootless UID mapping and power-loss behavior remain unrun and are explicitly noted.

The root POM is the single source for the release version. Review it and the repository
constant in `scripts/release-config.sh`; no image, version, or output assignments are
required.

> **Version warning:** The `0.0.2` validation image was built and pushed from an
> isolated copy; this checkout's POMs remain at `0.0.1`. Running the no-argument build
> script from this checkout would target `0.0.1`, not `0.0.2`; do not run it until the
> release version is intentionally advanced and reviewed.

## 1. Assemble the Maven distribution

Use Java 25, Maven, Docker, Python 3 and Strolch `2.8.0-1` from the configured Maven
release repository. A local Maven cache may contain a locally installed artifact with
the same version but different bytes; use a fresh local repository for release
verification and assembly when provenance is uncertain. By default,
`./build-and-push-docker.sh`
runs a clean Maven package with tests skipped, then assembles the image from
`intruvia-app/target/intruvia`. This is a quicker packaging path, not release acceptance.

For full PostgreSQL-backed Maven verification before building and pushing the image, use
the opt-in flag:

```bash
./build-and-push-docker.sh --with-tests
```

This runs `scripts/verify-postgresql.sh` and its clean Maven `verify` against a disposable
PostgreSQL container. Review dependency sources and the build result, and record exact
Strolch artifact hashes; hashes identify the built JARs but do not by themselves
establish their source provenance. Run full verification and all release acceptance
checks before handoff even if the image was initially built with tests skipped. For an
isolated provenance check, pass `-Dmaven.repo.local=/path/to/empty/repository` to
`scripts/verify-postgresql.sh`; see the release validation record for the exact method.

## 2. Build and publish the versioned image

Authenticate to the registry using the release owner's approved credentials. The
script derives `repo.strolch.li/intruvia:<root-POM-version>`, builds and pushes that
versioned reference, and prints SHA-256 hashes for the Strolch JARs used. The default
command skips tests; use `--with-tests` to run the PostgreSQL-backed verification before
the image build:

```bash
docker login repo.strolch.li
./build-and-push-docker.sh
# Or, for full PostgreSQL-backed verification before building and pushing:
./build-and-push-docker.sh --with-tests
```

Retain the command output. Pull the exact versioned reference printed by the script
before proceeding (replace the placeholder with the root POM version):

```bash
docker pull repo.strolch.li/intruvia:VERSION_FROM_ROOT_POM
```

The image is assembled from JARs, not the application source tree; runtime templates
are provided separately in the bundle.

## 3. Create and hand off the administrator bundle

After the image is published, create the administrator bundle with the matching POM
version and image reference. The builder writes into
`target/deployment-release/`, refuses to overwrite existing release files, and emits
both the archive and its checksum:

```bash
scripts/build-deployment-tarball.sh
```

Run the deployment image, PostgreSQL, tarball and Compose acceptance checks before
handoff; their commands and limits are recorded in
[`verification/023-acceptance.md`](verification/023-acceptance.md). That report is not
part of the administrator bundle. Give the administrator the
`intruvia-deployment-<root-POM-version>.tar.gz` file, its matching `.sha256` file, the
exact image reference and registry access instructions. Do not include a live `.env`,
runtime directory, database, passwords or deployed data. The archive builder includes
only reviewed distributable files and creates a protected `.env` containing
`repo.strolch.li/intruvia:<root-POM-version>`.

After the required acceptance checks pass and the reviewed source tag is pushed, create
the GitHub release and attach both generated artifacts. Set `VERSION` to the exact root
POM version used to build the bundle and `RELEASE_TAG` to the exact Git tag; they may use
different formats. Authenticate with an account authorized to publish releases. The
command refuses to create a release unless the tag already exists remotely, and it will
not replace an existing release:

```bash
VERSION='<root-POM-version>'
RELEASE_TAG='<GitHub-release-tag>'
gh release create "$RELEASE_TAG" \
  --verify-tag --generate-notes \
  "target/deployment-release/intruvia-deployment-${VERSION}.tar.gz" \
  "target/deployment-release/intruvia-deployment-${VERSION}.tar.gz.sha256" &&
gh release view "$RELEASE_TAG" --json url,assets
```

Check that the release lists both files and give administrators the same `VERSION` and
`RELEASE_TAG` values for the download commands. If creation fails because the release
already exists, verify it and upload only if the matching assets are absent. This command
does not replace existing assets:

```bash
gh release upload "$RELEASE_TAG" \
  "target/deployment-release/intruvia-deployment-${VERSION}.tar.gz" \
  "target/deployment-release/intruvia-deployment-${VERSION}.tar.gz.sha256" &&
gh release view "$RELEASE_TAG" --json url,assets
```

A checksum is not a signature and does not establish publisher identity. Do not publish
or describe a bundle as deployable until all release prerequisites and acceptance checks
above have passed.
