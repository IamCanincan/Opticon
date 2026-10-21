// **谁交的是空掩码：按「drawable 长什么样」摊一遍，不按包名摊。**
// 立这条的因由：`docs/devlog` 里那笔"全空掩码 `dac950aa…` 归属未定"挂了一整天，
//   而我先前用 `sig=` / `键=` 去认主，被自己推翻（`sig=dce53c1df856` 一把盖住 36 个标签）。
//   这一台**不认主**，只把日志里现成的读数按类摊开：类名 / intrinsic / bounds / 是不是整张透明。
// ⚠ 读的是**留存 logcat**，不是重跑模型 —— 所以它说"这批观测长这样"，不说"这台设备上只有这些"。
//   分母 = 日志里出现过的**行**，同一个图标一天打几百行；去重按 `键`（内容哈希，不是组件主键）。
// 跑法：node _blankwho.cjs D:/tmp/lc1.txt D:/tmp/lc2.txt …
const fs = require('fs');
const LINE = /Mono: diag#\d+ edge=(\d+) (?:sig=(\S+) )?键=(\S+) (.*)$/;
const rows = new Map();
let files = 0, diag = 0, unparsed = 0;
for (const p of process.argv.slice(2)) {
  if (!fs.existsSync(p)) { console.error(`跳过（不存在）：${p}`); continue; }
  files++;
  for (const l of fs.readFileSync(p, 'utf8').replace(/\r/g, '').split('\n')) {
    const m = LINE.exec(l);
    if (!m) continue;
    diag++;
    const [, edge, sig, key, rest] = m;
    // `rest` 形如：`? GradientDrawable intrinsic=-1x-1 bounds=236x236 adaptive=false state=… 整张透明 → 空层 …`
    const cls = /\s([A-Za-z][A-Za-z0-9]*) (?:fg=|intrinsic=)/.exec(rest);
    const name = rest.split(' ')[0];
    const intr = /intrinsic=(\S+)/.exec(rest);
    const bnd = /bounds=(\S+)/.exec(rest);
    const adapt = /adaptive=(\S+)/.exec(rest);
    const blank = /整张透明/.test(rest);
    const gave = /已交出掩码/.test(rest);
    const cached = /缓存=磁盘/.test(rest);
    const k = [name, cls ? cls[1] : '?', intr ? intr[1] : '?', bnd ? bnd[1] : '?', adapt ? adapt[1] : '?'].join(' | ');
    const a = rows.get(k) || { n: 0, blank: 0, gave: 0, cached: 0, keys: new Set(), edges: new Set(), sigs: new Set() };
    a.n++; a.keys.add(key); a.edges.add(edge); if (sig) a.sigs.add(sig);
    if (blank) a.blank++; if (gave) a.gave++; if (cached) a.cached++;
    rows.set(k, a);
  }
}
console.log(`读了 ${files} 份 logcat，解析出 ${diag} 行 diag（没对上行的 ${unparsed}）`);
console.log('标签 | 类 | intrinsic | bounds | adaptive     行数  distinct键  整张透明  已交掩码  走磁盘缓存  键样例');
const out = [...rows.entries()].sort((x, y) => y[1].n - x[1].n);
for (const [k, a] of out) {
  if (a.n < 3 && a.blank === 0) continue;   // 一次性噪声不占版面
  console.log(`${k.padEnd(46)} ${String(a.n).padStart(5)} ${String(a.keys.size).padStart(11)} ` +
    `${String(a.blank).padStart(10)} ${String(a.gave).padStart(10)} ${String(a.cached).padStart(11)}  ` +
    `${[...a.keys].slice(0, 3).join(',')}  sig=${a.sigs.size} 边长=${[...a.edges].join('/')}`);
}
console.log('\n== 只看"整张透明"的那一族：它们占了几把不同的键 ==');
const blankKeys = new Set();
for (const [, a] of rows) if (a.blank > 0) a.keys.forEach(k => blankKeys.add(k));
const allKeys = new Set();
for (const [, a] of rows) a.keys.forEach(k => allKeys.add(k));
console.log(`所有出现过的键 ${allKeys.size} 把，其中被"整张透明"打过 ${blankKeys.size} 把`);
for (const [k, a] of rows) if (a.blank > 0) console.log(`  ${k}  透明行 ${a.blank}/${a.n}  键 ${[...a.keys].join(',')}`);
