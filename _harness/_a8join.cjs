// **内容配内容**：把设备上我们缓存的那张掩码（`<键>_<边长>.a8`）与桌面库里真正上屏的那张
// （`mono_icon`，256² ALPHA_8）配上对 —— 这是分母那半缺的"另一根轴"。
//
// 为什么这根轴成立：桌面存的就是我们交出去那张的重采样，所以**同一支图标两边形状相同**。
//   （反过来也成立并且有用：配不上的那些，正是"上屏那张不是我们交的"—— 这一族今天已经撞到过一次，
//    cainiao 的 `mono_icon` 与我们的交付 IoU 0.001。）
// 为什么不用签名硬凑：`键=` 是**纯内容哈希**（`MonoKey.keyOf(像素, under, 边长, 三个旗)`，里面没有身份），
//   所以一把键天然可以属于多个组件；而库里又没有资源名列 ⇒ 只能拿像素本身当轴。
//
// 做法：两边都下采样到 256²、再各压成 **16×16 的均值签名**（256 个数），
//   最近邻 = 面积加权绝对差；同时报**第二名与第一名的差**（差太小 = 认不出，别硬认）。
// 正对照：日志里 164 个 `pm:` 组件是**当场带 `键=`** 的（确定归属），
//   拿这根轴去配它们，看内容配出来的组件**是不是同一个** —— 不对就说明这台仪器没牙。
//
// 跑法：`node _a8join.cjs <缓存目录> <库里 .gray 目录> [日志里的 delivery.tsv]`
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const CACHE = process.argv[2] || 'D:/tmp/a8live';
const DBDIR = process.argv[3] || 'D:/tmp/mono_v21';
const DELIV = process.argv[4] || 'D:/tmp/denom/delivery.tsv';

const walk = d => fs.readdirSync(d, { withFileTypes: true }).flatMap(e =>
  e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]);

/** 读一张 ALPHA_8：库里是裸 256²；我们是 8 字节头 + edge²。返回边长 + 灰度数组。 */
function readA8(file, skip) {
  const b = fs.readFileSync(file);
  const body = b.subarray(skip);
  const edge = Math.round(Math.sqrt(body.length));
  if (edge * edge !== body.length) return null;
  return { edge, px: body };
}
/** 面积加权下采样到 256²（与 `_a8down.cjs` 同一条守恒式：均匀图必须还是那个值）。 */
function down256(px, edge) {
  const out = new Float64Array(65536);
  for (let y = 0; y < 256; y++) {
    const y0 = Math.floor(y * edge / 256), y1 = Math.max(y0 + 1, Math.floor((y + 1) * edge / 256));
    for (let x = 0; x < 256; x++) {
      const x0 = Math.floor(x * edge / 256), x1 = Math.max(x0 + 1, Math.floor((x + 1) * edge / 256));
      let s = 0;
      for (let yy = y0; yy < y1; yy++) for (let xx = x0; xx < x1; xx++) s += px[yy * edge + xx];
      out[y * 256 + x] = s / ((y1 - y0) * (x1 - x0));
    }
  }
  return out;
}
/** 16×16 均值签名（0..1）。 */
function sig(px256) {
  const s = new Float64Array(256);
  for (let by = 0; by < 16; by++) for (let bx = 0; bx < 16; bx++) {
    let acc = 0;
    for (let y = by * 16; y < by * 16 + 16; y++) for (let x = bx * 16; x < bx * 16 + 16; x++) acc += px256[y * 256 + x];
    s[by * 16 + bx] = acc / 256 / 255;
  }
  return s;
}
const dist = (a, b) => { let s = 0; for (let i = 0; i < 256; i++) s += Math.abs(a[i] - b[i]); return s / 256; };

// ---- 自检：这台仪器有没有牙 ----
{
  const flat = new Float64Array(65536).fill(128);
  const s = sig(flat);
  const okFlat = Math.abs(s[0] - 128 / 255) < 1e-9;
  const a = new Float64Array(65536), b = new Float64Array(65536);
  for (let i = 0; i < 65536; i++) { a[i] = i % 256 < 128 ? 255 : 0; b[i] = i % 256 < 64 ? 255 : 0; }
  const sep = dist(sig(a), sig(b)), same = dist(sig(a), sig(a));
  console.log(`自检：均匀 128 下采样后还是 128 = ${okFlat ? '✓' : '✗'}；` +
    `同图距离 ${same.toFixed(4)}（要 0）、两种竖条距离 ${sep.toFixed(4)}（要明显>0）`);
  if (!okFlat || same !== 0 || sep < 0.05) { console.error('!! 签名本身不可信，下面的配对照全部不算'); process.exit(2); }
}

