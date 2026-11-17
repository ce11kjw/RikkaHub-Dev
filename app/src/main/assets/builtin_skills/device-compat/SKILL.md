---
name: device-compat
description: >-
  Make a rooted phone usable with apps that over-flag it: hide root, fix system properties,
  Play Integrity tiers, SUSFS configuration. Use when a normal app refuses to run because it
  detected root. For root itself not working, use android-root.
  让 root 手机能正常用那些查太严的 App：隐藏 root、修正系统属性、Play Integrity 各档位、
  SUSFS 配置。当正常 App 因为检测到 root 而拒绝运行时用这个。
  root 功能本身坏了（模块不生效、开不了机），用 android-root。
---

# 设备兼容性

**目标是把手机还原成「正常设备」，不是绕过风控做坏事。**

判断标准：这个检测是不是在防**实际风险**？
- 检测 root 是为了防支付劫持 → 合理
- 检测 root 就不让登录 → 过度

## 应用检测什么

按检测难度和常见程度排：

| 检测项 | 怎么查 | 常见程度 |
|---|---|---|
| **su 二进制** | 找 `/system/bin/su`、`/system/xbin/su`、PATH 里搜 | 极高 |
| **root 管理 App** | 包名匹配（Magisk Manager、KernelSU 等） | 极高 |
| **属性** | `ro.debuggable`、`ro.secure`、`ro.build.tags` | 高 |
| **挂载状态** | `/proc/mounts` 里有没有异常挂载点 | 中 |
| **内核信息** | `uname -r` 是否含自定义标识 | 中 |
| **SELinux** | `getenforce` 是否是 Permissive | 中 |
| **Bootloader** | `ro.boot.verifiedbootstate` | 中 |
| **完整性** | Play Integrity / SafetyNet | 高 |
| **App 列表** | 是否装了特定应用（Momo 这类检测器） | 中 |
| **文件路径** | `/data/adb/`、模块目录是否存在 | 高 |
| **进程** | 有没有 `magiskd`、`ksud` 之类 | 中 |

## 分层处理

**不要想着一次全改。** 从上往下，改一层测一次。

### 第一层：基础隐藏

KernelSU 管理器里直接开：
- **隐藏 root**（对目标 App 隐藏）
- **挂载命名空间隔离**

Magisk 里用 **DenyList**（旧称 MagiskHide）。

**这一步能解决大部分 App。** 解决不了再往下。

### 第二层：SUSFS

KernelSU + SUSFS 内核时，开这些：

```
hide sus paths        # 隐藏 root 相关路径
hide sus mounts       # 隐藏挂载点
uname spoofing        # 伪装内核版本
```

**spoof 时填原厂内核版本** —— 填个不存在或明显自定义的版本反而更可疑。

查原厂版本：同机型没 root 的机器上 `adb shell uname -r`，或官方 ROM 包里找。

### 第三层：属性修正

需要改的属性（**改之前记原值**）：

```
ro.debuggable          → 0
ro.secure              → 1
ro.build.tags          → release-keys
ro.boot.verifiedbootstate → green
```

用 `resetprop`（Magisk）或模块的 `system.prop`。

**注意**：有些属性是只读的，改不动。改不动就别硬来，换别的路径。

### 第四层：完整性

Play Integrity 分三档：

| 档位 | 含义 | 能过吗 |
|---|---|---|
| BASIC | 设备基本可信 | 一般能过 |
| DEVICE | 设备未被篡改 | 需要较完整的隐藏 |
| STRONG | 硬件级证明 | **基本过不了**（需要 TEE 未被破坏） |

**如果 App 要求 STRONG**，那基本没有可靠办法 —— 这时候该考虑：
- 换一个不那么严的同类 App
- 用备用机（不 root）处理这个场景
- 反馈给开发者

## 排查方法

**① 先确认到底卡在哪**

装一个检测类 App（Momo 之类）看它报什么。**它报的项就是目标 App 大概率也查的**。

**② 一次只改一项**

改完重启，再测。同时改五项，好了不知道哪个起作用，坏了也不知道哪个搞坏的。

**③ 用日志验证**

```
adb logcat | grep -i "root\|magisk\|kernelsu\|detect"
```

很多 App 会把检测结果打日志（尤其是 debug 版）。

## 边界

**可以做的**：
- 让 root 手机能正常用银行/购物/打车类 App
- 隐藏 root 避免误判
- 修正被改过的系统属性

**不该做的**：
- 绕过付费/会员限制
- 伪造设备做批量注册/薅羊毛
- 绕过真正在防风险的检测（比如支付环境校验）

如果某个 App 明确检测到你 root 是为了**保护你的资金安全**，那隐藏它反而对你不利。

## 务实建议

**最省事的方案往往不是技术方案**：

1. **备用机** —— 一台不 root 的旧手机专门跑这些 App，比折腾隐藏省心
2. **多用户/工作资料** —— Android 原生支持，隔离得比任何隐藏都干净
3. **换 App** —— 同类产品里总有对 root 宽容的

技术手段能做，但要算成本：折腾两小时让一个 App 能用，不如买台二手备用机。

## 最后提醒

改系统属性、装隐藏模块都可能**让系统不稳定**，尤其是改了 `ro.secure` 这类启动相关的。

- 改之前备份 boot
- 一次只改一项
- 出问题能快速回滚（记下原值）
- 主力机谨慎操作
