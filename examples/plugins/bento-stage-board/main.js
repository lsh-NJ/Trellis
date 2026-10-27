// Bento 舞台看板 —— 一个「真的在干活」的复杂插件：
// 三个挂载点、五张卡、六个交互、自己的 KV 数据层，图表/表格/环形都由宿主画。
// 数据全部来自本插件自己的 KV（不读宿主任务），所以它既是看板也是演示数据源：
// 第一次装上点「载入演示数据」即可看到完整效果，之后每次点击都会真的改数。
var TIERS = [
  { key: "early", name: "早鸟", price: 180, cap: 800 },
  { key: "pre", name: "预售", price: 260, cap: 2000 },
  { key: "door", name: "现场", price: 320, cap: 1000 },
  { key: "guest", name: "赠票", price: 0, cap: 200 }
];
var CHECKS = ["场地合同", "消防备案", "安保方案", "艺人接待", "票务对账"];
var BUDGET = 486000;
var CAPACITY = 4000;

function num(data, key, fallback) {
  var raw = data.store[key];
  if (raw === undefined || raw === null || raw === "") { return fallback; }
  var n = Number(raw);
  return isNaN(n) ? fallback : n;
}
function csv(data, key) {
  var raw = data.store[key];
  if (!raw) { return []; }
  var parts = String(raw).split(",");
  var out = [];
  for (var i = 0; i < parts.length; i++) {
    var n = Number(parts[i]);
    out.push(isNaN(n) ? 0 : n);
  }
  return out;
}
function tierSold(data, key) { return num(data, "stage:sold:" + key, 0); }
function soldTotal(data) {
  var t = 0;
  for (var i = 0; i < TIERS.length; i++) { t += tierSold(data, TIERS[i].key); }
  return t;
}
function revenue(data) {
  var t = 0;
  for (var i = 0; i < TIERS.length; i++) { t += tierSold(data, TIERS[i].key) * TIERS[i].price; }
  return t;
}
function spent(data) { return num(data, "stage:spent", 0); }
function crowd(data) { return num(data, "stage:crowd", 0); }
function crowd7(data) { var v = csv(data, "stage:crowd7"); while (v.length < 7) { v.push(0); } return v; }
function sales7(data) { var v = csv(data, "stage:sales7"); while (v.length < 7) { v.push(0); } return v; }
function heat(data) {
  var v = crowd7(data);
  var out = [];
  for (var i = 0; i < v.length; i++) { out.push(v[i] > 200 ? 5 : Math.ceil(v[i] / 40)); }
  return out;
}
function weekdayLabels(data) {
  var today = Math.floor(data.now / 86400000);
  var names = ["日", "一", "二", "三", "四", "五", "六"];
  var out = [];
  for (var i = 6; i >= 0; i--) {
    var d = new Date((today - i) * 86400000);
    out.push(names[d.getUTCDay()]);
  }
  return out;
}
function checkState(data, name) { return data.store["stage:check:" + name] || "wait"; }
function risks(data) {
  var out = [];
  var checksOk = 0;
  for (var i = 0; i < CHECKS.length; i++) {
    var st = checkState(data, CHECKS[i]);
    if (st === "ok") { checksOk++; }
    if (st !== "ok") { out.push({ title: CHECKS[i], state: st === "no" ? "阻断" : "待办" }); }
  }
  var use = spent(data) / BUDGET;
  if (use > 0.85) { out.push({ title: "预算执行 " + Math.round(use * 100) + "%", state: use > 1 ? "超支" : "偏紧" }); }
  var occupancy = soldTotal(data) / CAPACITY;
  if (occupancy < 0.6) { out.push({ title: "上座率 " + Math.round(occupancy * 100) + "%", state: "偏低" }); }
  return { list: out, checksOk: checksOk };
}
function logText(data) {
  var raw = data.store["stage:log"] || "";
  return raw;
}
function pushLog(data, line) {
  var parts = [];
  var old = String(data.store["stage:log"] || "").split("|");
  for (var i = 0; i < old.length && parts.length < 4; i++) {
    if (old[i]) { parts.push(old[i]); }
  }
  var hh = new Date(data.now).getHours();
  var mm = new Date(data.now).getMinutes();
  var stamp = (hh < 10 ? "0" : "") + hh + ":" + (mm < 10 ? "0" : "") + mm;
  parts.unshift(stamp + " " + line);
  return parts.slice(0, 5).join("|");
}
function seed(data) {
  var save = {};
  var today = Math.floor(data.now / 86400000);
  save["stage:sold:early"] = "742";
  save["stage:sold:pre"] = "1180";
  save["stage:sold:door"] = "316";
  save["stage:sold:guest"] = "88";
  save["stage:spent"] = "172400";
  save["stage:crowd"] = "126";
  save["stage:crowd7"] = "88,120,64,150,190,132,126";
  save["stage:sales7"] = "18,32,12,45,61,38,52";
  save["stage:check:场地合同"] = "ok";
  save["stage:check:消防备案"] = "ok";
  save["stage:check:安保方案"] = "wait";
  save["stage:check:艺人接待"] = "no";
  save["stage:check:票务对账"] = "ok";
  save["stage:log"] = "09:12 开启现场检票|09:04 早鸟票核销 12 张";
  return { say: "演示数据已载入：卖了 " + (742 + 1180 + 316 + 88) + " 张，现场 " + 126 + " 人", save: save };
}
function sell(data, payload) {
  var tier = payload.tier || "door";
  var key = "stage:sold:" + tier;
  var save = {};
  save[key] = String(tierSold(data, tier) + 1);
  save["stage:log"] = pushLog(data, "售出 1 张" + tier);
  return { say: "卖出一张", save: save };
}
function checkin(data) {
  var save = {};
  save["stage:crowd"] = String(crowd(data) + 1);
  var today = Math.floor(data.now / 86400000);
  var v = crowd7(data);
  v[v.length - 1] = v[v.length - 1] + 1;
  save["stage:crowd7"] = v.join(",");
  save["stage:log"] = pushLog(data, "现场 +1");
  return { say: "现场现在 " + (crowd(data) + 1) + " 人", save: save };
}
function spend(data) {
  var save = {};
  save["stage:spent"] = String(spent(data) + 5000);
  save["stage:log"] = pushLog(data, "支出 5000");
  return { say: "记了一笔 5000 元支出", save: save };
}
function cycleCheck(data, payload) {
  var name = payload.check || CHECKS[0];
  var order = { wait: "ok", ok: "no", no: "wait" };
  var next = order[checkState(data, name)] || "wait";
  var save = {};
  save["stage:check:" + name] = next;
  save["stage:log"] = pushLog(data, name + " → " + next);
  return { say: name + " 改成 " + (next === "ok" ? "已确认" : next === "no" ? "已阻断" : "待办"), save: save };
}
function reset(data) {
  var save = {};
  var keys = ["stage:sold:early", "stage:sold:pre", "stage:sold:door", "stage:sold:guest",
    "stage:spent", "stage:crowd", "stage:crowd7", "stage:sales7", "stage:log"];
  for (var i = 0; i < keys.length; i++) { save[keys[i]] = ""; }
  for (var j = 0; j < CHECKS.length; j++) { save["stage:check:" + CHECKS[j]] = ""; }
  return { say: "已清空，可重新载入", save: save };
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

function banner(data) {
  var r = risks(data);
  var sold = soldTotal(data);
  var occupancy = Math.round(sold / CAPACITY * 100);
  var children = [
    {
      type: "row",
      gap: 8,
      children: [
        { type: "badge", text: "Bento", tone: "default" },
        { type: "badge", text: "已售 " + occupancy + "%", tone: "accent" },
        { type: "badge", text: r.list.length > 0 ? r.list.length + " 项风险" : "无风险", tone: r.list.length > 0 ? "danger" : "success" },
        { type: "badge", text: "检查 " + r.checksOk + "/" + CHECKS.length }
      ]
    },
    { type: "text", text: "已售 " + sold + " / " + CAPACITY + " 张 · 票房 " + revenue(data) + " 元 · 现场 " + crowd(data) + " 人", style: "caption", tone: "muted" }
  ];
  return { type: "column", gap: 6, children: children };
}

function cumulative(data) {
  var week = sales7(data);
  var out = [];
  var total = soldTotal(data) - (function () { var t = 0; for (var i = 0; i < week.length; i++) { t += week[i]; } return t; })();
  for (var i = 0; i < week.length; i++) { total += week[i]; out.push(total); }
  return out;
}

function stage(data) {
  var sold = soldTotal(data);
  var occupancy = Math.round(sold / CAPACITY * 100);
  var use = Math.round(spent(data) / BUDGET * 100);
  var tiers = [];
  var rows = [];
  for (var i = 0; i < TIERS.length; i++) {
    var t = TIERS[i];
    tiers.push({ label: t.name, value: tierSold(data, t.key) });
    rows.push([t.name, String(tierSold(data, t.key)), t.price + " 元", String(tierSold(data, t.key) * t.price)]);
  }
  var r = risks(data);
  var week = sales7(data);
  var labels = weekdayLabels(data);
  var bars = [];
  for (var j = 0; j < week.length; j++) { bars.push({ label: labels[j], value: week[j] }); }

  var riskRows = [];
  for (var k = 0; k < r.list.length && riskRows.length < 4; k++) {
    riskRows.push([r.list[k].title, r.list[k].state]);
  }

  return {
    type: "column",
    gap: 10,
    children: [
      head("票务累计 / CUMULATIVE SALES", "跑调音乐节 · 开票窗口"),
      {
        type: "grid",
        columns: 4,
        children: [
          { type: "stat", label: "已售", value: String(sold) + " / " + CAPACITY },
          { type: "stat", label: "票房", value: String(revenue(data)) + " 元" },
          { type: "ring", value: occupancy, max: 100, label: "上座率" },
          { type: "ring", value: use, max: 130, label: "预算" }
        ]
      },
      { type: "divider" },
      { type: "area", values: cumulative(data), max: CAPACITY, label: "累计出票 / CUMULATIVE" },
      { type: "columns", values: week, labels: labels, label: "近七天出票（张）" },
      { type: "bars", label: "票档已售（张）", items: tiers },
      { type: "heat", values: heat(data), max: 5, labels: labels },
      { type: "text", text: "近七天现场人数（格越深人越多）", style: "caption", tone: "muted" },
      { type: "divider" },
      { type: "table", caption: "票档明细", headers: ["档位", "已售", "单价", "小计"], rows: rows },
      {
        type: "row",
        gap: 6,
        children: [
          { type: "button", label: "现场 +1", on: "sell", args: { tier: "door" } },
          { type: "button", label: "记支出 5000", on: "spend" },
          { type: "button", label: "重新载入", on: "seed" },
          { type: "button", label: "清空", on: "reset" }
        ]
      }
    ]
  };
}

function ops(data) {
  var r = risks(data);
  var week = crowd7(data);
  var labels = weekdayLabels(data);
  var log = logText(data).split("|");
  var logRows = [];
  for (var i = 0; i < log.length && logRows.length < 5; i++) {
    if (log[i]) { logRows.push(["·", log[i]]); }
  }
  var riskRows = [];
  for (var k = 0; k < r.list.length && riskRows.length < 5; k++) {
    riskRows.push([r.list[k].title, r.list[k].state]);
  }
  var checkChildren = [];
  for (var c = 0; c < CHECKS.length; c++) {
    var st = checkState(data, CHECKS[c]);
    checkChildren.push({
      type: "button",
      label: CHECKS[c] + " · " + (st === "ok" ? "已确认" : st === "no" ? "阻断" : "待办"),
      on: "cycle",
      args: { check: CHECKS[c] }
    });
  }
  return {
    type: "column",
    gap: 10,
    children: [
      head("风险与预算 / OPS", "现场态势"),
      {
        type: "row",
        gap: 16,
        children: [
          { type: "dotmatrix", value: String(crowd(data)), label: "现场人数" },
          { type: "stepper", label: "现场人数", value: crowd(data), unit: "人", min: 0, max: 4000, step: 1, on: "checkin" },
          { type: "stat", label: "预算执行", value: Math.round(spent(data) / BUDGET * 100) + "%" },
          { type: "stat", label: "风险", value: String(r.list.length) + " 项" }
        ]
      },
      { type: "section", title: "入场检查（点一下切换状态）", children: checkChildren },
      riskRows.length > 0
        ? { type: "table", caption: "风险清单", headers: ["项目", "状态"], rows: riskRows }
        : { type: "notice", text: "没有风险项 —— 检查全绿、预算和上座率都在安全区" },
      logRows.length > 0
        ? { type: "table", caption: "最近操作", headers: ["时间", "事件"], rows: logRows }
        : { type: "notice", text: "还没有操作记录，点上面的按钮试试" },
      { type: "sparkline", values: week, min: 0, max: 200, label: "近七天现场人数走势" }
    ]
  };
}

trellis.register({
  cards: [
    { id: "banner", point: "slot.home.header", order: 10, render: banner },
    { id: "stage", point: "slot.home.section", order: 36, render: stage },
    { id: "ops", point: "slot.report.chart", order: 44, render: ops }
  ],
  on: {
    seed: seed,
    sell: sell,
    checkin: checkin,
    spend: spend,
    cycle: cycleCheck,
    reset: reset
  }
});
