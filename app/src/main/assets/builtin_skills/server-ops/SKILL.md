---
name: server-ops
description: >-
  Inspect and operate a Linux server: disk, memory, processes, ports, service state, log tails. Use when asked why a disk is full, why a service is down, or to restart something.
  查看和操作 Linux 服务器当前状态：磁盘、内存、进程、端口、服务启停、日志尾部。当用户说「磁盘满了」「服务开着没」「帮我重启」时使用。要追根因用 debug-first。
---

# 服务器运维排查

按顺序排查，每一步都先把结论说出来再动下一步。不要一上来就重启服务 —— 那会掩盖真正的原因。

## 先确认连得上、是什么机器

```
ssh_exec(host, port, username, password, command="uname -a && cat /etc/os-release | head -3")
```

把内核、发行版、主机名记下来。后面所有命令都基于这个环境（比如 Debian 用 `apt`，CentOS 用 `yum`）。

## 磁盘满了

不要只看 `df -h` 就动手删东西，先找出是谁占的：

```
df -h                          # 哪个分区满了
du -xh --max-depth=1 / 2>/dev/null | sort -rh | head -20
```

进入占比最大的目录继续 `du --max-depth=1`，逐层下钻。几个常见的坑：

- **日志**：`journalctl --disk-usage`，用 `journalctl --vacuum-size=100M` 收缩
- **被删除但没释放的文件**：`lsof +L1 | head`（找到已删但进程还开着的文件，只能重启对应进程）
- **包缓存**：`apt-get clean` / `yum clean all`
- **Docker**：`docker system df`，`docker builder prune -af` 通常能回收几个 G

**删之前必须确认那是什么。** 遇到不认识的目录先 `ls -la` 看一眼，别直接 `rm -rf`。

## 服务起不来

```
systemctl status <服务名> --no-pager -l
journalctl -u <服务名> -n 100 --no-pager
```

看的是最后几行错误，不是开头。常见原因：端口被占、配置文件语法错、权限不对、依赖服务没起。

端口占用：
```
ss -tlnp | grep <端口>
```

## 机器卡

```
uptime                    # load average，和核数对比（nproc）
free -m                   # 内存，重点看 available 而不是 free
top -bn1 | head -20       # 谁在吃 CPU
```

`load average` 三个数分别是 1/5/15 分钟。如果持续高于核数，说明有任务在排队。

内存不够时看是不是有进程一直在涨：
```
ps aux --sort=-%mem | head -10
```

## 网络不通

```
curl -sS -m 5 -o /dev/null -w "%{http_code} %{time_total}s\n" <url>
ss -tlnp                     # 本机在听哪些端口
ip route                     # 默认路由对不对
```

判断链路问题要**分段测**：本机回环 → 本机到网关 → 本机到目标。别一步跳到「是不是被墙了」。

## 报结果的方式

- 先给**结论**（磁盘是 /var/log 占了 3G、服务是因为端口冲突起不来），再给证据
- 命令输出只贴关键几行，别把几百行日志全贴出来
- 要执行会改动机器的操作（删文件、重启服务）之前，先说清楚要做什么、影响是什么，等用户确认

## 安全底线

- 不要执行 `rm -rf /`、`chmod -R 777 /`、`dd` 这类不可逆命令
- 不要在没有备份的情况下删数据目录
- 不要改 `sshd_config` 之类的远程访问配置而不确认（可能把自己锁在外面）
- 用户说「不用管了」的机器就别再动了
