#!/usr/bin/env bash
set -Eeuo pipefail

readonly BASE_DIR="${MESSAGE_CENTER_BASE_DIR:-/opt/crm-logistics-message-center}"
readonly RELEASES_DIR="$BASE_DIR/releases"
readonly INCOMING_DIR="$BASE_DIR/incoming"
readonly CURRENT_LINK="$BASE_DIR/current"
readonly SERVICE_NAME="message-center.service"
readonly ENV_FILE="/etc/message-center/message-center.env"
readonly SERVICE_FILE="/etc/systemd/system/$SERVICE_NAME"
readonly JAVA_BIN="/usr/bin/java"
readonly HEALTH_URL="http://127.0.0.1:8107/api/contacts"
readonly MAX_RELEASES=5

die() {
  printf 'DEPLOY_ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令: $1"
}

require_java_17() {
  [[ -x "$JAVA_BIN" ]] || die "缺少 Java 17: $JAVA_BIN"
  local version
  version="$($JAVA_BIN -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -n 1)"
  [[ "$version" == 17.* ]] || die "需要 Java 17，当前为: ${version:-unknown}"
}

preflight() {
  local command_name
  for command_name in awk curl docker find head id install journalctl ln mv readlink \
    realpath rm sed seq sort systemctl tar useradd; do
    require_command "$command_name"
  done
  docker compose version >/dev/null
  require_java_17
}

[[ "${EUID}" -eq 0 ]] || die "deploy.sh 必须由 root 执行"
[[ "$#" -eq 2 ]] || die "用法: deploy.sh <release-id> <staging-dir>"
require_command realpath

readonly RELEASE_ID="$1"
readonly STAGING_DIR="$(realpath -m "$2")"
readonly RELEASE_DIR="$RELEASES_DIR/$RELEASE_ID"

[[ "$RELEASE_ID" =~ ^[A-Za-z0-9._-]+$ ]] || die "release-id 含非法字符"
[[ "$STAGING_DIR" == "$INCOMING_DIR/"* ]] || die "staging-dir 不在 incoming 目录内"
[[ ! -e "$RELEASE_DIR" ]] || die "发布目录已存在: $RELEASE_DIR"
[[ -s "$STAGING_DIR/message-center.jar" ]] || die "缺少后端 Jar"
[[ -s "$STAGING_DIR/frontend.tar.gz" ]] || die "缺少前端压缩包"
[[ -s "$STAGING_DIR/message-center.service" ]] || die "缺少 systemd unit"
[[ -s "$ENV_FILE" ]] || die "缺少生产环境文件: $ENV_FILE"
preflight

if ! id -u message-center >/dev/null 2>&1; then
  useradd --system \
    --home-dir /var/lib/message-center \
    --create-home \
    --shell /usr/sbin/nologin \
    message-center
fi

install -d -m 0755 "$BASE_DIR" "$RELEASES_DIR" "$INCOMING_DIR"
install -d -o message-center -g message-center -m 0750 /var/lib/message-center

previous_target=""
if [[ -L "$CURRENT_LINK" ]]; then
  previous_target="$(readlink -f "$CURRENT_LINK")"
fi

previous_unit="$RELEASES_DIR/.message-center.service-$RELEASE_ID.previous"
previous_unit_existed=false
if [[ -f "$SERVICE_FILE" ]]; then
  install -m 0644 "$SERVICE_FILE" "$previous_unit"
  previous_unit_existed=true
fi

service_was_enabled=false
if systemctl is-enabled --quiet "$SERVICE_NAME"; then
  service_was_enabled=true
fi

switch_current() {
  local target="$1"
  local temporary_link="$BASE_DIR/.current-$RELEASE_ID-$$"
  rm -f -- "$temporary_link"
  ln -s "$target" "$temporary_link"
  mv -Tf "$temporary_link" "$CURRENT_LINK"
}

wait_for_health() {
  local attempt status
  for attempt in $(seq 1 60); do
    if systemctl is-active --quiet "$SERVICE_NAME"; then
      status="$(curl \
        --silent \
        --show-error \
        --output /dev/null \
        --write-out '%{http_code}' \
        --max-time 2 \
        "$HEALTH_URL" || true)"
      if [[ "$status" == "401" ]]; then
        return 0
      fi
    fi
    sleep 2
  done
  return 1
}

