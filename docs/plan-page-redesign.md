# 计划页重做 + 放弃计划 + 首周安排（2026-11-08）

> 状态：**已落地（2026-11-08）**——后端 task-6 / 计划工具 task-7 / 计划页 UI task-8 全部完成，` :app:ui:jvmTest` 与全量 JVM 套件绿，dev 截图验收通过（阶段条 / 周刻度 / 日折叠像素 / 右侧阶段进度 / 资源展开 / 周日总结 / 放弃计划）。
> 参考样片：`docs/design/plan-page-sample.html`（启发，不照搬；保持现有暖纸色 + 墨绿 + 硬朗字体的风格）。

## 1. 用户要的效果

1. 顶部不再用「一个大方框 = 一周」；**阶段**按计划大纲划，**周**压成一条可拖动的刻度条。
2. 每天可折叠展开；完成进度从进度条改成**像素方块**（不要太小），节点前仍保留方形圆角勾选框。
3. 右侧边栏按**阶段**画进度条（不再按周）；每日数字保持原有硬朗风格。
4. 学习节点点击展开显示**对应学习资源**；B 站目录不能原样照搬（要清洗、过滤片尾/预告，单元标题是学习点，分集只是资源）。
5. 周视图里的总结**在最后一天可看，也可以点按钮生成**（不只是自动）。
6. 计划支持**放弃**。
7. 大纲安排完以后 AGENT **直接排第一周**；排周的顺序是**先写周文档，再排天**。

## 2. 数据模型（后端 task-6）

### 2.1 阶段 `plan_stage`

```
plan_stage(
  id TEXT PRIMARY KEY,
  plan_version_id TEXT NOT NULL,
  name TEXT NOT NULL,
  week_start TEXT NOT NULL,   -- ISO 周，如 2026-W45
  week_end   TEXT NOT NULL,
  focus      TEXT NOT NULL DEFAULT '',
  sort_order INTEGER NOT NULL,
  created_at TEXT NOT NULL
)
```

- 契约：`StageDraft(name, weekStart, weekEnd, focus, sortOrder)`、`StageView(id, name, weekStart, weekEnd, focus, done, total)`。
- 命令：`setStages(env, planId, stages)`（幂等替换当前版本阶段，事件 `plan.stages_set`）。
- 查询：`stages(planId)`；`done/total` 用阶段周区间内的承诺单元 + occurrence 状态聚合。
- 来源：`research_plan` 写；计划文档的 `## 阶段` 段落与它同源（文档是「想法」，阶段是「结构」）。

### 2.2 单元资源 `unit_resource`

```
unit_resource(id, unit_id, kind, title, url, locator, source, created_at)
```

- `kind`：`bilibili | web | doc | other`；`locator`：如「第 3 集」「12:30」；`url` 可打开。
- 命令：`setUnitResources(env, planId, unitId, resources)`（替换该单元资源）。
- 视图：`DayOccurrence.resources: List<UnitResourceView>`（周/池视图逐步补）。
- B 站导入时写入：`kind="bilibili"`, `title=原分集标题`, `url=https://www.bilibili.com/video/<bvid>?p=<page>`, `locator="第 N 集"`。

### 2.3 放弃计划

- 不加新的状态枚举（避免 CHECK 约束迁移）：`process.abandoned_at TEXT NULL`；`abandonPlan` → `plan.status='ended'`、`process.status='ended'`、`abandoned_at=now`、事件 `plan.abandoned`。
- `plans()` 把有 `abandoned_at` 的映射为 `status="abandoned"`；计划页显示「已放弃」，不再作为默认焦点。

### 2.4 周总结时机

- `PlanStateView.reviewableWeek`：今天是当前周最后一天（周日）且本周还没复盘时 = 本周；保留 `pendingReviewWeek`（已结束但没复盘的周）。
- `generateReview` 允许「本周且今天是周日」；周六调用仍拒绝。
- 计划页在最后一周显示「生成本周总结」；生成后展示 `narrative/facts/question/suggestion`。

## 3. AGENT 侧流程（task-7）

### 3.1 大纲之后立即排第一周

