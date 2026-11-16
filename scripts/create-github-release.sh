#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail
export LC_ALL=C

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
completed_steps=()

usage() {
	cat <<'EOF'
Usage: scripts/create-github-release.sh \
	--release-version VERSION \
	--next-snapshot-version VERSION \
	--release-notes-file FILE \
	--mastodon-text-file FILE \
	--mastodon-instance HTTPS_URL \
	--remote NAME --remote-url GITHUB_URL --branch NAME \
	--github-repository OWNER/REPOSITORY \
	--approve-release vVERSION

MASTODON_ACCESS_TOKEN must be set in the environment. The approval value must
exactly match the tag that will be created.
EOF
}

fail() {
	local reason=$1
	local recovery=$2
	printf 'ERROR: %s\n' "$reason" >&2
	if ((${#completed_steps[@]})); then
		printf 'Completed actions: %s\n' "$(IFS='; '; printf '%s' "${completed_steps[*]}")" >&2
	else
		printf 'Completed actions: none\n' >&2
	fi
	printf 'Manual recovery: %s\n' "$recovery" >&2
	printf 'No automatic retry or rollback was performed.\n' >&2
	exit 1
}

run_step() {
	local label=$1
	local recovery=$2
	shift 2
	printf '==> %s\n' "$label"
	if "$@"; then
		completed_steps+=("$label")
	else
		fail "$label failed" "$recovery"
	fi
}

post_mastodon() {
	python3 - <<'PY' | curl --config - --fail --silent --show-error --output /dev/null \
		--data-urlencode "status@$mastodon_text_file" "$mastodon_endpoint"
import os
import sys

token = os.environ.get("MASTODON_ACCESS_TOKEN", "")
if not token or any(ord(character) < 32 for character in token):
	sys.exit(2)
escaped = token.replace("\\", "\\\\").replace('"', '\\"')
sys.stdout.write('header = "Authorization: Bearer ' + escaped + '"\n')
PY
}

assert_only_version_changes() {
	local previous_version=$1
	local target_version=$2
	local status line actual_sorted expected_sorted
	local -a actual_paths=()
	local -a expected_paths=()
	status=$(git status --porcelain --untracked-files=all) || \
		fail "Unable to inspect the worktree after version update" "Inspect Git status and repair the repository before continuing."
	while IFS= read -r line; do
		[[ -n $line ]] && actual_paths+=("${line:3}")
	done <<< "$status"
	if [[ $previous_version != "$target_version" ]]; then
		expected_paths=(pom.xml intruvia-core/pom.xml intruvia-rest/pom.xml intruvia-web/pom.xml intruvia-app/pom.xml)
	fi
	actual_sorted=$(printf '%s\n' "${actual_paths[@]}" | sort)
	expected_sorted=$(printf '%s\n' "${expected_paths[@]}" | sort)
	[[ $actual_sorted == "$expected_sorted" ]] || \
		fail "Worktree contains changes beyond the expected version POMs" "Inspect all generated/modified paths and restore a clean release worktree manually."
}

assert_clean_worktree() {
	local status
	status=$(git status --porcelain --untracked-files=all) || \
		fail "Unable to inspect the worktree after commit" "Inspect Git status and repair the repository before any push."
	[[ -z $status ]] || fail "Worktree is not clean after commit" "Inspect uncommitted paths and the local commit before any push."
}

resolve_file() {
	local file=$1
	local directory
	directory=$(cd -- "$(dirname -- "$file")" && pwd) || return 1
	printf '%s/%s\n' "$directory" "$(basename -- "$file")"
}

release_version=
next_snapshot_version=
release_notes_file=
mastodon_text_file=
mastodon_instance=
remote_name=
expected_remote_url=
branch=
github_repository=
approved_tag=
declare -A seen_options=()

while (($#)); do
	case $1 in
		--release-version|--next-snapshot-version|--release-notes-file|--mastodon-text-file|--mastodon-instance|--remote|--remote-url|--branch|--github-repository|--approve-release)
			(($# >= 2)) || { usage >&2; exit 2; }
			key=$1
			[[ -z ${seen_options[$key]+present} ]] || fail "Duplicate option: $key" "Supply each option once and review the release values."
			seen_options[$key]=present
			value=$2
			shift 2
			case $key in
				--release-version) release_version=$value ;;
				--next-snapshot-version) next_snapshot_version=$value ;;
				--release-notes-file) release_notes_file=$value ;;
				--mastodon-text-file) mastodon_text_file=$value ;;
				--mastodon-instance) mastodon_instance=$value ;;
				--remote) remote_name=$value ;;
				--remote-url) expected_remote_url=$value ;;
				--branch) branch=$value ;;
				--github-repository) github_repository=$value ;;
				--approve-release) approved_tag=$value ;;
			esac
			;;
		--help|-h)
			usage
			exit 0
			;;
		*)
			usage >&2
			printf 'ERROR: Unknown argument: %s\n' "$1" >&2
			exit 2
			;;
	esac
