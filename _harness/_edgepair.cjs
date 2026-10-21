// **同一个图标、两种 canvas 边长** —— 真机自己给的对照（384 = 桌面，192 = 抽屉那一联）。
//
// 为什么要单开一台：之前那句"跨 canvas 这一轴没有对照样本"是因为抽屉展示走桌面缓存、
// 根本不叫我们；14:51 那次「清图标缓存并重启桌面」之后，192 那一联第一次真的重算了，
// 于是这一轴终于有了**屏上**的 A/B（不再是台子里二次重采样那套地板）。
//
// ⚠ 键必须带 edge：`sig` 是 8×8 降采样指纹，**跨尺寸会撞车**（本轮实测：同一个
//   `sig=823635670e37` 同时是 edge=384 与 edge=192 两行）。按名字或按 sig join 都会把
//   两种画法并成一条，然后把"输入不同"读成"缓存串了"。
// 用法：node _edgepair.cjs <日志> [pid]
const fs = require('fs');
const FILE = process.argv[2] || '_wc2.txt';
const PID = process.argv[3] || null;
const L = fs.readFileSync(FILE, 'utf8').split('\n').filter(l => /Mono: diag#/.test(l) && l.includes('几何='));

const g = (s, k) => ((s.match(new RegExp('(?:^| )' + k + '=([^ ]*)')) || [])[1] || '-');
const byName = new Map();
for (const l of L) {
  if (PID && l.split(/\s+/)[2] !== PID) continue;
  const m = l.match(/键=\S+ (\S+)\s/);
  if (!m || !m[1].includes('/')) continue;         // 无包名的快捷方式一律丢（它们会全并成一个键）
  const name = m[1], edge = g(l, 'edge');
  const rec = {
    sig: g(l, 'sig'), 几何: g(l, '几何'), 档: g(l, '档'), 接管: g(l, '接管'),
    判剩: g(l, '判剩'), 分档: g(l, '分档'), 底占: g(l, '底占'), 框内: g(l, '框内'), 淡: g(l, '淡'),
    拦: g(l, '拦'), 盘实: g(l, '盘实'),
  };
  if (!byName.has(name)) byName.set(name, new Map());
  const e = byName.get(name);
  if (!e.has(edge)) e.set(edge, rec);              // 同边长取第一条（同一条输入应当同结果）
}
const KEYS = ['几何', '档', '接管', '判剩', '分档', '底占', '框内', '淡', '拦', '盘实'];
const two = [...byName].filter(([, m]) => m.size >= 2);
console.log(`== 同图标跨 canvas：${byName.size} 个有判据行的图标里，**在 ≥2 种 edge 上重算过的 ${two.length} 个** ==`);
let diff = 0;
for (const [name, m] of two) {
  const es = [...m.entries()].sort((x, y) => +x[0] - +y[0]);
  const base = es[0][1];
  const bad = KEYS.filter(k => es.slice(1).some(([, r]) => r[k] !== '-' && base[k] !== '-' && r[k] !== base[k]));
  if (bad.length) diff++;
  const line = es.map(([e, r]) => `${e}:${r.几何}${r.档 === '-' ? '' : '/' + r.档}${r.判剩 === '-' ? '' : '/判剩' + r.判剩}${r.接管 === '-' ? '' : '/' + r.接管}`).join('   ');
  console.log(`  ${bad.length ? '≠' : '='} ${name.padEnd(52)} ${line}` +
    (bad.length ? `   ← 变的字段：${bad.join(',')}` : ''));
}
if (two.length === 0) console.log('  （还是没样本 —— 这句不是"没漂移"）');
console.log(`== 结论：${two.length} 个跨 canvas 对照里，判据字段有变化的 ${diff} 个 ==`);
