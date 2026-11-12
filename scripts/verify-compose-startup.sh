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
	if [[ "$image_created" == true && -d "$bundle/secrets" ]]; then
		docker run --rm --user 0:0 --mount "type=bind,source=$bundle/secrets,target=/secret" \
			--entrypoint sh "$image" -c 'rm -f /secret/viewer-password; chmod 0777 /secret' >/dev/null 2>&1 || true
	fi
	if [[ "$image_created" == true ]]; then docker image rm "$image" >/dev/null 2>&1 || true; fi
	rm -rf -- "$work"
}
trap cleanup EXIT

failure() {
	local output
	output=$(compose logs --no-color 2>&1 || true)
	output=${output//compose-viewer-test-canary/[redacted]}
	output=${output//compose_admin_test_password_0123456789/[redacted]}
	output=${output//compose_app_test_password_9876543210/[redacted]}
	printf 'Compose startup check failed: %s\n%s\n' "$1" "$output" >&2
	exit 1
}

if docker image inspect "$image" >/dev/null 2>&1; then
	printf 'Test image tag already exists\n' >&2
	exit 1
fi
"$root/scripts/build-deployment-image.sh" "$image" "$root/intruvia-app/target/intruvia"
image_created=true
mkdir "$work/out" "$bundle"
"$root/scripts/build-deployment-tarball.sh" 0.0.1-test "$image" "$work/out"
(cd "$work/out" && sha256sum -c intruvia-deployment-0.0.1-test.tar.gz.sha256)
tar -xzf "$work/out/intruvia-deployment-0.0.1-test.tar.gz" -C "$bundle"
printf '#!/bin/sh\nsleep 10\ndate +%%s%%N > /var/lib/postgresql/.intruvia-delay-finished\n' > "$bundle/db/00-delay.sh"
chmod 0755 "$bundle/db/00-delay.sh"
printf 'services:\n  db:\n    volumes:\n      - type: bind\n        source: ./db/00-delay.sh\n        target: /docker-entrypoint-initdb.d/00-delay.sh\n        read_only: true\n' > "$bundle/delay.override.yaml"
sed -i -e 's|^INTRUVIA_PUBLIC_ORIGIN=$|INTRUVIA_PUBLIC_ORIGIN=https://intruvia.example.org|' \
	-e 's|^POSTGRES_ADMIN_PASSWORD=$|POSTGRES_ADMIN_PASSWORD=compose_admin_test_password_0123456789|' \
	-e 's|^INTRUVIA_DB_PASSWORD=$|INTRUVIA_DB_PASSWORD=compose_app_test_password_9876543210|' "$bundle/.env"
chmod 0600 "$bundle/.env"
umask 077
printf '%s\n' 'compose-viewer-test-canary' > "$bundle/secrets/viewer-password"
docker run --rm --user 0:0 --mount "type=bind,source=$bundle/secrets,target=/secret" --entrypoint sh "$image" -c '
	set -eu
	chown 10001:10001 /secret /secret/viewer-password
	chmod 700 /secret
	chmod 600 /secret/viewer-password
'
compose config --quiet
[[ -z $(compose ps --all -q) ]] || { printf 'Test project already exists\n' >&2; exit 1; }
for name in postgres_data runtime app_logs caddy_data caddy_config; do
	if docker volume inspect "${project}_$name" >/dev/null 2>&1; then printf 'Test volume already exists\n' >&2; exit 1; fi
done
for name in proxy database; do
	if docker network inspect "${project}_$name" >/dev/null 2>&1; then printf 'Test network already exists\n' >&2; exit 1; fi
done
compose_created=true
compose up -d --wait --wait-timeout 240 db app || failure 'services did not become ready'
app=$(compose ps -q app)
db=$(compose ps -q db)
[[ -n "$app" && -n "$db" && -z $(docker port "$app") && -z $(docker port "$db") ]] || failure 'application or database port is published'
db_started=$(date -d "$(docker inspect --format '{{.State.StartedAt}}' "$db")" +%s%N)
delay_finished=$(compose exec -T db cat /var/lib/postgresql/.intruvia-delay-finished)
app_started=$(date -d "$(docker inspect --format '{{.State.StartedAt}}' "$app")" +%s%N)
[[ "$delay_finished" =~ ^[0-9]+$ ]] || failure 'database delay marker missing'
(( delay_finished - db_started >= 9000000000 && app_started >= delay_finished )) || failure 'application started before delayed database initialization finished'
compose exec -T app sh -c '
	set -eu
	test ! -e /run/secrets/viewer-password
	test -f /var/lib/intruvia/runtime/.intruvia-initialized
	test ! -e /var/lib/intruvia/runtime/.intruvia-initializing
	for flag in allowSchemaCreation allowSchemaMigration allowSchemaDrop runMigrationsOnStart pollMigrations; do
		grep -Fq "<$flag>false</$flag>" /var/lib/intruvia/runtime/config/StrolchConfiguration.xml
	done
' || failure 'runtime initialization or schema flags'
role=$(compose exec -T db sh -c 'PGPASSWORD="$INTRUVIA_DB_PASSWORD" psql -X -h 127.0.0.1 -U "$INTRUVIA_DB_USERNAME" -d "$POSTGRES_DB" -tAc "SELECT NOT r.rolsuper AND NOT r.rolcreatedb AND NOT r.rolcreaterole AND d.datdba = r.oid FROM pg_roles r JOIN pg_database d ON d.datname = current_database() WHERE r.rolname = current_user"')
[[ "$role" == t ]] || failure 'database role'
before=$(compose exec -T app sha256sum /var/lib/intruvia/runtime/config/PrivilegeConfig.xml /var/lib/intruvia/runtime/config/PrivilegeUsers.xml)
compose exec -T db sh -c 'PGPASSWORD="$INTRUVIA_DB_PASSWORD" psql -X -h 127.0.0.1 -U "$INTRUVIA_DB_USERNAME" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -c "CREATE TABLE public.compose_test (value integer); INSERT INTO public.compose_test VALUES (42)"' >/dev/null
compose restart app >/dev/null
healthy=false
for _ in {1..120}; do
	if [[ $(docker inspect --format '{{.State.Health.Status}}' "$app") == healthy ]]; then healthy=true; break; fi
	sleep 1
done
[[ "$healthy" == true ]] || failure 'application did not become ready after restart'
after=$(compose exec -T app sha256sum /var/lib/intruvia/runtime/config/PrivilegeConfig.xml /var/lib/intruvia/runtime/config/PrivilegeUsers.xml)
[[ "$before" == "$after" ]] || failure 'privilege files changed during restart'
value=$(compose exec -T db sh -c 'PGPASSWORD="$INTRUVIA_DB_PASSWORD" psql -X -h 127.0.0.1 -U "$INTRUVIA_DB_USERNAME" -d "$POSTGRES_DB" -tAc "SELECT value FROM public.compose_test"')
[[ "$value" == 42 ]] || failure 'database data did not persist'
output=$(compose logs --no-color app db)
[[ "$output" != *compose-viewer-test-canary* && "$output" != *compose_admin_test_password_0123456789* &&
	"$output" != *compose_app_test_password_9876543210* ]] || failure 'credentials appeared in service logs'
printf 'Delayed database readiness, private Compose startup and restart passed using image %s\n' "$(docker image inspect --format '{{.Id}}' "$image")"