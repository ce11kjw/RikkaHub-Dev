#!/bin/sh
# 索引一个项目。用法: index.sh <项目路径>
DIR="${1:-.}"
[ -d "$DIR" ] || { echo "目录不存在: $DIR" >&2; exit 1; }
command -v cgr >/dev/null 2>&1 || { echo "先跑 scripts/setup.sh" >&2; exit 1; }

echo "索引 $DIR ...（大项目会跑很久）"
cgr start --repo-path "$DIR" --update-graph 2>&1 | tail -20
