// 「单色算法到底从什么图标开算」—— 把真机日志里那一行的**输入类型分布**数出来，
// 别拿记忆里的教科书分类回答。日志字段来自 `MonochromeSynth.describe()`：
//   `<包名/资源> <最外层类> fg=<前景层类>@<WxH> intrinsic=WxH bounds=WxH adaptive=…`
// 用法：node _inputmix.cjs <logcat 文件> [pid]
const fs = require('fs');
const [FILE, PID] = process.argv.slice(2);
if (!FILE) { console.log('用法：node _inputmix.cjs <logcat> [pid]'); process.exit(2); }
const L = fs.readFileSync(FILE, 'utf8').split(/\r?\n/).filter(l => /Mono: diag#/.test(l) && l.includes('几何='));
const pick = (l) => {
  const p = l.split(/\s+/)[2];
  if (PID && p !== PID) return null;
  const body = (l.split('Opticon : ')[1] || '');
  // 行首是 `Mono: diag#27 edge=… sig=… 键=… <包名/资源> <最外层类> fg=…`：从 `键=` 之后开始取
  const m = /键=\S+ (\S+) (\w+)(?: fg=(\w+)@(\d+)x(\d+))? intrinsic=(\d+)x(\d+) bounds=(\d+)x(\d+) adaptive=(\w+)/.exec(body);
  // ⚠ 组号：1=名字 2=最外层类 3/4/5=前景层类与宽高（非自适应时是 undefined）6/7=intrinsic 8/9=bounds 10=adaptive
  return m && { who: m[1], outer: m[2], fg: m[3] || '-', fgW: +m[4] || 0, intr: m[6] + 'x' + m[7], bounds: m[8] + 'x' + m[9], adaptive: m[10] };
};
const rows = L.map(pick).filter(Boolean);
// 同一个图标会被量很多次（每次 bind 一个新实例），**按图标去重**才是"有几种输入"
const byWho = new Map();
for (const r of rows) if (!byWho.has(r.who)) byWho.set(r.who, r);
const uniq = [...byWho.values()];
const tally = (key) => {
  const m = new Map();
  for (const r of uniq) m.set(r[key], (m.get(r[key]) || 0) + 1);
  return [...m].sort((a, b) => b[1] - a[1]);
};
const pct = (m) => m.map(([k, v]) => `${k} ${v}(${((v / m.reduce((s, [, n]) => s + n, 0)) * 100).toFixed(0)}%)`).join('  ');
console.log(`== 真机输入类型账（${FILE}${PID ? ' pid=' + PID : ''}）：${rows.length} 行判据，按图标去重 ${uniq.length} 张 ==`);
console.log('   最外层 drawable：' + pct(tally('outer')));
console.log('   前景层 drawable（非自适应的那批没有 fg=，记成 -）：' + pct(tally('fg')));
console.log('   adaptive 标记：' + pct(tally('adaptive')));
const sz = new Map();
for (const r of uniq) sz.set(r.intr, (sz.get(r.intr) || 0) + 1);
console.log('   intrinsic 尺寸 top：' + [...sz].sort((a, b) => b[1] - a[1]).slice(0, 8).map(([k, v]) => `${k}×${v}`).join('  '));
// 老式（非自适应）那批的 fg 一定是 `-`；自适应里 fg 是位图 vs 矢量决定"换边长会不会糊"
const legacy = uniq.filter(r => r.adaptive === 'false');
console.log(`   非自适应 ${legacy.length} 张：${legacy.map(r => r.who.split('/').pop() + '=' + r.outer).slice(0, 10).join('  ')}`);
const vec = uniq.filter(r => r.fg === 'VectorDrawable').length;
const bmp = uniq.filter(r => r.fg === 'BitmapDrawable').length;
console.log(`   ⇒ 换画布时"重新光栅化(矢量 ${vec} 张)"与"缩放同一张位图(位图 ${bmp} 张)"的比例：${vec}:${bmp}`);
