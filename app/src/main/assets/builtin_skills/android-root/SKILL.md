---
name: android-root
description: >-
  Solve rooting framework problems: KernelSU vs Magisk vs APatch differences, kernel module version
  mismatch, module not applying, bootloop after flashing. Use for why root itself broke.
  For hiding root from detection apps, use device-compat.
  解决 root 框架本身的问题：三者差异、内核模块版本不匹配、模块不生效、刷机后开不了机。
  只管"root 这个功能坏了"。要把 root 从检测里藏起来，用 device-compat。
---

# Android Root

三个框架的原理不同，**别把 Magisk 的经验直接套到 KernelSU 或 APatch 上**。

## 三者的本质差异

| | Magisk | KernelSU | APatch |
|---|---|---|---|
| **原理** | 修改 ramdisk，用户态挂载 | 内核模块（LKM/GKI） | 内核补丁（kpatch） |
| **需要** | 解锁 BL + 能改 boot | **和内核版本严格匹配** | 需要内核源码/补丁 |
| **特点** | 兼容性最好 | 内核层，隐藏性更好 | 无需重新编译整个内核 |
| **更新** | 更新 app + 刷 boot | 内核和 app 版本要对应 | 类似 |

**常见错误**：在 KernelSU 上装 Magisk 模块能用，但**模块的 systemless 行为不一样**，涉及挂载的模块会失效。

## KernelSU 特有的坑

**① 内核版本必须严格匹配**

KernelSU 的内核模块是**按内核版本编译**的。刷错版本会：
- 开不了机（内核 panic）
- 或进了系统但 root 不生效

**先确认**：

```
adb shell uname -r          # 内核版本
adb shell uname -a          # 完整信息
```

然后找**完全一致**的 LKM。版本号差一位都不行。

**② GKI vs 非 GKI**

- GKI 内核（很多新机型）→ 用官方 LKM
- 非 GKI → 必须自己编译或找机型专用包

**③ SUSFS 配置**

SUSFS 是内核侧的文件系统隐藏，配置在：

```
adb shell cat /sys/kernel/susfs/*   # 查看状态
```

常见需要开的：
- **hide sus paths** — 隐藏 root 相关路径
- **hide sus mounts** — 隐藏挂载点
- **uname spoofing** — 伪装内核版本（检测会比对 `uname -r`）

**spoof 内核版本时**，要填**原厂内核的真实版本**，填个不存在的版本反而更可疑。

## 刷机失败后的救援顺序

**按这个顺序试，不要跳步**：

```
1. 强制重启（长按电源 10-15 秒）
2. 进 Recovery，看能不能连 adb
3. 能连 adb → adb pull /proc/last_kmsg 或 /sys/fs/pstore/ 看崩溃原因
4. 进 Fastboot → 刷回原厂 boot.img（最有效）
5. 都不行 → 找机型专用的救砖工具（9008 / SP Flash Tool）
```

**关键**：刷机前一定要先备份 `boot.img`。没有备份就只能找原厂包。

## 模块开发

**这里讲的是 Magisk / KernelSU / APatch 模块**（开机脚本 + 文件 overlay）。
写 Xposed hook 模块是另一回事，用 `xposed-module`。

**目录结构**：

```
module/
├── module.prop          必要：id/name/version/author
├── post-fs-data.sh      启动早期，挂载相关
├── service.sh           启动后期，服务相关
├── system.prop          属性覆盖
└── system/              要 overlay 的文件
```

**调试方法**：

```
adb logcat | grep -i "magisk\|kernelsu\|apatch"
adb shell ls /data/adb/modules/       # 已安装模块
adb shell cat /data/adb/modules/<id>/disable   # 存在这个文件 = 模块被禁用
```

**模块让系统开不了机时**：进 Recovery，删掉 `/data/adb/modules/<id>/`，或创建 `disable` 空文件。

## 属性修改的边界

用 `resetprop`（Magisk）或等效方式改属性时：

- **改之前先记原值**，否则改错了回不去
- **只改需要的**，一次改一堆容易触发更多检测
- **有些属性是只读的**，改不了就是改不了，别硬来
- **改完要验证**：`adb shell getprop <key>` 确认真的生效了

## 排查思路

**root 不生效**：
```
1. 确认管理器 app 显示已安装
2. adb shell su -c id   → 有没有返回 uid=0
3. 没有 → 检查内核模块是否加载（KernelSU/APatch 特有）
4. 检查是不是被其他 root 方案占用（同时装了两个会冲突）
```

**模块装了没效果**：
```
1. ls /data/adb/modules/<id>/  → 有没有 disable/remove 文件
2. 看模块的 post-fs-data.sh 有没有执行（加 echo 到日志验证）
3. 检查模块声明的 minMagisk/minKernelSU 版本
```

## 安全底线

- 不要在没有备份的情况下改 boot / 分区表
- 不要在主力机上做实验性操作（先找台备用机）
- 刷机前确认电池 > 50%，中途断电可能变砖
- 涉及 BL 锁的操作前，确认清楚能不能再锁回去