done

for name in release_version next_snapshot_version release_notes_file mastodon_text_file mastodon_instance \
	remote_name expected_remote_url branch github_repository approved_tag; do
	[[ -n ${!name} ]] || { usage >&2; fail "Missing required option for $name" "Supply every required option; no changes were made."; }
done

[[ $release_version =~ ^[0-9][A-Za-z0-9._+-]*$ && $release_version != *-SNAPSHOT ]] || \
	fail "Release version must be a non-SNAPSHOT Maven version" "Choose a valid release version and rerun after reviewing the state."
[[ $next_snapshot_version =~ ^[0-9][A-Za-z0-9._+-]*-SNAPSHOT$ ]] || \
	fail "Next development version must end in -SNAPSHOT" "Choose a valid next SNAPSHOT version and rerun after reviewing the state."
tag="v${release_version}"
[[ $approved_tag == "$tag" ]] || \
	fail "Release approval must exactly match $tag" "Review the release gates, then explicitly approve the exact tag."
git check-ref-format "refs/tags/$tag" >/dev/null 2>&1 || \
	fail "Release version does not form a valid Git tag" "Choose a valid version and review the release state."
[[ $remote_name =~ ^[A-Za-z0-9._-]+$ ]] || fail "Invalid Git remote name" "Use the exact configured Git remote name."
[[ $github_repository =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || \
	fail "GitHub repository must be OWNER/REPOSITORY" "Supply the exact GitHub.com repository path."

for command_name in git gh curl python3; do
	command -v "$command_name" >/dev/null 2>&1 || fail "Required command not found: $command_name" "Install the required command and rerun after reviewing the repository state."
done
[[ -x $root/scripts/verify-postgresql.sh ]] || \
	fail "scripts/verify-postgresql.sh is missing or not executable" "Restore the project verification script before attempting a release."
[[ -s $release_notes_file ]] || fail "Release notes file is missing or empty" "Prepare reviewed release notes before starting a release."
[[ -s $mastodon_text_file ]] || fail "Mastodon text file is missing or empty" "Prepare reviewed announcement text before starting a release."
[[ -n ${MASTODON_ACCESS_TOKEN:-} ]] || \
	fail "MASTODON_ACCESS_TOKEN is not set" "Load the Mastodon token into the environment without putting it in command arguments."

release_notes_file=$(resolve_file "$release_notes_file") || fail "Cannot resolve release notes file" "Provide a readable release notes file."
mastodon_text_file=$(resolve_file "$mastodon_text_file") || fail "Cannot resolve Mastodon text file" "Provide a readable Mastodon text file."
[[ -r $release_notes_file && -s $release_notes_file ]] || fail "Release notes file is not readable or is empty" "Prepare reviewed release notes before starting a release."
[[ -r $mastodon_text_file && -s $mastodon_text_file ]] || fail "Mastodon text file is not readable or is empty" "Prepare reviewed announcement text before starting a release."

if ! python3 - "$mastodon_text_file" "$mastodon_instance" <<'PY'
import sys
from urllib.parse import urlparse

text_path, instance = sys.argv[1:]
try:
	with open(text_path, encoding="utf-8") as file:
		text = file.read()
except (OSError, UnicodeError) as error:
	print(f"Invalid Mastodon text file: {error}", file=sys.stderr)
	sys.exit(1)
if not text.strip() or len(text.strip()) > 500 or "\0" in text:
	print("Mastodon text must contain 1–500 characters", file=sys.stderr)
	sys.exit(1)
url = urlparse(instance)
if url.scheme != "https" or not url.hostname or url.username or url.password or url.path not in ("", "/") or url.query or url.fragment:
	print("Mastodon instance must be an HTTPS origin without a path, query or fragment", file=sys.stderr)
	sys.exit(1)
try:
	url.port
except ValueError:
	print("Mastodon instance has an invalid port", file=sys.stderr)
	sys.exit(1)
PY
then
	fail "Mastodon announcement settings are invalid" "Correct the instance URL or announcement text and review the release state."
fi

case "$expected_remote_url" in
	"https://github.com/${github_repository}"|"https://github.com/${github_repository}.git"|\
	"git@github.com:${github_repository}"|"git@github.com:${github_repository}.git"|\
	"ssh://git@github.com/${github_repository}"|"ssh://git@github.com/${github_repository}.git") ;;
	*) fail "Expected remote URL does not identify https://github.com/${github_repository}" "Confirm the exact repository and remote URL before running the release." ;;