`research_plan`：调研 → 空壳计划（`createShell`）→ `setStages` → 目标/里程碑 → 大纲文档 → **继续排第一周**（同一回合内）：LLM 出本周条目 → **写周文档** → `appendUnits` → 按每日预算 `placeUnit` → 返回「第一周已排」。

### 3.2 先写周文档，再排天

`plan_week`（及首周复用同一逻辑 `planOneWeek`）：
1. 读计划文档 + 上周完成情况；
2. LLM 出 `focus` + 条目（title/chapter/kind/minutes）；
3. **先** `saveWeek(planId, week, 周文档)`（本周重点、每天安排草案）；
4. **再** `appendUnits` + 逐日 `placeUnit`；
5. 失败时保留已写文档，并在结果里说明哪一步失败。

### 3.3 B 站目录清洗

- 分集标题清洗：去「第 N 集/话」「P1」「【】」「()」等标签。
- 黑名单过滤：片尾 / 预告 / 花絮 / 彩蛋 / 广告 / 联系方式 / 关注 / 下一集 / 合集 / 简介 / 开箱 等（中英文、大小写）。
- 过滤后 <3 个时退回原始分集，并在结果里给警告。
- 单元标题是**学习点**语义（章节/清洗后的名字），分集信息落到 `unit_resource`，不再逐字照搬。

## 4. UI 结构（task-8）

```
┌ 阶段条：认识 RAG │ 检索与向量 │ 生成与评估 │ 项目 ──────────────┐
│ 周刻度：W1 ─ W2 ─ W3 ─[W4]─ W5 ─ W6 ─ W7 ─ W8（可拖）          │  右侧：阶段进度
├──────────────────────────────────────────────────────────────┤  认识 RAG  4/9
│ 第 4 周 · 第 2 章   11.30 — 12.06 · 6 件 · 约 2.5 小时          │  检索与向量 0/7
│ ┌ 周一 12.01 ─ 2 件 ─ □□■ ┐   ← 可折叠；像素点 = 完成情况        │  ...
│ │ □ 认识 RAG 的链路           资源 ▸                          │
│ │ □ 跑通一次最小向量检索      资源 ▸                          │
│ └───────────────────────────┘                                │
│ ...（周二～周日）                                            │
│ 周总结（最后一天显示「生成本周总结」）                        │
└──────────────────────────────────────────────────────────────┘
```

- 周选择替换原来的「大方框周卡片」；阶段条读 `planStages`。
- 每日卡片可折叠；像素方块每 occurrence 一格（10–12dp）。
- 节点点击展开资源；勾选框行为不变。
- 宽屏右侧阶段进度栏；窄屏隐藏。
- 计划设置对话框底部「放弃这个计划」（危险色 + 二次确认）。

## 5. 文件分工

| 任务 | 负责人 | 文件 |
|---|---|---|
| task-6 后端 | backend-plan | `features/learning-process/...`、`sdk-domain/.../plan/` |
| task-7 计划工具 | agent-plan | `PlanTools.kt`、`ResearchTools.kt`、`PlanWeekTools.kt` + 测试 |
| task-8 计划页 UI | ui-plan | `PlanScreen.kt`、`AppRuntime.kt`（计划页部分）、`PlanPageParts.kt` + 测试 |
| 集成/文档/提交 | Lead | 本文件、`AGENTS.md`、`docs/plan-page.md`、`docs/roadmap.md`、全量测试、提交 |

## 6. 验收

- 后端：`./gradlew :features:learning-process:jvmTest` 绿；阶段/资源/放弃/周日总结四组测试。
- 计划工具：`:app:ui:jvmTest --tests PlanToolsTest --tests ResearchToolsTest --tests PlanWeekToolsTest` 绿；bvid 导入不再包含片尾类节点、节点有资源；research_plan 后第一周已排；周文档先于 occurrence 落盘。
- UI：`:app:ui:jvmTest` 全绿；dev 实例（`TRELLIS_HOME=/tmp/trellis-plan-ui`）截图核对阶段条/刻度/像素/资源展开/总结按钮/放弃入口。
- 全量 JVM 任务集（8 项）+ 提交 push。
