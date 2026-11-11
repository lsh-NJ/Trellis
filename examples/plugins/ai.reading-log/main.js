// 读书记录 v3 —— 卡片只留数据与动作：列表 + 两处浮窗输入。
//
// 用户原话（2026-11-10）：「输入/修改/添加这类操作都做成浮窗」，并删掉多余说明行。
// 所以卡片上只有：标题「读书」→ 每本书一行（书名 + 唯一一处进度，整行点开笔记）
// → 底部一行两个输入触发器（加书 / 记页数，点开才是浮窗输入框）。
//
// 数据沿用 v1 的 store 键，老用户记录不用迁移：
//   books       书名，用 "||" 分隔
//   p:<书名>     当前页
//   t:<书名>     上次记录页码的时间戳（毫秒）
//   tot:<书名>   总页数（旧数据没有；0/缺省 = 未设置）
var MAX_BOOKS = 6;

function isSpace(c) {
  var n = c.charCodeAt(0);
  return n === 32 || n === 9 || n === 10 || n === 13;
}

function trim(s) {
  var a = String(s == null ? '' : s);
  var i = 0;
  var j = a.length;
  while (i < j && isSpace(a.charAt(i))) { i++; }
  while (j > i && isSpace(a.charAt(j - 1))) { j--; }
  return a.substring(i, j);
}

function num(v) {
  var n = Number(v);
  return isNaN(n) || n < 0 ? 0 : Math.floor(n);
}

function loadBooks(data) {
  var raw = data.store.books;
  if (!raw) { return []; }
  var parts = String(raw).split('||');
  var out = [];
  for (var i = 0; i < parts.length; i++) {
    var t = trim(parts[i]);
    if (t) { out.push(t); }
  }
  return out;
}

function progressText(cur, tot) {
  if (tot > 0) { return cur + ' ｜ ' + tot + ' 页'; }
  return cur + ' 页';
}

// 解析成 { name, cur, tot }。数字段既可写成「300」，也可写成「120/300」：
//   三体            → name=三体, cur=null, tot=null
//   三体 300        → cur=null, tot=300（加书时表示总页数；记页数时表示当页）
//   三体 120/300    → cur=120, tot=300
function parseEntry(text) {
  var s = trim(text);
  if (!s) { return null; }
  var m = s.match(/^(.+?)\s+(\d+)(?:\s*\/\s*(\d+))?$/);
  if (m) {
    return {
      name: trim(m[1]),
      cur: m[3] ? Number(m[2]) : null,
      tot: m[3] ? Number(m[3]) : Number(m[2])
    };
  }
  return { name: s, cur: null, tot: null };
}

function render(data) {
  var list = loadBooks(data);

  var kids = [{ type: 'text', text: '读书', style: 'title' }];

  if (list.length === 0) {
    kids.push({ type: 'text', text: '还没有在读的书。', style: 'caption', tone: 'muted' });
  }

  for (var j = 0; j < list.length && j < MAX_BOOKS; j++) {
    var name = list[j];
    var cur = num(data.store['p:' + name]);
    var tot = num(data.store['tot:' + name]);
    // 整行都可点：存在同名笔记就跳过去，没有就建一篇再跳。
    kids.push({
      type: 'listrow',
      title: name,
      subtitle: progressText(cur, tot),
      trailing: '打开笔记',
      action: 'openKnowledgeNote',
      args: { title: '读书笔记：《' + name + '》' }
    });
  }

  // 两个入口共用一行；默认就是浮窗输入，卡片上只留触发器文字（submitLabel）。
  kids.push({
    type: 'row',
    gap: 16,
    children: [
      {
        type: 'input',
        hint: '加一本书：书名 可带总页数（如 三体 300）',
        submitLabel: '加一本书',
        on: 'addBook'
      },
      {
        type: 'input',
        hint: '记页数：书名 页数（如 三体 120）',
        submitLabel: '记一页',
        on: 'setPage'
      }
    ]
  });

  return { type: 'column', gap: 10, children: kids };
}

// 加书 / 给已存在的书改总页数（v2 语义不变）。
function addBook(data, payload) {
  var entry = parseEntry(payload.value);
  if (!entry || !entry.name) { return { say: '先填一个书名' }; }
  var name = entry.name.split('||').join(' ');
  if (name.length > 30) { name = name.substring(0, 30); }

  var list = loadBooks(data);
  var exists = false;
  for (var i = 0; i < list.length; i++) {
    if (list[i] === name) { exists = true; }
  }

  var save = {};
  if (exists) {
    // 已存在的书名只改页数，不会再加一本。
    if (entry.tot == null) {
      return { say: '《' + name + '》已在列表里；要改页数就写「' + name + ' 页数」' };
    }
    save['tot:' + name] = String(entry.tot);
    if (entry.cur != null) { save['p:' + name] = String(Math.min(entry.cur, entry.tot)); }
    return { say: '《' + name + '》总页数改为 ' + entry.tot + ' 页', save: save };
  }

  if (list.length >= MAX_BOOKS) { return { say: '最多同时记 ' + MAX_BOOKS + ' 本' }; }
  list.push(name);
  save.books = list.join('||');
  if (entry.tot != null) { save['tot:' + name] = String(entry.tot); }
  if (entry.cur != null) { save['p:' + name] = String(Math.min(entry.cur, entry.tot)); }
  return { say: '《' + name + '》加进来了', save: save };
}

// 记页数：「书名 页数」或「书名 页数/总页数」；只改已经在列表里的书。
function setPage(data, payload) {
  var entry = parseEntry(payload.value);
  if (!entry || !entry.name) { return { say: '先写「书名 页数」' }; }
  var name = entry.name;
  var page = entry.cur != null ? entry.cur : entry.tot;
  if (page == null) { return { say: '先写「书名 页数」' }; }

  var list = loadBooks(data);
  var found = false;
  for (var i = 0; i < list.length; i++) {
    if (list[i] === name) { found = true; }
  }
  if (!found) { return { say: '列表里没有《' + name + '》' }; }

  var tot = entry.cur != null ? entry.tot : num(data.store['tot:' + name]);
  if (tot > 0 && page > tot) { page = tot; }
  var save = {};
  save['p:' + name] = String(page);
  save['t:' + name] = String(data.now);
  if (entry.cur != null && entry.tot > 0) { save['tot:' + name] = String(entry.tot); }
  return { say: '《' + name + '》记到第 ' + page + ' 页', save: save };
}

trellis.register({
  cards: [{ id: 'reading', point: 'slot.home.section', order: 900, render: render }],
  on: {
    addBook: addBook,
    setPage: setPage
  }
});
