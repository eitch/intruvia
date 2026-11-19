#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$root/target"
work=$(mktemp -d "$root/target/compose-startup-test.XXXXXXXX")
project="intruvia-test-$$-${RANDOM}${RANDOM}"
image="intruvia-compose-check-$$-${RANDOM}${RANDOM}:0.0.1"
bundle="$work/bundle"
image_created=false
compose_created=false
unset INTRUVIA_IMAGE INTRUVIA_PUBLIC_ORIGIN POSTGRES_ADMIN_USERNAME POSTGRES_ADMIN_PASSWORD \
	INTRUVIA_DB_NAME INTRUVIA_DB_USERNAME INTRUVIA_DB_PASSWORD COMPOSE_FILE COMPOSE_PROJECT_NAME COMPOSE_PROFILES COMPOSE_ENV_FILES

compose() {
	docker compose --project-name "$project" --project-directory "$bundle" --file "$bundle/compose.yaml" \
		--file "$bundle/delay.override.yaml" --env-file "$bundle/.env" "$@"
}

cleanup() {
	if [[ "$compose_created" == true ]]; then compose down --volumes --remove-orphans >/dev/null 2>&1 || true; fi
	if [[ "$image_created" == true && -d "$work" ]]; then
		docker run --rm --user 0:0 --mount "type=bind,source=$work,target=/test" \
			--entrypoint sh "$image" -c 'rm -rf /test/* /test/.[!.]* /test/..?*' >/dev/null 2>&1 || true
	fi
	if [[ "$image_created" == true ]]; then docker image rm "$image" >/dev/null 2>&1 || true; fi
	rm -rf -- "$work"
}
trap cleanup EXIT

