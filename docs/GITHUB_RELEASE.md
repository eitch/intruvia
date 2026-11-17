# GitHub source release procedure

For the recommended end-to-end release—including the versioned Docker image and
administrator tarball—use [`scripts/release.sh`](RELEASE.md). The separate
`scripts/create-github-release.sh` workflow below remains available for a source-only
GitHub release, the next Maven `-SNAPSHOT` transition, and one Mastodon announcement.
It does not publish a Maven artifact, Docker image, or deployment tarball.

## Release gates

Before running the script, the release owner must:

1. Review the [0.0.1 release acceptance record](docs/verification/022-acceptance.md),
   current release notes, dependency pins, the validated Strolch `2.8.0-1` release and
   the exact release commit. The script's Maven verification is not a substitute for
   reviewing the release evidence or an
   operator's release approval.
2. Resolve and record any release blockers. The published `0.0.2` image was pulled,
   audited and upgraded from `0.0.1`; its digest, state-retention evidence and unrun
   environment-specific checks are recorded in the
   [0.0.2 deployment verification record](docs/verification/023-acceptance.md).
   This source-only workflow does not perform Docker image or deployment-bundle
   publication; do not infer those actions from a successful GitHub source release.
3. Prepare reviewed GitHub release notes and the final Mastodon text as UTF-8 files.
   Mastodon text is limited to 500 characters by the script.
4. Confirm the exact GitHub.com owner/repository, configured remote name and URL,
   current branch, release version, next `-SNAPSHOT` version, and the intended
   account/instance for the announcement. The script does not infer a release
   account, branch, version or release contents.
5. Ensure the release branch is clean and synchronized with its configured upstream.
   The release owner must have permission to push the branch and tag and create a
   GitHub release. Git push credentials and the GitHub CLI must both have access to
   the intended repository; the CLI must already be authenticated to github.com.
6. Install Bash, Git, Python 3, GitHub CLI (`gh`), `curl`, Docker, Java 25 and Maven
   3.10. The Docker engine must be available to run the project's PostgreSQL 18
   verification container (`postgres:18-bookworm`).
7. Load a Mastodon access token with permission to publish a status into the
   `MASTODON_ACCESS_TOKEN` environment variable. Do not pass the token as a command
   argument or enable shell tracing. Keep it private and revoke it if exposure is
   suspected.

The script requires a tag-specific `--approve-release vVERSION` argument. This is
an explicit operator gate, not evidence that the required product, provenance,
deployment or publication checks passed.

## Run

Example only: replace every placeholder with the reviewed values. No real account,
branch, version, release notes, Mastodon instance or post is provided by this guide.

```bash
read -r -s -p 'Mastodon access token: ' MASTODON_ACCESS_TOKEN
printf '\n'
export MASTODON_ACCESS_TOKEN

scripts/create-github-release.sh \
  --release-version 1.2.3 \
  --next-snapshot-version 1.2.4-SNAPSHOT \
  --release-notes-file /path/to/reviewed-release-notes.md \
  --mastodon-text-file /path/to/reviewed-mastodon-post.txt \
  --mastodon-instance https://mastodon.example \
  --remote origin \
  --remote-url git@github.com:OWNER/REPOSITORY.git \
  --branch RELEASE_BRANCH \
  --github-repository OWNER/REPOSITORY \
  --approve-release v1.2.3
```

The script checks the exact remote/branch, clean synchronized worktree, existing
local/remote tag state, GitHub authentication/repository identity, all five Maven
POM versions, and the PostgreSQL-backed verification before publishing. The tag is
`vVERSION`; a release version equal to the current POM version is allowed and makes
an explicitly empty release commit. The annotated tag and branch are pushed without
force. GitHub's release is created for that verified tag, then the five POMs move to
the requested next SNAPSHOT and that commit is pushed. The Mastodon post happens
last. A Mastodon token is supplied to `curl` through standard input configuration,
not as a command-line argument.

GitHub automatically provides source archives for the tag. This separate procedure does
not attach the deployment tarball or publish container images. Never infer those actions
or their verification from a successful source release.

## Partial failure and recovery

The workflow stops at the first failed action and prints completed actions and a
manual recovery note. It never force-pushes, rolls back, retries an external action,
or silently retries an announcement. Since local Git, GitHub and Mastodon operations
cannot be one transaction, a failure may leave partial state:

| Failure point | Before another action |
|---|---|
| Version edit or PostgreSQL verification | Inspect all five POMs and verification output. Correct the issue and start a newly reviewed release attempt; the script leaves local changes for inspection. |
| Release commit, tag, or either push | Inspect local and remote branch/tag state. Complete only the missing operation after confirming the exact commit and ref; never overwrite a ref. |
| GitHub release creation | Check GitHub for a release that may have succeeded despite a client/network error before retrying. The branch and tag may already be public. |
| Next-SNAPSHOT commit/push | The GitHub release may already exist. Inspect the five POMs and branch, then complete the development transition manually without force. |
| Mastodon request | The server may have accepted the post before the client reported failure. Check the account before manually retrying to avoid duplicate public posts. |

The isolated regression suite uses local Git repositories and controlled `gh`/`curl`
doubles; it never publishes to real services:

```bash
bash -n scripts/create-github-release.sh scripts/test-github-release.sh
bash scripts/test-github-release.sh
```