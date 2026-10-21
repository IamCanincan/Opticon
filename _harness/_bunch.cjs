// **交付的"档数"有多少是虚的**：`分档=a/b(α1,α2,…)` 里 ≥3 个档挤在同一个 α 值。
// 跑法：node _bunch.cjs <bench输出>
// 这是结构信号，不是又一个全局聚合 —— 记忆说这族缺的正是"另一种 kinds 的证据"。
const fs = require("fs");
const L = fs.readFileSync(process.argv[2] || "bench/bench_runtime.txt", "utf8").split(/\r?\n/);
let n = null, tot = 0;
const hits = [];
for (const l of L) {
  const h = l.match(/^---+\s+(\S+)/); if (h) { n = h[1]; continue }
  const m = l.match(/分档=(\d+)\/(\d+)\(([^)]*)\)/); if (!m || !n) continue;
  tot++;
  const lv = m[3].split(",").map(Number);
  const c = {};
  for (const v of lv) c[v] = (c[v] || 0) + 1;
  const mx = Math.max(...Object.values(c));
  if (mx >= 3) {
    const at = Object.keys(c).find((k) => c[k] === mx);
    hits.push({ n: n.replace(/^icon\d+_/, "").replace(/-src$/, ""), mx, at,
      fill: (l.match(/框内=([0-9.]+)/) || [0, "?"])[1], top: (l.match(/顶占比=([0-9-]+)/) || [0, "?"])[1],
      lv: m[3] });
  }
  n = null;
}
console.log(`${tot} 张里 ${hits.length} 张有 ≥3 个档挤在同一个 α（${(hits.length * 100 / Math.max(tot, 1)).toFixed(0)}%）：`);
for (const r of hits.sort((a, b) => b.mx - a.mx))
  console.log(`  挤 ${r.mx} 档在 α=${r.at}  框内=${r.fill} 顶=${r.top}  ${r.n.slice(0, 46)}  [${r.lv}]`);
