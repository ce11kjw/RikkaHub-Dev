---
name: log-analyzer
description: >-
  Analyze log text or log files: counts by level, most repeated errors, spikes over time, key field
  extraction. Use whenever the user pastes logs or points at a log file and wants them understood.
  分析日志文本或日志文件：各级别数量、重复最多的错误、异常突增、关键字段提取。
  当用户贴了日志、给了日志文件路径、或说"帮我分析这份日志"时使用。
---

# 日志分析

日志太多看不过来时，先用脚本统计，再挑重点看。

## 先跑统计

```
run_skill_script(skill="log-analyzer", path="scripts/analyze.js", args=["<日志文件路径>"])
```

脚本会输出：

- 各日志级别（ERROR / WARN / INFO / DEBUG）的数量
- 出现最多的 10 条错误（按消息去重后计数）
- 时间跨度与每分钟错误数峰值
- 疑似堆栈的第一行

如果没有文件路径，把日志内容**存到技能目录下的临时文件**再跑：

```
run_skill_script(skill="log-analyzer", path="scripts/analyze.js", args=["tmp.log"])
```

## 拿到统计之后看什么

**不要把所有错误都列出来。** 按这个顺序判断：

1. **错误集中在少数几条** → 大概率是同一个根因，看那一条的堆栈
2. **错误均匀分散** → 可能是上游不稳或资源不足，看时间分布
3. **某个时间点突然爆发** → 对齐那一刻发生了什么（发布、流量峰值、定时任务）
4. **只有 WARN 没有 ERROR** → 通常不影响功能，先确认是不是噪音

## 配合排查

分析的目的是找根因，找到线索后：

- 要追代码逻辑 → 用 `debug-first` 技能，先提假设再验证
- 要查服务器状态 → 用 `server-ops` 技能，看磁盘/内存/进程

## 脚本输出不够用时

`analyze.js` 只做通用统计。需要特定字段（比如从 JSON 日志里提取某个 key）时，
直接改脚本参数或让模型据此另写一段 JS —— 内置引擎跑得动，不用装 node。
