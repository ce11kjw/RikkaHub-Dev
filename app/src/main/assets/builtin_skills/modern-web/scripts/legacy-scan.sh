#!/bin/sh
# 扫描项目里的遗留写法。用法: legacy-scan.sh [目录]
DIR="${1:-.}"
npx --yes modern-web-guidance@latest legacy-scan "$DIR" 2>/dev/null || {
  echo "CLI 不可用，先跑 scripts/setup.sh" >&2
  exit 1
}