esac

cd "$root"
actual_branch=$(git branch --show-current) || fail "Cannot determine the current Git branch" "Check out the intended release branch and rerun."
[[ $actual_branch == "$branch" ]] || fail "Current branch $actual_branch does not match requested branch $branch" "Check out the exact reviewed release branch and rerun."
git check-ref-format --branch "$branch" >/dev/null 2>&1 || fail "Invalid branch name" "Supply a valid exact branch name."
actual_remote_url=$(git remote get-url "$remote_name") || fail "Git remote $remote_name is not configured" "Configure the intended remote and rerun."
[[ $actual_remote_url == "$expected_remote_url" ]] || \
	fail "Git remote URL does not match the explicitly approved URL" "Confirm and supply the exact configured GitHub remote URL."
fetch_remote_urls=$(git remote get-url --all "$remote_name") || \
	fail "Unable to determine all fetch URLs for the remote" "Confirm the configured Git fetch destinations before release."
[[ $fetch_remote_urls == "$expected_remote_url" ]] || \
	fail "Git remote has extra or mismatched fetch URLs" "Remove extra or mismatched fetch URLs before release."
push_remote_urls=$(git remote get-url --push --all "$remote_name") || \
	fail "Unable to determine the remote push URL" "Confirm the configured Git push destination before release."
[[ $push_remote_urls == "$expected_remote_url" ]] || \
	fail "Git push URL does not match the explicitly approved URL" "Remove extra or mismatched push URLs before release."
upstream=$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null) || \
	fail "Current branch has no upstream" "Configure the reviewed branch to track $remote_name/$branch and rerun."
[[ $upstream == "$remote_name/$branch" ]] || fail "Branch upstream $upstream does not match $remote_name/$branch" "Set the intended upstream and rerun."
worktree_status=$(git status --porcelain --untracked-files=all) || \
	fail "Unable to inspect the worktree" "Inspect Git status and repair the repository before starting a release."
[[ -z $worktree_status ]] || \
	fail "Working tree is not clean" "Review or commit all existing changes, then start again from the clean release revision."
tracking_counts=$(git rev-list --left-right --count "HEAD...$upstream") || \
	fail "Unable to compare the branch with its upstream" "Repair the branch tracking state and rerun."
[[ $tracking_counts =~ ^0[[:space:]]+0$ ]] || \
	fail "Local branch is not synchronized with $upstream" "Review and synchronize the branch manually before starting a new release attempt."
remote_branch=$(git ls-remote --heads "$remote_name" "refs/heads/$branch") || \
	fail "Unable to inspect the remote branch" "Check network access and remote state; no release action was attempted."
remote_commit=${remote_branch%%$'\t'*}
[[ -n $remote_commit && $remote_commit == "$(git rev-parse HEAD)" ]] || \
	fail "Remote branch is missing or does not point at the local revision" "Review and synchronize the branch manually before starting a new release attempt."
if git show-ref --verify --quiet "refs/tags/$tag"; then
	fail "Local tag $tag already exists" "Inspect the existing tag and release state; do not overwrite or force-push it."
fi
remote_tag=$(git ls-remote --tags --refs "$remote_name" "refs/tags/$tag") || \
	fail "Unable to inspect the remote tag state" "Check remote access and tag state before retrying."
[[ -z $remote_tag ]] || fail "Remote tag $tag already exists" "Inspect the existing tag and GitHub release; do not overwrite or force-push it."

gh auth status --hostname github.com >/dev/null || \
	fail "GitHub CLI is not authenticated to github.com" "Authenticate the intended GitHub account, then review the repository and release state."
gh_repository_result=$(gh repo view "$github_repository" --json nameWithOwner --jq .nameWithOwner) || \
	fail "GitHub repository cannot be read with the configured account" "Check repository access and confirm the exact GitHub repository before retrying."
[[ $gh_repository_result == "$github_repository" ]] || \
	fail "GitHub CLI resolved a different repository" "Resolve the repository identity mismatch before starting a release."

current_version=$(python3 "$root/scripts/update-maven-reactor-version.py" check "$root") || \
	fail "Maven reactor POM versions are inconsistent or invalid" "Review all five reactor POMs manually; no release action was performed."
