#!/bin/sh
# 在 workspace 里准备 modern-web-guidance CLI。
# 幂等：装过就不再装。

if command -v node >/dev/null 2>&1 && command -v npx >/dev/null 2>&1; then
  echo "node/npx 已就绪: $(node -v)"
else
  echo "安装 nodejs..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq && apt-get install -y -qq --no-install-recommends nodejs npm || {
    echo "安装失败，检查网络或 apt 源" >&2
    exit 1
  }
fi

# 预热一次，把包缓存下来
npx --yes modern-web-guidance@latest --version >/dev/null 2>&1 && echo "CLI 就绪"
