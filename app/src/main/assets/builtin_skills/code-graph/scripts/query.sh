#!/bin/sh
# 查询图谱。用法: query.sh "<问题>"
Q="$1"
[ -n "$Q" ] || { echo "用法: query.sh \"<问题>\"" >&2; exit 1; }
command -v cgr >/dev/null 2>&1 || { echo "先跑 scripts/setup.sh" >&2; exit 1; }
# 用 -p（非交互）模式跑单次查询
echo "$Q" | cgr start 2>&1 | tail -40
