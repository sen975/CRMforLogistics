#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "当前脚本仅支持 macOS。" >&2
  exit 2
fi

if open -b com.tencent.WeWorkMac 2>/dev/null; then
  exit 0
fi

if open -a "企业微信" 2>/dev/null; then
  exit 0
fi

if open -a "WeCom" 2>/dev/null; then
  exit 0
fi

echo "未找到企业微信客户端，请先安装企业微信 macOS 版。" >&2
exit 1
