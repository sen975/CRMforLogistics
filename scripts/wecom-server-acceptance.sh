#!/usr/bin/env bash
set -euo pipefail

# Read-only production acceptance. It never prints tokens, secrets, message bodies or cursors.
BASE="${MESSAGE_CENTER_BASE:-/data/project_testing/source/message-center-spring}"
BACKEND="${MESSAGE_CENTER_BACKEND:-$BASE/backend}"
ENV_FILE="${MESSAGE_CENTER_ENV_FILE:-$BACKEND/.env}"
JAR="${MESSAGE_CENTER_JAR:-/data/project_testing/source/message-center.jar}"
PUBLIC_URL="${MESSAGE_CENTER_PUBLIC_URL:-https://www.blindac.com}"
LOCAL_URL="${MESSAGE_CENTER_LOCAL_URL:-http://127.0.0.1:8107}"

echo "== process and artifact =="
PID="$(pgrep -f '/data/project_testing/source/message-center.jar' | head -n 1 || true)"
test -n "$PID" && tr '\0' ' ' < "/proc/$PID/cmdline" && echo || echo "jar process: NOT_RUNNING"
test -f "$JAR" && sha256sum "$JAR" || echo "jar: MISSING"

echo "== static API gate =="
FORBIDDEN_HITS="$(rg -n '/cgi-bin/(?:.*?/)?get_corp_token|/cgi-bin/user/list_id' "$BACKEND/src/main/java" || true)"
if [[ -n "$FORBIDDEN_HITS" ]]; then
  printf '%s\n' "$FORBIDDEN_HITS" >&2
  echo "FORBIDDEN WeCom API path found" >&2
  exit 20
fi

# suite_access_token is a template-authorization credential and may only be
# requested by the authorization gateway. It must never be a runtime token source.
SUITE_HITS="$(rg -l '/cgi-bin/service/get_suite_token' "$BACKEND/src/main/java" || true)"
for file in $SUITE_HITS; do
  if [[ "$file" != */channel/wecom/WeComAuthorizationGateway.java ]]; then
    echo "FORBIDDEN suite token runtime path: $file" >&2
    exit 21
  fi
done
echo "forbidden runtime paths: clear; suite token: authorization-only"

echo "== local and public routes =="
for path in /api/v1/wecom/installations /api/v1/wecom/conversation-view/bootstrap /api/v1/wecom/js-sdk-config; do
  local_code="$(curl -sS -o /dev/null --max-time 10 -w '%{http_code}' "$LOCAL_URL$path" || true)"
  public_code="$(curl -skS -o /dev/null --max-time 15 -w '%{http_code}' "$PUBLIC_URL$path" || true)"
  printf '%s local=%s public=%s\n' "$path" "$local_code" "$public_code"
done

echo "== database schema and counts =="
if [[ -r "$ENV_FILE" ]] && command -v psql >/dev/null 2>&1; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
  JDBC_URL="${SPRING_DATASOURCE_URL:-}"
  PG_URL="${JDBC_URL#jdbc:postgresql://}"
  PG_HOSTPORT="${PG_URL%%/*}"
  PG_DB="${PG_URL#*/}"
  PG_DB="${PG_DB%%\?*}"
  PG_HOST="${PG_HOSTPORT%%:*}"
  PG_PORT="${PG_HOSTPORT#*:}"
  [[ "$PG_PORT" == "$PG_HOSTPORT" ]] && PG_PORT=5432
  [[ "$PG_DB" == "$PG_URL" || -z "$PG_DB" ]] && PG_DB=message_center
  PGPASSWORD="${SPRING_DATASOURCE_PASSWORD:-}" psql -X -q -p "$PG_PORT" -h "$PG_HOST" \
    -U "${SPRING_DATASOURCE_USERNAME:-}" -d "$PG_DB" \
    -c "select (select version from flyway_schema_history order by installed_rank desc limit 1) as flyway_version, (select count(*) from wecom_installations where deleted_at is null) as installations, (select count(*) from wecom_parties) as parties, (select count(*) from wecom_source_conversations) as source_conversations, (select count(*) from wecom_chatdata_messages) as chatdata_messages, (select count(*) from wecom_chatdata_ingest_failures where resolved_at is null) as unresolved_failures;" \
    2>/dev/null || echo "database: UNAVAILABLE (run with explicit psql connection variables)"
  unset PGPASSWORD
else
  echo "database: SKIPPED (missing env or psql)"
fi

echo "acceptance checks finished"
