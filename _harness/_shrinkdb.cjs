// 变异工具（正对照用）：把库里某个包的 `mono_icon` **整体缩小**一圈，别的都不动。
// 为什么要有它：新闸必须"改坏就响"才算闸；而这道闸吃的是设备产物，改不了离线代码来制造坏 ——
//   那就**改产物**（真实形状、真实画布），证明这把尺真的看着剪影的大小。
// 用法：node _harness/_shrinkdb.cjs <源库> <输出库> <包名> [缩比=0.6]
const fs = require('fs');
const { DatabaseSync } = require('node:sqlite');
const [src, dst, pkg, sArg] = process.argv.slice(2);
const s = sArg ? +sArg : 0.6;
fs.copyFileSync(src, dst);
const d = new DatabaseSync(dst);
const rows = d.prepare('SELECT componentName c, mono_icon m FROM icons').all();
const hit = rows.filter((r) => r.c.split('/')[0] === pkg && r.m);
if (!hit.length) { console.log(`!! 库里没有 ${pkg} 的单色行 —— 这次变异不会命中任何东西`); process.exit(1); }
const blob = Buffer.from(hit[0].m);
const n = Math.round(Math.sqrt(blob.length));
if (n * n !== blob.length) { console.log('!! 那张不是正方形 ALPHA_8'); process.exit(1); }
const out = Buffer.alloc(n * n);
const c = (n - 1) / 2;
for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
  // 以中心为锚**反向采样**：目标格 (x,y) ← 源格 中心 + (偏移/s)，越界留空 ⇒ 墨被缩到中间 s 倍
  const sx = Math.round(c + (x - c) / s), sy = Math.round(c + (y - c) / s);
  if (sx < 0 || sy < 0 || sx >= n || sy >= n) continue;
  out[y * n + x] = blob[sy * n + sx];
}
const upd = d.prepare('UPDATE icons SET mono_icon = ? WHERE componentName = ?');
let touched = 0;
for (const r of hit) { upd.run(out, r.c); touched++; }
d.close();
console.log(`已把 ${pkg} 的 ${touched} 行单色缩到 ${s}×（外接框应当同比变小）→ ${dst}`);
