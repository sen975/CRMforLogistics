#!/usr/bin/env sh
set -eu

EXPECTED_SHA256='9d793d028c217f63ff55af753e5715b53d3e3321955169e4a3d798b35e33c6fb'
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ARCHIVE=${1:-}

if [ -z "$ARCHIVE" ] || [ ! -f "$ARCHIVE" ]; then
  echo '用法: ./prepare-official-sdk.sh /path/to/java_demo_src_1.4.0.tar.gz' >&2
  exit 2
fi

ACTUAL_SHA256=$(shasum -a 256 "$ARCHIVE" | awk '{print $1}')
if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
  echo '官方 Java 1.4.0 示例源码校验失败' >&2
  exit 3
fi

VENDOR_DIR="$PROJECT_DIR/target/official-java-1.4.0"
rm -rf "$VENDOR_DIR"
mkdir -p "$VENDOR_DIR"
tar -xzf "$ARCHIVE" -C "$VENDOR_DIR"
cp "$PROJECT_DIR/overlay/src/main/java/mytype/mycom/mygroup/DemoCallProgramHandler.java" \
  "$VENDOR_DIR/SpecDemo/src/main/java/mytype/mycom/mygroup/DemoCallProgramHandler.java"
mkdir -p "$VENDOR_DIR/SpecDemo/src/main/java/com/crmforlogistics/wecomchatdata"
cp "$PROJECT_DIR/src/main/java/com/crmforlogistics/wecomchatdata/SyncMessageAbility.java" \
  "$VENDOR_DIR/SpecDemo/src/main/java/com/crmforlogistics/wecomchatdata/SyncMessageAbility.java"
cp "$PROJECT_DIR/src/main/java/com/crmforlogistics/wecomchatdata/AbilityIdMatcher.java" \
  "$VENDOR_DIR/SpecDemo/src/main/java/com/crmforlogistics/wecomchatdata/AbilityIdMatcher.java"
cp "$PROJECT_DIR/src/main/java/com/crmforlogistics/wecomchatdata/SummaryAbility.java" \
  "$VENDOR_DIR/SpecDemo/src/main/java/com/crmforlogistics/wecomchatdata/SummaryAbility.java"
cp "$PROJECT_DIR/src/main/java/com/crmforlogistics/wecomchatdata/AbilityDispatcher.java" \
  "$VENDOR_DIR/SpecDemo/src/main/java/com/crmforlogistics/wecomchatdata/AbilityDispatcher.java"
tr -d '\r' < "$VENDOR_DIR/SpecDemo/src/main/java/mytype/mycom/mygroup/SvrConfig.java" \
  > "$VENDOR_DIR/SpecDemo/src/main/java/mytype/mycom/mygroup/SvrConfig.java.lf"
mv "$VENDOR_DIR/SpecDemo/src/main/java/mytype/mycom/mygroup/SvrConfig.java.lf" \
  "$VENDOR_DIR/SpecDemo/src/main/java/mytype/mycom/mygroup/SvrConfig.java"
(
  cd "$VENDOR_DIR/SpecDemo"
  patch -p1 < "$PROJECT_DIR/patches/pom-java17.patch"
  patch -p1 < "$PROJECT_DIR/patches/request-processor-minimal.patch"
  patch -p1 < "$PROJECT_DIR/patches/runtime-resource-limits.patch"
)
cp "$VENDOR_DIR/libWeWorkSpecSDK.so" "$PROJECT_DIR/target/libWeWorkSpecSDK.so"
echo "官方专区 Java 1.4.0 源码已准备到 $VENDOR_DIR"
