// 合成判据行里名字解不出来（`?`）的那批，到底是**谁**、从**哪个出口**进来的。
// 用法：node _harness/_whyq.cjs <logcat dump 文件>
//
// 为什么先写这一台而不是先改代码：按包开的刀（TRIM_PKGS / 任何新名单）在 `?` 上恒为假，
// 而 10-19 那轮我只数出"63% 是 ?"就停了 —— 那个数答不了"该接哪一根线"。这一台把 `?` 按
// **drawable 类 + 前景层类 + 是否自适应**分组，哪一组占大头，钩子就该往哪儿补。
//
// ⚠ 自证：解析器先证明它认得这份格式（两种 logcat 格式、pid 别取成毫秒），
//   并且必须同时解出「带名字」与「?」两类各 >0 行才继续；否则一律非零退出。
const fs = require('fs');
const file = process.argv[2];
if (!file) { console.log('用法：node _harness/_whyq.cjs <日志文件>'); process.exit(1); }
const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean);

// threadtime: `10-20 12:14:35.949 14320 14420 D Opticon : <正文>`
// 老格式：  `D/Opticon ( 7614): <正文>`
const TT = /^\S+ \S+ (\d+) \d+ [VDWI] Opticon\s*: (.*)$/;
const OLD = /^([VDWI])\/Opticon\s*\(\s*(\d+)\): (.*)$/;
const pids = new Map();
const rows = [];
for (const l of lines) {
  let m = TT.exec(l); let pid, body;
  if (m) { pid = m[1]; body = m[2]; }
  else { m = OLD.exec(l); if (!m) continue; pid = m[2]; body = m[3]; }
  // ⚠ 别写成 `m.length === 3 ? m[3] : m[2]`：threadtime 那一支的 m.length **本来就是 3**
  //   （全匹配 + 两个组），那样取到的是 undefined，于是整台子安静地报 0 行。
  // 只收**判据行**。`Mono: diag#N mask r|....` 那 8 行是同一张的缩略图，
  // 混进来的话第 3 位永远是 "mask" —— 于是"带名字"被虚报到 1569 条（这台子第一跑就是这么骗我的）。
  if (!/^Mono: diag#\d+ edge=/.test(body)) continue;
  // 正文：`Mono: diag#856 [sig=…] [键=…] <谁> <类> fg=… adaptive=true …`
  // ⚠ `sig=` / `键=` 都是**可选**的（命中缓存那趟带、真重算那趟不带），按固定下标取名字
  //   会在其中一类上错位 —— 先把这两类字段整段剔掉再按位取。
  const f = body.split(' ').filter(t => !/^(edge=|sig=|键=)/.test(t));
  const who = f[2];
  const cls = f[3];
  const adaptive = /adaptive=true/.test(body);
  const fg = (/fg=(\w+)@(\d+)/.exec(body) || []).slice(1).join('@');
  rows.push({ pid, who, cls, adaptive, fg, edge: (/edge=(\S+)/.exec(body) || [0, '?'])[1], body });
  pids.set(pid, (pids.get(pid) || 0) + 1);
}
const named = rows.filter(r => r.who && r.who !== '?');
const q = rows.filter(r => r.who === '?');
// 字段对齐的硬自证：第 4 位必须是一个 drawable 类名。不是 ⇒ 我把可选字段数错了。
const badCls = rows.filter(r => !/Drawable$/.test(r.cls || ''));
console.log('解析自检：diag 判据行 ' + rows.length + ' 条，带名字 ' + named.length + '，`?` ' + q.length +
  '，第 4 位不是 Drawable 的 ' + badCls.length + ' 条');
const big = [...pids.entries()].sort((a, b) => b[1] - a[1]).slice(0, 3);
console.log('        进程分布（必须有一个几百行的大进程）：' + big.map(e => e[0] + '→' + e[1] + '行').join('  '));
if (rows.length < 50 || named.length === 0 || q.length === 0 || badCls.length > 0) {
  console.log('!! 解析器没同时认得两类行（或这份日志压根没有合成行）—— 读数作废');
  process.exit(1);
}
console.log('        样例（带名字）：' + named[0].body.slice(0, 96));
console.log('        样例（?）      ：' + q[0].body.slice(0, 96));

function group(title, key) {
  const m = new Map();
  for (const r of q) { const k = key(r); m.set(k, (m.get(k) || 0) + 1); }
  console.log('\n=== `?` 按' + title + ' ===');
  for (const [k, v] of [...m.entries()].sort((a, b) => b[1] - a[1]).slice(0, 10))
    console.log('  ' + String(v).padStart(4) + '  ' + (v * 100 / q.length).toFixed(1).padStart(5) + '%  ' + k);
}
group('交付类 × 是否自适应', r => r.cls + (r.adaptive ? ' adaptive' : ' 非adaptive'));
group('前景层类', r => r.fg || '(无 fg= 字段)');
group('边长', r => r.edge);

// 名字解不出来时，**同一个包**在带名字那批里出现过没有？出现过 = 这一张是同一个包的另一个出口，
// 说明缺的是那条出口，不是那个包；没出现过 = 这个包从来没被登记过。
const namedPkg = new Set(named.map(r => r.who.split('/')[0]));
const qPkg = new Map();
for (const r of q) {
  const comp = r.body.match(/component=(\S+)/);
  const k = comp ? comp[1] : '(diag 行里没有 component 字段)';
  qPkg.set(k, (qPkg.get(k) || 0) + 1);
}
console.log('\n（`?` 行里有没有带组件名可读的字段：' + (qPkg.size === 1 && qPkg.has('(diag 行里没有 component 字段)') ? '没有 —— 只能靠类/出口归因' : '有') + '）');
const byCls2 = new Map();
for (const r of q) byCls2.set(r.cls, (byCls2.get(r.cls) || 0) + 1);
console.log('`?` 的交付类合计：' + [...byCls2.entries()].sort((a, b) => b[1] - a[1]).map(e => e[0] + ' ' + e[1]).join(' / '));
