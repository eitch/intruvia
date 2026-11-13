#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail
export LC_ALL=C

root=$(cd "$(dirname "$0")/.." && pwd)
builder=$root/scripts/build-deployment-tarball.sh
mkdir -p "$root/target"
work=$(mktemp -d "$root/target/deployment-tarball-test.XXXXXXXX")
trap 'rm -rf -- "$work"' EXIT
mkdir "$work/out" "$work/extracted"

fail() {
	printf 'FAIL: %s\n' "$*" >&2
	exit 1
}

version=1.2.3-test
image=registry.example.org:5443/team/intruvia:v1.2.3
archive=intruvia-deployment-$version.tar.gz
"$builder" "$version" "$image" "$work/out"
(cd "$work/out" && sha256sum -c "$archive.sha256")
tar -tzf "$work/out/$archive" | sort > "$work/actual"
printf '%s\n' \
	'./' \
	'./.env' \
	'./Caddyfile' \
	'./LICENSE' \
	'./LICENSE-Strolch' \
	'./README.md' \
	'./compose.yaml' \
	'./db/' \
	'./db/init.sh' \
	'./docs/' \
	'./docs/DEPLOYMENT.md' \
	'./docs/deployment/' \
	'./docs/deployment/compose-initialization-recovery.md' \
	'./integrations/' \
	'./integrations/fail2ban/' \
	'./integrations/fail2ban/README.md' \
	'./integrations/fail2ban/intruvia.py' \
	'./integrations/fail2ban/intruvia_notify.py' \
	'./integrations/fail2ban/jail.local.example' \
	'./runtime-templates/' \
	'./runtime-templates/config/' \
	'./runtime-templates/config/PrivilegeConfig.xml.example' \
	'./runtime-templates/config/PrivilegeRoles.xml.example' \
	'./runtime-templates/config/PrivilegeUsers.xml.example' \
	'./runtime-templates/config/StrolchConfiguration.xml' \
	'./runtime-templates/config/ingestion.properties.example' \
	'./secrets/' | sort > "$work/expected"
diff -u "$work/expected" "$work/actual" || fail 'archive contents differ from the allowlist'
tar --numeric-owner -tvzf "$work/out/$archive" | awk '$2 != "0/0" { exit 1 }' || fail 'archive ownership is not 0/0'
(umask 077; tar -xpzf "$work/out/$archive" -C "$work/extracted")
bundle=$work/extracted
[[ $(stat -c %a "$bundle/.env") == 600 ]] || fail '.env is not mode 0600'
[[ $(stat -c %a "$bundle/secrets") == 700 ]] || fail 'secrets directory is not mode 0700'
[[ $(stat -c %a "$bundle/runtime-templates") == 755 && $(stat -c %a "$bundle/runtime-templates/config") == 755 ]] ||
	fail 'templates are not traversable after protected extraction'
