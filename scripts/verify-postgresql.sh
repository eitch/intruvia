#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")/.."
# PostgreSQL is a test service only; no Maven/Docker test library is introduced.
image="${INTRUVIA_TEST_POSTGRES_IMAGE:-postgres:18-bookworm}"
container="intruvia-verify-$$-$RANDOM"
cleanup() { docker rm -f "$container" >/dev/null; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
docker run --detach --rm --name "$container" --publish 127.0.0.1::5432 \
	--env POSTGRES_USER=intruvia_test --env POSTGRES_PASSWORD=intruvia_test \
	--env POSTGRES_DB=intruvia_test "$image" >/dev/null
for ((attempt = 0; attempt < 30; attempt++)); do
	if docker exec "$container" pg_isready -h 127.0.0.1 -U intruvia_test -d intruvia_test >/dev/null; then
	  break
	fi
	sleep 1
done
docker exec "$container" pg_isready -h 127.0.0.1 -U intruvia_test -d intruvia_test
port="$(docker port "$container" 5432/tcp)"
export INTRUVIA_TEST_DB_URL="jdbc:postgresql://127.0.0.1:${port##*:}/intruvia_test"
export INTRUVIA_TEST_DB_USERNAME=intruvia_test
export INTRUVIA_TEST_DB_PASSWORD=intruvia_test
docker image inspect "$image" --format 'PostgreSQL test image: {{.Id}} {{json .RepoDigests}}'
docker exec "$container" psql -h 127.0.0.1 -U intruvia_test -d intruvia_test -c 'SELECT version();'
mvn -B clean verify "$@"
