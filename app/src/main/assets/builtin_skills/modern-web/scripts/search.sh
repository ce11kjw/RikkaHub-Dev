#!/bin/sh
# 按需求搜指南。用法: search.sh "<需求描述>"
Q="$1"
if [ -z "$Q" ]; then
  echo "用法: search.sh \"<需求描述>\"" >&2
  exit 1
fi
npx --yes modern-web-guidance@latest search "$Q" 2>/dev/null || {
  echo "CLI 不可用，先跑 scripts/setup.sh" >&2
  exit 1
}
