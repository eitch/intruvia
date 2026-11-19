#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail
export LC_ALL=C

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
mkdir -p "$root/target"
work=$(mktemp -d "$root/target/unified-release-test.XXXXXXXX")
trap 'rm -rf -- "$work"' EXIT
real_git=$(command -v git)
bin="$work/bin"
mkdir -p "$bin"

cat > "$bin/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
real_git=$RELEASE_TEST_REAL_GIT
args=("$@")
while [[ ${args[0]:-} == -C ]]; do
	args=("${args[@]:2}")
done
if [[ ${args[0]:-} == remote && ${args[1]:-} == get-url ]]; then
	if [[ ${args[2]:-} == --push ]]; then
		printf '%s\n' "${RELEASE_TEST_PUSH_URL:-$RELEASE_TEST_REMOTE_URL}"
	else
		printf '%s\n' "$RELEASE_TEST_REMOTE_URL"
	fi
	exit 0
fi
if [[ ${args[0]:-} == push ]]; then
	ref=${args[2]:-}
	case $ref in
		HEAD:refs/heads/*)
			printf 'git-push branch\n' >> "$RELEASE_TEST_LOG"
			if [[ ${RELEASE_TEST_FAIL:-} == push-branch && ! -e $RELEASE_TEST_LOG.branch-pushed ]]; then
				exit 41
			fi
			if [[ ${RELEASE_TEST_FAIL:-} == push-snapshot && -e $RELEASE_TEST_LOG.branch-pushed ]]; then
				exit 43
			fi
			: > "$RELEASE_TEST_LOG.branch-pushed"
			;;
		refs/tags/*)
			printf 'git-push tag\n' >> "$RELEASE_TEST_LOG"
			if [[ ${RELEASE_TEST_FAIL:-} == push-tag ]]; then
				exit 42
			fi
			;;
	esac
fi
exec "$real_git" "$@"
EOF

cat > "$bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-} ${2:-}" in
	'auth status') exit 0 ;;
	'repo view') printf '%s\n' "$RELEASE_TEST_GITHUB_REPOSITORY" ;;
	'api --paginate') printf '%s\n' "${RELEASE_TEST_EXISTING_RELEASES:-}" ;;
	'release create')
		printf 'gh-create %s\n' "$3" >> "$RELEASE_TEST_LOG"
		for argument in "${@:4}"; do
			case $argument in
				*.tar.gz|*.tar.gz.sha256) printf 'gh-asset %s\n' "${argument##*/}" >> "$RELEASE_TEST_LOG" ;;
			esac
		done
		[[ ${RELEASE_TEST_FAIL:-} != github ]]
		;;
	'release view')
		printf 'gh-view %s\n' "$3" >> "$RELEASE_TEST_LOG"
		[[ ${RELEASE_TEST_FAIL:-} != github-view ]] || exit 44
		printf '{"url":"https://github.com/owner/intruvia/releases/tag/%s","assets":[{"name":"intruvia-deployment-%s.tar.gz"},{"name":"intruvia-deployment-%s.tar.gz.sha256"}]}\n' \
			"$3" "$RELEASE_TEST_VERSION" "$RELEASE_TEST_VERSION"
		;;
	*) exit 45 ;;
esac
EOF

cat > "$bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-} ${2:-}" in
	'info ') exit 0 ;;
	'manifest inspect')
		if [[ ${RELEASE_TEST_IMAGE_EXISTS:-} == true ]]; then
			printf '{"schemaVersion":2}\n'
			exit 0
		fi
		printf 'manifest unknown: manifest unknown\n' >&2
		exit 1
		;;
	'tag '* )
		printf 'docker-tag %s %s\n' "$2" "$3" >> "$RELEASE_TEST_LOG"
		[[ ${RELEASE_TEST_FAIL:-} != docker-tag-minor || $3 != *:1.2 ]] || exit 47
		[[ ${RELEASE_TEST_FAIL:-} != docker-tag-latest || $3 != *:latest ]] || exit 49
		;;
	'push '*)
		printf 'docker-push %s\n' "$2" >> "$RELEASE_TEST_LOG"
		[[ ${RELEASE_TEST_FAIL:-} != docker-push ]] || exit 48
		[[ ${RELEASE_TEST_FAIL:-} != docker-push-minor || $2 != *:1.2 ]]
		[[ ${RELEASE_TEST_FAIL:-} != docker-push-latest || $2 != *:latest ]]
		;;
	*) exit 46 ;;
