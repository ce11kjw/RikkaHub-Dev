#!/bin/sh
# 安装 code-graph-rag。幂等。
if command -v cgr >/dev/null 2>&1; then
  echo "cgr 已安装: $(cgr --version 2>/dev/null | head -1)"
  exit 0
fi

echo "检查 Python 版本..."
if ! command -v python3 >/dev/null 2>&1; then
  echo "安装 python3..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq && apt-get install -y -qq --no-install-recommends python3 python3-pip python3-venv ripgrep
fi

PYVER=$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")' 2>/dev/null)
echo "Python $PYVER"

case "$PYVER" in
  3.12|3.13|3.14) ;;
  *)
    echo "需要 Python 3.12+，当前 $PYVER" >&2
    echo "Ubuntu 24.04 自带 3.12，如果你的系统更老需要先升级" >&2
    exit 1
    ;;
esac

echo "安装 code-graph-rag..."
pip3 install --break-system-packages -q "code-graph-rag[treesitter-full,ast-grep,semantic]" 2>&1 | tail -3

command -v cgr >/dev/null 2>&1 && echo "安装完成" || { echo "安装失败" >&2; exit 1; }
