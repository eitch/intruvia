#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail
export LC_ALL=C

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
mkdir -p "$root/target"
work=$(mktemp -d "$root/target/github-release-test.XXXXXXXX")
trap 'rm -rf -- "$work"' EXIT
real_git=$(command -v git)
bin="$work/bin"
mkdir -p "$bin"

cat > "$bin/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ ${1:-} == remote && ${2:-} == get-url ]]; then
	printf '%s\n' "$RELEASE_TEST_REMOTE_URL"
	exit 0
fi
if [[ ${1:-} == push ]]; then
	printf 'git-push %s\n' "${3:-}" >> "$RELEASE_TEST_LOG"
	if [[ ${RELEASE_TEST_FAIL:-} == push-branch && ${3:-} == HEAD:* ]]; then
		exit 41
	fi
	if [[ ${RELEASE_TEST_FAIL:-} == push-tag && ${3:-} == refs/tags/* ]]; then
		exit 42
	fi
	if [[ ${RELEASE_TEST_FAIL:-} == push-snapshot && ${3:-} == HEAD:* ]]; then
		if [[ -e "$RELEASE_TEST_LOG.push-seen" ]]; then
			exit 43
		fi
		: > "$RELEASE_TEST_LOG.push-seen"
	fi
fi
exec "$RELEASE_TEST_REAL_GIT" "$@"
EOF

cat > "$bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ ${1:-} == auth && ${2:-} == status ]]; then
	exit 0
fi
if [[ ${1:-} == repo && ${2:-} == view ]]; then
	printf '%s\n' "$RELEASE_TEST_GITHUB_REPOSITORY"
	exit 0
fi
if [[ ${1:-} == release && ${2:-} == create ]]; then
	printf 'gh-release %s\n' "$3" >> "$RELEASE_TEST_LOG"
	if [[ ${RELEASE_TEST_FAIL:-} == github ]]; then
		exit 43
	fi
	exit 0
fi
exit 44
EOF

cat > "$bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
arguments=$*
[[ $arguments == *'--config -'* ]] || exit 45
[[ $arguments == *"status@$RELEASE_TEST_MASTODON_FILE"* ]] || exit 46
[[ $arguments != *"$MASTODON_ACCESS_TOKEN"* ]] || exit 47
configuration=
while IFS= read -r line; do
	configuration+="$line"$'\n'
done
[[ $configuration == *"Authorization: Bearer $MASTODON_ACCESS_TOKEN"* ]] || exit 48
printf 'mastodon\n' >> "$RELEASE_TEST_LOG"
if [[ ${RELEASE_TEST_FAIL:-} == mastodon ]]; then
	exit 22
fi
EOF

chmod 0755 "$bin/git" "$bin/gh" "$bin/curl"
printf '%s\n' 'Reviewed test release notes.' > "$work/release-notes.md"
printf '%s\n' 'Intruvia test release announcement.' > "$work/mastodon.txt"
remote_url='git@github.com:owner/intruvia.git'

setup_project() {
	local name=$1
	release_version=${2:-0.0.1}
	next_snapshot_version=${3:-0.0.2-SNAPSHOT}
	tag="v$release_version"
	case_root="$work/$name"
	project="$case_root/project"
	bare="$case_root/remote.git"
	log="$case_root/commands.log"
	output="$case_root/output.log"
	mkdir -p "$project/scripts"
	"$real_git" init --quiet --bare "$bare"
	"$real_git" init --quiet -b main "$project"
	"$real_git" -C "$project" config user.name 'Release Test'
	"$real_git" -C "$project" config user.email 'release-test@example.invalid'
	cp -p "$root/scripts/create-github-release.sh" "$project/scripts/"
	cp -p "$root/scripts/update-maven-reactor-version.py" "$project/scripts/"
	cat > "$project/pom.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
	<modelVersion>4.0.0</modelVersion>
	<groupId>li.intruvia</groupId>
	<artifactId>intruvia</artifactId>
	<packaging>pom</packaging>
	<modules>
		<module>intruvia-core</module>
		<module>intruvia-rest</module>
		<module>intruvia-web</module>
		<module>intruvia-app</module>
	</modules>
	<version>0.0.1</version>
</project>
EOF
	for module in intruvia-core intruvia-rest intruvia-web intruvia-app; do
		mkdir -p "$project/$module"
		cat > "$project/$module/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>li.intruvia</groupId>
		<artifactId>intruvia</artifactId>
		<version>0.0.1</version>
	</parent>
	<artifactId>$module</artifactId>
</project>
EOF
	done
	cat > "$project/scripts/verify-postgresql.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
version=$(python3 "$(dirname "$0")/update-maven-reactor-version.py" check "$(dirname "$0")/..")
printf 'verify %s\n' "$version" >> "$RELEASE_TEST_LOG"
[[ $version == "$RELEASE_TEST_EXPECTED_VERSION" ]] || exit 49
[[ ${RELEASE_TEST_FAIL:-} != verify ]]
EOF
	chmod 0755 "$project/scripts/verify-postgresql.sh"
	"$real_git" -C "$project" add -- .
	"$real_git" -C "$project" commit --quiet -m 'Initial fixture'
	"$real_git" -C "$project" remote add origin "$bare"
	"$real_git" -C "$project" push --quiet --set-upstream origin main
	"$real_git" --git-dir="$bare" symbolic-ref HEAD refs/heads/main
	: > "$log"
	args=(
		--release-version "$release_version"
		--next-snapshot-version "$next_snapshot_version"
		--release-notes-file "$work/release-notes.md"
		--mastodon-text-file "$work/mastodon.txt"
		--mastodon-instance https://mastodon.example
		--remote origin
		--remote-url "$remote_url"
		--branch main
		--github-repository owner/intruvia
		--approve-release "$tag"
	)
}

invoke_script() (
	cd "$project"
	PATH="$bin:$PATH" \
	RELEASE_TEST_LOG="$log" \
	RELEASE_TEST_FAIL="${test_fail:-}" \
	RELEASE_TEST_REAL_GIT="$real_git" \
	RELEASE_TEST_REMOTE_URL="$remote_url" \
	RELEASE_TEST_GITHUB_REPOSITORY=owner/intruvia \
	RELEASE_TEST_EXPECTED_VERSION="$release_version" \
	RELEASE_TEST_MASTODON_FILE="$work/mastodon.txt" \
	MASTODON_ACCESS_TOKEN='test-token-value' \
	"$project/scripts/create-github-release.sh" "${args[@]}"
)

expect_failure() {
	local expected=$1
	if invoke_script > "$output" 2>&1; then
		printf 'FAIL: expected release failure containing: %s\n' "$expected" >&2
		exit 1
	fi
	local result
	result=$(<"$output")
	[[ $result == *"$expected"* ]] || {
		printf 'FAIL: release failure did not report: %s\n' "$expected" >&2
		printf '%s\n' "$result" >&2
		exit 1
	}
	[[ $result == *'Manual recovery:'* ]] || {
		printf 'FAIL: release failure omitted manual recovery guidance\n' >&2
		printf '%s\n' "$result" >&2
		exit 1
	}
}

setup_project success 0.0.2 0.0.3-SNAPSHOT
test_fail=
invoke_script > "$output" 2>&1 || {
	printf 'FAIL: successful isolated release workflow failed\n' >&2
	cat "$output" >&2
	exit 1
}
printf '%s\n' \
	'verify 0.0.2' \
	'git-push HEAD:main' \
	'git-push refs/tags/v0.0.2' \
	'gh-release v0.0.2' \
	'git-push HEAD:main' \
	'mastodon' > "$work/expected.log"
cmp "$work/expected.log" "$log"
[[ $(python3 "$project/scripts/update-maven-reactor-version.py" check "$project") == 0.0.3-SNAPSHOT ]]
release_commit=$("$real_git" -C "$project" rev-parse 'refs/tags/v0.0.2^{commit}')
release_files=$("$real_git" -C "$project" diff-tree --no-commit-id --name-only -r "$release_commit" | sort)
printf '%s\n' intruvia-app/pom.xml intruvia-core/pom.xml intruvia-rest/pom.xml intruvia-web/pom.xml pom.xml > "$work/expected-files.txt"
cmp "$work/expected-files.txt" <(printf '%s\n' "$release_files")
[[ $("$real_git" -C "$project" rev-parse refs/remotes/origin/main) == $("$real_git" -C "$project" rev-parse HEAD) ]]
[[ -n $("$real_git" -C "$project" ls-remote --tags --refs origin refs/tags/v0.0.2) ]]

setup_project same-version
test_fail=
invoke_script > "$output" 2>&1 || {
	printf 'FAIL: same-version release workflow failed\n' >&2
	cat "$output" >&2
	exit 1
}
same_version_commit=$("$real_git" -C "$project" rev-parse 'refs/tags/v0.0.1^{commit}')
[[ -z $("$real_git" -C "$project" diff-tree --no-commit-id --name-only -r "$same_version_commit") ]]

setup_project dirty
printf '%s\n' 'uncommitted change' > "$project/dirty.txt"
test_fail=
expect_failure 'Working tree is not clean'
[[ ! -s $log ]]

setup_project out-of-sync
peer="$case_root/peer"
"$real_git" clone --quiet "$bare" "$peer"
"$real_git" -C "$peer" config user.name 'Release Test'
"$real_git" -C "$peer" config user.email 'release-test@example.invalid'
printf '%s\n' 'remote-only update' > "$peer/remote-update.txt"
"$real_git" -C "$peer" add -- remote-update.txt
"$real_git" -C "$peer" commit --quiet -m 'Remote update'
"$real_git" -C "$peer" push --quiet origin main
test_fail=
expect_failure 'Remote branch is missing or does not point at the local revision'
[[ ! -s $log ]]

setup_project existing-tag
"$real_git" -C "$project" tag -a v0.0.1 -m 'Existing tag'
"$real_git" -C "$project" push --quiet origin refs/tags/v0.0.1
test_fail=
expect_failure 'Local tag v0.0.1 already exists'
[[ ! -s $log ]]

setup_project remote-tag
"$real_git" -C "$project" tag -a v0.0.1 -m 'Remote existing tag'
"$real_git" -C "$project" push --quiet origin refs/tags/v0.0.1
"$real_git" -C "$project" tag -d v0.0.1 >/dev/null
test_fail=
expect_failure 'Remote tag v0.0.1 already exists'
[[ ! -s $log ]]

setup_project wrong-approval
args[${#args[@]}-1]=v9.9.9
test_fail=
expect_failure 'Release approval must exactly match v0.0.1'
[[ ! -s $log ]]
[[ $(python3 "$project/scripts/update-maven-reactor-version.py" check "$project") == 0.0.1 ]]

setup_project verification-failure
test_fail=verify
expect_failure 'PostgreSQL-backed verification at 0.0.1 failed'
printf '%s\n' 'verify 0.0.1' > "$work/expected.log"
cmp "$work/expected.log" "$log"
[[ $(python3 "$project/scripts/update-maven-reactor-version.py" check "$project") == 0.0.1 ]]

setup_project push-failure
test_fail=push-branch
expect_failure 'Push release branch main failed'
printf '%s\n' 'verify 0.0.1' 'git-push HEAD:main' > "$work/expected.log"
cmp "$work/expected.log" "$log"
[[ -z $("$real_git" -C "$project" ls-remote --tags --refs origin refs/tags/v0.0.1) ]]

setup_project tag-push-failure
test_fail=push-tag
expect_failure 'Push release tag v0.0.1 failed'
printf '%s\n' 'verify 0.0.1' 'git-push HEAD:main' 'git-push refs/tags/v0.0.1' > "$work/expected.log"
cmp "$work/expected.log" "$log"
[[ -z $("$real_git" -C "$project" ls-remote --tags --refs origin refs/tags/v0.0.1) ]]

setup_project github-failure
test_fail=github
expect_failure 'Create GitHub release v0.0.1 failed'
printf '%s\n' \
	'verify 0.0.1' \
	'git-push HEAD:main' \
	'git-push refs/tags/v0.0.1' \
	'gh-release v0.0.1' > "$work/expected.log"
cmp "$work/expected.log" "$log"
[[ $(python3 "$project/scripts/update-maven-reactor-version.py" check "$project") == 0.0.1 ]]

setup_project snapshot-push-failure
test_fail=push-snapshot
expect_failure 'Push next SNAPSHOT branch main failed'
printf '%s\n' \
	'verify 0.0.1' \
	'git-push HEAD:main' \
	'git-push refs/tags/v0.0.1' \
	'gh-release v0.0.1' \
	'git-push HEAD:main' > "$work/expected.log"
cmp "$work/expected.log" "$log"
[[ $(python3 "$project/scripts/update-maven-reactor-version.py" check "$project") == 0.0.2-SNAPSHOT ]]
[[ -n $("$real_git" -C "$project" ls-remote --tags --refs origin refs/tags/v0.0.1) ]]

setup_project mastodon-failure
test_fail=mastodon
expect_failure 'Post Mastodon announcement failed'
printf '%s\n' \
	'verify 0.0.1' \
	'git-push HEAD:main' \
	'git-push refs/tags/v0.0.1' \
	'gh-release v0.0.1' \
	'git-push HEAD:main' \
	'mastodon' > "$work/expected.log"
cmp "$work/expected.log" "$log"
[[ $(python3 "$project/scripts/update-maven-reactor-version.py" check "$project") == 0.0.2-SNAPSHOT ]]
mastodon_failure_output=$(<"$output")
[[ $mastodon_failure_output == *'avoid a duplicate post'* ]]
[[ $mastodon_failure_output != *'test-token-value'* ]]

printf 'GitHub release workflow tests passed\n'