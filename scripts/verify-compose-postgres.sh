#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
containers=()
cleanup() {
	for container in "${containers[@]}"; do docker rm -fv "$container" >/dev/null 2>&1 || true; done
}
trap cleanup EXIT
export POSTGRES_USER=intruvia_admin
export POSTGRES_DB=intruvia
export POSTGRES_PASSWORD=admin_test_password_0123456789
export INTRUVIA_DB_USERNAME=intruvia
export INTRUVIA_DB_PASSWORD=app_test_password_9876543210

start_database() {
	docker run --detach --network none \
		--env POSTGRES_USER --env POSTGRES_DB --env POSTGRES_PASSWORD \
		--env INTRUVIA_DB_USERNAME --env INTRUVIA_DB_PASSWORD \
		--mount "type=bind,source=$root/deployment/db/init.sh,target=/docker-entrypoint-initdb.d/10-intruvia.sh,readonly" \
		postgres:18-bookworm
}

failure() {
	local output
	output=$(docker logs "$1" 2>&1 || true)
	output=${output//"$POSTGRES_PASSWORD"/[redacted]}
	output=${output//"$INTRUVIA_DB_PASSWORD"/[redacted]}
	printf 'PostgreSQL initialization check failed:\n%s\n' "$output" >&2
	exit 1
}

container=$(start_database)
containers+=("$container")
ready=false
for _ in {1..120}; do
	if docker exec "$container" sh -c 'PGPASSWORD="$INTRUVIA_DB_PASSWORD" psql -X -h 127.0.0.1 -U "$INTRUVIA_DB_USERNAME" -d "$POSTGRES_DB" -tAc "SELECT 1"' >/dev/null 2>&1; then
		ready=true
		break
	fi
	[[ $(docker inspect --format '{{.State.Running}}' "$container") == true ]] || failure "$container"
	sleep 1
done
[[ "$ready" == true ]] || failure "$container"
role=$(docker exec "$container" sh -c 'PGPASSWORD="$INTRUVIA_DB_PASSWORD" psql -X -h 127.0.0.1 -U "$INTRUVIA_DB_USERNAME" -d "$POSTGRES_DB" -tAc "SELECT NOT r.rolsuper AND NOT r.rolcreatedb AND NOT r.rolcreaterole AND NOT r.rolreplication AND NOT r.rolbypassrls AND d.datdba = r.oid FROM pg_roles r JOIN pg_database d ON d.datname = current_database() WHERE r.rolname = current_user"')
[[ "$role" == t ]] || failure "$container"
scram=$(docker exec "$container" psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT rolpassword LIKE 'SCRAM-SHA-256\$%' FROM pg_authid WHERE rolname = '$INTRUVIA_DB_USERNAME'")
[[ "$scram" == t ]] || failure "$container"
output=$(docker logs "$container" 2>&1)
[[ "$output" != *"$POSTGRES_PASSWORD"* && "$output" != *"$INTRUVIA_DB_PASSWORD"* ]] || failure "$container"

export INTRUVIA_DB_PASSWORD="$POSTGRES_PASSWORD"
invalid=$(start_database)
containers+=("$invalid")
for _ in {1..120}; do
	[[ $(docker inspect --format '{{.State.Running}}' "$invalid") == false ]] && break
	sleep 1
done
[[ $(docker inspect --format '{{.State.Running}}' "$invalid") == false ]] || failure "$invalid"
output=$(docker logs "$invalid" 2>&1)
[[ "$output" == *'Invalid database initialization settings'* && "$output" != *"$POSTGRES_PASSWORD"* ]] || failure "$invalid"
printf 'PostgreSQL initialization, dedicated role, SCRAM and invalid-credential checks passed\n'