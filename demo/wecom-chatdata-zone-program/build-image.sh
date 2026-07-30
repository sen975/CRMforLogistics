#!/usr/bin/env sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VENDOR_DIR="$PROJECT_DIR/target/official-java-1.4.0"
IMAGE_NAME='wecom-chatdata-zone-program:0.1.0'
EXPORT_FILE="$PROJECT_DIR/target/wecom-chatdata-zone-program-linux-amd64.tar"
EXPECTED_ABILITY_ID='conversation_viewer_sync'
EXPECTED_SUMMARY_ABILITY_ID='conversation_daily_summary'

if [ ! -d "$VENDOR_DIR/SpecDemo" ] || [ ! -f "$PROJECT_DIR/target/libWeWorkSpecSDK.so" ]; then
  echo '请先运行 prepare-official-sdk.sh' >&2
  exit 2
fi

(
  cd "$VENDOR_DIR/SpecDemo"
  mvn -q -DskipTests package
)
cp "$VENDOR_DIR/SpecDemo/target/SpecDemo-1.0-SNAPSHOT-jar-with-dependencies.jar" \
  "$PROJECT_DIR/target/wecom-chatdata-zone-program.jar"
echo "固定能力: $EXPECTED_ABILITY_ID, $EXPECTED_SUMMARY_ABILITY_ID"

docker build --platform linux/amd64 -t "$IMAGE_NAME" "$PROJECT_DIR"
ARCHITECTURE=$(docker image inspect "$IMAGE_NAME" --format '{{.Architecture}}')
if [ "$ARCHITECTURE" != 'amd64' ]; then
  echo "镜像架构错误: $ARCHITECTURE" >&2
  exit 3
fi

CONTAINER_ID=$(docker create --platform linux/amd64 "$IMAGE_NAME")
cleanup() {
  docker rm "$CONTAINER_ID" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM
docker export -o "$EXPORT_FILE" "$CONTAINER_ID"
tar -tf "$EXPORT_FILE" >/dev/null
echo "企业微信可上传 rootfs 包: $EXPORT_FILE"
