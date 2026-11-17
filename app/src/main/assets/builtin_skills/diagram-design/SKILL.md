---
name: diagram-design
description: >-
  Draw diagrams as one self-contained HTML + SVG file: architecture, sequence, quadrant, flow,
  comparison, timeline and 45 more types. Editorial quality, no shadows, no Mermaid slop.
  Use whenever the user asks for a diagram, chart, or visual explanation.
  把图直接画成自包含的 HTML + SVG 文件：架构图、时序图、象限图、流程图、对比图、时间线等
  50 种类型。编辑级质量，不用阴影，不用 Mermaid 那种粗糙输出。
  当用户要图、要图表、要可视化说明、要架构图/时序图/流程图时使用。
---

# 画图

输出版式讲究、可独立打开的 HTML+SVG，不是 Mermaid 那种粗糙框线图。

## 先选对图型

不同需求对应不同图型，**别一律用流程图**：

| 要表达 | 图型 |
|---|---|
| 系统由哪些部分组成 | architecture |
| 一次请求的时间顺序 | sequence |
| 两个维度上的定位 | quadrant |
| 步骤与分支 | flow |
| 多方案对比 | comparison / matrix |
| 随时间变化 | timeline |
| 层级关系 | tree / hierarchy |
| 数据占比 | donut / bar |
| 状态流转 | state |

完整 45+ 种类型见官方 gallery。

**代码结构图怎么选**：

| 想知道什么 | 画什么 |
|---|---|
| 系统由哪些部分组成 | 模块图（方块 + 连线） |
| 一次请求经过哪些环节 | 时序图（参与者 + 箭头） |
| 数据怎么流转 | 数据流图 |
| 表之间怎么关联 | ER 图 |
| 谁调用谁 | 调用图 |

**要查真实的调用关系，先用 `code-graph` 技能查图谱**，再拿结果来画 ——
凭印象画出来的依赖关系，十有八九是错的。

## 用法

```
run_skill_script(skill="diagram-design", path="scripts/diagram.sh", args=["<你的图需求>"])
```

**描述里要说清三件事**：

| 要素 | 例子 |
|---|---|
| **画什么** | 「微服务架构：网关、三个服务、一个数据库、一个缓存」 |
| **关系** | 「网关调三个服务，服务读写数据库，服务走缓存」 |
| **重点** | 「强调网关是单点」 |

例：
```
run_skill_script(..., args=["画一个微服务架构图：客户端 → 网关 → 用户/订单/支付三个服务 → PostgreSQL + Redis。网关是单点故障要从视觉上强调出来"])
```

## 视觉规范（内置）

- **不用阴影** —— 阴影让图显廉价
- **不超过三个色系** —— 一个主色 + 中性色 + 一个强调色
- **连线要有标签** —— 光秃秃的箭头说不清是「调用」还是「读取」
- **节点名字说人话** —— 写「用户服务」不写 `UserServiceImpl`
- **一张图讲一件事** —— 超过 15 个节点就拆

## 让它匹配你的品牌

如果项目有自己的配色和字体，可以一次性导入：

```
run_skill_script(skill="diagram-design", path="scripts/onboard.sh", args=["<你的网站URL>"])
```

之后所有图都会用同一套视觉。

## 什么时候不用

- **只是要个快速示意图** → 用 Mermaid 直接写在对话里更快
- **要能编辑的矢量源文件** → 这个输出的是 HTML+SVG，要改得改代码
- **流程图逻辑复杂（20+ 分支）** → 拆成多张，或改用文字描述

## 和其他技能配合

| 场景 | 顺序 |
|---|---|
| 写技术文档配图 | `tech-writing` 定结构 → `diagram-design` 出图 |
| 讲清代码结构 | `code-graph` 查关系 → `diagram-design` 画出来 |
| 方案汇报 | `superpowers` 写方案 → `diagram-design` 出架构图 |
