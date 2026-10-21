// 同一个图标在**两种 canvas 边长**上的判据差 —— 桌面(384) vs 抽屉(192) 这一条轴。
//
// 为什么单开一台：`intrinsic=` 与 `edge=` 不是一回事 —— 前者是 drawable 自己的尺寸，
// 后者是桌面递给我们画布的边长。`intr_round1.tsv` 里那批 `intrinsic=192x192` 说的是源图小，
// **不是**"这张被按 192 画过"。真正会让同一个 app 在抽屉里与桌面上长得不一样的，是 `edge=`。
//
// 用法：node _edgevar.cjs [日志…]（默认把那三份存好的 Mono 行快照都读进来）
const fs = require('fs');
const FILES = process.argv.slice(2).filter(a => !a.startsWith('--'));
const list = FILES.length ? FILES : ['_devlog.txt', '_devlog2.txt', '_devlog3.txt'];

const g = (l, k) => ((l.match(new RegExp('(?:^| )' + k + '=([^ ]*)')) || [])[1] || '-');
const perEdge = {};
const seen = new Map();          // name -> edge -> 第一次见到的判据
for (const f of list) {
  if (!fs.existsSync(f)) continue;
  for (const l of fs.readFileSync(f, 'utf8').split('\n')) {
    if (!/Mono: diag/.test(l) || !l.includes('盘实=')) continue;
    const e = (l.match(/edge=(\d+)/) || [])[1] || '?';
    perEdge[e] = (perEdge[e] || 0) + 1;
    const m = l.match(/sig=([0-9a-f]{12}) 键=\S+ (.*?) 票=/);
    if (!m) continue;
    const name = m[2].split(' ')[0];
    if (!/\//.test(name)) continue;                 // 解不出包名的快捷方式丢掉，别混成同一个键
    if (!seen.has(name)) seen.set(name, new Map());
    const s = seen.get(name);
    if (!s.has(e)) s.set(e, { sig: m[1], lv: g(l, '分档'), dz: g(l, '底占'), dan: g(l, '淡'), kn: g(l, '框内'), ps: g(l, '盘实') });
  }
}
console.log(`== 跨 canvas 边长对照（读 ${list.join(', ')}）==`);
console.log('  带判据行按 edge：', JSON.stringify(perEdge));
console.log(`  解得出包名的图标 ${seen.size} 个；**在 ≥2 种 edge 上都有判据的：${[...seen].filter(([, m]) => m.size >= 2).length} 个**`);
const lv = x => { const m = (x || '').match(/^(\d+)\//); return m ? +m[1] : NaN; };
for (const [name, m] of seen) {
  if (m.size < 2) continue;
  const arr = [...m.entries()];
  const sp = Math.max(...arr.map(([, r]) => lv(r.lv))) - Math.min(...arr.map(([, r]) => lv(r.lv)));
  console.log(`  ${name}`);
  for (const [e, r] of arr) console.log(`      edge=${e}  ${r.lv.padEnd(20)} 底占=${r.dz} 淡=${r.dan} 框内=${r.kn} 盘实=${r.ps} sig=${r.sig}`);
  console.log(`      ⇒ 档数跨度 ${sp}${sp ? '  **桌面上两种画法，层次真的不同**' : '  层次一致'}`);
}
if ([...seen].filter(([, m]) => m.size >= 2).length === 0)
  console.log('  （没有对照样本 —— 这不是"没漂移"，是没人在两种 edge 上重算过同一个图标）');
