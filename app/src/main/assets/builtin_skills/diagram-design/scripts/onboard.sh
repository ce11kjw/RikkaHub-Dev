#!/bin/sh
# 导入品牌配色字体。用法: onboard.sh <网站URL>
URL="$1"
[ -n "$URL" ] || { echo "用法: onboard.sh <网站URL>" >&2; exit 1; }
command -v diagram-design >/dev/null 2>&1 || { echo "先跑 scripts/setup.sh" >&2; exit 1; }
diagram-design onboard "$URL" 2>&1 | tail -10