esac
EOF

cat > "$bin/mvn" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
cat > "$bin/java" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod 0755 "$bin/git" "$bin/gh" "$bin/docker" "$bin/mvn" "$bin/java"

fail() {
	printf 'FAIL: %s\n' "$*" >&2
	exit 1
}

setup_project() {
	local name=$1 version=$2
	case_root="$work/$name"
	project="$case_root/project"
	bare="$case_root/remote.git"
	log="$case_root/commands.log"
	output="$case_root/output.log"
	remote_url='git@github.com:owner/intruvia.git'
	push_url=
	mkdir -p "$project/scripts"
	"$real_git" init --quiet --bare "$bare"
	"$real_git" init --quiet -b main "$project"
	"$real_git" -C "$project" config user.name 'Release Test'
	"$real_git" -C "$project" config user.email 'release-test@example.invalid'
	cp -p "$root/scripts/release.sh" "$project/scripts/"
	cp -p "$root/scripts/release-config.sh" "$project/scripts/"
	cp -p "$root/scripts/update-maven-reactor-version.py" "$project/scripts/"
	cat > "$project/pom.xml" <<EOF
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
	<version>$version</version>
</project>
EOF
	printf '/target/\n' > "$project/.gitignore"
	for module in intruvia-core intruvia-rest intruvia-web intruvia-app; do
		mkdir -p "$project/$module"
		cat > "$project/$module/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>li.intruvia</groupId>
		<artifactId>intruvia</artifactId>
		<version>$version</version>
	</parent>
	<artifactId>$module</artifactId>
</project>
EOF
	done
	cat > "$project/scripts/verify-postgresql.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
version=$(python3 "$root/scripts/update-maven-reactor-version.py" check "$root")
printf 'verify %s\n' "$version" >> "$RELEASE_TEST_LOG"
[[ ${RELEASE_TEST_FAIL:-} != verify ]]
mkdir -p "$root/intruvia-app/target/intruvia"
printf 'fixture distribution\n' > "$root/intruvia-app/target/intruvia/intruvia-app-$version.jar"
EOF
	cat > "$project/scripts/build-deployment-image.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'image-build %s\n' "$1" >> "$RELEASE_TEST_LOG"
[[ ${RELEASE_TEST_FAIL:-} != image-build ]]
EOF
	cat > "$project/scripts/build-deployment-tarball.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
version=$1
image=$2
output=$3
archive="intruvia-deployment-$version.tar.gz"
mkdir -p "$output"
printf 'bundle %s %s\n' "$version" "$image" >> "$RELEASE_TEST_LOG"
[[ ${RELEASE_TEST_FAIL:-} != bundle ]] || exit 1
printf 'fixture archive for %s\n' "$version" > "$output/$archive"
(cd "$output" && sha256sum "$archive" > "$archive.sha256")
EOF
	chmod 0755 "$project/scripts/verify-postgresql.sh" "$project/scripts/build-deployment-image.sh" \
		"$project/scripts/build-deployment-tarball.sh"
	"$real_git" -C "$project" add -- .
	"$real_git" -C "$project" commit --quiet -m 'Initial fixture'
	"$real_git" -C "$project" remote add origin "$bare"
	"$real_git" -C "$project" push --quiet --set-upstream origin main
	"$real_git" --git-dir="$bare" symbolic-ref HEAD refs/heads/main
	: > "$log"
}