shopt -s dotglob nullglob
secret_files=("$bundle/secrets/"*)
shopt -u dotglob nullglob
[[ ${#secret_files[@]} == 0 ]] || fail 'secrets directory is not empty'
sed "s|^INTRUVIA_IMAGE=$|INTRUVIA_IMAGE=$image|" "$root/deployment/.env.example" > "$work/expected-env"
cmp "$work/expected-env" "$bundle/.env" || fail 'environment changed beyond the image reference'
for key in POSTGRES_ADMIN_PASSWORD INTRUVIA_DB_PASSWORD INTRUVIA_PUBLIC_ORIGIN; do
	grep -Fx "$key=" "$bundle/.env" >/dev/null || fail "$key must be empty"
done

compare_file() {
	local source=$1 destination=$2
	cmp "$root/$source" "$bundle/$destination" || fail "contents changed: $source"
	[[ $(stat -c %a "$root/$source") == "$(stat -c %a "$bundle/$destination")" ]] || fail "mode changed: $source"
}
compare_file deployment/compose.yaml compose.yaml
compare_file deployment/Caddyfile Caddyfile
compare_file deployment/db/init.sh db/init.sh
compare_file LICENSE LICENSE
compare_file intruvia-app/src/main/runtime/LICENSE-Strolch LICENSE-Strolch
compare_file README.md README.md
compare_file docs/DEPLOYMENT.md docs/DEPLOYMENT.md
compare_file docs/deployment/compose-initialization-recovery.md docs/deployment/compose-initialization-recovery.md
for file in StrolchConfiguration.xml PrivilegeConfig.xml.example PrivilegeRoles.xml.example PrivilegeUsers.xml.example \
		ingestion.properties.example; do
	compare_file "intruvia-app/src/main/runtime/config/$file" "runtime-templates/config/$file"
done
for file in README.md intruvia.py intruvia_notify.py jail.local.example; do
	compare_file "integrations/fail2ban/$file" "integrations/fail2ban/$file"
done

expect_failure() {
	if "$@" > /dev/null 2>&1; then
		fail "unexpected success: $*"
	fi
}
for bad in '' ../1 1/2 .1 latest 1+bad; do
	expect_failure "$builder" "$bad" "$image" "$work/out"
done
for bad in '' intruvia intruvia:latest intruvia:Latest intruvia:stable registry.example.org:5000/team/intruvia \
		intruvia: intruvia:1/other intruvia@sha256:abc 'intruvia:1@sha256:abc' \
		'UPPERCASE:1' 'repo//image:1' 'https://example.com/image:1' 'repo/image:1$bad'; do
	expect_failure "$builder" 2.0 "$bad" "$work/out"
done
expect_failure "$builder" "$version" "$image" "$work/out"
(cd "$work/out" && sha256sum -c "$archive.sha256")
printf 'pre-existing checksum\n' > "$work/out/intruvia-deployment-2.1.tar.gz.sha256"
expect_failure "$builder" 2.1 "$image" "$work/out"
[[ ! -e $work/out/intruvia-deployment-2.1.tar.gz ]] || fail 'archive published despite existing checksum'
[[ $(<"$work/out/intruvia-deployment-2.1.tar.gz.sha256") == 'pre-existing checksum' ]] || fail 'checksum overwritten'
printf 'pre-existing archive\n' > "$work/out/intruvia-deployment-2.4.tar.gz"
expect_failure "$builder" 2.4 "$image" "$work/out"
[[ $(<"$work/out/intruvia-deployment-2.4.tar.gz") == 'pre-existing archive' ]] || fail 'archive overwritten'
[[ ! -e $work/out/intruvia-deployment-2.4.tar.gz.sha256 ]] || fail 'checksum published despite existing archive'
ln -s missing "$work/out/intruvia-deployment-2.5.tar.gz"
expect_failure "$builder" 2.5 "$image" "$work/out"
[[ -L $work/out/intruvia-deployment-2.5.tar.gz ]] || fail 'existing symlink replaced'

digest=sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
"$builder" 2.2 "registry.example.org/team/intruvia@$digest" "$work/out"
(cd "$work/out" && sha256sum -c intruvia-deployment-2.2.tar.gz.sha256)
tar -xOzf "$work/out/intruvia-deployment-2.2.tar.gz" ./.env > "$work/digest-env"
grep -Fx "INTRUVIA_IMAGE=registry.example.org/team/intruvia@$digest" "$work/digest-env" > /dev/null || fail 'digest lost'

# The isolated fixture permits negative tests without altering parent-owned inputs.
fixture=$work/fixture
mkdir -p "$fixture/scripts" "$fixture/deployment/db" "$fixture/docs/deployment" \
		"$fixture/intruvia-app/src/main/runtime/config" "$fixture/integrations/fail2ban"
cp -p "$builder" "$fixture/scripts/"
cp -p "$root/scripts/release-config.sh" "$fixture/scripts/"
printf '%s\n' '<project xmlns="http://maven.apache.org/POM/4.0.0"><version>2.4.6-test</version></project>' > "$fixture/pom.xml"
cp -p "$root/deployment/compose.yaml" "$root/deployment/Caddyfile" "$root/deployment/.env.example" "$fixture/deployment/"
cp -p "$root/deployment/db/init.sh" "$fixture/deployment/db/"
cp -p "$root/LICENSE" "$root/README.md" "$fixture/"
cp -p "$root/docs/DEPLOYMENT.md" "$fixture/docs/"
cp -p "$root/docs/deployment/compose-initialization-recovery.md" "$fixture/docs/deployment/"
cp -p "$root/intruvia-app/src/main/runtime/LICENSE-Strolch" "$fixture/intruvia-app/src/main/runtime/"
for file in StrolchConfiguration.xml PrivilegeConfig.xml.example PrivilegeRoles.xml.example PrivilegeUsers.xml.example \
		ingestion.properties.example; do
	cp -p "$root/intruvia-app/src/main/runtime/config/$file" "$fixture/intruvia-app/src/main/runtime/config/"
done
for file in README.md intruvia.py intruvia_notify.py jail.local.example; do
	cp -p "$root/integrations/fail2ban/$file" "$fixture/integrations/fail2ban/"
done
fixture_builder=$fixture/scripts/build-deployment-tarball.sh
"$fixture_builder"
default_archive=$fixture/target/deployment-release/intruvia-deployment-2.4.6-test.tar.gz
default_checksum=$default_archive.sha256
[[ -f $default_archive && -f $default_checksum ]] || fail 'no-argument defaults did not create the POM-versioned bundle'
(cd "$(dirname "$default_archive")" && sha256sum -c "$(basename "$default_checksum")")
tar -xOzf "$default_archive" ./.env > "$work/default-env"
grep -Fx 'INTRUVIA_IMAGE=repo.strolch.li/intruvia:2.4.6-test' "$work/default-env" > /dev/null ||
	fail 'default image reference does not use the project repository and POM version'
expect_failure "$fixture_builder"
[[ -f $default_archive && -f $default_checksum ]] || fail 'refused default rebuild removed existing artifacts'
mkdir -p "$fixture/deployment/secrets" "$fixture/intruvia-app/src/main/runtime/data"
printf 'PRIVATE-SENTINEL\n' > "$fixture/deployment/.env"
printf 'PRIVATE-SENTINEL\n' > "$fixture/deployment/secrets/password"
printf 'PRIVATE-SENTINEL\n' > "$fixture/intruvia-app/src/main/runtime/data/live-data"
printf 'PRIVATE-SENTINEL\n' > "$fixture/intruvia-app/src/main/runtime/config/PrivilegeUsers.xml"
"$fixture_builder" 2.3 "$image" "$work/out"
tar -tzf "$work/out/intruvia-deployment-2.3.tar.gz" | sort > "$work/fixture-actual"
diff -u "$work/expected" "$work/fixture-actual" || fail 'fixture included non-allowlisted files'
mkdir "$work/fixture-extracted"
tar -xzf "$work/out/intruvia-deployment-2.3.tar.gz" -C "$work/fixture-extracted"
if grep -rF 'PRIVATE-SENTINEL' "$work/fixture-extracted" > /dev/null; then
	fail 'fixture secrets or runtime data leaked into bundle'
fi

expect_unsafe_template() {
	grep -F 'SECRET-CANARY' "$2" > /dev/null || fail "canary injection failed: $1"
	if "$fixture_builder" 3.1 "$image" "$work/out" > "$work/unsafe-output" 2>&1; then
		fail "modified template accepted: $1"
	fi
	grep -F "$3" "$work/unsafe-output" > /dev/null || fail "unexpected failure for modified template: $1"
	[[ ! -e $work/out/intruvia-deployment-3.1.tar.gz && ! -e $work/out/intruvia-deployment-3.1.tar.gz.sha256 ]] ||
		fail "modified template published artifacts: $1"
	shopt -s nullglob
	local leftovers=("$work/out/".intruvia-deployment.*)
	shopt -u nullglob
	[[ ${#leftovers[@]} == 0 ]] || fail "modified template left staging files: $1"
	if grep -F 'SECRET-CANARY' "$work/unsafe-output" > /dev/null; then
		fail "modified template leaked into error output: $1"
	fi
}
runtime_config=intruvia-app/src/main/runtime/config
for file in StrolchConfiguration.xml PrivilegeConfig.xml.example PrivilegeRoles.xml.example PrivilegeUsers.xml.example \
		ingestion.properties.example; do
	if [[ $file == *.xml* ]]; then
		printf '\n<!-- SECRET-CANARY -->\n' >> "$fixture/$runtime_config/$file"
	else
		printf '\n# SECRET-CANARY\n' >> "$fixture/$runtime_config/$file"
	fi
	expect_unsafe_template "$file comment" "$fixture/$runtime_config/$file" "Unreviewed runtime template: $file"
	cp -p "$root/$runtime_config/$file" "$fixture/$runtime_config/$file"
done
sed -i 's|<databasePath></databasePath>|<databasePath>SECRET-CANARY</databasePath>|' \
		"$fixture/$runtime_config/StrolchConfiguration.xml"
expect_unsafe_template 'StrolchConfiguration.xml value' "$fixture/$runtime_config/StrolchConfiguration.xml" \
		'Unreviewed runtime template: StrolchConfiguration.xml'
cp -p "$root/$runtime_config/StrolchConfiguration.xml" "$fixture/$runtime_config/StrolchConfiguration.xml"
sed -i 's/CHANGE-ME/SECRET-CANARY/' "$fixture/$runtime_config/PrivilegeConfig.xml.example"
expect_unsafe_template 'PrivilegeConfig.xml.example secret' "$fixture/$runtime_config/PrivilegeConfig.xml.example" \
		'Unreviewed runtime template: PrivilegeConfig.xml.example'
cp -p "$root/$runtime_config/PrivilegeConfig.xml.example" "$fixture/$runtime_config/PrivilegeConfig.xml.example"
sed -i 's|<State>SYSTEM</State>|<State>SECRET-CANARY</State>|' "$fixture/$runtime_config/PrivilegeUsers.xml.example"
expect_unsafe_template 'PrivilegeUsers.xml.example value' "$fixture/$runtime_config/PrivilegeUsers.xml.example" \
		'Unreviewed runtime template: PrivilegeUsers.xml.example'
cp -p "$root/$runtime_config/PrivilegeUsers.xml.example" "$fixture/$runtime_config/PrivilegeUsers.xml.example"
printf '\n# SECRET-CANARY\n' >> "$fixture/deployment/.env.example"
expect_unsafe_template '.env.example comment' "$fixture/deployment/.env.example" \
		'Unsafe or unexpected deployment environment template'
cp -p "$root/deployment/.env.example" "$fixture/deployment/.env.example"

printf 'POSTGRES_ADMIN_PASSWORD=not-empty\n' >> "$fixture/deployment/.env.example"
expect_failure "$fixture_builder" 3.0 "$image" "$work/out"
cp -p "$root/deployment/.env.example" "$fixture/deployment/.env.example"
sed -i 's/^INTRUVIA_DB_PASSWORD=$/INTRUVIA_DB_PASSWORD=not-empty/' "$fixture/deployment/.env.example"
expect_failure "$fixture_builder" 3.0 "$image" "$work/out"
cp -p "$root/deployment/.env.example" "$fixture/deployment/.env.example"
printf 'API_TOKEN=secret\n' >> "$fixture/deployment/.env.example"
expect_failure "$fixture_builder" 3.0 "$image" "$work/out"
cp -p "$root/deployment/.env.example" "$fixture/deployment/.env.example"
rm "$fixture/deployment/db/init.sh"
ln -s "$root/deployment/db/init.sh" "$fixture/deployment/db/init.sh"
expect_failure "$fixture_builder" 3.0 "$image" "$work/out"
[[ ! -e $work/out/intruvia-deployment-3.0.tar.gz ]] || fail 'failed build published an archive'

printf 'Deployment tarball tests passed\n'