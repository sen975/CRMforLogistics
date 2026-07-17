#!/usr/bin/env bash
set -euo pipefail
set -o noclobber

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
secrets_dir=$(cd "$script_dir/.." && pwd)/secrets

mkdir -p "$secrets_dir"
chmod 700 "$secrets_dir"

create_secret() {
  local name=$1
  local format=$2
  local byte_count=$3
  local path="$secrets_dir/$name"

  if [ -e "$path" ]; then
    printf 'exists: %s\n' "$name"
    return
  fi

  umask 077
  case "$format" in
    base64) openssl rand -base64 "$byte_count" > "$path" ;;
    hex) openssl rand -hex "$byte_count" > "$path" ;;
    *) printf 'unsupported secret format for %s\n' "$name" >&2; return 2 ;;
  esac
  chmod 600 "$path"
  printf 'created: %s\n' "$name"
}

create_secret postgres_password base64 32
create_secret minio_access_key hex 20
create_secret minio_secret_key base64 40
create_secret credential_master_key base64 32
create_secret bootstrap_admin_password base64 32
