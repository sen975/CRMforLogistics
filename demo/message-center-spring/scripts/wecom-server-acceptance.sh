#!/usr/bin/env bash
set -euo pipefail

# Read-only production checks. Never print tokens, credentials, message bodies, or full IDs.
BASE_URL="${BASE_URL:-https://www.blindac.com}"
JAR_PATH="${JAR_PATH:-/data/project_testing/source/message-center.jar}"
FRONTEND_INDEX="${FRONTEND_INDEX:-/data/project_testing/source/message-center-spring/frontend/dist/index.html}"

echo "== runtime =="
if [[ -f "$JAR_PATH" ]]; then sha256sum "$JAR_PATH"; else echo "jar=N/A"; fi
if [[ -f "$FRONTEND_INDEX" ]]; then
  grep -oE '/assets/[^" ]+\.js' "$FRONTEND_INDEX" | head -n 3
else echo "frontend_index=N/A"; fi

echo "== public routes =="
curl -skS -o /tmp/wecom-bootstrap-acceptance.body -w 'bootstrap HTTP %{http_code}\n' \
  -X POST "$BASE_URL/api/v1/wecom/conversation-view/bootstrap"
sed -E 's/(token|secret|Authorization)[^,}]*/\1=<redacted>/Ig' /tmp/wecom-bootstrap-acceptance.body | head -c 1000
printf '\n'
curl -skS -o /tmp/conversations-acceptance.body -w 'conversations HTTP %{http_code}\n' \
  "$BASE_URL/api/conversations"
sed -E 's/[0-9a-f]{8}-[0-9a-f-]{27,}/<uuid>/Ig' /tmp/conversations-acceptance.body | head -c 1000
printf '\n'

echo "== approved upstream paths =="
if rg -n '/cgi-bin/user/list_id|externalcontact/groupchat/list/get' \
  /data/project_testing/source/message-center-spring/backend/src 2>/dev/null; then
  echo 'UNAPPROVED_PATH_FOUND' >&2
  exit 2
fi
echo 'path gate passed'
