// 读 `MonoCache` 自己写下的归属索引（`opticon-mono/<身份>/index.tsv`，每行 `键\t边长\t标签`）。
//
// 它解决的是分母那半：`键=` 是**纯内容哈希**（里面没有身份），而桌面库没有资源名列，
//   所以以前"这张缓存是谁的"只有一个来源 —— 清图标缓存那一次当场抓的 logcat，而那个窗口会滚。
//   今天量过一次：1,449 行 `Mono:` 里 937 行的标签是资源名形式，配不上 `componentName`。
//
// 跑法：`node _owner.cjs <拉下来的 opticon-mono 目录> [日志清单 delivery.tsv]`
//   拉：`adb pull /data/user/0/com.android.launcher3/cache/opticon-mono D:/tmp/a8live`
//
// ⚠ 这台子带**正对照**：日志当场带 `键=` 的那些是确定归属，拿索引去配它们必须一致；
//   不一致就说明写的一方或读的一方坏了 —— 而不是"索引更准"。
const fs = require('fs');
const path = require('path');
const DIR = process.argv[2] || 'D:/tmp/a8live';
const LOG = process.argv[3] || 'D:/tmp/denom/delivery.tsv';

const walk = d => { try { return fs.readdirSync(d, { withFileTypes: true }).flatMap(e =>
  e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]) } catch (e) { return [] } };
const files = walk(DIR);
const idx = files.filter(f => path.basename(f) === 'index.tsv');
console.log(`${DIR}：${files.filter(f => f.endsWith('.a8')).length} 张 .a8，找到索引文件 ${idx.length} 个`);
if (!idx.length) {
  console.log('!! 一个 `index.tsv` 都没有 —— 要么这台机器上装的版本还没有这一层，' +
    '要么写失败了。**没有索引不等于"没有归属"**，去查装机版本与 `sweep()` 有没有把它删了。');
  process.exit(2);
}
const rows = [];
let dropped = 0;
for (const f of idx) for (const l of fs.readFileSync(f, 'utf8').split(/\r?\n/)) {
  if (!l.trim()) continue;
  const c = l.split('\t');
  if (c.length !== 3) { dropped++; continue; }        // 跨进程 append 交错的那一截，丢但不许瞒
  rows.push({ key: c[0], edge: +c[1], label: c[2], file: path.dirname(f) });
}
console.log(`索引 ${rows.length} 行（**丢掉 ${dropped} 行不是三段的** —— 交错/半行，非零就要查）`);
const byKey = new Map();
for (const r of rows) (byKey.get(r.key) || byKey.set(r.key, []).get(r.key)).push(r);
const shared = [...byKey.values()].filter(v => new Set(v.map(x => x.label)).size > 1);
const widest = shared.length ? Math.max(...shared.map(v => new Set(v.map(x => x.label)).size)) : 0;
console.log(`独立键 ${byKey.size} 个；**一把键对着多个组件的 = ${shared.length} 个**` +
  (shared.length ? `（最多的一把对着 ${widest} 个标签）` : ''));
console.log('  ⇒ 这一条就是"键不能当组件主键"的直接证据，不用再靠猜');

// 掩码文件 ↔ 索引：有多少张缓存根本没有归属行
const masks = new Set(rows.length ? files.filter(f => f.endsWith('.a8')).map(f => path.basename(f).split('_')[0]) : []);
const noOwner = [...masks].filter(k => !byKey.has(k));
console.log(`缓存里的独立键 ${masks.size} 把，**没有归属行的 ${noOwner.length} 把**` +
  `（这些多半是"装这一版之前"就写下的旧文件 —— 归属索引只能从它上线那一刻开始记）`);

// ---- 正对照：日志当场带 键= 的那些，索引必须说同一个标签 ----
if (fs.existsSync(LOG)) {
  const truth = [];
  for (const l of fs.readFileSync(LOG, 'utf8').trim().split(/\r?\n/).slice(1)) {
    const c = l.split('\t'); if (c[2]) truth.push({ key: c[2], edge: c[3], label: c[0] });
  }
  // 🔑 **两边先归一再比。** 索引那一列存的是整行 `describe()`（名字在最前，后面跟着类名/intrinsic/bounds），
  //   而动态快捷方式的名字在索引里带 `pm:` 前缀、在日志清单里不带 ——
  //   2026-10-21 现量：日志 `com.alibaba.android.rimet/dt_scan` vs 索引 `pm:com.alibaba.android.rimet/dt_scan BitmapDrawable …`。
  //   以前写的是 `n === label` 直接比整行 ⇒ **正对照恒 0**，于是"索引坏了"与"尺子比错了口径"分不开
  //   （本仓那条老规矩：拿一个指标当裁判前先测它会不会把金标准判成不及格）。
  const bare = s => String(s).trim().split(' ')[0].replace(/^pm:/, '');
  const tail = s => { const p = bare(s).split('/'); return p.length > 1 ? p.slice(1).join('/') : ''; };
  let tried = 0, agree = 0; const bad = [];
  for (const t of truth) {
    const got = byKey.get(t.key); if (!got) continue;
    tried++;
    const same = got.filter(r => String(r.edge) === t.edge).map(r => r.label);
    const want = tail(t.label);
    const hit = same.some(n => bare(n) === bare(t.label) || (want && bare(n).endsWith('/' + want)));
    if (hit) agree++;
    else bad.push(`   日志=${bare(t.label)}  索引=${same.map(bare).join(',') || '(这一边长没有行)'}` +
      `  该键全部标签=${[...new Set(got.map(r => bare(r.label)))].slice(0, 3).join(',')}`);
  }
  console.log(`\n### 正对照（日志确定归属的键，索引说得是不是同一个）：可比 ${tried} 把，**一致 ${agree}**` +
    (tried === 0 ? '  ⇒ 可比 0 = 这份清单与这次的缓存没有交集，**不算对过**' :
      (!agree ? '  ⇒ 一把都没对上 = 索引坏了或标签口径不同，下面的数都别用' : '')));
  bad.slice(0, 6).forEach(l => console.log(l));
} else console.log(`\n（没有 ${LOG}，正对照跳过 —— 那需要一次清缓存的活窗口）`);
