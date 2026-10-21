// 第十闸：换支账 —— **并且把"换支"分成两类报**。
//
// 为什么必须分（2026-10-17 摊四档边长抓到的）：以前这一闸只数"`调=1` 有没有出现"，
// 于是"这张算了跨度、没过线"与"这张**压根没算**（`tonalClass` 返回 null）"混成同一个数。
// 而后者里 `forceRef`（参照那一遍，按设计不看跨度）是**正常**的 —— 把它当"漂移"去查就是查空气。
// 真机第一读：1579 行"没算"里只有 70 行是 forceRef，其余是 `pairs == 0`（没插桩那会儿落在未知）。
//
// 状态取自 MonochromeMask 的 report：`调=1` 开火 / `调跨=N` 算了没过线 / `调未算=N` 没算
//   （1 框太小、2 框内无实心、3 forceRef、4 凑不出相邻对）。
//
// 用法：node _branchstab.cjs bench/reg/bench_96.txt bench/reg/bench_192.txt ...
const fs = require("fs");
const files = process.argv.slice(2);
if (files.length < 2) { console.log('没有边长档可比 —— 要 EDGES=96,192,384,768 才量得到'); process.exit(2); }
const nm = f => f.replace(/^.*bench_/, "").replace(/\.txt$/, "");
const sets = files.map(f => {
  const s = new Map(); let cur = null;
  for (const l of fs.readFileSync(f, "utf8").split(/\r?\n/)) {
    const h = /^--- (\S+)/.exec(l); if (h) { cur = h[1].replace(/\.png$/, ""); continue }
    if (!cur) continue;
    if (/调=1/.test(l)) s.set(cur, "on");
    else if (/调跨=\d+/.test(l)) s.set(cur, "off");
    else { const n = /调未算=(\d)/.exec(l); if (n) s.set(cur, "nc" + n[1]); }
  }
  return { f, edge: nm(f), s };
});
const on = x => x === "on" ? 1 : 0;
console.log('每档：开火 / 算了没过线 / 没算（按原因）');
for (const x of sets) {
  const c = { on: 0, off: 0, nc: {} };
  for (const v of x.s.values()) { if (v === "on") c.on++; else if (v === "off") c.off++; else c.nc[v.slice(2)] = (c.nc[v.slice(2)] || 0) + 1 }
  const ncs = Object.keys(c.nc).sort().map(k => "nc" + k + "=" + c.nc[k]).join(" ");
  console.log(`  ${x.edge.padStart(4)}：${String(c.on).padStart(3)} / ${String(c.off).padStart(3)} / ${ncs || "0"}`);
}
// 主档按**名字**挑（以前按位置挑，换 EDGES 就悄悄拿 288 当 384 用）
let baseName = sets.map(x => x.edge).includes("384") ? "384" : sets[sets.length - 1].edge;
const base = sets.find(x => x.edge === baseName);
let worst = 0, worstName = '', worstWhy = '';
const whyOf = v => v === undefined ? "该档没出现这张" : v === "off" ? "算了但没过线"
  : v === "on" ? "开火了" : v === "nc3" ? "没算·forceRef（按设计）"
    : v === "nc1" ? "没算·框太小" : v === "nc2" ? "没算·框内无实心" : v === "nc4" ? "没算·凑不出相邻对" : "没算·" + v;
for (const x of sets) {
  if (x === base) continue;
  const all = new Set([...x.s.keys(), ...base.s.keys()]);
  const diff = [...all].filter(k => on(x.s.get(k)) !== on(base.s.get(k)));
  if (x.edge === '96') {
    console.log(`  96 档差 ${diff.length} 张 —— 但这一档大部分是"内容框 < 96 直接不算"，只报数不当闸`);
    continue;
  }
  // 把这一档的换支按"另一档是什么状态"分类
  const by = {};
  for (const k of diff) { const other = on(base.s.get(k)) === 1 ? x.s.get(k) : base.s.get(k); by[whyOf(other)] = (by[whyOf(other)] || 0) + 1 }
  const split = Object.entries(by).sort((a, b) => b[1] - a[1]).map(([k, v]) => k + " " + v).join("  |  ");
  console.log(`  ${x.edge} vs ${baseName}：换支 ${diff.length} 张 —— ${split}`);
  if (diff.length > worst) {
    worst = diff.length; worstName = x.edge;
    // 只数"真漂移"：算过但结果不同（on↔off）。"没算"那一类不算进底线。
    const real = diff.filter(k => { const a = on(base.s.get(k)) ? "on" : base.s.get(k), b = on(x.s.get(k)) ? "on" : x.s.get(k); return [a, b].every(v => v === "on" || v === "off") }).length;
    worstWhy = `其中"算了但结果不同" ${real} 张`;
  }
}
const floor = parseInt(fs.existsSync('bench/branch_floor.txt') ? fs.readFileSync('bench/branch_floor.txt', 'utf8').trim() : '999', 10);
console.log(`\n最大换支档差 = ${worst} 张（${worstName} vs ${baseName}），底线 ${floor}   ${worstWhy}`);
if (worst > floor) { console.log('!! 换支比底线多 —— 同一张图标会在桌面不同位置长得不一样'); process.exit(1); }