invoke_release() (
	cd "$project"
	export PATH="$bin:$PATH"
	export RELEASE_TEST_LOG="$log"
	export RELEASE_TEST_REAL_GIT="$real_git"
	export RELEASE_TEST_REMOTE_URL="$remote_url"
	export RELEASE_TEST_PUSH_URL="${push_url:-$remote_url}"
	export RELEASE_TEST_GITHUB_REPOSITORY=owner/intruvia
	export RELEASE_TEST_VERSION="$expected_release"
	export RELEASE_TEST_FAIL="${test_fail:-}"
	export RELEASE_TEST_IMAGE_EXISTS="${image_exists:-}"
	export RELEASE_TEST_EXISTING_RELEASES="${existing_releases:-}"
	printf '%s\n' "$release_type" "${gate_confirmation:-yes}" "${tag_approval:-$expected_tag}" | \
		"$project/scripts/release.sh"
)

expect_failure() {
	local expected=$1 result
	if invoke_release > "$output" 2>&1; then
		fail "expected failure containing: $expected"
	fi
	result=$(<"$output")
	[[ $result == *"$expected"* ]] || {
		printf '%s\n' "$result" >&2
		fail "failure did not report: $expected"
	}
	[[ $result == *'Manual recovery:'* ]] || fail 'failure omitted manual recovery guidance'
}

assert_success() {
	local release_version=$1 next_version=$2 tag=$3
	invoke_release > "$output" 2>&1 || {
		printf '%s\n' "$(<"$output")" >&2
		fail "release $tag did not complete"
	}
	[[ $(python3 "$project/scripts/update-maven-reactor-version.py" check "$project") == "$next_version" ]] ||
		fail "POM reactor did not advance to $next_version"
	local release_commit remote_branch remote_tag
	release_commit=$("$real_git" -C "$project" rev-parse "$tag^{commit}")
	"$real_git" -C "$project" show "$tag:pom.xml" | grep -Fq "<version>$release_version</version>" ||
		fail "tag $tag does not contain release version $release_version"
	remote_branch=$("$real_git" --git-dir="$bare" rev-parse refs/heads/main)
	[[ $remote_branch == $("$real_git" -C "$project" rev-parse HEAD) ]] || fail 'next-SNAPSHOT branch was not pushed'
	remote_tag=$("$real_git" --git-dir="$bare" rev-parse "refs/tags/$tag")
	[[ $remote_tag == $("$real_git" -C "$project" rev-parse "refs/tags/$tag") ]] || fail 'release tag was not pushed'
	[[ $("$real_git" -C "$project" rev-parse "$tag^{commit}") == "$release_commit" ]] || fail 'release tag commit changed'
	printf '%s\n' \
		"verify $release_version" \
		"image-build repo.strolch.li/intruvia:$release_version" \
		"docker-tag repo.strolch.li/intruvia:$release_version repo.strolch.li/intruvia:${release_version%%.*}" \
		"docker-tag repo.strolch.li/intruvia:$release_version repo.strolch.li/intruvia:${release_version%.*}" \
		"docker-tag repo.strolch.li/intruvia:$release_version repo.strolch.li/intruvia:latest" \
		"bundle $release_version repo.strolch.li/intruvia:$release_version" \
		'git-push branch' \
		'git-push tag' \
		"docker-push repo.strolch.li/intruvia:${release_version%%.*}" \
		"docker-push repo.strolch.li/intruvia:${release_version%.*}" \
		"docker-push repo.strolch.li/intruvia:$release_version" \
		'docker-push repo.strolch.li/intruvia:latest' \
		"gh-create $tag" \
		"gh-asset intruvia-deployment-$release_version.tar.gz" \
		"gh-asset intruvia-deployment-$release_version.tar.gz.sha256" \
		"gh-view $tag" \
		'git-push branch' > "$work/expected.log"
	cmp "$work/expected.log" "$log" || fail "publication order or GitHub assets were incorrect for $tag"
}

release_type=major
expected_release=2.0.0
expected_tag=v2.0.0
setup_project major 1.2.3
assert_success 2.0.0 2.0.1-SNAPSHOT v2.0.0

release_type=minor
expected_release=1.3.0
expected_tag=v1.3.0
setup_project minor 1.2.3
assert_success 1.3.0 1.3.1-SNAPSHOT v1.3.0

