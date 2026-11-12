#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Existing local verification tools only; disposable loopback PostgreSQL.
set -euo pipefail
export JAVA_HOME=/home/eitch/.sdkman/candidates/java/25.0.5.0+8-tem
export JUNIT_JAR=/home/eitch/.m2/repository/junit/junit/4.13.2/junit-4.13.2.jar
export PLAYWRIGHT_MODULE=/usr/lib/chatgpt/resources/cua_node/lib/node_modules/playwright
export XDG_CONFIG_HOME=/tmp/intruvia-020/config XDG_CACHE_HOME=/tmp/intruvia-020/cache
export CAPACITY_MMDB=/tmp/intruvia-020/city.mmdb
mkdir -p /tmp/intruvia-020
container=intruvia-020-browser
trap 'docker rm -f "$container" >/dev/null' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
docker run --detach --rm --name "$container" --publish 127.0.0.1::5432 \
	--env POSTGRES_USER=intruvia_test --env POSTGRES_PASSWORD=intruvia_test --env POSTGRES_DB=intruvia_test postgres:18-bookworm
for ((attempt=0; attempt<30; attempt++)); do
	if docker exec "$container" pg_isready -h 127.0.0.1 -U intruvia_test >/dev/null; then break; fi
	sleep 1
done
port=$(docker port "$container" 5432/tcp)
export INTRUVIA_TEST_DB_URL="jdbc:postgresql://127.0.0.1:${port##*:}/intruvia_test"
export INTRUVIA_TEST_DB_USERNAME=intruvia_test INTRUVIA_TEST_DB_PASSWORD=intruvia_test
"$JAVA_HOME/bin/java" -cp 'intruvia-core/target/test-classes:intruvia-app/target/intruvia/lib/*' \
	li.intruvia.core.geo.SyntheticCityDatabase "$CAPACITY_MMDB"
/home/eitch/.nvm/versions/node/v26.13.0/bin/node docs/verification/020-capacity.cjs
