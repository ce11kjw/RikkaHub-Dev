---
name: monitor-dashboard
description: >-
  Build low-power monitoring dashboards that push live data to a browser: real-time transport
  selection, battery-aware refresh strategy, frontend charting, multi-device aggregation.
  Use when building a device monitor, server status page, or any dashboard with live numbers.
  做实时监控面板：实时推送选型、省电刷新策略、前端图表、多设备数据聚合。
  当要做设备监控、服务器状态页、或任何带实时数字的面板时使用。
---

# 监控面板

监控面板最大的坑不是画图，是**采集端把手机电耗光了**，或者**数据一多前端就卡**。

## 先定实时方案

| 方案 | 耗电 | 延迟 | 什么时候用 |
|---|---|---|---|
| **WebSocket** | 中 | 低 | 需要双向、高频（>1/s） |
| **SSE** | 低 | 低 | 只服务端推、单向（推荐） |
| **长轮询** | 中 | 中 | 老环境不支持上面两个 |
| **短轮询** | 高 | 高 | 只有这个能用时 |

**默认选 SSE**：单向推送够用、断线自动重连、比 WebSocket 省电。

短轮询（每 2 秒请求一次）在小面板上看不出来，但**在手机上就是电老虎**——每次请求都要唤醒网络模块。

## 采集端的省电策略（关键）

**① 按屏幕状态调频**

```
屏幕亮 → 1 秒 / 次（用户在看，要实时）
屏幕灭 → 30 秒 / 次（没人看，降频）
后台超 5 分钟 → 停止采集，只保留连接
```

**② 变化阈值**

温度、电量这类慢变量，**变化小于阈值就不发**：

```
温度变化 < 0.5°C  → 不发
电量变化 < 1%     → 不发
CPU 占用变化 < 5% → 不发
```

少了这些过滤，数据量能降 80%。

**③ 批量发送**

```
不要：每秒发一个字段
应该：攒 5 秒，一次发一批
```

**④ 用系统 API 而不是轮询文件**

读 `/proc/stat` 算 CPU 占用会触发磁盘 IO。Android 上有更好的：

- 电量/温度 → `BatteryManager`
- CPU → `System.getCpuUsage()` 或 `/proc/stat` 但降低采样频率
- 内存 → `ActivityManager.MemoryInfo`

## 前端渲染

**① 图表别用重型库**

| 数据量 | 用什么 |
|---|---|
| < 100 点 | 任何库都行（Chart.js、ECharts） |
| 100-1000 点 | 轻量的（uPlot、Chart.js） |
| > 1000 点 | 先降采样再画，或换 canvas 直接画 |

**② 环形缓冲**

只保留最近 N 个点，不要无限追加数组：

```js
const MAX = 300;
if (points.length > MAX) points.shift();
```

**③ 别每帧都重绘**

数据更新后合并到下一帧再画：

```js
let pending = false;
function onData(d) {
  buffer.push(d);
  if (pending) return;
  pending = true;
  requestAnimationFrame(() => {
    render(buffer);
    pending = false;
  });
}
```

**④ 数字用等宽字体**

否则数字变化时宽度跳动，看着很闹心。

## 多设备聚合

**数据结构**：

```json
{
  "device_id": "k60u",
  "ts": 1730000000,
  "cpu": 23.5,
  "mem": {"used": 4.2, "total": 8},
  "temp": 38,
  "battery": {"level": 76, "charging": true}
}
```

**统一时间戳**：用 UTC 毫秒，前端自己转本地时区。别传格式化好的字符串。

**离线判定**：服务端记录每个设备最后上报时间，超过 N 倍采集周期就标记离线。

## 常见问题

| 现象 | 原因 |
|---|---|
| 数据延迟大 | 采集端降频了，或用了短轮询 |
| 手机发烫 | 采集频率太高，或读 /proc 太频繁 |
| 面板打开慢 | 首屏一次性拉了全部历史数据 |
| 图表卡顿 | 点数太多没降采样 |
| 断线后不恢复 | 没做重连，或重连没退避 |

## 部署提醒

- **反向代理要开 SSE 支持**：nginx 默认会缓冲响应，要加 `proxy_buffering off;`
- **心跳**：SSE 每 30 秒发一个注释行 `: ping` 保活，否则代理会掐断
- **鉴权**：面板别裸奔，至少加个 token
