---
name: code-graph
description: >-
  Query a codebase as a knowledge graph instead of reading files: find callers, trace dependencies,
  locate dead code, map impact before editing. Built on tree-sitter parsing into a local SQLite
  graph. Use on any project bigger than a few files when you need to know how things connect.
  把代码库当知识图谱来查，而不是一个个读文件：找调用者、追依赖、定位死代码、改前评估影响。
  基于 tree-sitter 解析成本地 SQLite 图谱。当项目不止几个文件、需要搞清连接关系时使用。
---

# 代码图谱查询

`grep` 只能找字符串，找不到「谁调用了这个函数」——那需要理解语法结构。

## 什么时候用

| 需求 | grep 能不能做 | 用这个 |
|---|---|---|
| 这个函数被谁调用 | ❌ 只能找字符串 | ✅ 沿调用边查 |
| 删掉这个类影响什么 | ❌ | ✅ 依赖分析 |
| 哪些代码没人用 | ❌ | ✅ 从入口走可达性 |
| 改这个接口要动哪里 | ❌ | ✅ 反向引用 |
| 某个名字出现在哪 | ✅ | 用 grep 就够 |

**判断标准**：要「关系」用这个，要「文本」用 grep。

## 先建图谱

第一次用要索引，一次性成本：

```
run_skill_script(skill="code-graph", path="scripts/index.sh", args=["<项目路径>"])
```

它会：
1. 在 workspace 里装 `code-graph-rag`（Python 3.12+，我们环境符合）
2. 解析项目所有源文件
3. 建图存到 `~/.cgr/graph.sqlite`

**耗时参考**：1 万行代码约 1-2 分钟，10 万行约 10-20 分钟。

## 查询

```
run_skill_script(skill="code-graph", path="scripts/query.sh", args=["这个函数被谁调用：ChatService.compressMidTurn"])
```

用自然语言问，它内部会转成图查询。

**好问题的特征**：
- 指明具体名字（类名.方法名）
- 问关系而不是问文本
- 一个问题的范围清楚

| 好的问法 | 不好的问法 |
|---|---|
| `XxxService.process 被谁调用` | `这个项目怎么工作的` |
| `删掉 Foo 类会影响哪些文件` | `代码有问题吗` |
| `哪些函数从没被调用过` | `帮我优化一下` |

## 典型用法

**① 改代码前评估影响**

```
run_skill_script(..., args=["如果改 Assistant 的 localTools 字段类型，哪些地方要跟着改"])
```

比手工 grep 靠谱得多 —— 它会沿类型引用和调用链走。

**② 找死代码**

```
run_skill_script(..., args=["列出所有定义了但从未被调用的公开函数"])
```

**③ 理解陌生项目**

```
run_skill_script(..., args=["从 main 开始，到数据库写入经过哪些模块"])
```

配合 `understand-anything` 技能：先看结构，再用图谱查具体连接。

## 限制

- **只支持解析成功的语言**：Kotlin/Swift 只有结构性支持（模块、函数、类），不如 Python/TS 精确
- **动态调用查不到**：反射、字符串形式的调用、依赖注入的绑定
- **图谱要更新**：代码改了要重新索引，否则查的是旧结构

## 和其他技能配合

| 场景 | 顺序 |
|---|---|
| 接手新项目 | `understand-anything` 摸骨架 → `code-graph` 查具体连接 |
| 大改动前 | `code-graph` 评估影响 → `superpowers` 写计划 → `tdd` |
| 找腐化 | `improve-architecture` 找候选 → `code-graph` 确认影响面 |
