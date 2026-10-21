// 真机上**跨 canvas 的全部 19 对**交付掩码逐格比 —— 把"只有 2 张拉下来看过"这本欠账清掉。
//
// 三样对照一起跑，缺一样这台子就不算立起来：
//  ① **自对照**（同一张文件跟自己比）必须 IoU=1.000、|Δα| 中位 0 —— 不然读数是加载器造的。
//  ② **正对照**（两张**不同图标**、同边长）—— 给出"画得不一样"长什么样，否则 0.97 无从解释。
//  ③ 被测对（同一图标、192 vs 384）。
// ⚠ 键必须带 edge：`sig` 是 8×8 降采样指纹，跨尺寸会撞车（本轮真撞上过）。
// 用法：node _a8sweep.cjs [日志] [pid]
const fs = require('fs');
const path = require('path');
const { compare } = require('./_a8cmp.cjs');
const FILE = process.argv[2] || '_wc2.txt';
const PID = process.argv[3] || '25686';
const DIR = '_a8/all';

const g = (s, k) => ((s.match(new RegExp('(?:^| )' + k + '=([^ ]*)')) || [])[1] || '-');
const by = new Map();
for (const l of fs.readFileSync(FILE, 'utf8').split('\n')) {
  if (!/Mono: diag#/.test(l) || !l.includes('几何=') || l.split(/\s+/)[2] !== PID) continue;
  const m = l.match(/键=\S+ (\S+)\s/);
  if (!m || !m[1].includes('/')) continue;
  const e = g(l, 'edge'), k = g(l, '键');
  if (!by.has(m[1])) by.set(m[1], new Map());
  if (!by.get(m[1]).has(e)) by.get(m[1]).set(e, k);
}
const f = (k, e) => path.join(DIR, `${k}_${e}.a8`);
const two = [...by].filter(([, m]) => m.size >= 2);
console.log(`== pid ${PID}：${by.size} 个有判据行的图标里，在 ≥2 种 edge 上重算过的 ${two.length} 个 ==`);
if (two.length === 0) { console.log('!! 没样本，这台子不开火'); process.exit(1); }

// ① 自对照
let selfOk = 0;
for (const [, m] of two) for (const [e, k] of m) {
  const p = f(k, e);
  if (!fs.existsSync(p)) continue;
  const r = compare(p, p);
  if (r.iou === 1 && r.dMed === 0) selfOk++;
  else console.log(`  !! 自对照不成立：${k}_${e} IoU=${r.iou.toFixed(3)} |Δα|中位=${r.dMed} —— 加载器有问题，下面全部作废`);
}
console.log(`  ① 自对照 ${selfOk} 对全部 IoU=1.000 / |Δα| 中位 0`);
if (selfOk === 0) process.exit(1);

const rows = [];
for (const [name, m] of two) {
  const es = [...m.entries()].sort((x, y) => +x[0] - +y[0]);
  const [se, sk] = es[0], [le, lk] = es[es.length - 1];
  const pa = f(sk, se), pb = f(lk, le);
  const ok = fs.existsSync(pa) && fs.existsSync(pb);
  rows.push({ name, se, le, ok, r: ok ? compare(pa, pb) : null });
}
const have = rows.filter(r => r.ok);
console.log(`  两边掩码都还在磁盘缓存里的 ${have.length}/${rows.length}（缓存按 48 MB 淘汰，扔掉的算**没对照**）`);

// ② 正对照：拿被测那批的**大边长那张**两两互比（不同图标、同一边长）
const bigs = have.map(r => f(by.get(r.name).get(String(r.le)), r.le)).filter(p => fs.existsSync(p));
const ctrl = [];
for (let i = 0; i + 1 < bigs.length; i++) ctrl.push(compare(bigs[i], bigs[i + 1]).iou);
const q = (a, p) => { const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(s.length * p))]; };
console.log(`  ② 正对照（不同图标、同边长）${ctrl.length} 对：IoU 中位 ${q(ctrl, 0.5).toFixed(3)}、` +
  `最高 ${Math.max(...ctrl).toFixed(3)} —— 这就是"画得不一样"的样子`);

console.log(`  ③ 被测：同一图标 ${have.length ? have[0].se : '?'} vs ${have.length ? have[0].le : '?'}（最近邻放大后逐格）`);
have.sort((x, y) => x.r.iou - y.r.iou);
for (const { name, r } of have) {
  console.log(`     IoU ${r.iou.toFixed(3)}  |Δα| 中位 ${String(r.dMed).padStart(3)} p90 ${String(r.dP90).padStart(3)}  ` +
    `档 ${r.a.nLv}→${r.b.nLv}  墨 ${(r.a.ink * 100).toFixed(1)}%→${(r.b.ink * 100).toFixed(1)}%  ${name}`);
}
const ious = have.map(r => r.r.iou);
const below = ious.filter(v => v < q(ctrl, 0.99)).length;
console.log(`  == 结论：${have.length} 对里 IoU 中位 ${q(ious, 0.5).toFixed(3)}、最低 ${Math.min(...ious).toFixed(3)}；` +
  `低于正对照上沿的 ${below} 对。` + (below === 0 ? '⇒ 这一轴**没有把任何一张画成另一张**。' : '⇒ 见上面排头的几张。') + ` ==`);
const miss = rows.filter(r => !r.ok).map(r => r.name);
if (miss.length) console.log(`  没对照的 ${miss.length} 个（掩码已被淘汰）：${miss.join('、')}`);