release_type=patch
expected_release=1.2.4
expected_tag=v1.2.4
setup_project patch 1.2.3
assert_success 1.2.4 1.2.5-SNAPSHOT v1.2.4

release_type=patch
expected_release=1.2.4
expected_tag=v1.2.4
setup_project snapshot 1.2.3-SNAPSHOT
assert_success 1.2.4 1.2.5-SNAPSHOT v1.2.4

release_type=patch
expected_release=1.2.4
expected_tag=v1.2.4
setup_project dirty 1.2.3
printf '%s\n' 'uncommitted fixture change' > "$project/dirty.txt"
expect_failure 'Working tree is not clean'
[[ ! -s $log ]] || fail 'dirty worktree performed release actions'

setup_project wrong-approval 1.2.3
tag_approval=v9.9.9
expect_failure 'Release approval must exactly match v1.2.4'
[[ ! -s $log ]] || fail 'wrong approval performed release actions'
tag_approval=

setup_project invalid-version 1.2.3-SNAPSHOT
release_type=unknown
if invoke_release > "$output" 2>&1; then
	fail 'invalid release type was accepted'
fi
[[ $(python3 "$project/scripts/update-maven-reactor-version.py" check "$project") == 1.2.3-SNAPSHOT ]] ||
	fail 'invalid release type modified POMs'

release_type=updated
setup_project old-release-type 1.2.3
expect_failure 'Release type must be major, minor, or patch'
[[ ! -s $log ]] || fail 'old release type performed release actions'
[[ $(python3 "$project/scripts/update-maven-reactor-version.py" check "$project") == 1.2.3 ]] ||
	fail 'old release type modified POMs'

release_type=patch
setup_project existing-local-tag 1.2.3
"$real_git" -C "$project" tag v1.2.4
expect_failure 'Local Git tag v1.2.4 already exists'
[[ ! -s $log ]] || fail 'existing local tag proceeded to build or publication'

setup_project existing-remote-tag 1.2.3
"$real_git" -C "$project" tag v1.2.4
"$real_git" -C "$project" push --quiet origin refs/tags/v1.2.4
"$real_git" -C "$project" tag -d v1.2.4 >/dev/null
expect_failure 'Remote Git tag v1.2.4 already exists'
[[ ! -s $log ]] || fail 'existing remote tag proceeded to build or publication'

setup_project existing-image 1.2.3
image_exists=true
expect_failure 'Docker image repo.strolch.li/intruvia:1.2.4 already exists'
image_exists=
[[ ! -s $log ]] || fail 'existing image proceeded to build or publication'

setup_project existing-release 1.2.3
existing_releases=v1.2.4
expect_failure 'GitHub release v1.2.4 already exists'
existing_releases=
[[ ! -s $log ]] || fail 'existing GitHub release proceeded to build or publication'

setup_project out-of-sync 1.2.3
peer="$case_root/peer"
"$real_git" clone --quiet "$bare" "$peer"
"$real_git" -C "$peer" config user.name 'Release Test'
"$real_git" -C "$peer" config user.email 'release-test@example.invalid'
printf '%s\n' 'remote-only update' > "$peer/remote-update.txt"
"$real_git" -C "$peer" add -- remote-update.txt
"$real_git" -C "$peer" commit --quiet -m 'Remote update'
"$real_git" -C "$peer" push --quiet origin main
expect_failure 'Release branch is not synchronized exactly with its remote'
[[ ! -s $log ]] || fail 'unsynchronized branch proceeded to build or publication'

setup_project wrong-push-url 1.2.3
push_url='git@github.com:someone/other.git'
expect_failure 'Git push URL targets a different repository'
[[ ! -s $log ]] || fail 'mismatched push URL proceeded to build or publication'
push_url=

setup_project symlink-output 1.2.3
mkdir -p "$project/target" "$case_root/outside"
ln -s "$case_root/outside" "$project/target/deployment-release"
expect_failure 'Deployment release output path must not contain symlinks'
[[ ! -s $log ]] || fail 'symlinked output path proceeded to build or publication'

