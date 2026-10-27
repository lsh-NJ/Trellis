// Bento：把宿主给的语义节点拼成一张高密度看板。
// 数据只有两份：data.tasks（含时间戳）和 data.summary —— 没有任何绘图代码，
// 柱状/走势/环形/方格都由宿主画，插件只决定「画什么数」。
function dayIndex(ms) { return Math.floor(ms / 86400000); }
function weekdayLabel(day) {
  var d = new Date(day * 86400000);
  return ["日", "一", "二", "三", "四", "五", "六"][d.getUTCDay()];
}
function completionsPerDay(data, count) {
  var today = dayIndex(data.now);
  var out = [];
  for (var i = count - 1; i >= 0; i--) {
    var day = today - i;
    var n = 0;
    for (var j = 0; j < data.tasks.length; j++) {
      var done = data.tasks[j].completedAt;
      if (done && dayIndex(done) === day) { n++; }
    }
    out.push(n);
  }
  return out;
}
function dueLabel(task, today, tomorrow) {
  if (!task.dueAt) { return ""; }
  var day = dayIndex(task.dueAt);
  if (day === today) { return "今天"; }
  if (day === tomorrow) { return "明天"; }
  if (day < today) { return "逾期"; }
  return "排期";
}
function openTasks(data) {
  var out = [];
  for (var i = 0; i < data.tasks.length; i++) {
    if (data.tasks[i].status !== "DONE") { out.push(data.tasks[i]); }
  }
  return out;
}
function renderShift(data) {
  var today = dayIndex(data.now);
  var tomorrow = today + 1;
  var s = data.summary;
  var week = completionsPerDay(data, 7);
  var labels = [];
  for (var i = 6; i >= 0; i--) { labels.push(weekdayLabel(today - i)); }

  var open = openTasks(data);
  var soonest = null;
  for (var k = 0; k < open.length; k++) {
    var task = open[k];
    if (!task.dueAt) { continue; }
    if (soonest === null || task.dueAt < soonest.dueAt) { soonest = task; }
  }

  var rows = [];
  for (var r = 0; r < open.length && rows.length < 4; r++) {
    var t = open[r];
    rows.push([
      t.title.length > 16 ? t.title.substring(0, 16) + "…" : t.title,
      dueLabel(t, today, tomorrow),
      t.spentMinutes ? t.spentMinutes + " 分" : "—"
    ]);
  }

  return {
    type: "column",
    gap: 10,
    children: [
      {
        type: "grid",
        columns: 4,
        children: [
          { type: "stat", label: "待办", value: String(s.todo) },
          { type: "stat", label: "今天到期", value: String(s.dueToday) },
          { type: "stat", label: "逾期", value: String(s.overdue) },
          { type: "ring", value: s.completionRate, max: 100, label: "完成率" }
        ]
      },
      { type: "divider" },
      { type: "bars", label: "近七天完成", items: week.map(function (n, i) { return { label: labels[i], value: n }; }) },
      {
        type: "row",
        gap: 6,
        children: [
          { type: "badge", text: "连续 " + s.streakDays + " 天", tone: "accent" },
          { type: "badge", text: "本周 " + week.reduce(function (a, b) { return a + b; }, 0) + " 件", tone: "success" },
          { type: "badge", text: soonest ? "最近：" + dueLabel(soonest, today, tomorrow) : "没有排期", tone: soonest ? "danger" : "muted" }
        ]
      },
      { type: "divider" },
      rows.length > 0
        ? { type: "table", caption: "未完成 · 前四条", headers: ["任务", "期限", "已投入"], rows: rows }
        : { type: "notice", text: "今天没有安排 —— 在下面输入框按 N 加一条" }
    ]
  };
}

function head(kicker, title) {
  return {
    type: "column",
    gap: 2,
    children: [
      { type: "text", text: kicker, style: "caption", tone: "muted", font: "mono" },
      { type: "text", text: title, style: "title" }
    ]
  };
}
function renderBoard(data) {
  var today = dayIndex(data.now);
  var week = completionsPerDay(data, 7);
  var labels = [];
  for (var i = 6; i >= 0; i--) { labels.push(weekdayLabel(today - i)); }
  var s = data.summary;
  return {
    type: "column",
    gap: 8,
    children: [
      head("本周节奏 / RHYTHM", "完成率 " + s.completionRate + "% · 连续 " + s.streakDays + " 天"),
      {
        type: "row",
        gap: 12,
        children: [
          { type: "stat", label: "完成率", value: s.completionRate + "%" },
          { type: "stat", label: "连续", value: s.streakDays + " 天" },
          { type: "stat", label: "投入", value: s.spentMinutes + " 分" }
        ]
      },
      { type: "heat", values: week.map(function (n) { return n > 5 ? 5 : n; }), max: 5, labels: labels },
      { type: "sparkline", values: week, min: 0, max: 5, label: "最近七天完成数" }
    ]
  };
}

trellis.register({
  cards: [
    { id: "shift", point: "slot.report.chart", order: 40, render: renderShift },
    { id: "board", point: "slot.home.section", order: 34, render: renderBoard }
  ]
});
