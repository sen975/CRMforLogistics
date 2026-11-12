#!/usr/bin/env bash
set -euo pipefail

# Read-only server acceptance for email diagnostics. It never prints credentials,
# account identifiers, message subjects, message bodies, or IMAP configuration.
BASE="${MESSAGE_CENTER_BASE:-/data/project_testing/source/message-center-spring}"
BACKEND="${MESSAGE_CENTER_BACKEND:-$BASE/backend}"
ENV_FILE="${MESSAGE_CENTER_ENV_FILE:-$BACKEND/.env}"
JAR="${MESSAGE_CENTER_JAR:-/data/project_testing/source/message-center.jar}"
LOCAL_URL="${MESSAGE_CENTER_LOCAL_URL:-http://127.0.0.1:8107}"
PUBLIC_URL="${MESSAGE_CENTER_PUBLIC_URL:-https://www.blindac.com}"
EMAIL_ACCOUNT_ID="${MESSAGE_CENTER_EMAIL_ACCOUNT_ID:-}"

require_file() {
  [[ -r "$1" ]] || {
    echo "required file is unavailable: $1" >&2
    exit 2
  }
}

require_file "$ENV_FILE"
require_file "$JAR"
command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
command -v psql >/dev/null || { echo "psql is required" >&2; exit 2; }
command -v shasum >/dev/null || { echo "shasum is required" >&2; exit 2; }

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

JDBC_URL="${SPRING_DATASOURCE_URL:-}"
[[ "$JDBC_URL" == jdbc:postgresql://* ]] || {
  echo "SPRING_DATASOURCE_URL must be a PostgreSQL JDBC URL" >&2
  exit 2
}
[[ -n "${SPRING_DATASOURCE_USERNAME:-}" && -n "${SPRING_DATASOURCE_PASSWORD:-}" ]] || {
  echo "database credentials are required" >&2
  exit 2
}

PG_URL="${JDBC_URL#jdbc:postgresql://}"
PG_HOSTPORT="${PG_URL%%/*}"
PG_DB="${PG_URL#*/}"
PG_DB="${PG_DB%%\?*}"
PG_HOST="${PG_HOSTPORT%%:*}"
PG_PORT="${PG_HOSTPORT#*:}"
[[ "$PG_PORT" == "$PG_HOSTPORT" ]] && PG_PORT=5432

if [[ -n "$EMAIL_ACCOUNT_ID" && ! "$EMAIL_ACCOUNT_ID" =~ ^[0-9a-fA-F-]{36}$ ]]; then
  echo "MESSAGE_CENTER_EMAIL_ACCOUNT_ID must be a UUID" >&2
  exit 2
fi

psql_query() {
  PGPASSWORD="$SPRING_DATASOURCE_PASSWORD" psql -X -v ON_ERROR_STOP=1 -q \
    -p "$PG_PORT" -h "$PG_HOST" -U "$SPRING_DATASOURCE_USERNAME" -d "$PG_DB" -c "$1"
}

echo "== artifact =="
shasum -a 256 "$JAR"

echo "== service health =="
curl -fsS --max-time 10 "$LOCAL_URL/actuator/health" >/dev/null
echo "local health: OK"
curl -kfsS --max-time 15 "$PUBLIC_URL/actuator/health" >/dev/null
echo "public health: OK"

account_filter=""
if [[ -n "$EMAIL_ACCOUNT_ID" ]]; then
  account_filter="and id = '$EMAIL_ACCOUNT_ID'::uuid"
fi

echo "== email account state =="
psql_query "
  select id, auth_status, sync_status, last_synced_at
  from channel_accounts
  where channel_type = 'email' and deleted_at is null $account_filter
  order by updated_at desc;"

echo "== email message counts =="
psql_query "
  select count(*) as total,
         count(*) filter (where occurred_at >= now() - interval '24 hours') as recent_24h
  from messages
  where channel_account_id in (
    select id from channel_accounts
    where channel_type = 'email' and deleted_at is null $account_filter
  );"

echo "== last sync error =="
echo "N/A: channel_accounts persists sync_status and last_synced_at but no sync error code."

unset PGPASSWORD SPRING_DATASOURCE_PASSWORD
echo "read-only acceptance checks finished"
