// 归因那道"384² 掩码 → 256² 上屏"的落差：拿我们真交出去的那张 .a8，按几种重采样各降一次，
// 看哪一种能造出桌面上屏那一张（有墨/全实/均α 三个数一起对）。
// 用法：node _harness/_a8down.cjs <我们交的.a8> <上屏的.gray> [--selftest]
//
// ⚠ **上一版的"盒式平均"是错的**：它把 `floor(y0)..ceil(y1)` 里**整格数**当分母，
// 于是 1.5 格的窗口被按 2×2=4 格求平均 —— 面积不守恒，把均α 从 13.1 算成 3.8。
// 现在改成**面积加权**（每个源格按与目标格的重叠长度计权），并带一条自证：
// **一张均匀 50% 灰的图降采样后均α 必须还是 50%** —— 不守恒的台子不许出数。
const fs = require('fs');
const [, , a8p, grayp, ...rest] = process.argv;

/** 目标边长 n 时，源边长 S 的每一格对该目标格的重叠权重（ separable：x、y 同一条表）。 */
function weights(S, n, t) {
  const c = (t + 0.5) * S / n - 0.5;          // 目标格中心落到源坐标
  const lo = c - S / n / 2, hi = c + S / n / 2;
  const w = [];
  for (let i = Math.max(0, Math.floor(lo + 0.5)); i <= Math.min(S - 1, Math.ceil(hi - 0.5)); i++) {
    const ov = Math.min(hi, i + 0.5) - Math.max(lo, i - 0.5);
    if (ov > 0) w.push([i, ov]);
  }
  return w;
}
function areaDown(src, S, n) {
  const out = new Uint8Array(n * n);
  const rows = Array.from({ length: n }, (_, t) => weights(S, n, t));
  for (let ty = 0; ty < n; ty++) {
    const wy = rows[ty], sy = wy.reduce((s, a) => s + a[1], 0);
    for (let tx = 0; tx < n; tx++) {
      const wx = rows[tx], sx = wx.reduce((s, a) => s + a[1], 0);
      let sum = 0;
      for (const [iy, fy] of wy) for (const [ix, fx] of wx) sum += src[iy * S + ix] * fx * fy;
      out[ty * n + tx] = Math.round(sum / (sy * sx));
    }
  }
  return out;
}
function nearDown(src, S, n) {
  const out = new Uint8Array(n * n);
  for (let ty = 0; ty < n; ty++) for (let tx = 0; tx < n; tx++) {
    const sy = Math.min(S - 1, Math.round((ty + 0.5) * S / n - 0.5));
    const sx = Math.min(S - 1, Math.round((tx + 0.5) * S / n - 0.5));
    out[ty * n + tx] = src[sy * S + sx];
  }
  return out;
}
const stat = (a) => {
  let ink = 0, sol = 0, mass = 0;
  for (const v of a) { if (v > 0) ink++; if (v >= 255) sol++; mass += v; }
  return { ink: ink * 100 / a.length, sol: sol * 100 / a.length, mean: mass / a.length };
};
const fmt = (name, s) => `${name.padEnd(24)} 有墨${s.ink.toFixed(1).padStart(5)} 全实${s.sol.toFixed(1).padStart(5)} 均α${s.mean.toFixed(1).padStart(6)}`;

if (rest.includes('--selftest')) {
  // 守恒自证：均匀图、半图两种，降采样后总墨量不许变（面积加权必须做到，上一版做不到）
  const S = 384, n = 256;
  const flat = new Uint8Array(S * S).fill(128);
  const half = new Uint8Array(S * S); for (let i = 0; i < S * S; i++) half[i] = (i % S) < S / 2 ? 255 : 0;
  let bad = 0;
  const chk = (name, got, want, tol) => { const ok = Math.abs(got - want) <= tol; if (!ok) bad++;
    console.log(`  ${ok ? '✓' : '✗'} ${name} → ${got.toFixed(2)}（要 ${want}${tol ? `±${tol}` : ''}）`); };
  console.log('== 台子自证：面积加权必须守恒总墨量 ==');
  const a = stat(areaDown(flat, S, n)), b = stat(areaDown(half, S, n));
  chk('均匀 128 → 均α', a.mean, 128, 0.01);
  chk('均匀 128 → 有墨%', a.ink, 100, 0.01);
  chk('左半全 255 → 均α 约一半', stat(areaDown(half, S, n)).mean, 127.5, 1.0);
  console.log(bad ? `!! ${bad} 条不成立 —— 这一栏不许出数` : 'OK 守恒成立，下面的均α 可以引用');
  process.exit(bad ? 1 : 0);
}

const raw = fs.readFileSync(a8p);
const body = raw.length - Math.round(Math.sqrt(raw.length)) ** 2 === 8 ? raw.subarray(8) : raw;   // 去掉 8 字节头
const src = Uint8Array.from(body);
const S = Math.round(Math.sqrt(src.length));
const dst = Uint8Array.from(fs.readFileSync(grayp));
const D = Math.round(Math.sqrt(dst.length));
console.log(`源 = 我们交出去的 ${S}² 掩码；对照 = 桌面上屏的 ${D}²`);
console.log(fmt('★ 上屏真值', stat(dst)));
console.log(fmt('源原样（384²）', stat(src)));
console.log(fmt('最近邻 →256', stat(nearDown(src, S, D))));
console.log(fmt('面积加权 →256', stat(areaDown(src, S, D))));

// 最后一问：上屏那一张**是不是我们给的那张的形状**？
// 若是 ⇒ 差的是浓度（桌面乘了一道东西）；若不是 ⇒ 桌面根本没用它（另一条案）。
const dn = areaDown(src, S, D);
let both = 0, ours = 0, theirs = 0;
for (let i = 0; i < D * D; i++) {
  const a = dn[i] >= 128, b = dst[i] >= 128;
  if (a) ours++; if (b) theirs++; if (a && b) both++;
}
const ratio = (ours && theirs) ? (both / (ours + theirs - both)) : 0;
let ms = 0, msN = 0;
for (let i = 0; i < D * D; i++) if (dn[i] >= 128 && dst[i] > 0) { ms += Math.abs(dn[i] - dst[i]); msN++; }
console.log(`\n形状 IoU(α≥128) = ${ratio.toFixed(3)}   我们 ${(ours * 100 / (D * D)).toFixed(1)}% vs 上屏 ${(theirs * 100 / (D * D)).toFixed(1)}%`);
console.log(`重叠格里 |Δα| 均值≈${msN ? (ms / msN).toFixed(0) : '-'}  ← 形状对得上而这一栏很大 = 浓度被乘过`);
process.exit(ratio >= 0.85 ? 0 : 3);
