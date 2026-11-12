#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
secrets_dir=$(cd "$script_dir/.." && pwd)/secrets
active_temp=

mkdir -p "$secrets_dir"
chmod 700 "$secrets_dir"

cleanup_temp() {
  if [ -n "$active_temp" ] && [ -e "$active_temp" ]; then
    rm -f "$active_temp"
  fi
  active_temp=
}

trap cleanup_temp EXIT HUP INT TERM

create_secret() {
  local name=$1
  local format=$2
  local byte_count=$3
  local path="$secrets_dir/$name"

  if [ -e "$path" ]; then
    printf 'exists: %s\n' "$name"
    return
  fi

  case "$format" in
    base64|hex) ;;
    *) printf 'unsupported secret format for %s\n' "$name" >&2; return 2 ;;
  esac

  umask 077
  active_temp=$(mktemp "$secrets_dir/.${name}.tmp.XXXXXX")
  chmod 600 "$active_temp"
  if [ "$format" = base64 ]; then
    openssl rand -base64 "$byte_count" > "$active_temp"
  else
    openssl rand -hex "$byte_count" > "$active_temp"
  fi

  if ln "$active_temp" "$path" 2>/dev/null; then
    cleanup_temp
    printf 'created: %s\n' "$name"
  else
    cleanup_temp
    if [ -e "$path" ]; then
      printf 'exists: %s\n' "$name"
    else
      printf 'unable to create secret file: %s\n' "$name" >&2
      return 1
    fi
  fi
}

create_secret postgres_password base64 32
create_secret minio_access_key hex 20
create_secret minio_secret_key base64 40
create_secret credential_master_key base64 32
create_secret bootstrap_admin_password base64 32