failure() {
	local output
	output=$(compose logs --no-color db 2>&1 || true)
	output=${output//compose_admin_test_password_0123456789/[redacted]}
	output=${output//compose_app_test_password_9876543210/[redacted]}
	output=${output//compose_wrong_test_password_0123456789/[redacted]}
	printf 'Compose startup check failed: %s\n%s\n' "$1" "$output" >&2
	exit 1
}

if docker image inspect "$image" >/dev/null 2>&1; then
	printf 'Test image tag already exists\n' >&2
	exit 1
fi
version=$(sed -n 's/^version=//p' "$root/intruvia-app/target/maven-archiver/pom.properties")
application="$root/intruvia-app/target/intruvia/intruvia-app-$version.jar"
if [[ -z "$version" || ! -f "$application" || ! -d "$root/intruvia-app/target/intruvia/lib" ]]; then
	printf 'Build the current app distribution before running the Compose startup check\n' >&2
	exit 1
fi
distribution="$work/distribution"
mkdir "$distribution"
install -m 0644 "$application" "$distribution/"
cp -R "$root/intruvia-app/target/intruvia/lib" "$distribution/"
"$root/scripts/build-deployment-image.sh" "$image" "$distribution"
image_created=true
mkdir "$work/out" "$bundle"
"$root/scripts/build-deployment-tarball.sh" 0.0.1-test "$image" "$work/out"
(cd "$work/out" && sha256sum -c intruvia-deployment-0.0.1-test.tar.gz.sha256)
tar -xzf "$work/out/intruvia-deployment-0.0.1-test.tar.gz" -C "$bundle"
printf '#!/bin/sh\nsleep 10\ndate +%%s%%N > "$PGDATA/.intruvia-delay-finished"\n' > "$bundle/db/00-delay.sh"
chmod 0755 "$bundle/db/00-delay.sh"
printf 'services:\n  db:\n    healthcheck:\n      interval: 1s\n      timeout: 2s\n      retries: 3\n      start_period: 30s\n    volumes:\n      - type: bind\n        source: ./db/00-delay.sh\n        target: /docker-entrypoint-initdb.d/00-delay.sh\n        read_only: true\n' > "$bundle/delay.override.yaml"
sed -i -e 's|^INTRUVIA_PUBLIC_ORIGIN=$|INTRUVIA_PUBLIC_ORIGIN=https://intruvia.example.org|' \
	-e 's|^POSTGRES_ADMIN_PASSWORD=$|POSTGRES_ADMIN_PASSWORD=compose_admin_test_password_0123456789|' \
	-e 's|^INTRUVIA_DB_PASSWORD=$|INTRUVIA_DB_PASSWORD=compose_app_test_password_9876543210|' "$bundle/.env"
chmod 0600 "$bundle/.env"
umask 077
compose config --quiet
compose config --format json > "$work/compose.json" || failure 'Compose configuration could not be resolved'
python3 - "$work/compose.json" "$bundle" <<'PY'
import json
import sys
from pathlib import Path

configuration = json.loads(Path(sys.argv[1]).read_text())
bundle = Path(sys.argv[2]).resolve()
state = bundle / "state"
expected = {
    "prepare-state": {
        ("/var/lib/intruvia/runtime", state / "runtime"),
        ("/var/log/intruvia", state / "app-logs"),
        ("/run/secrets", bundle / "secrets"),
    },
    "db": {
        ("/var/lib/postgresql", state / "postgres"),
        ("/docker-entrypoint-initdb.d/10-intruvia.sh", bundle / "db/init.sh"),
        ("/docker-entrypoint-initdb.d/00-delay.sh", bundle / "db/00-delay.sh"),
    },
    "app": {
        ("/var/lib/intruvia/runtime", state / "runtime"),
        ("/var/log/intruvia", state / "app-logs"),
        ("/opt/intruvia/templates", bundle / "runtime-templates"),
        ("/run/secrets", bundle / "secrets"),
    },
    "caddy": {
        ("/etc/caddy/Caddyfile", bundle / "Caddyfile"),
        ("/data", state / "caddy-data"),
        ("/config", state / "caddy-config"),
    },
}
if configuration.get("volumes"):
    raise SystemExit("Compose still declares named volumes")
for service, expected_mounts in expected.items():
    actual_mounts = set()
    for mount in configuration["services"][service].get("volumes", []):
        if mount.get("type") != "bind":
            raise SystemExit(f"{service} has a non-bind mount at {mount.get('target')}")
        actual_mounts.add((mount["target"], Path(mount["source"]).resolve()))
    if actual_mounts != {(target, source.resolve()) for target, source in expected_mounts}:
        raise SystemExit(f"{service} bind mounts differ: {actual_mounts!r}")
PY
[[ -z $(compose ps --all -q) ]] || { printf 'Test project already exists\n' >&2; exit 1; }
for name in proxy database; do
	if docker network inspect "${project}_$name" >/dev/null 2>&1; then printf 'Test network already exists\n' >&2; exit 1; fi
done
compose_created=true
compose up -d --wait --wait-timeout 240 db || failure 'database did not become ready'
prepare_state=$(compose ps --all -q prepare-state)
[[ -n "$prepare_state" && $(docker inspect --format '{{.State.Status}}:{{.State.ExitCode}}' "$prepare_state") == exited:0 ]] ||
	failure 'application state ownership was not prepared before database startup'
startup_output=$(timeout --signal=INT --kill-after=180s 30s \
	docker compose --project-name "$project" --project-directory "$bundle" --file "$bundle/compose.yaml" \
	--file "$bundle/delay.override.yaml" --env-file "$bundle/.env" run --rm --no-deps -T app 2>&1 || true)
if [[ ! "$startup_output" =~ Initial\ viewer\ password\ \(shown\ once\;\ save\ it\ now\):[[:space:]]*([0-9a-f]{64}) ]]; then
	failure 'attached Compose first start did not display the initial password'
fi
startup_password=${BASH_REMATCH[1]}
without_password=${startup_output//"$startup_password"/}
[[ $((${#startup_output} - ${#without_password})) == 64 ]] || failure 'initial password was not printed exactly once'
unset startup_output startup_password without_password
compose up -d --wait --wait-timeout 240 db app || failure 'services did not become ready after attached first start'
app=$(compose ps -q app)
db=$(compose ps -q db)
[[ -n "$app" && -n "$db" && -z $(docker port "$app") && -z $(docker port "$db") ]] || failure 'application or database port is published'
[[ $(docker inspect --format '{{.HostConfig.LogConfig.Type}}' "$app") == none ]] || failure 'application Docker logging is not disabled'
db_started=$(date -d "$(docker inspect --format '{{.State.StartedAt}}' "$db")" +%s%N)
delay_finished=$(compose exec -T db sh -c 'cat "$PGDATA/.intruvia-delay-finished"')
app_started=$(date -d "$(docker inspect --format '{{.State.StartedAt}}' "$app")" +%s%N)
[[ "$delay_finished" =~ ^[0-9]+$ ]] || failure 'database delay marker missing'
(( delay_finished - db_started >= 9000000000 && app_started >= delay_finished )) || failure 'application started before delayed database initialization finished'
compose exec -T app sh -c '
	set -eu
	test ! -e /run/secrets/viewer-password
	test -f /var/lib/intruvia/runtime/.intruvia-initialized
	test ! -e /var/lib/intruvia/runtime/.intruvia-initializing
	for path in /var/lib/intruvia/runtime /var/log/intruvia /run/secrets; do
		test "$(stat -c %u:%g "$path")" = 10001:10001
		test "$(stat -c %a "$path")" = 700
	done
	for flag in allowSchemaCreation allowSchemaMigration allowSchemaDrop runMigrationsOnStart pollMigrations; do
		grep -Fq "<$flag>false</$flag>" /var/lib/intruvia/runtime/config/StrolchConfiguration.xml
	done
' || failure 'runtime initialization or schema flags'
role=$(compose exec -T db sh -c 'PGPASSWORD="$INTRUVIA_DB_PASSWORD" psql -X -h db -U "$INTRUVIA_DB_USERNAME" -d "$POSTGRES_DB" -tAc "SELECT NOT r.rolsuper AND NOT r.rolcreatedb AND NOT r.rolcreaterole AND d.datdba = r.oid FROM pg_roles r JOIN pg_database d ON d.datname = current_database() WHERE r.rolname = current_user"')
[[ "$role" == t ]] || failure 'database role'
before=$(compose exec -T app sha256sum /var/lib/intruvia/runtime/config/PrivilegeConfig.xml /var/lib/intruvia/runtime/config/PrivilegeUsers.xml)
compose exec -T db sh -c 'PGPASSWORD="$INTRUVIA_DB_PASSWORD" psql -X -h db -U "$INTRUVIA_DB_USERNAME" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -c "CREATE TABLE public.compose_test (value integer); INSERT INTO public.compose_test VALUES (42)"' >/dev/null
compose restart app >/dev/null
healthy=false
for _ in {1..120}; do
	if [[ $(docker inspect --format '{{.State.Health.Status}}' "$app") == healthy ]]; then healthy=true; break; fi
	sleep 1
done
[[ "$healthy" == true ]] || failure 'application did not become ready after restart'
after=$(compose exec -T app sha256sum /var/lib/intruvia/runtime/config/PrivilegeConfig.xml /var/lib/intruvia/runtime/config/PrivilegeUsers.xml)
[[ "$before" == "$after" ]] || failure 'privilege files changed during restart'
value=$(compose exec -T db sh -c 'PGPASSWORD="$INTRUVIA_DB_PASSWORD" psql -X -h db -U "$INTRUVIA_DB_USERNAME" -d "$POSTGRES_DB" -tAc "SELECT value FROM public.compose_test"')
[[ "$value" == 42 ]] || failure 'database data did not persist'
locked_output=$(compose run --rm -T --no-deps --entrypoint /usr/local/bin/intruvia-reset-viewer-password app 2>&1 || true)
[[ "$locked_output" != *'Replacement viewer password'* ]] || failure 'reset exposed a password while the app was running'
compose stop app >/dev/null
reset_output=$(compose run --rm -T --no-deps --entrypoint /usr/local/bin/intruvia-reset-viewer-password app)
[[ "$reset_output" =~ Replacement\ viewer\ password\ \(shown\ once\;\ save\ it\ now\):$'\n'([0-9a-f]{64})$ ]] ||
	failure 'container password reset output was not available on the attached console'
reset_password=${BASH_REMATCH[1]}
[[ $(docker inspect --format '{{.HostConfig.LogConfig.Type}}' "$app") == none ]] || failure 'application Docker logging is enabled'
compose up -d --wait --wait-timeout 180 app || failure 'application did not restart after reset'
unset reset_output reset_password locked_output
output=$(compose logs --no-color db)
[[ "$output" != *compose_admin_test_password_0123456789* &&
	"$output" != *compose_app_test_password_9876543210* ]] || failure 'database credentials appeared in service logs'
compose stop app >/dev/null
sed -i 's/^INTRUVIA_DB_PASSWORD=compose_app_test_password_9876543210$/INTRUVIA_DB_PASSWORD=compose_wrong_test_password_0123456789/' "$bundle/.env"
if compose up -d --force-recreate --wait --wait-timeout 60 db >/dev/null 2>&1; then
	failure 'database health accepted a changed password for the initialized volume'
fi
db=$(compose ps --all -q db)
[[ -n "$db" && $(docker inspect --format '{{.State.Health.Status}}' "$db") == unhealthy ]] ||
	failure 'database did not become unhealthy with the wrong password'
if compose up -d --force-recreate --wait --wait-timeout 20 app >/dev/null 2>&1; then
	failure 'app startup succeeded while database authentication was unhealthy'
fi
app=$(compose ps --all -q app)
[[ -n "$app" && $(docker inspect --format '{{.State.Status}}' "$app") != running ]] ||
	failure 'app started while database authentication was unhealthy'
printf 'Network-authenticated database readiness, wrong-password health rejection, private Compose startup, restart and offline password reset passed using image %s\n' \
	"$(docker image inspect --format '{{.Id}}' "$image")"