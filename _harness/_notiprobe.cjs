// 探一把：框架那批 `stat_sys_*` 图标**到底有没有透明通道**，墨该按哪一尺算。
// 为什么要探这个：`_noticink.cjs` 第一版对 77 张 PNG 一律报 1.000 —— 一个恒等值不是分布，是尺没刻度
//   （本仓那一族：0 命中/恒等值先疑仪器）。两种可能：① 这些图整张不透明（墨=暗像素，不是 alpha）；
//   ② 我的 alpha 取样取错了字节。这一台子把两件事分开量。
// 跑法：node _notiprobe.cjs <目录>   （目录里是解出来的 res/drawable-*/stat_sys_*.png）
const fs = require('fs');
const path = require('path');
const { png } = require('./pngread.cjs');
const dir = process.argv[2];
if (!dir) { console.error('用法: node _notiprobe.cjs <解出来的 res 目录>'); process.exit(2); }
const walk = d => fs.readdirSync(d, { withFileTypes: true }).flatMap(e =>
  e.isDirectory() ? walk(path.join(d, e.name)) : (e.name.startsWith('stat_') && e.name.endsWith('.png') ? [path.join(d, e.name)] : []));
const files = walk(dir);
if (!files.length) { console.error(`!! ${dir} 里没有 stat_*.png —— 这一台子什么都量不到`); process.exit(9); }
const rows = [];
for (const f of files) {
  let p; try { p = png(f); } catch (e) { console.log(`  跳过 ${path.basename(f)}：${e.message.slice(0, 48)}`); continue; }
  const n = p.w * p.h, ch = p.ch;
  let tr = 0, solid = 0, solidDark = 0, opaque = 0;
  for (let i = 0; i < n; i++) {
    const a = ch >= 4 ? p.data[i * ch + 3] : 255;
    const lum = (p.data[i * ch] * 299 + p.data[i * ch + 1] * 587 + p.data[i * ch + 2] * 114) / 1000;
    if (a === 0) tr++;
    if (a >= 128) { solid++; opaque++; if (lum < 128) solidDark++; }
  }
  rows.push({ name: path.basename(f), ch, w: p.w, h: p.h, trPct: 100 * tr / n, solidPct: 100 * solid / n, darkPct: 100 * solidDark / n });
}
const hasAlphaVar = rows.filter(r => r.trPct > 1);
console.log(`n=${rows.length} 张 stat_*.png`);
console.log(`  带真实透明的（透明像素 >1%）：**${hasAlphaVar.length}**`);
console.log(`  整张不透明的：${rows.length - hasAlphaVar.length}  ← 这些的"墨"只能是暗像素，alpha 尺量不出来`);
const pct = a => a.length ? `${(100 * a.length / rows.length).toFixed(0)}%` : '0%';
console.log(`  通道数分布 ${Object.entries(rows.reduce((m, r) => (m[r.ch] = (m[r.ch] || 0) + 1, m), {})).map(([k, v]) => k + ':' + v).join(' ')}`);
console.log(`  画布尺寸分布 ${Object.entries(rows.reduce((m, r) => { const k = r.w + 'x' + r.h; m[k] = (m[k] || 0) + 1; return m; }, {})).sort((a, b) => b[1] - a[1]).slice(0, 5).map(([k, v]) => k + ':' + v).join(' ')}`);
for (const r of rows.slice(0, 6))
  console.log(`   例 ${r.name.padEnd(40)} ${r.w}x${r.h} ch=${r.ch} 透明 ${r.trPct.toFixed(0)}% 实心 ${r.solidPct.toFixed(0)}% 实心且暗 ${r.darkPct.toFixed(0)}%`);
console.log(`\n  ⇒ 墨的取法：透明占比中位 ${(() => { const v = rows.map(r => r.trPct).sort((a, b) => a - b); return v[Math.floor(v.length / 2)].toFixed(0); })()}%` +
  `；若这一族普遍"整张不透明"，尺就该换成**非白**（亮度 <128），而不是 alpha`);
