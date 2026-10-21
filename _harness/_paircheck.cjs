// 对"我们交过货"的那批组件逐个做**内容比对**：我们交的 384² 掩码 vs 桌面上屏那一张 256²。
// 与 _whosemask 的区别：这里**不猜配对** —— 键 直接来自日志（`diag … 键=K <label>`），
// 组件名与库的行同形（`pm:包名/id` ↔ `包名/id`），所以每一对都是**确定的**一对。
// 用法：node _harness/_paircheck.cjs <logcat> <a8目录> <mono目录>
const fs = require('fs'), path = require('path');
const [, , logp, A8, MONO] = process.argv;

function readGray(f) { const b = fs.readFileSync(f); return { side: Math.round(Math.sqrt(b.length)), a: Uint8Array.from(b) }; }
function readA8(f) { const b = fs.readFileSync(f); const off = b.length - Math.round(Math.sqrt(b.length)) ** 2 === 8 ? 8 : 0;
  const a = Uint8Array.from(b.subarray(off)); return { side: Math.round(Math.sqrt(a.length)), a }; }
/** 面积加权降采样（守恒版，与 _a8down 同一套） */
function down(a, S, n) {
  const w = Array.from({ length: n }, (_, t) => {
    const c = (t + 0.5) * S / n - 0.5, lo = c - S / n / 2, hi = c + S / n / 2, list = [];
    for (let i = Math.max(0, Math.floor(lo + 0.5)); i <= Math.min(S - 1, Math.ceil(hi - 0.5)); i++) {
      const ov = Math.min(hi, i + 0.5) - Math.max(lo, i - 0.5); if (ov > 0) list.push([i, ov]);
    } return list;
  });
  const out = new Uint8Array(n * n);
  for (let ty = 0; ty < n; ty++) { const sy = w[ty].reduce((s, x) => s + x[1], 0);
    for (let tx = 0; tx < n; tx++) { const sx = w[tx].reduce((s, x) => s + x[1], 0); let sum = 0;
      for (const [iy, fy] of w[ty]) for (const [ix, fx] of w[tx]) sum += a[iy * S + ix] * fx * fy;
      out[ty * n + tx] = Math.round(sum / (sy * sx)); } }
  return out;
}
const thr = (a) => { const s = Array.from(a).sort((x, y) => x - y); return Math.max(24, s[Math.floor(s.length * 0.995)] * 0.4); };
const iou = (p, q, n) => { const tp = thr(p), tq = thr(q); let b = 0, o = 0; for (let i = 0; i < n * n; i++) { const x = p[i] >= tp, y = q[i] >= tq; if (x || y) { o++; if (x && y) b++; } } return o ? b / o : 1; };

const a8dir = fs.readdirSync(A8).map((d) => path.join(A8, d)).find((d) => fs.statSync(d).isDirectory());
const lines = fs.readFileSync(logp, 'utf8').split(/\r?\n/);
const pairs = new Map(); const keysOf = new Map();
for (const l of lines) {
  const m = l.match(/diag#\d+ edge=(\d+)\s+(?:sig=\S+\s+)?键=([0-9a-f]{6,})\s+(pm:\S+)/);
  if (m) {
    pairs.set(m[2], { comp: m[3].slice(3), edge: +m[1] });
    const kk = m[3].slice(3); if (!keysOf.has(kk)) keysOf.set(kk, new Set()); keysOf.get(kk).add(m[2]);
  }
}
const multi = [...keysOf.entries()].filter(([, s]) => s.size > 1);
console.log(`组件对多个键的：${multi.length} 个（这些**配不准，剔掉**）`); multi.slice(0,6).forEach(([c,s]) => console.log(`  ${c}  键数=${s.size}`));
const hist = { hi: 0, mid: 0, lo: 0, nomask: 0, nogray: 0 }; const rows = [];
for (const [key, r] of pairs) {
  if (keysOf.get(r.comp).size > 1) continue;
  const mf = path.join(a8dir, `${key}_${r.edge}.a8`);
  if (!fs.existsSync(mf)) { hist.nomask++; continue; }
  const gf = path.join(MONO, r.comp.replace(/[^\w.]+/g, '_') + '.gray');
  if (!fs.existsSync(gf)) { hist.nogray++; continue; }
  const ours = readA8(mf), on = readGray(gf);
  const d = down(ours.a, ours.side, on.side);
  const v = iou(d, on.a, on.side);
  (v >= 0.85 ? (hist.hi++, rows) : v >= 0.5 ? (hist.mid++, rows) : (hist.lo++, rows)).push(`  IoU ${v.toFixed(3)}  ${r.comp.padEnd(50)} 键=${key}`);
}
console.log(`日志里"我们交过货"且名字可配组件的：${pairs.size} 个`);
console.log(`  形状对得上（IoU≥0.85）= ${hist.hi}   半对（0.5~0.85）= ${hist.mid}   **对不上（<0.5）= ${hist.lo}**`);
console.log(`  跳过：缓存里没这张 .a8 = ${hist.nomask}，库里没这一行 = ${hist.nogray}`);
rows.sort().slice(0, 24).forEach((s) => console.log(s));
if (hist.lo > 0) console.log(`\n🔴 对不上 ${hist.lo} 个 ⇒ cainiao 那种**不是个案**：桌面为这批组件存的 mono_icon 另有出处。`);
else if (hist.hi > 0) console.log('\n⇒ 至少这些是通的；cainiao 那种是个案。');
