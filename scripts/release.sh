#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail
export LC_ALL=C

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
completed_steps=()

fail() {
	printf 'ERROR: %s\n' "$1" >&2
	if ((${#completed_steps[@]})); then
		printf 'Completed steps:\n' >&2
		printf '  - %s\n' "${completed_steps[@]}" >&2
	fi
	printf '%s\n' 'Manual recovery: inspect POM versions, Git refs, registry tags, and GitHub release assets before retrying. Never overwrite or force-push a published version.' >&2
	exit 1
}

if (($#)); then
	printf 'Usage: %s\n' "$0" >&2
	exit 2
fi

for tool in git python3 gh docker mvn java sha256sum; do
	command -v "$tool" >/dev/null 2>&1 || fail "Required command is missing: $tool"
done

current_version=$(python3 "$root/scripts/update-maven-reactor-version.py" check "$root") ||
	fail 'Unable to read a consistent Maven reactor version'

printf 'Current Maven version: %s\n' "$current_version"
printf '%s\n' 'Choose the release type: major, minor, or patch.'
read -r -p 'Release type: ' release_type || fail 'No release type was entered'
case $release_type in
	major|minor|patch) ;;
	*) fail 'Release type must be major, minor, or patch' ;;
esac

if ! versions=$(python3 - "$current_version" "$release_type" <<'PY'
import re
import sys

match = re.fullmatch(r"([0-9]+)\.([0-9]+)\.([0-9]+)(?:-SNAPSHOT)?", sys.argv[1])
if match is None:
    raise SystemExit("Maven version must be MAJOR.MINOR.PATCH with an optional -SNAPSHOT suffix")
major, minor, patch = (int(value) for value in match.groups())
kind = sys.argv[2]
if kind == "major":
    major, minor, patch = major + 1, 0, 0
elif kind == "minor":
    minor, patch = minor + 1, 0
elif kind == "patch":
    patch += 1
else:
    raise SystemExit("Unsupported release type")
release = f"{major}.{minor}.{patch}"
print(release)
print(f"{major}.{minor}.{patch + 1}-SNAPSHOT")
PY
); then
	fail 'Unable to derive release and next SNAPSHOT versions'
fi
release_version=${versions%%$'\n'*}
next_snapshot_version=${versions#*$'\n'}
tag="v$release_version"
image_repository=repo.strolch.li/intruvia
image_reference="$image_repository:$release_version"
archive_name="intruvia-deployment-$release_version.tar.gz"
checksum_name="$archive_name.sha256"
output_directory="$root/target/deployment-release"
archive_path="$output_directory/$archive_name"
checksum_path="$output_directory/$checksum_name"

printf '\nRelease plan:\n  Version: %s -> %s\n  Git tag: %s\n  Docker image: %s\n  GitHub assets: %s and %s\n  Next Maven version: %s\n' \
	"$current_version" "$release_version" "$tag" "$image_reference" "$archive_name" "$checksum_name" \
	"$next_snapshot_version"
read -r -p 'Have you reviewed the release gates and acceptance evidence? Type yes to continue: ' gates_approved ||
	fail 'Release-gate confirmation was not provided'
[[ $gates_approved == yes ]] || fail 'Release gates were not confirmed'
read -r -p "Type $tag to approve this release: " approved_tag || fail 'Release approval was not provided'
[[ $approved_tag == "$tag" ]] || fail "Release approval must exactly match $tag"

worktree_status=$(git -C "$root" status --porcelain=v1 --untracked-files=all) || fail 'Unable to inspect Git worktree'
[[ -z $worktree_status ]] || fail 'Working tree is not clean; commit or remove all changes before releasing'
branch=$(git -C "$root" branch --show-current) || fail 'Unable to determine the current branch'
[[ -n $branch ]] || fail 'A release cannot be created from a detached HEAD'
upstream_ref=$(git -C "$root" rev-parse --symbolic-full-name '@{upstream}' 2>/dev/null) ||
	fail 'The current branch has no configured upstream'
if [[ ! $upstream_ref =~ ^refs/remotes/([^/]+)/(.+)$ ]]; then
	fail 'The current branch upstream is not a remote branch'
fi
remote_name=${BASH_REMATCH[1]}
upstream_branch=${BASH_REMATCH[2]}
[[ $branch == "$upstream_branch" ]] || fail 'The current branch name does not match its upstream branch'
remote_url=$(git -C "$root" remote get-url "$remote_name") || fail "Unable to read remote URL for $remote_name"

parse_github_repository() {
	python3 - "$1" <<'PY'
import re
import sys
from urllib.parse import urlsplit

remote = sys.argv[1]
if "://" in remote:
    parsed = urlsplit(remote)
    if parsed.hostname != "github.com" or parsed.query or parsed.fragment:
        raise SystemExit("Release remote must be a GitHub.com repository")
    path = parsed.path.lstrip("/")
else:
    match = re.fullmatch(r"(?:[^@/]+@)?github\.com:(.*)", remote)
    if match is None:
        raise SystemExit("Release remote must be a GitHub.com repository")
    path = match.group(1).lstrip("/")
if path.endswith(".git"):
    path = path[:-4]
parts = path.split("/")
if len(parts) != 2 or not all(re.fullmatch(r"[A-Za-z0-9_.-]+", part) for part in parts):
    raise SystemExit("Release remote must identify exactly OWNER/REPOSITORY")
print("/".join(parts))
PY

}

if ! github_repository=$(parse_github_repository "$remote_url"); then
	fail 'Unable to identify the GitHub repository from the configured remote'
fi
push_urls=$(git -C "$root" remote get-url --push --all "$remote_name") ||
	fail "Unable to read push URL for $remote_name"
[[ -n $push_urls ]] || fail "No push URL is configured for $remote_name"
while IFS= read -r push_url; do
	[[ -n $push_url ]] || fail "An empty push URL is configured for $remote_name"
	if ! push_repository=$(parse_github_repository "$push_url"); then
		fail "Git push URL for $remote_name does not identify a GitHub repository"
	fi
	[[ ${push_repository,,} == ${github_repository,,} ]] ||
		fail 'Git push URL targets a different repository than the GitHub fetch remote'
done <<< "$push_urls"

head_commit=$(git -C "$root" rev-parse HEAD) || fail 'Unable to read the current commit'
if ! remote_branch_state=$(git -C "$root" ls-remote --heads "$remote_name" "refs/heads/$branch"); then
	fail 'Unable to read the release branch from its Git remote'
fi
read -r remote_commit remote_ref <<< "$remote_branch_state"
[[ $remote_ref == "refs/heads/$branch" && $remote_commit == "$head_commit" ]] ||
	fail 'Release branch is not synchronized exactly with its remote'

git -C "$root" var GIT_COMMITTER_IDENT >/dev/null 2>&1 || fail 'Git committer name and email must be configured'
docker info >/dev/null 2>&1 || fail 'Docker engine is not available'
gh auth status --hostname github.com >/dev/null 2>&1 || fail 'GitHub CLI is not authenticated to github.com'
resolved_repository=$(gh repo view "$github_repository" --json nameWithOwner --jq '.nameWithOwner') ||
	fail 'GitHub CLI cannot access the repository identified by the Git remote'
[[ ${resolved_repository,,} == ${github_repository,,} ]] || fail 'GitHub CLI resolved a different repository than the Git remote'
github_repository=$resolved_repository

local_tag_ref="refs/tags/$tag"
if git -C "$root" show-ref --verify --quiet "$local_tag_ref"; then
	fail "Local Git tag $tag already exists"
fi
set +e
remote_tag_state=$(git -C "$root" ls-remote --exit-code --tags --refs "$remote_name" "$local_tag_ref" 2>&1)
remote_tag_status=$?
set -e
if ((remote_tag_status == 0)); then
	fail "Remote Git tag $tag already exists"
elif ((remote_tag_status != 2)); then
	fail 'Unable to establish that the remote Git tag is absent'
fi

if ! release_tags=$(gh api --paginate "repos/$github_repository/releases?per_page=100" --jq '.[].tag_name'); then
	fail 'Unable to inspect existing GitHub releases'
fi
while IFS= read -r existing_tag; do
	[[ $existing_tag != "$tag" ]] || fail "GitHub release $tag already exists"
done <<< "$release_tags"

check_image_absent() {
	local result status
	set +e
	result=$(docker manifest inspect "$image_reference" 2>&1)
	status=$?
	set -e
	if ((status == 0)); then
		fail "Docker image $image_reference already exists"
	fi
	case ${result,,} in
		*'manifest unknown'*|*'no such manifest'*|*'manifest not found'*) ;;
		*) fail "Unable to establish that Docker image $image_reference is absent" ;;
	esac
}
check_image_absent

[[ ! -L $root/target && ! -L $output_directory ]] || fail 'Deployment release output path must not contain symlinks'
[[ ! -e $output_directory || -d $output_directory ]] || fail 'Deployment release output path is not a directory'
[[ ! -e $archive_path && ! -L $archive_path && ! -e $checksum_path && ! -L $checksum_path ]] ||
	fail 'A local deployment release artifact already exists; move it aside only after review'

if ! source "$root/scripts/release-config.sh"; then
	fail 'Unable to load the configured image repository'
fi
[[ $INTRUVIA_IMAGE_REPOSITORY == "$image_repository" ]] || fail 'Release image repository differs from the project release configuration'

if ! python3 "$root/scripts/update-maven-reactor-version.py" set "$root" "$current_version" "$release_version"; then
	fail 'Unable to set the release version in all five Maven POMs'
fi
completed_steps+=("Set all five Maven POMs to $release_version")
if [[ $(python3 "$root/scripts/update-maven-reactor-version.py" check "$root") != "$release_version" ]]; then
	fail 'Release-version reactor check failed'
fi
expected_paths=$(printf '%s\n' intruvia-app/pom.xml intruvia-core/pom.xml intruvia-rest/pom.xml intruvia-web/pom.xml pom.xml)
changed_paths=$(git -C "$root" diff --name-only)
[[ $changed_paths == "$expected_paths" ]] || fail 'Only the five Maven POM versions may change during release preparation'
git -C "$root" diff --check || fail 'Release-version POM diff contains whitespace errors'

if ! "$root/scripts/verify-postgresql.sh"; then
	fail "PostgreSQL-backed Maven verification at $release_version failed"
fi
completed_steps+=("Passed PostgreSQL-backed Maven verification at $release_version")

distribution="$root/intruvia-app/target/intruvia"
[[ -d $distribution ]] || fail 'Maven verification did not produce the assembled application distribution'
if ! "$root/scripts/build-deployment-image.sh" "$image_reference" "$distribution"; then
	fail "Failed to build Docker image $image_reference"
fi
completed_steps+=("Built Docker image $image_reference")

mkdir -p -- "$output_directory" || fail 'Unable to create the deployment release output directory'
if ! "$root/scripts/build-deployment-tarball.sh" "$release_version" "$image_reference" "$output_directory"; then
	fail 'Failed to build the administrator deployment tarball and checksum'
fi
[[ -f $archive_path && -f $checksum_path ]] || fail 'Deployment tarball or checksum is missing after the build'
if ! (cd "$output_directory" && sha256sum -c "$checksum_name"); then
	fail 'Deployment tarball checksum verification failed'
fi
completed_steps+=("Built and verified $archive_name and $checksum_name")

git -C "$root" add -- pom.xml intruvia-core/pom.xml intruvia-rest/pom.xml intruvia-web/pom.xml intruvia-app/pom.xml ||
	fail 'Unable to stage release POM versions'
git -C "$root" diff --cached --check || fail 'Staged release POM diff contains whitespace errors'
if ! git -C "$root" commit -m "Release $tag"; then
	fail 'Unable to commit the release POM versions'
fi
completed_steps+=("Committed release version $release_version")
if ! git -C "$root" tag -a "$tag" -m "Intruvia $tag"; then
	fail "Unable to create annotated Git tag $tag"
fi
completed_steps+=("Created annotated Git tag $tag")
if ! git -C "$root" push "$remote_name" "HEAD:refs/heads/$branch"; then
	fail "Unable to push release branch $branch"
fi
completed_steps+=("Pushed release branch $branch")
if ! git -C "$root" push "$remote_name" "refs/tags/$tag"; then
	fail "Unable to push release tag $tag"
fi
completed_steps+=("Pushed release tag $tag")

check_image_absent
if ! docker push "$image_reference"; then
	fail "Unable to push Docker image $image_reference"
fi
completed_steps+=("Pushed Docker image $image_reference")

if ! gh release create "$tag" --repo "$github_repository" --verify-tag --generate-notes \
	"$archive_path" "$checksum_path"; then
	fail "Unable to create GitHub release $tag with the deployment assets"
fi
completed_steps+=("Created GitHub release $tag with its deployment assets")
if ! release_details=$(gh release view "$tag" --repo "$github_repository" --json url,assets); then
	fail "Unable to verify GitHub release assets for $tag"
fi
if ! python3 - "$archive_name" "$checksum_name" "$release_details" <<'PY'
import json
import sys

release = json.loads(sys.argv[3])
assets = {asset.get("name") for asset in release.get("assets", [])}
required_assets = set(sys.argv[1:3])
missing = required_assets - assets
if missing:
    raise SystemExit("GitHub release is missing assets: " + ", ".join(sorted(missing)))
print("Verified GitHub release assets: " + ", ".join(sys.argv[1:3]))
PY
then
	fail "GitHub release $tag is missing the deployment tarball or checksum"
fi

if ! python3 "$root/scripts/update-maven-reactor-version.py" set "$root" "$release_version" "$next_snapshot_version"; then
	fail "Unable to set the next development version $next_snapshot_version"
fi
completed_steps+=("Set all five Maven POMs to $next_snapshot_version")
if [[ $(python3 "$root/scripts/update-maven-reactor-version.py" check "$root") != "$next_snapshot_version" ]]; then
	fail 'Next-SNAPSHOT reactor check failed'
fi
git -C "$root" diff --check || fail 'Next-SNAPSHOT POM diff contains whitespace errors'
git -C "$root" add -- pom.xml intruvia-core/pom.xml intruvia-rest/pom.xml intruvia-web/pom.xml intruvia-app/pom.xml ||
	fail 'Unable to stage next-SNAPSHOT POM versions'
git -C "$root" diff --cached --check || fail 'Staged next-SNAPSHOT POM diff contains whitespace errors'
if ! git -C "$root" commit -m "Prepare next development version $next_snapshot_version"; then
	fail 'Unable to commit the next development version'
fi
completed_steps+=("Committed next development version $next_snapshot_version")
if ! git -C "$root" push "$remote_name" "HEAD:refs/heads/$branch"; then
	fail "Unable to push next development version $next_snapshot_version"
fi
completed_steps+=("Pushed next development version $next_snapshot_version")

printf '\nRelease %s completed.\nGitHub: %s\nDocker: %s\nBundle: %s\nChecksum: %s\nNext Maven version: %s\n' \
	"$tag" "$github_repository" "$image_reference" "$archive_path" "$checksum_path" "$next_snapshot_version"
