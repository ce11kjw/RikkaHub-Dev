---
name: modern-web
description: >-
  Modern web platform guidance from Google Chrome, Microsoft Edge, Mozilla Firefox and MDN teams:
  250 features covering CSS layout, HTML, JS APIs, performance and accessibility. Use when writing
  web UI, CSS, or when a web feature might have a native solution now. Steers away from legacy
  patterns and bloated JavaScript.
  来自 Chrome/Edge/Firefox/MDN 官方的现代 Web 平台指南（250 项特性，涵盖 CSS 布局、HTML、
  JS API、性能、无障碍）。当要写网页、CSS，或某个需求可能已有原生方案时使用。
  避免生成过时的写法和不必要的 JavaScript。
---

# 现代 Web 平台

AI 训练数据里有大量遗留代码，容易为本来有原生方案的事情写一堆 JS。这个技能用来对齐到当前平台能力。

## 用之前先确认环境

这些指南靠一个 CLI 工具检索，需要在 workspace 的 Linux 环境里跑（Ubuntu base 默认没有 node）：

```
run_skill_script(skill="modern-web", path="scripts/setup.sh")
```

它会在 workspace 里装好 node 并拉取检索工具。只跑一次。

## 三种用法

**① 按需求搜**

```
run_skill_script(skill="modern-web", path="scripts/search.sh", args=["animate a dialog backdrop"])
```

返回相关指南的 ID 和摘要，挑最有用的那个。

**② 按 ID 取全文**

```
run_skill_script(skill="modern-web", path="scripts/retrieve.sh", args=["animate-to-from-top-layer"])
```

**③ 扫描项目里的旧写法**

```
run_skill_script(skill="modern-web", path="scripts/legacy-scan.sh", args=["<目录>"])
```

## 常见需求对应的原生方案

写之前先查表，别急着引库或写 JS：

| 需求 | 别再这样做 | 现在用 |
|---|---|---|
| 弹层/提示框 | 自己算定位、写 z-index 战争 | **Popover API** + **Anchor Positioning** |
| 模态框 | div + 手动锁滚动 | **`<dialog>`** + `showModal()` |
| 下拉选择 | 自己实现列表 | **Customizable `<select>`** |
| 页面切换动画 | 手动过渡 | **View Transitions** |
| 响应式布局 | 只按屏幕宽度 | **Container Queries** |
| 复杂网格对齐 | 嵌套 div | **Subgrid** |
| 展开/收起 | JS 控制高度 | **`<details>`** + `::details-content` |
| 表单校验 | 自己写正则提示 | **`:user-invalid`** + 原生校验 |
| 滚动动画 | IntersectionObserver 手算 | **Scroll-driven animations** |
| 文本换行难看 | 手动插 `<br>` | **`text-wrap: balance / pretty`** |
| 主题色 | 两套变量手写 | **`light-dark()`** + **`color-scheme`** |
| 自适应输入框 | JS 算 scrollHeight | **`field-sizing: content`** |
| 长列表 | 一次性渲染 | **`content-visibility`** |

## 判断标准

写 Web 代码前问自己：

1. **平台有原生方案吗？** 先查，没有再用库
2. **这个库是不是在补平台的旧缺陷？** 那些缺陷可能已经修了
3. **能不用 JS 吗？** CSS 能做的不写 JS
4. **降级方案够轻吗？** 用特性检测 + 合理降级，不要引入大体积 polyfill

## 什么时候不用这个技能

- 不是 Web 项目（Android、后端、CLI）
- 用的是老浏览器且不能升级（明确要求兼容 IE 那种）
- 需求本身和平台能力无关（业务逻辑、数据处理）
