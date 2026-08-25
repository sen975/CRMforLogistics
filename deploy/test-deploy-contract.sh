#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
workflow="$repo_root/.github/workflows/deploy-message-center.yml"
service="$repo_root/deploy/message-center.service"
script="$repo_root/deploy/deploy.sh"
env_example="$repo_root/deploy/message-center.env.example"
pom="$repo_root/demo/message-center-spring/backend/pom.xml"
dev_config="$repo_root/demo/message-center-spring/backend/src/main/resources/application-dev.yml"

test -f "$workflow"
test -f "$service"
test -f "$script"
test -f "$env_example"

grep -Fq "branches: [main]" "$workflow"
grep -Fq "demo/message-center-spring/**" "$workflow"
grep -Fq "PROD_KNOWN_HOSTS" "$workflow"
grep -Fq "PROD_SSH_KEY" "$workflow"
grep -Fq "mvn -B test" "$workflow"
grep -Fq "mvn -B -Pproduction -DskipTests clean package" "$workflow"
grep -Fq "生产 Jar 不得包含 application-dev.yml" "$workflow"
grep -Fq 'jar_entries="$(unzip -Z1' "$workflow"
grep -Fq "npm test" "$workflow"
grep -Fq "frontend.tar.gz" "$workflow"

grep -Fq "EnvironmentFile=/etc/message-center/message-center.env" "$service"
grep -Fq "/opt/crm-logistics-message-center/current/backend/message-center.jar" "$service"
grep -Fq "User=message-center" "$service"

grep -Fq 'systemctl restart "$SERVICE_NAME"' "$script"
grep -Fq "ROLLBACK" "$script"
grep -Fq "/api/contacts" "$script"
grep -Fq "MAX_RELEASES=5" "$script"
grep -Fq "restore_previous_unit" "$script"
grep -Fq "cleanup_failed_release" "$script"
grep -Fq 'if [[ "$rollback_ok" == true ]]' "$script"
grep -Fq "require_java_17" "$script"
grep -Fq "docker compose version" "$script"

grep -Fq "<id>production</id>" "$pom"
grep -Fq "<exclude>application-dev.yml</exclude>" "$pom"

grep -Fq 'password: ${SPRING_DATASOURCE_PASSWORD:}' "$dev_config"
grep -Fq 'aliyun-access-key-id: ${ALIYUN_ACCESS_KEY_ID:}' "$dev_config"
grep -Fq 'smtp-password: ${SMTP_PASSWORD:}' "$dev_config"
grep -Fq 'wecom-suite-secret: ${WECOM_SUITE_SECRET:}' "$dev_config"

grep -Fq "SPRING_PROFILES_ACTIVE=chatapp-only" "$env_example"
grep -Fq "SERVER_ADDRESS=127.0.0.1" "$env_example"
grep -Fq "SERVER_PORT=8107" "$env_example"
grep -Fq "APP_WECOM_ENABLED=false" "$env_example"

bash -n "$script"
