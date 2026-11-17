#!/bin/sh
# 按 ID 取指南全文。用法: retrieve.sh <id>
ID="$1"
if [ -z "$ID" ]; then
  echo "用法: retrieve.sh <指南ID>" >&2
  exit 1
fi
npx --yes modern-web-guidance@latest retrieve "$ID" 2>/dev/null || {
  echo "CLI 不可用，先跑 scripts/setup.sh" >&2
  exit 1
}
