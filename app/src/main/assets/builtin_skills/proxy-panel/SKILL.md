---
name: proxy-panel
description: >-
  Proxy node panels: x-ui / 3x-ui / k-ui differences, subscription link generation, reality
  handshake failures, reverse proxy and CDN setup, node-down triage.
  Use for any panel deployment, subscription or connectivity problem.
  代理节点面板：x-ui / 3x-ui / k-ui 的差异、订阅链接生成、reality 握手失败、
  反向代理与 CDN、节点不通排查。遇到面板部署、订阅、连接问题时使用。
---

# 代理面板

排查思路比配置更重要：**先确定是哪一段断了**，再动配置。

## 分段定位（最重要）

节点不通时，**别一上来就改配置**，按链路段排查：

```
客户端 → 入口（CDN/中转）→ 落地机 → 目标网站
```

**逐段测**：

```
① 本机能不能连到入口 IP？      → ping / tcping 端口
② 入口能不能连到落地机？        → 在入口机器上测
③ 落地机能不能出网？            → curl 一个网站
④ TLS / 协议握手成功吗？        → 客户端日志看具体错在哪步
```

**哪一段断了，问题就在哪一段。** 不分段就瞎改，改完也不知道是哪个改动起的作用。

## 三种面板的差异

| | x-ui | 3x-ui | k-ui |
|---|---|---|---|
| 维护状态 | 原版，更新慢 | 社区活跃分支 | 另有分支 |
| reality 支持 | 需较新版本 | ✅ | ✅ |
| 订阅功能 | 基础 | 完善 | 各有特色 |
| API | 有 | 有且文档较全 | 有 |

**注意**：这三个的**配置格式不完全兼容**，迁移时别直接覆盖数据库。

## reality 握手失败

最常见的几种，按出现频率排：

**① 目标站（dest）不可用**

```
1.2.22.2502:443  ← 这个目标站可能已经关了或改了
```

reality 依赖一个真实的 TLS 站点做掩护。目标站挂了，握手必然失败。

**解决**：换一个**同地区、支持 TLS1.3、有正常证书**的目标站。

**② shortId 不匹配**

客户端和服务端的 `shortId` 必须一致。常见错误是复制配置时漏了字符。

**③ 公钥/私钥对不上**

服务端生成的是密钥对，客户端只填**公钥**。填错就会握手失败。

**④ 时间不同步**

reality 依赖时间戳。机器时间偏差超过 2 分钟就会失败：

```
timedatectl status    # 看时间同步状态
```

## 订阅链接

**基本格式**：

```
https://面板域名/sub/{token}
```

**常见问题**：

| 现象 | 原因 |
|---|---|
| 订阅拉取失败 | 面板的 sub 端口没开，或被防火墙拦 |
| 拉到了但节点不可用 | 订阅里的域名/IP 是内网地址 |
| base64 解码失败 | 编码时混入了换行或多余字符 |
| 客户端不认 | 有的客户端只认特定格式（clash / v2ray / singbox 各不同） |

**订阅地址一定要能公网访问** —— 在服务器上用 `curl` 测一次，别只在浏览器里看（可能有缓存或登录态）。

## 反向代理

**nginx 反代面板时最容易踩的**：

```nginx
location / {
    proxy_pass http://127.0.0.1:54321;
    # 这三行必须加，否则 WebSocket 连不上
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    # 面板路径可能带前缀，要透传
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
```

**Cloudflare 隧道（1101 / 1033 这类错误）**：

| 错误码 | 含义 | 处理 |
|---|---|---|
| 1101 | Worker 抛异常 | 看 Worker 日志 |
| 1033 | 隧道没连上 | 检查 cloudflared 服务状态 |
| 502 | 源站不通 | 隧道指向的本地端口没起来 |
| 521 | 源站拒绝 | 源站服务挂了或防火墙拦了 |

**隧道排查顺序**：

```
systemctl status cloudflared     # 服务在跑吗
journalctl -u cloudflared -n 50  # 有报错吗
curl 127.0.0.1:<本地端口>         # 源站活着吗
```

## 部署检查清单

上线前过一遍：

- [ ] 面板端口不暴露公网（用反代或只开内网）
- [ ] 面板登录改过默认密码
- [ ] 节点端口在防火墙放行
- [ ] 订阅地址能从外网访问
- [ ] reality 的目标站可用
- [ ] 服务器时间已同步
- [ ] 配置有备份（换机器能快速恢复）

## 不要做的事

- 不要在不知道哪段断了的情况下改配置
- 不要同时改多个地方（改完不知道是哪个起作用）
- 不要用默认端口和默认密码
- 删节点/删配置前先导出备份
