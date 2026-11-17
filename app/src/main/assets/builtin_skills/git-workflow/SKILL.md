---
name: git-workflow
description: >-
  Git operations: commit granularity, branching, commit messages, conflicts, reverting. Use when asked to commit, branch, merge, roll back, or triage repo state.
  Git 操作：提交粒度、分支策略、写提交说明、处理冲突、回退。当用户要求提交、开分支、合并、回滚时使用。
---

# Git 工作流

## 提交前必做：先看清有什么

```bash
git status
git diff            # 未暂存的改动
git diff --staged   # 已暂存的改动
```

**必须分清"我改的"和"本来就有的"。** 上个对话遗留的未提交改动被顺手一起提交，是最常见的翻车方式。

## 一次提交只做一件事

一个提交 = 一个可以独立描述、独立回退的改动。

拆分的判断标准：**如果这个提交需要回退，会不会带走不该走的东西？** 会，就拆。

- 修 bug + 顺手重构 → 两个提交
- 加功能 + 改格式化 → 两个提交
- 改了三个不相关的模块 → 三个提交

## 提交说明

```
fix(mount): 修复升星后词条等级不更新的问题

根因是 buildMountAttrs 只算了等级和星级，没有同步 skills[].level。
改为在 normalizeMountRow 里按星级阈值重算，这样历史错误数据读档时也会被修正。

影响：所有坐骑数据在读档时会被重新计算一次。
```

格式：`类型(范围): 一句话` + 空行 + 为什么/根因/影响。

**先想清楚"为什么"，再写"改了什么"。** 看代码就知道改了什么，只有你知道为什么。

## 分支

- 小改动直接在主分支做（个人项目）
- 实验性的、可能推翻的、要给别人看的 → 开分支
- 开分支前先 `git fetch`，基于最新的 remote 分支开

## 冲突

```bash
git status              # 看哪些文件冲突
git diff                # 看 <<<<<<< 标记
```

处理原则：

1. **先搞懂两边都在干什么**，不要盲目选一边
2. 冲突解决后**必须能跑**，不能只让标记消失
3. 解决完 `git add` + `git commit`（不要 `-m`，保留 merge 信息）

## 回退：分清三种情况

| 情况 | 命令 | 说明 |
|---|---|---|
| 还没提交，想丢弃 | `git restore <file>` | 危险，改动直接没了 |
| 提交了但没推 | `git reset --soft HEAD~1` | 保留改动，只撤销提交 |
| 提交了也推了 | `git revert <sha>` | 生成反向提交，历史保留 |

**永远不要 `push --force` 到共享分支**，除非确认没有别人基于它工作。

## 几个安全习惯

- 危险操作前先 `git stash` 或建个临时分支
- `git reset --hard` 之前，确认没有未提交的工作（`git status` 干净）
- 想找回"丢了的"提交：`git reflog`，通常都能救回来
- 推之前 `git log --oneline origin/main..HEAD` 看看要推哪些

## 提交信息用中文还是英文

看仓库已有习惯。混着来最难看。没有先例就用中文（中文团队）或英文（开源项目）。

## 别做的事

- 不要 `git add -A` 之前不看 `git status`（会带进临时文件、日志、密钥）
- 不要把密码、token、密钥提交进去（提交后就洗不掉了，改历史很痛苦）
- 不要为了"保持历史干净"而 rebase 已经推出去的分支
