#!/bin/sh
# 安装 diagram-design CLI。幂等。
if command -v diagram-design >/dev/null 2>&1; then
  echo "已安装"
  exit 0
fi
if ! command -v node >/dev/null 2>&1; then
  echo "安装 node..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq && apt-get install -y -qq --no-install-recommends nodejs npm
fi
echo "拉取 diagram-design..."
npx --yes skills add cathrynlavery/diagram-design 2>&1 | tail -10
echo "完成"
