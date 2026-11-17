// 日志统计。用内置 QuickJS 执行，不依赖 node，脚本可以直接读同目录/指定路径的文件。
//
// 用法: analyze.js <日志路径>
// 输出: JSON（各级别计数、高频错误、时间分布）

const path = __args[0];
if (!path) {
  print(JSON.stringify({ error: '需要传入日志文件路径作为第一个参数' }));
} else if (!fs.exists(path)) {
  print(JSON.stringify({ error: '找不到文件: ' + path }));
} else {
  const text = fs.readText(path);
  const lines = text.split('\n');

  const levels = {};
  const errorMessages = {};
  const timestamps = [];
  let stackHead = null;

  // 常见的时间格式：2026-11-15 08:12:33 / 2026-11-15T08:12:33 / 08:12:33
  const timeRe = /(\d{4}-\d{2}-\d{2}[ T])?(\d{2}:\d{2}):\d{2}/;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!line) continue;

    // 级别：兼容 E/ W/ I/ D/ 和 ERROR WARN INFO DEBUG 两种写法
    const lv = line.match(/\b(ERROR|WARN|WARNING|INFO|DEBUG|FATAL|E|W|I|D)\b/);
    if (lv) {
      const name = lv[1] === 'WARNING' ? 'WARN' :
                   lv[1] === 'E' ? 'ERROR' : lv[1] === 'W' ? 'WARN' :
                   lv[1] === 'I' ? 'INFO' : lv[1] === 'D' ? 'DEBUG' : lv[1];
      levels[name] = (levels[name] || 0) + 1;

      if (name === 'ERROR' || name === 'FATAL') {
        // 去掉时间戳和级别前缀，剩下的才是消息本体，便于归并
        let msg = line.replace(timeRe, '').replace(/\b(ERROR|FATAL)\b/, '').trim();
        // 数字和 UUID 换成占位符，否则每条都"不一样"，统计不出重复
        msg = msg.replace(/\d+/g, 'N').replace(/[0-9a-f]{8}-[0-9a-f-]{27,}/gi, 'UUID');
        msg = msg.slice(0, 160);
        if (msg) errorMessages[msg] = (errorMessages[msg] || 0) + 1;
      }
    }

    const t = line.match(timeRe);
    if (t) timestamps.push((t[1] || '') + t[2]);

    if (!stackHead && /^\s+at\s|Exception|Error:/.test(line)) stackHead = line.trim().slice(0, 200);
  }

  // 高频错误排序
  const topErrors = Object.keys(errorMessages)
    .map(k => ({ message: k, count: errorMessages[k] }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 10);

  // 每分钟出现次数，找峰值
  const perMinute = {};
  for (const t of timestamps) perMinute[t] = (perMinute[t] || 0) + 1;
  const minuteKeys = Object.keys(perMinute).sort();
  let peak = null;
  for (const k of minuteKeys) {
    if (!peak || perMinute[k] > peak.count) peak = { minute: k, count: perMinute[k] };
  }

  print(JSON.stringify({
    file: path,
    total_lines: lines.length,
    levels: levels,
    time_range: minuteKeys.length ? { from: minuteKeys[0], to: minuteKeys[minuteKeys.length - 1] } : null,
    peak_minute: peak,
    error_unique: Object.keys(errorMessages).length,
    top_errors: topErrors,
    first_stack_line: stackHead,
    hint: topErrors.length ? '错误集中在前几条时，先看那一条的堆栈，多半是同一个根因' : '没有找到 ERROR 级别的行，确认日志格式是否被识别',
  }, null, 2));
}
