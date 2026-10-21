// 把判据台报告里的 `端=a/b`（带符号 ΔL 的 p5/p95）捞出来，按**跨度**排一遍。
// 为什么要这一列：跨度小 = 映射陡 = 一条平滑渐变被压成几级台阶，观感就是主人报的「过渡生硬」。
// 用法：node _spread.cjs bench/reg/bench_384.txt
const fs = require("fs");
const txt = fs.readFileSync(process.argv[2] || "bench/reg/bench_384.txt", "utf8").split(/\r?\n/);
let name = null, rows = [];
for (const L of txt) {
  const m = L.match(/^--- (.+?)\s/);
  if (m) { name = m[1]; continue; }
  const e = L.match(/端=(-?\d+)\/(-?\d+)/);
  if (e && name) { rows.push([+e[2] - +e[1], +e[1], +e[2], name, L]); name = null; }
}
rows.sort((a, b) => a[0] - b[0]);
const q = p => rows[Math.floor(rows.length * p)][0];
console.log(`n=${rows.length}  跨度 最小=${rows[0][0]} p10=${q(0.1)} p25=${q(0.25)} 中位=${q(0.5)} p75=${q(0.75)}`);
console.log("最陡的 16 张（跨度 / 端点 / 淡 / 顶占比 / 色阶）：");
for (const [s, a, b, n, L] of rows.slice(0, 16)) {
  const g = k => (L.match(new RegExp(k + "=([^ ]+)")) || [, "?"])[1];
  console.log(`  ${String(s).padStart(4)}  端=${a}/${b}  淡=${g("淡")} 顶占=${g("顶占比")} H=${g("H")} 色阶=${g("色阶")}  ${n.slice(0, 46)}`);
}