// ---- 库侧 ----
const db = [];
for (const f of walk(DBDIR).filter(x => x.endsWith('.gray'))) {
  const r = readA8(f, 0); if (!r || r.edge !== 256) continue;
  db.push({ name: path.basename(f, '.gray'), s: sig(down256(r.px, 256)) });
}
// ---- 缓存侧 ----
const cache = [];
for (const f of walk(CACHE).filter(x => x.endsWith('.a8'))) {
  const r = readA8(f, 8); if (!r) continue;
  const key = path.basename(f).split('_')[0];
  cache.push({ file: f, key, edge: r.edge, s: sig(down256(r.px, r.edge)), n: (() => { let ink = 0, m = 0; for (const v of r.px) { if (v > 0) ink++; m += v } return { ink: ink * 100 / r.edge / r.edge, mean: m / r.edge / r.edge } })() });
}
console.log(`库侧 ${db.length} 张 256²；缓存侧 ${cache.length} 张（边长 ${[...new Set(cache.map(c => c.edge))].join("/")}）`);

// ---- 日志给的确定归属（正对照的答卷）----
const truth = new Map();
if (fs.existsSync(DELIV)) for (const l of fs.readFileSync(DELIV, 'utf8').trim().split(/\r?\n/).slice(1)) {
  const c = l.split('\t'); if (c[2]) truth.set(c[2] + '_' + c[3], c[0]);   // 键_edge → component
}
let tried = 0, agreed = 0; const wrong = [];
const rows = [];
for (const c of cache) {
  let best = null, second = null;
  for (const d of db) {
    const v = dist(c.s, d.s);
    if (!best || v < best.v) { second = best; best = { d, v }; }
    else if (!second || v < second.v) second = { d, v };
  }
  const gap = second ? second.v - best.v : 1;
  rows.push({ key: c.key, edge: c.edge, ink: c.n.ink.toFixed(1), mean: c.n.mean.toFixed(1), to: best.d.name, d: best.v.toFixed(4), gap: gap.toFixed(4) });
  const t = truth.get(c.key + '_' + c.edge);
  if (t) {
    tried++;
    const hit = best.d.name.startsWith(t.replace(/[^\w.]+/g, '_').slice(0, 40));
    if (hit) agreed++; else wrong.push(`   日志=${t.slice(0, 40)}  内容配到=${best.d.name.slice(0, 40)}  距离 ${best.v.toFixed(4)} 次近差 ${gap.toFixed(4)}`);
  }
}
console.log(`\n### 正对照（日志当场带 键= 的那些，内容配出来的组件是不是同一个）：` +
  `可比 ${tried} 把，**配中 ${agreed}**${tried ? `（${(agreed * 100 / tried).toFixed(0)}%）` : '（一把都没有 = 这台仪器没有对照，下面的数都别用）'}`);
wrong.slice(0, 8).forEach(l => console.log(l));

// ---- 全表 ----
const conf = rows.filter(r => r.d < 0.02 && r.gap > 0.01);
console.log(`\n### 缓存 → 屏上组件：${rows.length} 把键里，**有把握配上**（距离<0.02 且与次近差>0.01）= ${conf.length}`);
for (const r of [...rows].sort((a, b) => a.d - b.d).slice(0, 10))
  console.log(`   d=${r.d} 差=${r.gap} 边${r.edge} 有墨 ${r.ink}% 均α ${r.mean}  ${r.key} → ${r.to.slice(0, 42)}`);
const amb = rows.filter(r => !(r.d < 0.02 && r.gap > 0.01));
console.log(`   …其余 ${amb.length} 把配不上或不确定（**这些就是"上屏那张不是我们交的"或签名分不开**）`);
fs.writeFileSync('D:/tmp/a8join.tsv', 'key\tedge\tink\tmean\tto\tdist\tgap\n' +
  rows.map(r => [r.key, r.edge, r.ink, r.mean, r.to, r.d, r.gap].join('\t')).join('\n') + '\n');
console.log('全表 → D:/tmp/a8join.tsv');