setup_project verify-failure 1.2.3
test_fail=verify
expect_failure 'PostgreSQL-backed Maven verification at 1.2.4 failed'
[[ $(python3 "$project/scripts/update-maven-reactor-version.py" check "$project") == 1.2.4 ]] ||
	fail 'failed verification unexpectedly changed the release version back'
[[ ! -s $log || $(<"$log") == 'verify 1.2.4' ]] || fail 'verification failure continued into image/bundle steps'

test_fail=image-build
setup_project image-build-failure 1.2.3
expect_failure 'Failed to build Docker image repo.strolch.li/intruvia:1.2.4'
[[ $(<"$log") == $'verify 1.2.4\nimage-build repo.strolch.li/intruvia:1.2.4' ]] ||
	fail 'image build failure continued to bundle or Git publication'

test_fail=docker-tag-minor
setup_project image-tag-failure 1.2.3
expect_failure 'Unable to tag Docker image repo.strolch.li/intruvia:1.2'
[[ $(<"$log") == $'verify 1.2.4\nimage-build repo.strolch.li/intruvia:1.2.4\ndocker-tag repo.strolch.li/intruvia:1.2.4 repo.strolch.li/intruvia:1\ndocker-tag repo.strolch.li/intruvia:1.2.4 repo.strolch.li/intruvia:1.2' ]] ||
	fail 'Docker tag failure continued to bundle or Git publication'

test_fail=docker-tag-latest
setup_project image-latest-tag-failure 1.2.3
expect_failure 'Unable to tag Docker image repo.strolch.li/intruvia:latest'
[[ $(<"$log") == $'verify 1.2.4\nimage-build repo.strolch.li/intruvia:1.2.4\ndocker-tag repo.strolch.li/intruvia:1.2.4 repo.strolch.li/intruvia:1\ndocker-tag repo.strolch.li/intruvia:1.2.4 repo.strolch.li/intruvia:1.2\ndocker-tag repo.strolch.li/intruvia:1.2.4 repo.strolch.li/intruvia:latest' ]] ||
	fail 'latest-tag failure continued to bundle or Git publication'

test_fail=bundle
setup_project bundle-failure 1.2.3
expect_failure 'Failed to build the administrator deployment tarball and checksum'
[[ $(<"$log") == $'verify 1.2.4\nimage-build repo.strolch.li/intruvia:1.2.4\ndocker-tag repo.strolch.li/intruvia:1.2.4 repo.strolch.li/intruvia:1\ndocker-tag repo.strolch.li/intruvia:1.2.4 repo.strolch.li/intruvia:1.2\ndocker-tag repo.strolch.li/intruvia:1.2.4 repo.strolch.li/intruvia:latest\nbundle 1.2.4 repo.strolch.li/intruvia:1.2.4' ]] ||
	fail 'bundle failure continued to Git publication'

test_fail=push-branch
setup_project branch-push-failure 1.2.3
expect_failure 'Unable to push release branch main'
[[ $(<"$log") == $'verify 1.2.4\nimage-build repo.strolch.li/intruvia:1.2.4\ndocker-tag repo.strolch.li/intruvia:1.2.4 repo.strolch.li/intruvia:1\ndocker-tag repo.strolch.li/intruvia:1.2.4 repo.strolch.li/intruvia:1.2\ndocker-tag repo.strolch.li/intruvia:1.2.4 repo.strolch.li/intruvia:latest\nbundle 1.2.4 repo.strolch.li/intruvia:1.2.4\ngit-push branch' ]] || {
	printf 'Observed release-boundary log:\n%s\n' "$(<"$log")" >&2
	fail 'release branch push failure proceeded to tag or image publication'
}

