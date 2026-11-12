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
container=intruvia-020-regressions
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
python3 - <<'REWRITE'
from pathlib import Path
for source, prefix in [('015-browser-smoke.cjs', '020-snapshot'), ('016-browser.cjs', '020-live'), ('017-browser.cjs', '020-recovery')]:
	old = source[:3]
	script = Path('docs/verification', source).read_text().replace(old + '-', prefix + '-')
	Path('/tmp/intruvia-020', prefix + '.cjs').write_text(script)
REWRITE
for suite in snapshot live recovery; do
	/home/eitch/.nvm/versions/node/v26.13.0/bin/node "/tmp/intruvia-020/020-$suite.cjs" \
		> "docs/verification/020-$suite.txt" 2>&1
done
docker exec "$container" psql -U intruvia_test -d intruvia_test -Atc \
	"SELECT count(*) FROM pg_database WHERE datname LIKE 'intruvia_test_%';"
