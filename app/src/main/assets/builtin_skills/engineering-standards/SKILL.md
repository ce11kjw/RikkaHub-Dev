---
name: engineering-standards
description: >-
  Engineering conventions: interface design, error handling, naming, logging. Use for team collaboration, shared modules, or when asked to follow standards.
  写代码的工程规范：接口怎么设计、错误怎么处理、命名怎么统一、日志怎么打。适用于团队协作、写公共模块、用户说"按规范来"时。
---

# 工程规范

规范不是限制，是**让下一个人少猜**。

## 接口设计

**命名说清动词和对象**
```
getUser(id)         ✅
user(id)            ❌ 是获取还是删除？
handleUser(id)      ❌ handle 什么都没说
```

**返回值统一**
- 成功返回数据，失败抛异常 **或** 返回 Result —— 二选一，全项目一致
- 不要有的地方返回 null 有的抛异常

**参数超过 3 个就考虑传对象**
```kotlin
// ❌ 调用方容易传错顺序
fun create(name, age, email, phone, address)

// ✅
fun create(request: CreateUserRequest)
```

**避免布尔参数**
```kotlin
// ❌ 调用处看不出 true 是什么意思
save(user, true)
// ✅
save(user, overwrite = true)
```

## 错误处理

**分层**
- 底层：抛具体异常，带上下文
- 中层：按需转成业务异常
- 顶层：记日志 + 返回用户能看懂的话

**不要吞异常**
```kotlin
// ❌ 出了问题完全查不到
try { doSomething() } catch (e: Exception) { }

// ✅ 至少留下痕迹
try { doSomething() } catch (e: Exception) {
    log.warn("doSomething failed, continuing", e)
}
```

**错误信息要能指导下一步**
```
❌ "操作失败"
✅ "保存失败：磁盘剩余 12MB，不足 100MB。清理空间后重试"
```

## 命名

| 类型 | 规则 | 例 |
|---|---|---|
| 类/组件 | 名词 | `UserRepository` |
| 函数 | 动词开头 | `fetchProfile()` |
| 布尔 | is/has/can 开头 | `isActive`, `canEdit` |
| 常量 | 全大写 | `MAX_RETRY` |
| 私有 | 前缀下划线或 private | `_cache` |

**避免的命名**：
- `data`、`info`、`manager`、`helper`、`util` —— 太泛，说明不了用途
- 缩写（`usr`、`btn`、`msg`）—— 除非是公认的

## 日志

**该打的**
- 关键流程的进入/退出（debug 级）
- 外部调用失败（warn 级）
- 影响用户的问题（error 级）

**不该打的**
- 循环里每条都打（日志爆炸）
- 敏感信息（密码、token、身份证）
- 只在开发时有用、上线没意义的

**格式**：`操作 + 对象 + 结果`
```
✅ "fetch user 123 failed: timeout after 5s"
❌ "error"
```

## 代码组织

- 一个函数做一件事，超过 50 行就该拆
- 嵌套不超过 3 层（用提前 return 减少嵌套）
- 常量不写魔法数字（`if (status == 3)` → `if (status == STATUS_DONE)`）
- 注释解释**为什么**，不解释**是什么**（代码本身能说明是什么）

## 提交前自检

- [ ] 有没有吞异常
- [ ] 有没有写死的值该提成常量
- [ ] 边界处理了吗（空、0、超长）
- [ ] 命名能否让新人看懂
- [ ] 有没有留下调试代码