test_fail=push-tag
setup_project tag-push-failure 1.2.3
expect_failure 'Unable to push release tag v1.2.4'
[[ $(<"$log") == $'verify 1.2.4\nimage-build repo.strolch.li/intruvia:1.2.4\ndocker-tag repo.strolch.li/intruvia:1.2.4 repo.strolch.li/intruvia:1\ndocker-tag repo.strolch.li/intruvia:1.2.4 repo.strolch.li/intruvia:1.2\ndocker-tag repo.strolch.li/intruvia:1.2.4 repo.strolch.li/intruvia:latest\nbundle 1.2.4 repo.strolch.li/intruvia:1.2.4\ngit-push branch\ngit-push tag' ]] ||
	fail 'tag push failure proceeded to image or GitHub publication'

test_fail=docker-push
setup_project docker-push-failure 1.2.3
expect_failure 'Unable to push Docker image repo.strolch.li/intruvia:1'
[[ -n $("$real_git" -C "$project" ls-remote --tags --refs origin refs/tags/v1.2.4) ]] ||
	fail 'expected the source tag to have been published before the image failure'
[[ $(<"$log") != *'gh-create'* ]] || fail 'GitHub release was created after image push failed'

test_fail=docker-push-minor
setup_project docker-push-minor-failure 1.2.3
expect_failure 'Unable to push Docker image repo.strolch.li/intruvia:1.2'
[[ $(<"$output") == *'Pushed Docker image repo.strolch.li/intruvia:1'* ]] ||
	fail 'partial image-push failure did not report the completed major tag'
[[ $(<"$output") != *'Pushed Docker image repo.strolch.li/intruvia:1.2.4'* ]] ||
	fail 'partial image-push failure incorrectly reported the patch tag as pushed'
[[ $(<"$log") == *'docker-push repo.strolch.li/intruvia:1'* && $(<"$log") == *'docker-push repo.strolch.li/intruvia:1.2'* ]] ||
	fail 'partial image-push failure did not stop at the minor tag'
[[ $(<"$log") != *'docker-push repo.strolch.li/intruvia:1.2.4'* ]] ||
	fail 'partial image-push failure continued to the patch tag'

test_fail=docker-push-latest
setup_project docker-push-latest-failure 1.2.3
expect_failure 'Unable to push Docker image repo.strolch.li/intruvia:latest'
for pushed_tag in 1 1.2 1.2.4; do
	[[ $(<"$output") == *"Pushed Docker image repo.strolch.li/intruvia:$pushed_tag"* ]] ||
		fail "latest-push failure did not report completed tag $pushed_tag"
done
[[ $(<"$output") != *'Pushed Docker image repo.strolch.li/intruvia:latest'* ]] ||
	fail 'latest-push failure incorrectly reported latest as pushed'
[[ $(<"$log") == *'docker-push repo.strolch.li/intruvia:latest'* ]] ||
	fail 'latest-push failure was not reached after version pushes'
[[ $(<"$log") != *'gh-create'* ]] || fail 'GitHub release was created after latest push failed'

test_fail=github
setup_project github-failure 1.2.3
expect_failure 'Unable to create GitHub release v1.2.4 with the deployment assets'
[[ -n $("$real_git" -C "$project" ls-remote --tags --refs origin refs/tags/v1.2.4) ]] ||
	fail 'expected the source tag to have been published before GitHub failure'
[[ $(<"$log") == *'docker-push repo.strolch.li/intruvia:1.2.4'* ]] || fail 'full-version image push did not precede GitHub release failure'
[[ $(python3 "$project/scripts/update-maven-reactor-version.py" check "$project") == 1.2.4 ]] ||
	fail 'GitHub failure unexpectedly advanced the Maven version'

test_fail=push-snapshot
setup_project snapshot-push-failure 1.2.3
expect_failure 'Unable to push next development version 1.2.5-SNAPSHOT'
[[ $(python3 "$project/scripts/update-maven-reactor-version.py" check "$project") == 1.2.5-SNAPSHOT ]] ||
	fail 'next-SNAPSHOT push failure lost the local development version'
[[ -n $("$real_git" -C "$project" ls-remote --tags --refs origin refs/tags/v1.2.4) ]] ||
	fail 'release tag was lost after next-SNAPSHOT push failure'

printf 'Unified release workflow tests passed\n'
