#!/bin/sh
# 快速扫描常见危险写法。只做初筛，命中项需要人工判断。
# 用法: scan.sh <目录或文件>

target="${1:-.}"

if [ ! -e "$target" ]; then
  echo "no such path: $target" >&2
  exit 1
fi

search() {
  pattern="$1"
  label="$2"
  result=$(grep -rn --include='*.kt' --include='*.java' --include='*.py' \
      --include='*.js' --include='*.ts' --include='*.go' --include='*.sh' \
      -E "$pattern" "$target" 2>/dev/null | head -5)
  if [ -n "$result" ]; then
    echo ""
    echo "## $label"
    echo "$result"
  fi
}

echo "== 危险写法扫描: $target =="

search 'catch *\([^)]*\) *\{ *\}' '空 catch 块（吞掉异常）'
search 'catch *\([^)]*\) *\{\s*$' 'catch 块（确认是否只打印不处理）'
search '(api[_-]?key|secret|password|token)\s*=\s*"[^"]{8,}"' '可能的硬编码凭据'
search 'Runtime\.getRuntime\(\)\.exec|ProcessBuilder|os\.system|subprocess\.' '外部命令执行（检查参数是否可控）'
search 'eval\(|exec\(' '动态执行（检查输入来源）'
search 'SELECT .*\+|WHERE .*\+|\+ *" *\+ *"' '可能的 SQL 拼接'
search 'evalJavascript|innerHTML *=' '可能的注入点'
search '\.execute\(\)' '同步 HTTP 调用（确认不在 UI 线程）'
search 'Thread\.sleep' 'sleep（确认不在主线程）'
search '(^|[^A-Za-z])(TODO|FIXME):' '遗留标记（后跟冒号才算）'

echo ""
echo "== 扫描完成（命中项需人工确认）=="
