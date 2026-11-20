#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Disposable PostgreSQL and locally installed browser tooling only.
set -euo pipefail
cd "$(dirname "$0")/../.."
set +u
source "${SDKMAN_DIR:-$HOME/.sdkman}/bin/sdkman-init.sh"
set -u
java -version
mvn -version
export JUNIT_JAR=/home/eitch/.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar
export PLAYWRIGHT_MODULE=/usr/lib/chatgpt/resources/cua_node/lib/node_modules/playwright
container="intruvia-023-admin-browser-$$"
cleanup() { docker rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
docker run --detach --rm --name "$container" --publish 127.0.0.1::5432 \
	--env POSTGRES_USER=intruvia_test --env POSTGRES_PASSWORD=intruvia_test --env POSTGRES_DB=intruvia_test \
	"${INTRUVIA_TEST_POSTGRES_IMAGE:-postgres:18-bookworm}" >/dev/null
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
mvn -B -pl intruvia-app -am package
/home/eitch/.nvm/versions/node/v26.13.0/bin/node docs/verification/023-admin-browser.cjs