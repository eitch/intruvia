#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
image="intruvia-image-check:$$-${RANDOM}${RANDOM}"
test_data=""
volume=""
cleanup() {
	if [[ -n "$volume" ]]; then docker volume rm "$volume" >/dev/null 2>&1 || true; fi
	docker image rm "$image" >/dev/null 2>&1 || true
	if [[ -n "$test_data" ]]; then rm -rf -- "$test_data"; fi
}
trap cleanup EXIT

distribution="$root/intruvia-app/target/intruvia"
shopt -s nullglob
application=("$distribution"/intruvia-app-*.jar)
[[ ${#application[@]} == 1 ]]
contents=$(jar tf "${application[0]}")
[[ "$contents" == *'PrivilegeTool.class'* && "$contents" == *'li/intruvia/app/DeploymentEntrypoint.class'* &&
	"$contents" == *'li/intruvia/app/quiet-logback.xml'* &&
	"$contents" == *'META-INF/LICENSE'* && "$contents" == *'META-INF/LICENSE-Strolch'* &&
	"$contents" != *'PrivilegeTool.java'* ]]
"$root/scripts/build-deployment-image.sh" "$image" "$distribution"
docker run --rm --entrypoint java "$image" -version
if output=$(docker run --rm "$image" 2>&1); then
	printf 'Container unexpectedly started without provisioning inputs\n' >&2
	exit 1
fi
[[ "$output" == *'Initialization failed; preserve the runtime and database and follow recovery instructions'* ]]
docker run --rm --entrypoint sh "$image" -c '
	set -eu
	test "$(id -u)" = 10001
	test "$(id -g)" = 10001
	test "$(stat -c %a /var/lib/intruvia/runtime)" = 700
	test -r /opt/intruvia/intruvia-app.jar
	for path in /opt/intruvia/*; do
		case "$path" in /opt/intruvia/intruvia-app.jar|/opt/intruvia/lib) ;; *) exit 1 ;; esac
	done
	for path in /opt/intruvia/lib/*; do
		case "$path" in *.jar) ;; *) exit 1 ;; esac
	done
'
if output=$(docker run --rm --entrypoint java "$image" -cp '/opt/intruvia/intruvia-app.jar:/opt/intruvia/lib/*' PrivilegeTool 2>&1); then
	printf 'PrivilegeTool unexpectedly accepted missing arguments\n' >&2
	exit 1
fi
[[ "$output" == *'Usage: config-dir hash password-file output-file'* ]]

umask 077
test_data=$(mktemp -d "$root/intruvia-app/target/image-smoke.XXXXXXXX")
mkdir "$test_data/config"
sed -e 's/secretKey" value="CHANGE-ME"/secretKey" value="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"/' \
	-e 's/secretSalt" value="CHANGE-ME"/secretSalt" value="fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"/' \
	"$distribution/runtime/config/PrivilegeConfig.xml.example" > "$test_data/config/PrivilegeConfig.xml"
for name in PrivilegeRoles PrivilegeUsers; do
	cp "$distribution/runtime/config/$name.xml.example" "$test_data/config/$name.xml"
done
chmod 600 "$test_data"/config/*.xml
printf '%s\n' 'intruvia-offline-test-canary' > "$test_data/password"
output=$(cd "$distribution" && java --class-path 'lib/*' docs/deployment/PrivilegeTool.java \
	"$test_data/config" hash "$test_data/password" "$test_data/standalone.hash" 2>&1)
[[ "$output" != *'intruvia-offline-test-canary'* && -s "$test_data/standalone.hash" &&
	$(stat -c %a "$test_data/standalone.hash") == 600 ]]

volume=$(docker volume create)
docker run --rm --user 0:0 --mount "type=volume,source=$volume,target=/test" \
	--mount "type=bind,source=$test_data,target=/input,readonly" --entrypoint sh "$image" -c '
	set -eu
	install -d -o 10001 -g 10001 -m 0700 /test/config
	cp /input/config/*.xml /test/config/
	cp /input/password /test/password
	chown -R 10001:10001 /test
	chmod 700 /test /test/config
	chmod 600 /test/config/*.xml /test/password
'
output=$(docker run --rm --mount "type=volume,source=$volume,target=/test" --entrypoint java "$image" \
	-cp '/opt/intruvia/intruvia-app.jar:/opt/intruvia/lib/*' PrivilegeTool /test/config hash /test/password /test/hash 2>&1)
[[ "$output" != *'intruvia-offline-test-canary'* ]]
docker run --rm --mount "type=volume,source=$volume,target=/test" --entrypoint sh "$image" -c '
	set -eu
	test -s /test/hash
	test "$(stat -c %a /test/hash)" = 600
	IFS= read -r hash < /test/hash
	IFS= read -r password < /test/password
	test "$hash" != "$password"
'