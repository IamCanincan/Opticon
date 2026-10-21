// 真机侧的跨 canvas 守卫（第十七闸的**上机那一半**，2026-10-17）。
//
// 为什么不是再建一台离线台子：`CrossEdgeKt` 喂的是**源图二次重采样**，它守得住生产式子
// （变异"去掉降采样"会让 XS 行逐字不同 → 红 ✓），但它量的不是屏幕上那一张。
// 而真机自己已经把答案打出来了 —— 每个图标在每一档边长上各有一行
// `Mono: diag#… edge=… <包名/资源> … 几何=… 档=… 判剩=… 接管=…`。
// **拿这份日志当判据，量的是装机那一条路**，而且抓 logcat 本来就是每次装机的固定动作，
// 所以这一半守卫**不额外要主人任何东西**。
//
// 判的是两层（与 CrossEdgeKt 同一口径）：
//  · **类**：`几何=`（同一档）与 `档=` —— 跨 edge 一张都不许翻；
//  · **数**：`判剩=` / `接管=` —— 报最大差，钉在输出行上。
// ⚠ 键必须带 edge：`sig` 是 8×8 降采样指纹，**跨尺寸会撞车**（本轮真撞过）。
// ⚠ **样本为 0 就是没测到，不是通过** —— 只在一档边长上跑过的进程会当场红在这里。
//
// 用法：node _xedge.cjs <logcat 文件> [pid] [--pin]
// 退码：0=与守卫一致；1=有翻档/没样本（`--pin` 只打印钉行，不比对）。
const fs = require('fs');
const [FILE, PID, FLAG] = process.argv.slice(2);
if (!FILE) { console.log('用法：node _xedge.cjs <logcat> [pid] [--pin]'); process.exit(2); }
const g = (s, k) => ((s.match(new RegExp('(?:^| )' + k + '=([^ ]*)')) || [])[1] || '-');
const by = new Map();
for (const l of fs.readFileSync(FILE, 'utf8').split('\n')) {
  if (!/Mono: diag#/.test(l) || !l.includes('几何=')) continue;
  const p = l.split(/\s+/)[2];
  if (PID && p !== PID) continue;
  const m = l.match(/键=\S+ (\S+)\s/);
  if (!m || !m[1].includes('/')) continue;          // 解不出包名的快捷方式一律丢（会全并成一个键）
  const edge = g(l, 'edge');
  const rec = { 几何: g(l, '几何'), 档: g(l, '档'), 判剩: g(l, '判剩'), 接管: g(l, '接管'), sig: g(l, 'sig') };
  if (!by.has(m[1])) by.set(m[1], new Map());
  const e = by.get(m[1]);
  if (e.has(edge)) {
    const old = e.get(edge);
    // 同图标同边长出现两行却读数不同 = 这台量具自己不确定，先报出来再谈别的
    for (const k of ['几何', '档', '判剩']) if (old[k] !== rec[k] && old[k] !== '-' && rec[k] !== '-')
      console.log(`  !! 同档自相矛盾 ${m[1]} edge=${edge} ${k}: ${old[k]} vs ${rec[k]}`);
  } else e.set(edge, rec);
}
const two = [...by].filter(([, m]) => m.size >= 2);
const edges = [...new Set([...by.values()].flatMap(m => [...m.keys()]))].sort((a, b) => +a - +b);
const pidTxt = PID || ([...new Set(fs.readFileSync(FILE, 'utf8').split('\n')
  .filter(l => /Mono: diag#/.test(l)).map(l => l.split(/\s+/)[2]))].join(',') || '?');
console.log(`== 真机跨 canvas 判据账：${FILE} pid=${pidTxt} edge档=${edges.join('/')} ==`);
console.log(`   有判据行的图标 ${by.size} 个，其中在 ≥2 档边长上真算过的 ${two.length} 个`);
if (two.length === 0) {
  console.log('!! **跨档样本 0** —— 这个进程只在一档边长上画过图标。这是**没测到**，不算通过。');
  console.log('   要出样本：点「清图标缓存并重启桌面」之后**把抽屉划开**（192 那一联才会真重算）。');
  process.exit(1);
}
let flip = 0, worstPct = 0, pctWho = '';
for (const [name, m] of two) {
  const es = [...m.entries()].sort((x, y) => +x[0] - +y[0]);
  const base = es[0][1];
  const bad = es.slice(1).filter(r =>
    (r[1].几何 !== '-' && base.几何 !== '-' && r[1].几何 !== base.几何) ||
    (r[1].档 !== '-' && base.档 !== '-' && r[1].档 !== base.档));
  if (bad.length) {
    flip++;
    console.log(`  !! 翻档 ${name}`);
    es.forEach(([e, r]) => console.log(`     ${e}=几何${r.几何} 档=${r.档} 判剩=${r.判剩} 接管=${r.接管} sig=${r.sig}`));
  }
  const ps = es.map(([, r]) => r.判剩).filter(v => v !== '-').map(Number);
  if (ps.length >= 2) { const d = Math.max(...ps) - Math.min(...ps); if (d > worstPct) { worstPct = d; pctWho = name; } }
}
const line = `XE n=${by.size} 跨档=${two.length} 档=${edges.join('/')} 翻档=${flip} 判剩差最大=${worstPct}(${pctWho})`;
console.log('   ' + line);
if (flip) { console.log('!! 同一个图标在桌面与抽屉长得不一样 —— 见上面那张名单'); process.exit(1); }
console.log(`   ✓ ${two.length} 个跨档图标**全部同一档**（判剩跨档最大差 ${worstPct}）`);
if (FLAG !== '--pin') {
  const pin = fs.existsSync('bench/reg/xedge.pin') ? fs.readFileSync('bench/reg/xedge.pin', 'utf8').trim() : null;
  if (!pin) { console.log('!! 没有 bench/reg/xedge.pin —— 没基线不算过（先 node _xedge.cjs <日志> <pid> --pin > bench/reg/xedge.pin 钉一次）'); process.exit(1); }
  if (pin !== line) { console.log('!! 与基线不同：\n   基线 ' + pin + '\n   这轮 ' + line); process.exit(1); }
  console.log('   与基线逐字相同 ✓');
}