restore_previous_unit() {
  if [[ "$previous_unit_existed" == true ]]; then
    install -m 0644 "$previous_unit" "$SERVICE_FILE"
    systemctl daemon-reload
    if [[ "$service_was_enabled" == true ]]; then
      systemctl enable "$SERVICE_NAME" >/dev/null
    else
      systemctl disable "$SERVICE_NAME" >/dev/null 2>&1
    fi
  else
    systemctl disable "$SERVICE_NAME" >/dev/null 2>&1
    rm -f -- "$SERVICE_FILE"
    systemctl daemon-reload
  fi
}

cleanup_failed_release() {
  rm -rf -- "$RELEASE_DIR" "$STAGING_DIR" "$previous_unit" || true
}

rollback() {
  local exit_code="$1"
  local rollback_ok=true
  trap - ERR
  printf 'ROLLBACK: 发布 %s 未通过，恢复上一版本。\n' "$RELEASE_ID" >&2

  if [[ -n "$previous_target" && -d "$previous_target" ]]; then
    if ! switch_current "$previous_target"; then
      rollback_ok=false
    fi
  else
    if ! systemctl stop "$SERVICE_NAME"; then
      rollback_ok=false
    fi
    if ! rm -f -- "$CURRENT_LINK"; then
      rollback_ok=false
    fi
  fi

  if ! restore_previous_unit; then
    rollback_ok=false
  fi
  if [[ -n "$previous_target" && -d "$previous_target" ]]; then
    if ! systemctl restart "$SERVICE_NAME" || ! wait_for_health; then
      rollback_ok=false
    fi
  fi
  if [[ "$rollback_ok" == true ]]; then
    cleanup_failed_release
  fi
  journalctl -u "$SERVICE_NAME" -n 100 --no-pager >&2 || true
  if [[ "$rollback_ok" != true ]]; then
    printf 'ROLLBACK_FAILED: 需要人工检查 current、systemd 和数据库状态。\n' >&2
  fi
  exit "$exit_code"
}

on_unexpected_error() {
  local exit_code="$?"
  rollback "$exit_code"
}

trap on_unexpected_error ERR

install -d -m 0755 "$RELEASE_DIR/backend" "$RELEASE_DIR/frontend"
install -m 0644 "$STAGING_DIR/message-center.jar" \
  "$RELEASE_DIR/backend/message-center.jar"
tar --extract \
  --gzip \
  --file "$STAGING_DIR/frontend.tar.gz" \
  --directory "$RELEASE_DIR/frontend" \
  --no-same-owner \
  --no-same-permissions
if [[ ! -s "$RELEASE_DIR/frontend/index.html" ]]; then
  printf 'DEPLOY_ERROR: 前端发布包缺少 index.html\n' >&2
  rollback 1
fi

install -m 0644 "$STAGING_DIR/message-center.service" "$SERVICE_FILE"
systemctl daemon-reload
systemctl enable "$SERVICE_NAME" >/dev/null
switch_current "$RELEASE_DIR"

if ! systemctl restart "$SERVICE_NAME" || ! wait_for_health; then
  rollback 1
fi

trap - ERR
rm -f -- "$previous_unit"
current_target="$(readlink -f "$CURRENT_LINK")"
mapfile -t old_releases < <(
  find "$RELEASES_DIR" \
    -mindepth 1 \
    -maxdepth 1 \
    -type d \
    -printf '%T@ %p\n' \
    | sort -nr \
    | awk -v keep="$MAX_RELEASES" 'NR > keep {sub(/^[^ ]+ /, ""); print}'
)

for old_release in "${old_releases[@]}"; do
  old_release="$(realpath -m "$old_release")"
  [[ "$old_release" == "$RELEASES_DIR/"* ]] || die "拒绝清理目录外路径"
  [[ "$old_release" == "$current_target" ]] && continue
  rm -rf -- "$old_release"
done

rm -rf -- "$STAGING_DIR"
printf 'DEPLOY_OK: %s\n' "$RELEASE_ID"
