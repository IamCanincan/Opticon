// 漂移的**归因**：14 张逐张看"哪一栏在跳"，而不是猜。
// 跑法：node _driftwhy.cjs
const fs = require("fs");
const EDGES = ["96", "192", "384", "768"];
const t = (l, k) => { for (const x of l.split(" ")) if (x.startsWith(k + "=")) return x.slice(k.length + 1); return null };
const per = new Map();
for (const e of EDGES) {
  const L = fs.readFileSync(`bench/reg/bench_${e}.txt`, "utf8").split(/\r?\n/);
  let n = null;
  for (let i = 0; i < L.length; i++) {
    const h = L[i].match(/^---+\s+(\S+)/); if (h) { n = h[1]; continue }
    if (!n || !L[i].includes("色阶=")) continue;
    if (!per.has(n)) per.set(n, {});
    const g = (k) => t(L[i], k);
    per.get(n)[e] = { fill: +g("框内"), H: g("H"), 拦: g("拦"), 同色: g("同色"), 底占: g("底占"), 底厚: g("底厚"), 带占: g("带占") };
    n = null;
  }
}
const why = {};
const rows = [];
for (const [name, m] of per) {
  const have = EDGES.filter((e) => m[e]);
  const f = have.map((e) => m[e].fill);
  const d = Math.max(...f) - Math.min(...f);
  if (d <= 0.15) continue;
  const Hs = [...new Set(have.map((e) => m[e].H))].join("");
  const bans = have.map((e) => m[e].拦).join("/");
  const key = `H ${have.map((e) => m[e].H).join("→")} | 拦 ${bans}`;
  why[key] = (why[key] || 0) + 1;
  rows.push({ name, d, have, m, key });
}
console.log(`漂移>0.15 的 ${rows.length} 行，按「哪一支赢 + 拦了哪几道」归并：`);
for (const [k, c] of Object.entries(why).sort((a, b) => b[1] - a[1])) console.log(`  ${String(c).padStart(2)} ×  ${k}`);
console.log("\n逐张（同色那一栏：负数 = -meanCov，即底自己不实、这一支被退掉）：");
for (const r of rows.sort((a, b) => b.d - a.d))
  console.log("  " + r.d.toFixed(2) + "  " + r.name.slice(0, 34).padEnd(35) +
    r.have.map((e) => `${e}:${r.m[e].H}拦${r.m[e].拦}`).join("  ").padEnd(34) +
    "  同色 " + r.have.map((e) => r.m[e].同色).join(" | "));
