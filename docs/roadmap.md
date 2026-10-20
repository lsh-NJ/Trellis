# 待办与已知缺口

按优先级。做完一条划掉一条。

---

## 1. 挂载点：9 个定义，界面上只渲染了 1 个（**暂不处理，2026-10-19 记**）

内核定义了 9 个挂载点，`RENDERED_MOUNT_POINTS` 里只放了 `slot.home.section`。
模型只能往看得见的地方挂，所以**实际能力面只有 1/9**。

### 白捡的（位置已经在屏幕上，只是被写死的内容占着）

| 挂载点 | 现在被什么占着 | 改哪儿 |
|---|---|---|
| `slot.home.header` | `Header(runtime)` 标题 + 统计栏 | `AppScreen.kt` 的 LazyColumn 第一条 item |
| `slot.row.trailing` | 「+25 分钟 / 删除」两个按钮 | `TaskRow` 末尾 |
| `slot.empty.state` | 一句写死的「还没有任务」 | `tasks.isEmpty()` 分支 |

接上之后 `RENDERED_MOUNT_POINTS` 从 1 变 4，**一天以内**。

### 要建页面的

| 挂载点 | 缺什么 |
|---|---|
| `slot.detail.aside` / `slot.detail.tab` | 没有详情页 |
| `slot.settings.section` | 没有设置页 |
| `slot.report.chart` | 没有报表页 |

### 要建容器的（**最值钱**）

| 挂载点 | 缺什么 | 为什么重要 |
|---|---|---|
| `slot.app.overlay` | 一个叠在最上层的浮层容器 | 没有它，AI 做不出任何「浮起来提醒你」的东西 —— 而那恰恰是「会做事」最容易被看见的形态 |

---

## 2. 其他已知缺口

- **数据字段只有 7 个，且首页全显示了** → AI 无论怎么退，退出来的都是重复。需要补 `streakDays`、`completionRate`、`oldestOpenTask` 这类首页没有的
- **动作只有 3 个**（addTask / logTime / completeTask）→ 没有 `notify`、`reschedule`、`deleteTask`
- **规则太弱**：单一事件、无 `when` 条件、无 `once`（现在每次触发都跑）
- **表达式缺列表和字符串拼接** → 做不了「列出今天到期的三项」
- **动作失败用户看不见** → 只记进诊断面板
- **无插件版本 / 回滚** → 覆盖安装失败就是没了
- **跨端**：只有桌面 JVM
- **系统性验收**：真机只跑过两次生成，没拿 10 个需求跑一遍
