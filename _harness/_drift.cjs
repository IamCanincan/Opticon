// 漂移点名台：`_regress.cjs` 只报一个合计（>0.15 有几张），这里把**是谁、往哪漂、
// 在哪一档跳**逐张列出来。跑法：node _drift.cjs
const fs = require("fs");
const EDGES = ["96", "192", "384", "768"];
const t = (l, k) => { for (const x of l.split(" ")) if (x.startsWith(k + "=")) return x.slice(k.length + 1); return null };
const num = (l, k) => { const v = t(l, k); const n = v === null ? NaN : +v; return Number.isFinite(n) ? n : NaN };

// 图标 → 边长 → 指标
const per = new Map();
for (const e of EDGES) {
  const L = fs.readFileSync(`bench/reg/bench_${e}.txt`, "utf8").split(/\r?\n/);
  let n = null;
  for (const l of L) {
    const h = l.match(/^---+\s+(\S+)/); if (h) { n = h[1]; continue }
    if (!n || !l.includes("色阶=")) continue;
    if (!per.has(n)) per.set(n, {});
    per.get(n)[e] = { fill: num(l, "框内"), z: num(l, "字均净"), lv: t(l, "分档"), top: num(l, "顶占比"), vote: (l.match(/票=(\d+)抹\/(\d+)抬/) || [0, 0, 0, 0]).slice(1).map(Number) };
    n = null;
  }
}
const rows = [];
for (const [name, m] of per) {
  const have = EDGES.filter((e) => m[e] && Number.isFinite(m[e].fill));
  if (have.length < 2) continue;
  const f = have.map((e) => m[e].fill);
  const d = Math.max(...f) - Math.min(...f);
  // 跳在哪一档：相邻两边长之间最大的那一步
  let jump = "", step = 0;
  for (let i = 1; i < have.length; i++) {
    const s = Math.abs(m[have[i]].fill - m[have[i - 1]].fill);
    if (s > step) { step = s; jump = have[i - 1] + "→" + have[i]; }
  }
  const mono = f.every((x, i) => i === 0 || x >= f[i - 1] - 1e-9) ? "单调升" :
    f.every((x, i) => i === 0 || x <= f[i - 1] + 1e-9) ? "单调降" : "来回跳";
  rows.push({ name, d, f, have, jump, mono, m });
}
rows.sort((a, b) => b.d - a.d);
console.log(`量到 ${rows.length} 张（四档都有数）。漂移 >0.15 的：${rows.filter(r => r.d > 0.15).length} 张\n`);
console.log("漂移   各档框内(96/192/384/768)              形状    最大那一步   字均净随边长                分档");
for (const r of rows.slice(0, 18)) {
  const fs_ = r.have.map((e) => r.m[e].fill.toFixed(2)).join(" ");
  const zs = r.have.map((e) => r.m[e].z.toFixed(0)).join("/");
  const lvs = r.have.map((e) => String(r.m[e].lv).split("/")[0]).join("/");
  console.log(
    r.d.toFixed(2) + "  " + fs_.padEnd(36) + r.mono.padEnd(8) + r.jump.padEnd(13) +
    zs.padEnd(24) + lvs + "   " + r.name.slice(0, 40)
  );
}
const shape = {};
for (const r of rows.filter((x) => x.d > 0.15)) shape[r.mono + " @" + r.jump] = (shape[r.mono + " @" + r.jump] || 0) + 1;
console.log("\n漂移>0.15 这一族的形状分布:", JSON.stringify(shape));
