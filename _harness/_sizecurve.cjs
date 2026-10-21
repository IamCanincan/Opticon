// 读 bench/size_*.tsv：把第十闸那笔账**拆开** —— 换支到底是「量随边长长」还是「格子把小框判成不可测」。
//   ① 每条量的逐张漂移（192 / 384 / 768 的 max−min）；
//   ② 同一条绝对阈值搬到别的边长，开火集合与 384 的**对称差**（漏的 + 多的，两头都数）；
//   ③ 关键一问：`跨裸`（同一个跨度、去掉"框比格小就不算"这一句）能不能把 192 档那批换支的救回来。
const fs = require('fs');
const file = process.argv[2] || 'bench/size_rec3u.tsv';
const names = ['跨度', '跨裸', '格跨度', '格p10', '阶数', '阶级跨度'];
const rows = fs.readFileSync(file, 'utf8').split(/\r?\n/).slice(1).filter(Boolean).map(l => l.split('\t'));
const by = new Map();
for (const r of rows) {
  const [name, edge, ...rest] = r;
  if (!by.has(name)) by.set(name, {});
  by.get(name)[+edge] = { v: rest.slice(0, 6).map(Number), on: +rest[6], null: +rest[7], box: rest[8] };
}
const EDGES = [96, 192, 384, 768];
const at = (n, e, ci) => by.get(n)[e] && by.get(n)[e].v[ci] >= 0 ? by.get(n)[e].v[ci] : null;
const all = [...by.keys()];
const fired = e => new Set(all.filter(n => by.get(n)[e] && by.get(n)[e].on));

console.log(`语料 ${all.length} 张（同一张图在 ${EDGES.join('/')} 各渲染一遍）`);
console.log('\n【现在这一条：仓里 tonalClass 的开火集合】');
for (const e of EDGES) console.log(`  ${e} 档开火 ${String(fired(e).size).padStart(2)} 张 · 直接判不可测 ${(all.filter(n => by.get(n)[e] && by.get(n)[e].null).length)
  .toString().padStart(2)} 张`);
const base = fired(384);
for (const e of EDGES) {
  if (e === 384) continue;
  const s = fired(e);
  const lost = [...base].filter(k => !s.has(k));
  const gain = [...s].filter(k => !base.has(k));
  console.log(`  ${e} vs 384：换 ${lost.length + gain.length} 张（丢 ${lost.length} / 多 ${gain.length}）` +
    `${lost.length ? '\n     丢：' + lost.map(k => `${k.slice(0, 26)}(${by.get(k)[384].v[0]}→${by.get(k)[e].v[0]})`).join(' ') : ''}`);
}

console.log('\n【逐张漂移：只算三档(192/384/768)都量得到的】');
for (const ci of names.map((x, i) => i)) {
  const meas = all.filter(n => [192, 384, 768].every(e => at(n, e, ci) !== null));
  const sp = meas.map(n => { const v = [192, 384, 768].map(e => at(n, e, ci)); return { n, s: Math.max(...v) - Math.min(...v), v }; })
    .sort((a, b) => b.s - a.s);
  console.log(`  ${names[ci].padEnd(5)} 可比 ${String(meas.length).padStart(2)} 张 · 漂>0 ${String(sp.filter(x => x.s > 0).length).padStart(2)} 张` +
    ` · 最大 ${sp.length ? sp[0].s : 0}${sp.length ? '  ' + sp.slice(0, 3).map(x => `${x.n.slice(0, 18)}=${x.v.join('/')}`).join('  ') : ''}`);
}

console.log('\n【候选规则模拟：开火 = 跨裸 >= T（去掉格子前提，其余一个字不改）】');
for (const T of [30, 35, 40]) {
  const setAt = e => new Set(all.filter(n => at(n, e, 1) !== null && at(n, e, 1) >= T));
  const b = setAt(384);
  const line = EDGES.map(e => { const s = setAt(e); const d = [...new Set([...s, ...b])].filter(k => s.has(k) !== b.has(k)); return `${e}:${s.size}${e !== 384 ? '(换' + d.length + ')' : ''}`; });
  console.log(`  T=${T}  每档开火 ${line.join('  ')}   [以 ${T} 处 384 集合为准]`);
  if (T === 35) {
    const s192 = setAt(192);
    const same = [...b].every(k => s192.has(k)) && b.size === s192.size;
    console.log(`  → 192 档与 384 档集合${same ? '一字不差' : '仍不同：' + [...new Set([...s192, ...b])].filter(k => s192.has(k) !== b.has(k)).map(k => k.slice(0, 22)).join(' ')}`);
    console.log(`  → 这一条会**新放进**现集合外的（384 档）：${[...setAt(384)].filter(k => !base.has(k)).map(k => k.slice(0, 22)).join(' ') || '无'}`);
  }
}
