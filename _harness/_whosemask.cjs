// 屏上那 303 张 mono_icon 里，有几张**对得上我们真交出去的掩码**？
// 不靠名字配（我们的标签是 drawable 资源名，库的行是 componentName，两根轴配不上）—— 用**内容签名**：
// 每张图缩成 8×8 的均值格（64 个数），比归一化距离。
// ⚠ 阳性对照：cainiao `shortcuts_identity_code` 已知与它自己那条 键 的 IoU = 0.001（第十三段），
//   所以它**必须**落在"找不到近亲"那一侧；它要是被配上，就是这个脚本坏了。
// 用法：node _harness/_whosemask.cjs <a8目录> <mono目录> [--top=5]
const fs = require('fs'), path = require('path');
const A8 = process.argv[2], MONO = process.argv[3];
const G = 8;                                     // 签名网格

function readA8(f) {
  const b = fs.readFileSync(f);
  const body = b.length - Math.round(Math.sqrt(b.length)) ** 2 === 8 ? b.subarray(8) : b;   // 去 8 字节头
  return { side: Math.round(Math.sqrt(body.length)), a: Uint8Array.from(body) };
}
/** 面积加权降到 n×n（与 _a8down 同一套：必须守恒，否则签名会偏） */
function down(src, S, n) {
  const w = [];
  for (let t = 0; t < n; t++) {
    const c = (t + 0.5) * S / n - 0.5, lo = c - S / n / 2, hi = c + S / n / 2, list = [];
    for (let i = Math.max(0, Math.floor(lo + 0.5)); i <= Math.min(S - 1, Math.ceil(hi - 0.5)); i++) {
      const ov = Math.min(hi, i + 0.5) - Math.max(lo, i - 0.5);
      if (ov > 0) list.push([i, ov]);
    }
    w.push(list);
  }
  const out = new Float64Array(n * n);
  for (let ty = 0; ty < n; ty++) {
    let sy = 0; for (const [, f] of w[ty]) sy += f;
    for (let tx = 0; tx < n; tx++) {
      let sx = 0; for (const [, f] of w[tx]) sx += f;
      let sum = 0;
      for (const [iy, fy] of w[ty]) for (const [ix, fx] of w[tx]) sum += src[iy * S + ix] * fx * fy;
      out[ty * n + tx] = sum / (sy * sx);
    }
  }
  return out;
}
/** 签名 = 8×8 均值格，按总量归一（这样"整张被乘了系数"不影响形状判别） */
function sig(a, side) {
  const g = new Float64Array(G * G);
  for (let y = 0; y < side; y++) for (let x = 0; x < side; x++) {
    g[((y * G / side) | 0) * G + ((x * G / side) | 0)] += a[y * side + x];
  }
  let tot = 0; for (const v of g) tot += v;
  return tot > 0 ? Array.from(g, (v) => v / tot) : Array.from(g);
}
const dist = (p, q) => Math.sqrt(p.reduce((s, v, i) => s + (v - q[i]) ** 2, 0));

const masks = [];
for (const d of fs.readdirSync(A8)) {
  const dir = path.join(A8, d);
  if (!fs.statSync(dir).isDirectory()) continue;
  for (const f of fs.readdirSync(dir)) {
    if (!f.endsWith('_384.a8')) continue;
    const { side, a } = readA8(path.join(dir, f));
    const s = sig(a, side);
    if (s.every((v) => v === 0)) continue;
    masks.push({ name: f.replace(/\.a8$/, ''), key: f.split('_')[0], s, ink: a.filter((v) => v > 0).length * 100 / (side * side) });
  }
}
const rows = [];
for (const f of fs.readdirSync(MONO)) {
  if (!f.endsWith('.gray')) continue;
  const { side, a } = readA8(path.join(MONO, f));
  const s = sig(a, side);
  if (s.every((v) => v === 0)) continue;
  const best = masks.map((m) => ({ n: m.key, d: dist(s, m.s) })).sort((x, y) => x.d - y.d);
  rows.push({ f, best: best[0], second: best[1], ink: a.filter((v) => v > 0).length * 100 / (side * side) });
}
rows.sort((x, y) => y.best.d - x.best.d);
const NEAR = 0.06;
const near = rows.filter((r) => r.best.d <= NEAR).length;
console.log(`我们交出去的掩码 ${masks.length} 张；屏上独立位图 ${rows.length} 张；近亲判据 签名距离 ≤ ${NEAR}`);
console.log(`对上 = ${near} 张，对不上 = ${rows.length - near} 张（${((rows.length - near) * 100 / rows.length).toFixed(0)}%）`);
console.log(`\n最不像的 ${+(process.argv.find(a => a.startsWith('--top=')) || '--top=5').split('=')[1]} 张：`);
for (const r of rows.slice(0, 10)) {
  console.log(`  距离${r.best.d.toFixed(3)} 次近${r.second.d.toFixed(3)} 上屏有墨${r.ink.toFixed(1).padStart(4)}%  最近的是 ${r.best.n.slice(0, 16)}  ${r.f.slice(0, 52)}`);
}
const cn = rows.find((r) => r.f.includes('cainiao.wireless_shortcuts_identity_code'));
console.log(cn ? `\n阳性对照 cainiao identity_code：最近距离 ${cn.best.d.toFixed(3)} → ${cn.best.d > NEAR ? '落在"对不上"侧 ✓（与第十三段 IoU 0.001 一致）' : '⚠ 被配上了 = 这个脚本坏了，别看上面的数'}` : '!! 对照那张不在目录里');
