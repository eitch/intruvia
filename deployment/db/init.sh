#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set +x
set -Eeuo pipefail

if [[ ! ${POSTGRES_USER:-} =~ ^[a-z][a-z0-9_]{0,62}$ || ${POSTGRES_USER:-} == pg_* ||
	! ${POSTGRES_DB:-} =~ ^[a-z][a-z0-9_]{0,62}$ || ${POSTGRES_DB:-} == pg_* ||
	${POSTGRES_DB:-} == postgres || ${POSTGRES_DB:-} == template0 || ${POSTGRES_DB:-} == template1 ||
	! ${INTRUVIA_DB_USERNAME:-} =~ ^[a-z][a-z0-9_]{0,62}$ || ${INTRUVIA_DB_USERNAME:-} == pg_* ||
	${INTRUVIA_DB_USERNAME:-} == "${POSTGRES_USER:-}" ||
	! ${POSTGRES_PASSWORD:-} =~ ^[A-Za-z0-9_-]{24,128}$ ||
	! ${INTRUVIA_DB_PASSWORD:-} =~ ^[A-Za-z0-9_-]{24,128}$ ||
	${INTRUVIA_DB_PASSWORD:-} == "${POSTGRES_PASSWORD:-}" ]]; then
	printf 'Invalid database initialization settings; inspect the protected .env file\n' >&2
	exit 1
fi

psql -X --quiet --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv app_user INTRUVIA_DB_USERNAME
\getenv db_name POSTGRES_DB
SELECT format('CREATE ROLE %I NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', :'app_user') \gexec
SELECT format('ALTER DATABASE %I OWNER TO %I', :'db_name', :'app_user') \gexec
SQL

{
	printf '\\password %s\n' "$INTRUVIA_DB_USERNAME"
	printf '%s\n%s\n' "$INTRUVIA_DB_PASSWORD" "$INTRUVIA_DB_PASSWORD"
} | psql -X --quiet --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"

psql -X --quiet --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv app_user INTRUVIA_DB_USERNAME
SELECT format('ALTER ROLE %I LOGIN', :'app_user') \gexec
SQL