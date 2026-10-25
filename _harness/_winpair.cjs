// 真机两本账**几何配对**（2026-10-25）：彩色 `裁圆外框` 的窗口 vs 单色 `Mono: diag` 的 `几何=`。
// 只对**非自适应且非细环满幅**：窗口统一后两者应逐张同 zoom；自适应是展开坐标（DeckKt 等价）、
// 细环满幅单色故意走 winMeasured，都单列跳过。
//
// 用法：node _winpair.cjs /tmp/opticon.log
// 自证：解析出的彩色窗 / 单色冷 diag / 配对数 / 跳过数都要打出来；配对 0 张 = 仪器没工作，非零退出。
const fs = require('fs');
const L = fs.readFileSync(process.argv[2] || '/tmp/opticon.log', 'utf8').split(/\r?\n/);
const colored = new Map(); // label -> {zoom, dir, raw}
const mono = new Map();    // label -> {zoom, dir, adaptive, ring? , raw}
const shortRing = new Set();
let cLines = 0, mLines = 0;
for (const line of L) {
  let m = line.match(/裁圆外框: (.+?) (?:画布 |量一张|快捷方式)/);
  if (m) {
    cLines++;
    const label = m[1];
    if (line.includes('快捷方式：细环 + 图案在圆内')) { shortRing.add(label); continue; }
    if (line.includes('快捷方式：图案碰到圆外')) continue;
    const z = line.match(/(缩|放) ([\d.]+)×/);
    if (z) colored.set(label, { dir: z[1], zoom: parseFloat(z[2]), raw: line.trim() });
    else if (line.includes('量一张') && line.includes('满幅（不放大）'))
      colored.set(label, { dir: '满', zoom: 0, raw: line.trim() });
    continue;
  }
  m = line.match(/Mono: diag#\d+ .*? 键=\S+ (\S+) \S*Drawable .*? adaptive=(true|false).*? 几何=(满幅|(?:缩|放)([\d.]+)×)/);
  if (m) {
    mLines++;
    const label = m[1];
    mono.set(label, {
      adaptive: m[2] === 'true',
      dir: m[3] === '满幅' ? '满' : m[3][0],
      zoom: m[4] ? parseFloat(m[4]) : 0,
      raw: line.trim(),
    });
  }
}
const keys = [...colored.keys()].filter(k => mono.has(k));
let pairs = 0, cmp = 0, bad = 0, skipped = 0, shown = 0;
const badRows = [];
for (const k of keys) {
  const c = colored.get(k), mo = mono.get(k);
  pairs++;
  if (mo.adaptive || shortRing.has(k)) { skipped++; continue; }
  cmp++;
  const same = c.dir === (mo.dir === '满' ? '满' : mo.dir) && Math.abs(c.zoom - mo.zoom) <= 0.02;
  if (!same) { bad++; badRows.push([k, c, mo]); }
  else if (shown < 2) { console.log('样例配对 OK:', k, `彩=${c.dir}${c.zoom}×`, `单=${mo.dir}${mo.zoom}×`); shown++; }
}
console.log(`彩色窗口行 ${cLines} / 单色冷 diag ${mLines} / 标签配对 ${pairs} / 参与比较 ${cmp} / 跳过 ${skipped}（自适应或细环）/ 不一致 ${bad}`);
for (const [k, c, mo] of badRows.slice(0, 10)) {
  console.log(`!! ${k}\n   彩 ${c.dir}${c.zoom}×  ${c.raw}\n   单 ${mo.dir}${mo.zoom}×  ${mo.raw}`);
}
if (pairs === 0) { console.log('!! 配对 0 张 —— 仪器没工作，不能当通过'); process.exit(2); }
if (bad > 0) process.exit(1);
