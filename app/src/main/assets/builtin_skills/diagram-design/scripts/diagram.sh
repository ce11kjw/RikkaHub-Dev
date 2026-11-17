#!/bin/sh
# 生成图。用法: diagram.sh "<图的描述>"
REQ="$1"
[ -n "$REQ" ] || { echo "用法: diagram.sh \"<图的描述>\"" >&2; exit 1; }

OUT="${2:-diagram.html}"

# 优先用已装的 CLI；没装则提示
if command -v diagram-design >/dev/null 2>&1; then
  diagram-design --prompt "$REQ" --out "$OUT" 2>&1 | tail -20
else
  echo "diagram-design CLI 未安装，先跑 scripts/setup.sh" >&2
  echo "" >&2
  echo "如果只想要快速示意图，我可以直接用 Mermaid 写在对话里。" >&2
  exit 1
fi
