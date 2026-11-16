# Intruvia release procedure

This guide is for release owners. Administrators should use the [installation and recovery guide](DEPLOYMENT.md), not this document. The recommended one-command workflow is `scripts/release.sh`: it updates the Maven reactor, verifies and assembles the application, builds the versioned Docker image and administrator tarball, commits the release POM versions and pushes an annotated Git tag, pushes the image and creates a GitHub release with the tarball and checksum, then advances the Maven reactor to the next `-SNAPSHOT` version.

The older [GitHub source-release procedure](GITHUB_RELEASE.md) remains available for a source-only GitHub release and Mastodon announcement. It is separate and does not publish the image or administrator bundle.

## Release gates and prerequisites

Before starting a release, the release owner must review and resolve all product and operational release gates. In particular, review [task 022 release acceptance](verification/022-acceptance.md), [task 023 deployment acceptance](verification/023-acceptance.md), the release notes, dependency provenance, and any recorded limitations. The script's clean Maven verification does not substitute for these reviews or grant approval to release.

Use a release checkout on the intended branch with its configured upstream. The working tree must be clean, and the local branch must exactly match the remote branch. Confirm the Git remote identifies the intended GitHub.com repository. The command needs permission to push the branch and tag, push to `repo.strolch.li/intruvia`, and create GitHub releases.

Required tools are Bash, Git, Python 3, Java 25, Maven 3.10, Docker, `sha256sum`, and GitHub CLI (`gh`) authenticated to the intended GitHub account. Docker must be running, logged in to `repo.strolch.li`, and able to run the project's PostgreSQL 18 verification container. Configure the Git author and committer identity before starting. Serialize releases and enforce immutable version tags in the registry; the script checks for existing tags and images, but cannot make an external registry's tag creation atomic with its preflight.

## Version selection and run

From the repository root, run:

```bash
scripts/release.sh
```

The script reads the current root Maven version and asks whether this is a `major`, `minor`, or `patch` release. It accepts a plain `MAJOR.MINOR.PATCH` version or that version with `-SNAPSHOT`, and increments the selected component from the current numeric version. For example, from `1.4.2-SNAPSHOT`, `minor` selects release `1.5.0`; after any successful release, the next development version is the next patch `-SNAPSHOT` (for example, `1.5.1-SNAPSHOT`).

Before editing the POMs, the script prints the proposed Maven version, `vVERSION` tag, image reference, GitHub asset names and next `-SNAPSHOT` version. Confirm that the release gates have been reviewed, then type the exact proposed tag (for example `v1.5.0`) to authorize the release. Any other input cancels before release files are changed.

After approval, the script verifies the clean synchronized branch, remote/repository identity, GitHub authentication, absence of the local/remote tag and GitHub release, absence of the versioned registry image and local output artifacts, and Docker availability. It then:

1. Sets all five Maven POM versions to the selected release version and runs `scripts/verify-postgresql.sh`.
2. Builds `repo.strolch.li/intruvia:VERSION` from the assembled distribution and creates `target/deployment-release/intruvia-deployment-VERSION.tar.gz` plus its `.sha256` file.
3. Verifies the archive checksum, commits the release POM versions, creates an annotated `vVERSION` tag, and pushes the release branch and tag without force.
4. Pushes the Docker image and creates the GitHub release using generated release notes, attaching the tarball and checksum. It verifies both asset names are listed.
5. Changes all five POMs to the next patch `-SNAPSHOT` version, commits that development version and pushes the branch without force.

The script does not publish Maven artifacts. GitHub's automatic source archives are in addition to the explicitly attached administrator bundle and checksum. A checksum detects accidental transfer damage; it is not a signature and does not independently authenticate the publisher.

## Partial failure and recovery

Git, the container registry and GitHub cannot participate in one transaction. The workflow stops at its first failed operation, reports completed steps and leaves local state for inspection; it does not roll back, retry, force-push or overwrite a version. Do not restart the command blindly after a failure.

| Failure point | Before another attempt |
|---|---|
| Version edit or Maven verification | Inspect all five POMs and Maven output. Resolve the cause and deliberately restore or continue the release-version edit. No image, tag or GitHub release has been published yet. |
| Image build or bundle creation | Inspect the local distribution, Docker image and `target/deployment-release/`. The tarball builder refuses to replace an existing artifact. Remove or move artifacts only after verifying they belong to this failed attempt. |
| Release commit, tag or Git push | Inspect local and remote branch/tag state. Complete only a missing operation after confirming the exact commit and ref; never overwrite a ref. |
| Docker image push | The release branch and tag may already be public. Inspect the registry before retrying; do not overwrite a tag. |
| GitHub release create or asset verification | The release may have succeeded despite a client/network error. Inspect its tag and assets before manually uploading anything or retrying. |
| Next `-SNAPSHOT` commit/push | The GitHub release and Docker image may already be public. Inspect the POMs and branch, then complete the development-version transition manually without force. |

The combined workflow's local regression tests use disposable Git repositories and controlled `gh`, Docker, Maven and verification-command doubles; they never publish to a registry or GitHub:

```bash
bash -n scripts/release.sh scripts/test-unified-release.sh
bash scripts/test-unified-release.sh
```

For the retained separate source-only workflow, see [GitHub source release](GITHUB_RELEASE.md). For image and administrator bundle details, the combined script invokes the reviewed helpers `scripts/build-deployment-image.sh` and `scripts/build-deployment-tarball.sh` after PostgreSQL-backed verification.