export INTRUVIA_RELEASE_VERSION=$release_version
if ! python3 "$root/scripts/update-maven-reactor-version.py" set "$root" "$current_version" "$release_version"; then
	fail "Unable to set the release version in all five Maven POMs" "Inspect the five POMs for partial updates; do not continue or publish until they are consistent."
fi
completed_steps+=("Set all five Maven POMs to $release_version")
assert_only_version_changes "$current_version" "$release_version"
git diff --check || fail "Release-version POM diff has whitespace errors" "Review and repair the POM diff before running verification or publishing."
if [[ $(python3 "$root/scripts/update-maven-reactor-version.py" check "$root") != "$release_version" ]]; then
	fail "Release POM version check failed" "Inspect all five POMs and restore a consistent reviewed version before continuing."
fi
run_step "PostgreSQL-backed verification at $release_version" \
	"Inspect the release-version POMs and verification output; fix the issue and begin a new reviewed release attempt." \
	"$root/scripts/verify-postgresql.sh"
[[ $(python3 "$root/scripts/update-maven-reactor-version.py" check "$root") == "$release_version" ]] || \
	fail "Verification changed the release POM versions" "Inspect all five POMs and repeat verification on the intended release version."
assert_only_version_changes "$current_version" "$release_version"
git diff --check || fail "Post-verification release diff has whitespace errors" "Review and repair the POM diff before committing."
git add -- pom.xml intruvia-core/pom.xml intruvia-rest/pom.xml intruvia-web/pom.xml intruvia-app/pom.xml || \
	fail "Unable to stage the five reactor POMs" "Inspect the index and worktree; stage only the intended release-version POMs manually."
run_step "Commit release version $release_version" \
	"Inspect the local release commit and POM state; do not publish until its contents are reviewed." \
	git commit --allow-empty -m "[New] Release $tag"
assert_clean_worktree
run_step "Create annotated tag $tag" \
	"Inspect the local commit and tag state; do not replace or force-update any tag." \
	git tag -a "$tag" -m "Intruvia $tag"
run_step "Push release branch $branch" \
	"Inspect remote branch and local commit state; if the branch was not updated, resolve manually without force-pushing." \
	git push "$remote_name" "HEAD:$branch"
run_step "Push release tag $tag" \
	"The release branch may already be pushed. Inspect both remote refs, then push only the missing intended tag without force." \
	git push "$remote_name" "refs/tags/$tag"
run_step "Create GitHub release $tag" \
	"Inspect GitHub for an existing or partially created release before any manual retry; the branch and tag may already be published." \
	gh release create "$tag" --repo "$github_repository" --verify-tag --title "Intruvia $tag" --notes-file "$release_notes_file"

if ! python3 "$root/scripts/update-maven-reactor-version.py" set "$root" "$release_version" "$next_snapshot_version"; then
	fail "Unable to set the next SNAPSHOT version in all five Maven POMs" "The GitHub release may already exist; inspect all five POMs and complete the development-version update manually."
fi
completed_steps+=("Set all five Maven POMs to $next_snapshot_version")
assert_only_version_changes "$release_version" "$next_snapshot_version"
git diff --check || fail "Next-SNAPSHOT POM diff has whitespace errors" "Review and repair the development-version POM diff before committing."
if [[ $(python3 "$root/scripts/update-maven-reactor-version.py" check "$root") != "$next_snapshot_version" ]]; then
	fail "Next-SNAPSHOT POM version check failed" "The GitHub release may already exist; inspect and repair all five POMs manually."
fi
git add -- pom.xml intruvia-core/pom.xml intruvia-rest/pom.xml intruvia-web/pom.xml intruvia-app/pom.xml || \
	fail "Unable to stage the next-SNAPSHOT POMs" "Inspect the index and worktree; stage only the intended development-version POMs manually."
run_step "Commit next SNAPSHOT version $next_snapshot_version" \
	"The GitHub release may already exist. Inspect local POMs and commit state before manually completing the next-SNAPSHOT transition." \
	git commit -m "[Project] Start $next_snapshot_version"
assert_clean_worktree
run_step "Push next SNAPSHOT branch $branch" \
	"The release/tag may already be public. Inspect the remote branch and local snapshot commit, then complete the push manually without force." \
	git push "$remote_name" "HEAD:$branch"

mastodon_endpoint="${mastodon_instance%/}/api/v1/statuses"
run_step "Post Mastodon announcement" \
	"The request may have been accepted despite the error. Inspect the account before any manual retry to avoid a duplicate post." \
	post_mastodon

printf 'Release workflow completed for %s.\n' "$tag"