---
name: improve-architecture
description: >-
  Survey a codebase for structural rot: shallow modules, tangled dependencies, duplication that
  should be one thing. Use every few days on a growing project, or when the code keeps getting
  harder to change.
  检查代码库的结构性腐化：浅模块、依赖缠结、本该合并的重复。适用于持续变大的项目，
  或"改起来越来越费劲"的时候。建议每隔几天跑一次。
---

# 架构改进

AI 写代码很快，代码库变乱也很快。**定期做一次结构体检**，比攒到重构便宜得多。

## 先找这五类问题

### 1. 浅模块

> 最好的模块是深的：用简单接口获得很多功能。

**症状**：一个类/函数只是转发调用，没有实质逻辑。

```kotlin
// 浅：只是转发
class UserManager(private val repo: UserRepo) {
    fun getUser(id: String) = repo.find(id)
}

// 深：封装了真正的复杂度
class UserManager(private val repo: UserRepo, private val cache: Cache) {
    fun getUser(id: String): User =
        cache.get(id) ?: repo.find(id).also { cache.put(it) }
}
```

**判断**：把这一层删掉，调用方会更简单还是更复杂？更简单 → 是浅模块，删掉。

### 2. 依赖缠结

**症状**：
- 循环依赖（A 用 B，B 又用 A）
- 一个模块 import 了半个项目
- 改一个文件，牵动五处

**检查**：
```
grep -c "^import" 每个文件 → 找出 import 特别多的
找 A→B 且 B→A 的成对引用
```

### 3. 该合并的重复

**症状**：两处代码几乎一样，只是参数不同。

**判断标准**：改动时如果**必须两边一起改**，那就是一个东西，该合并。
如果只是碰巧长得像、将来会各自演化，就不要合。

### 4. 概念没有名字

同一个东西在五个地方有五种叫法（`user`/`account`/`member`/`客户`/`profile`）。

**这通常意味着缺少共享语言** —— 用 `shared-language` 技能建一套术语。

### 5. 边界不清

模块之间职责重叠：
- 数据转换在 UI 层和业务层各做一遍
- 校验散落在三个地方
- 同一个状态存在两个地方，需要手动同步

## 怎么扫

**① 找最大的文件**

```bash
find . -name "*.kt" -exec wc -l {} + | sort -rn | head -15
```

超过 500 行的文件，通常混了多个职责。

**② 找改动最频繁的文件**

```bash
git log --name-only --pretty=format: | sort | uniq -c | sort -rn | head -15
```

改得最勤的地方，往往就是耦合最重的地方。

**③ 找长函数**

嵌套超过 3 层、超过 50 行的函数，通常在做多件事。

## 输出什么

**不要直接改代码。** 先给一份候选清单，按「收益/成本」排序：

```markdown
## 候选 1：ChatService 有 1500 行，混了 5 个职责

**问题**：会话管理、请求组装、压缩、错误处理、生命周期都在一个类里
**影响**：每次改动都要读 1500 行，容易改坏不相关的东西
**改法**：抽出 CompressionCoordinator（约 200 行），ChatService 只留调度
**成本**：中（涉及 3 个调用点）
**收益**：高（后续所有压缩相关改动都集中在一处）
```

排序原则：**收益高的不一定先做**，先做「成本低 + 收益中」的，攒几次小改进比来一次大重构安全。

## 什么时候不该做

- 代码能跑且很少改 → 别动
- 没有测试覆盖 → 先补测试再重构
- 只剩几天要交付 → 别碰结构
- 只是"看着不舒服" → 不是理由

**架构改进的目标是「让下次改动能更便宜」，不是「让代码更漂亮」。**
