#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail
export LC_ALL=C

root=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$root/target"
work=$(mktemp -d "$root/target/release-workflow-test.XXXXXXXX")
trap 'rm -rf -- "$work"' EXIT
fixture=$work/project
mkdir -p "$fixture/scripts" "$fixture/intruvia-app/target" "$work/bin"
cp -p "$root/build-and-push-docker.sh" "$fixture/"
cp -p "$root/scripts/release-config.sh" "$fixture/scripts/"
printf '%s\n' '<project xmlns="http://maven.apache.org/POM/4.0.0"><version>2.3.4</version></project>' > "$fixture/pom.xml"
cp -p "$fixture/pom.xml" "$work/valid-pom.xml"

printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' \
	'printf "verify\\n" >> "$RELEASE_TEST_LOG"' \
	'if [[ ${RELEASE_TEST_FAIL:-} == verify ]]; then exit 30; fi' > "$fixture/scripts/verify-postgresql.sh"
printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' \
	'[[ $# == 2 ]]' \
	'printf "image %s %s\\n" "$1" "$2" >> "$RELEASE_TEST_LOG"' \
	'if [[ ${RELEASE_TEST_FAIL:-} == image ]]; then exit 31; fi' > "$fixture/scripts/build-deployment-image.sh"
printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' \
	'printf "mvn" >> "$RELEASE_TEST_LOG"' \
	'printf " %s" "$@" >> "$RELEASE_TEST_LOG"' \
	'printf "\\n" >> "$RELEASE_TEST_LOG"' \
	'if [[ ${RELEASE_TEST_FAIL:-} == package ]]; then exit 29; fi' > "$work/bin/mvn"
printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' \
	'case ${1:-} in' \
	'tag)' \
	'[[ $# == 3 ]]' \
	'printf "tag %s %s\\n" "$2" "$3" >> "$RELEASE_TEST_LOG"' \
	'if [[ (${RELEASE_TEST_FAIL:-} == tag-minor && $3 == *:2.3) || (${RELEASE_TEST_FAIL:-} == tag-latest && $3 == *:latest) ]]; then exit 33; fi' \
	';;' \
	'push)' \
	'[[ $# == 2 ]]' \
	'printf "push %s\\n" "$2" >> "$RELEASE_TEST_LOG"' \
	'if [[ ${RELEASE_TEST_FAIL:-} == push || (${RELEASE_TEST_FAIL:-} == push-minor && $2 == *:2.3) || (${RELEASE_TEST_FAIL:-} == push-latest && $2 == *:latest) ]]; then exit 32; fi' \
	';;' \
	'*) exit 34 ;;' \
	'esac' > "$work/bin/docker"
chmod 0755 "$fixture/build-and-push-docker.sh" "$fixture/scripts/verify-postgresql.sh" \
	"$fixture/scripts/build-deployment-image.sh" "$work/bin/mvn" "$work/bin/docker"

log=$work/commands
export RELEASE_TEST_LOG=$log
export PATH="$work/bin:$PATH"
"$fixture/build-and-push-docker.sh" > "$work/build-output"
printf '%s\n' \
	"mvn -B -f $fixture/pom.xml clean package -DskipTests" \
	"image repo.strolch.li/intruvia:2.3.4 $fixture/intruvia-app/target/intruvia" \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:2' \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:2.3' \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:latest' \
	'push repo.strolch.li/intruvia:2' \
	'push repo.strolch.li/intruvia:2.3' \
	'push repo.strolch.li/intruvia:2.3.4' \
	'push repo.strolch.li/intruvia:latest' > "$work/expected"
cmp "$work/expected" "$log"
printf '%s\n' \
	'Pushed repo.strolch.li/intruvia:2' \
	'Pushed repo.strolch.li/intruvia:2.3' \
	'Pushed repo.strolch.li/intruvia:2.3.4' \
	'Pushed repo.strolch.li/intruvia:latest' > "$work/expected-output"
cmp "$work/expected-output" "$work/build-output"

: > "$log"
sed -i 's/<version>2.3.4<\/version>/<version>2.3.4-SNAPSHOT<\/version>/' "$fixture/pom.xml"
"$fixture/build-and-push-docker.sh" --with-tests > "$work/test-build-output"
printf '%s\n' \
	'verify' \
	"image repo.strolch.li/intruvia:2.3.4-SNAPSHOT $fixture/intruvia-app/target/intruvia" \
	'tag repo.strolch.li/intruvia:2.3.4-SNAPSHOT repo.strolch.li/intruvia:2-SNAPSHOT' \
	'tag repo.strolch.li/intruvia:2.3.4-SNAPSHOT repo.strolch.li/intruvia:2.3-SNAPSHOT' \
	'push repo.strolch.li/intruvia:2-SNAPSHOT' \
	'push repo.strolch.li/intruvia:2.3-SNAPSHOT' \
	'push repo.strolch.li/intruvia:2.3.4-SNAPSHOT' > "$work/expected"
cmp "$work/expected" "$log"
printf '%s\n' \
	'Pushed repo.strolch.li/intruvia:2-SNAPSHOT' \
	'Pushed repo.strolch.li/intruvia:2.3-SNAPSHOT' \
	'Pushed repo.strolch.li/intruvia:2.3.4-SNAPSHOT' > "$work/expected-output"
cmp "$work/expected-output" "$work/test-build-output"
if grep -Fq 'latest' "$log" "$work/test-build-output"; then
	printf 'FAIL: SNAPSHOT image workflow used the latest tag\n' >&2
	exit 1
fi

: > "$log"
sed -i 's/<version>2.3.4-SNAPSHOT<\/version>/<version>2.3.4-sNaPsHoT<\/version>/' "$fixture/pom.xml"
"$fixture/build-and-push-docker.sh" > "$work/mixed-snapshot-output"
printf '%s\n' \
	"mvn -B -f $fixture/pom.xml clean package -DskipTests" \
	"image repo.strolch.li/intruvia:2.3.4-sNaPsHoT $fixture/intruvia-app/target/intruvia" \
	'tag repo.strolch.li/intruvia:2.3.4-sNaPsHoT repo.strolch.li/intruvia:2-sNaPsHoT' \
	'tag repo.strolch.li/intruvia:2.3.4-sNaPsHoT repo.strolch.li/intruvia:2.3-sNaPsHoT' \
	'push repo.strolch.li/intruvia:2-sNaPsHoT' \
	'push repo.strolch.li/intruvia:2.3-sNaPsHoT' \
	'push repo.strolch.li/intruvia:2.3.4-sNaPsHoT' > "$work/expected"
cmp "$work/expected" "$log"
if grep -Fq 'latest' "$log" "$work/mixed-snapshot-output"; then
	printf 'FAIL: case-insensitive SNAPSHOT image workflow used the latest tag\n' >&2
	exit 1
fi

: > "$log"
sed -i 's/<version>2.3.4-sNaPsHoT<\/version>/<version>2.3.4-rc.1<\/version>/' "$fixture/pom.xml"
"$fixture/build-and-push-docker.sh" > "$work/qualified-output"
printf '%s\n' \
	"mvn -B -f $fixture/pom.xml clean package -DskipTests" \
	"image repo.strolch.li/intruvia:2.3.4-rc.1 $fixture/intruvia-app/target/intruvia" \
	'tag repo.strolch.li/intruvia:2.3.4-rc.1 repo.strolch.li/intruvia:2-rc.1' \
	'tag repo.strolch.li/intruvia:2.3.4-rc.1 repo.strolch.li/intruvia:2.3-rc.1' \
	'tag repo.strolch.li/intruvia:2.3.4-rc.1 repo.strolch.li/intruvia:latest' \
	'push repo.strolch.li/intruvia:2-rc.1' \
	'push repo.strolch.li/intruvia:2.3-rc.1' \
	'push repo.strolch.li/intruvia:2.3.4-rc.1' \
	'push repo.strolch.li/intruvia:latest' > "$work/expected"
cmp "$work/expected" "$log"
cp -p "$work/valid-pom.xml" "$fixture/pom.xml"

expect_failure() {
	if "$@" > /dev/null 2>&1; then
		printf 'FAIL: unexpected success: %s\n' "$*" >&2
		exit 1
	fi
}

: > "$log"
expect_failure "$fixture/build-and-push-docker.sh" unexpected
[[ ! -s $log ]] || { printf 'FAIL: argument rejection ran release commands\n' >&2; exit 1; }
expect_failure "$fixture/build-and-push-docker.sh" --with-tests unexpected
[[ ! -s $log ]] || { printf 'FAIL: invalid test option ran release commands\n' >&2; exit 1; }

sed -i 's/<version>2.3.4<\/version>/<version>${revision}<\/version>/' "$fixture/pom.xml"
expect_failure "$fixture/build-and-push-docker.sh"
[[ ! -s $log ]] || { printf 'FAIL: invalid POM version ran release commands\n' >&2; exit 1; }
printf -v long_version '1.2.%0128d' 0
printf '<project xmlns="http://maven.apache.org/POM/4.0.0"><version>%s</version></project>\n' "$long_version" > "$fixture/pom.xml"
expect_failure "$fixture/build-and-push-docker.sh"
[[ ! -s $log ]] || { printf 'FAIL: oversized POM version ran release commands\n' >&2; exit 1; }
cp -p "$work/valid-pom.xml" "$fixture/pom.xml"

: > "$log"
if RELEASE_TEST_FAIL=verify "$fixture/build-and-push-docker.sh" --with-tests > /dev/null 2>&1; then
	printf 'FAIL: verification failure was ignored\n' >&2
	exit 1
fi
printf 'verify\n' > "$work/expected"
cmp "$work/expected" "$log"

: > "$log"
if RELEASE_TEST_FAIL=package "$fixture/build-and-push-docker.sh" > /dev/null 2>&1; then
	printf 'FAIL: package failure was ignored\n' >&2
	exit 1
fi
printf '%s\n' "mvn -B -f $fixture/pom.xml clean package -DskipTests" > "$work/expected"
cmp "$work/expected" "$log"

: > "$log"
if RELEASE_TEST_FAIL=image "$fixture/build-and-push-docker.sh" > /dev/null 2>&1; then
	printf 'FAIL: image build failure was ignored\n' >&2
	exit 1
fi
printf '%s\n' \
	"mvn -B -f $fixture/pom.xml clean package -DskipTests" \
	"image repo.strolch.li/intruvia:2.3.4 $fixture/intruvia-app/target/intruvia" > "$work/expected"
cmp "$work/expected" "$log"

: > "$log"
if RELEASE_TEST_FAIL=push "$fixture/build-and-push-docker.sh" > /dev/null 2>&1; then
	printf 'FAIL: push failure was ignored\n' >&2
	exit 1
fi
printf '%s\n' \
	"mvn -B -f $fixture/pom.xml clean package -DskipTests" \
	"image repo.strolch.li/intruvia:2.3.4 $fixture/intruvia-app/target/intruvia" \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:2' \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:2.3' \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:latest' \
	'push repo.strolch.li/intruvia:2' > "$work/expected"
cmp "$work/expected" "$log"

: > "$log"
if RELEASE_TEST_FAIL=tag-minor "$fixture/build-and-push-docker.sh" > /dev/null 2>&1; then
	printf 'FAIL: Docker tag failure was ignored\n' >&2
	exit 1
fi
printf '%s\n' \
	"mvn -B -f $fixture/pom.xml clean package -DskipTests" \
	"image repo.strolch.li/intruvia:2.3.4 $fixture/intruvia-app/target/intruvia" \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:2' \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:2.3' > "$work/expected"
cmp "$work/expected" "$log"

: > "$log"
if RELEASE_TEST_FAIL=tag-latest "$fixture/build-and-push-docker.sh" > /dev/null 2>&1; then
	printf 'FAIL: latest Docker tag failure was ignored\n' >&2
	exit 1
fi
printf '%s\n' \
	"mvn -B -f $fixture/pom.xml clean package -DskipTests" \
	"image repo.strolch.li/intruvia:2.3.4 $fixture/intruvia-app/target/intruvia" \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:2' \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:2.3' \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:latest' > "$work/expected"
cmp "$work/expected" "$log"

: > "$log"
if RELEASE_TEST_FAIL=push-minor "$fixture/build-and-push-docker.sh" > /dev/null 2>&1; then
	printf 'FAIL: partial Docker push failure was ignored\n' >&2
	exit 1
fi
printf '%s\n' \
	"mvn -B -f $fixture/pom.xml clean package -DskipTests" \
	"image repo.strolch.li/intruvia:2.3.4 $fixture/intruvia-app/target/intruvia" \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:2' \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:2.3' \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:latest' \
	'push repo.strolch.li/intruvia:2' \
	'push repo.strolch.li/intruvia:2.3' > "$work/expected"
cmp "$work/expected" "$log"

: > "$log"
if RELEASE_TEST_FAIL=push-latest "$fixture/build-and-push-docker.sh" > "$work/latest-push-output" 2>&1; then
	printf 'FAIL: latest Docker push failure was ignored\n' >&2
	exit 1
fi
printf '%s\n' \
	"mvn -B -f $fixture/pom.xml clean package -DskipTests" \
	"image repo.strolch.li/intruvia:2.3.4 $fixture/intruvia-app/target/intruvia" \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:2' \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:2.3' \
	'tag repo.strolch.li/intruvia:2.3.4 repo.strolch.li/intruvia:latest' \
	'push repo.strolch.li/intruvia:2' \
	'push repo.strolch.li/intruvia:2.3' \
	'push repo.strolch.li/intruvia:2.3.4' \
	'push repo.strolch.li/intruvia:latest' > "$work/expected"
cmp "$work/expected" "$log"
printf '%s\n' \
	'Pushed repo.strolch.li/intruvia:2' \
	'Pushed repo.strolch.li/intruvia:2.3' \
	'Pushed repo.strolch.li/intruvia:2.3.4' > "$work/expected-output"
cmp "$work/expected-output" "$work/latest-push-output"

printf 'Release workflow tests passed\n'