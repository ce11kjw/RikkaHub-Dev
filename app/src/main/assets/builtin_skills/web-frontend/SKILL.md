---
name: web-frontend
description: >-
  Frontend engineering beyond visual design: rendering live data efficiently, virtual scrolling
  for long lists, chart performance, mobile layout pitfalls, state management for real-time UI.
  Use when the UI works but feels slow, janky, or breaks on real data.
  前端工程实现（不是设计）：高效渲染实时数据、长列表虚拟滚动、图表性能、移动端适配坑、
  实时 UI 的状态管理。当界面能跑但卡、掉帧、或被真实数据撑崩时使用。
---

# 前端工程

`frontend-taste` 和 `ui-ux-pro` 管"好不好看"，这个管**"快不快、稳不稳"**。

## 实时数据渲染

**核心原则：不要每次数据变化都重渲染整个组件树。**

**① 只更新变的部分**

```js
// ❌ 整个列表重绘
setData([...data, newItem]);

// ✅ 只追加一项（配合虚拟列表）
data.push(newItem);
virtualList.append(newItem);
```

**② 合并更新**

高频数据（每秒多次）合并到帧：

```js
let buffer = [];
let scheduled = false;

function onUpdate(value) {
  buffer.push(value);
  if (scheduled) return;
  scheduled = true;
  requestAnimationFrame(() => {
    applyBatch(buffer);
    buffer = [];
    scheduled = false;
  });
}
```

**③ 慢变量和快变量分开**

温度每分钟变一次，CPU 占用每秒变十次 —— 别放同一个更新循环里。

## 长列表

超过 100 项就必须虚拟滚动，否则滚动会卡。

**判断标准**：每个列表项 DOM 超过 10 个节点 × 500 项 = 5000 个节点，必卡。

**方案**：

| 场景 | 用什么 |
|---|---|
| 固定高度 | 最简单，按 index 算位置 |
| 高度不定 | 需要测量，用成熟库 |
| 需要 SEO | 不要虚拟滚动，改服务端分页 |

**坑**：虚拟列表配合动画会很麻烦，滚动时元素被复用，动画状态会串。

## 图表性能

**① 点数上限**

| 场景 | 点数 |
|---|---|
| 折线图 | 500 以内直接画 |
| 超过 500 | 降采样（LTTB 算法）或分段加载 |
| 实时曲线 | 环形缓冲，只留最近 N 个 |

**② 用 canvas 不用 svg**

超过 200 个数据点时，canvas 比 svg 快一个数量级。

**③ 动画关掉**

实时数据不要加过渡动画，数据一变动画就开始追，永远追不上。

## 移动端坑

**① 100vh 不准**

移动浏览器地址栏会收缩，`100vh` 会跳动：

```css
/* 用 dvh 代替 */
height: 100dvh;

/* 或者兜底 */
height: 100vh;
height: 100dvh;
```

**② 输入框被键盘遮挡**

- iOS：键盘弹出时视口不缩放，需要手动滚动
- Android：默认会缩放，但 `position: fixed` 的元素会被顶上去

**③ 点击延迟**

加 `<meta name="viewport" content="width=device-width">` 后基本没问题。仍有延迟说明有 touch 事件监听器。

**④ 滚动穿透**

弹层打开时，背景还能滚。要锁：

```css
body { overflow: hidden; }
/* 或者更稳的方式：记录 scrollY，关闭时恢复 */
```

**⑤ 1px 边框在高分屏变粗**

```css
border: 0.5px solid;  /* 或者用 transform: scaleY(0.5) 的伪元素 */
```

## 状态管理

**① 状态能推导就不要存**

```
❌ 同时存 list、filteredList、count
✅ 只存 list 和 filter，其余用 computed
```

两份状态就需要手动同步，迟早不同步。

**② 实时数据不要进全局状态**

每秒更新的数字放全局 store，会导致所有订阅组件重渲染。用局部状态或 ref。

**③ 请求状态单独管**

loading / error / data 三态分开，不要用一个 `status` 字符串（容易漏状态）。

## 性能排查顺序

界面卡的时候按这个顺序查，**别猜**：

```
1. 打开 DevTools Performance，录 3 秒
2. 看哪个函数耗时最长（火焰图最宽的块）
3. 看是不是在循环里做了 O(n) 的事
4. 看有没有频繁的 layout（强制同步布局）
5. 看动画是不是用 transform/opacity（这两个能 GPU 加速）
```

**常见元凶**：

- 在滚动回调里读 `offsetHeight`（强制回流）
- 在循环里改 DOM（每个改动都触发重排）
- 大图片没压缩
- 没用 `will-change` 但有复杂动画

## 交付前自检

- [ ] 列表 1000 项能流畅滚动吗
- [ ] 网络慢时（3G 模拟）有骨架屏吗
- [ ] 手机横竖屏切换布局不崩吗
- [ ] 键盘弹出时输入框可见吗
- [ ] 长时间开着（1 小时）内存不涨吗
