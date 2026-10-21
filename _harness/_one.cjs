// 把**一张图标在四档边长下的整条判定行**并排打出来，看哪一栏在跳。
// 跑法：node _one.cjs <名字子串> [栏名 …]
const fs = require("fs");
const EDGES = ["96", "192", "384", "768"];
const q = process.argv[2];
const show = process.argv.slice(3);
const t = (l, k) => { for (const x of l.split(" ")) if (x.startsWith(k + "=")) return x.slice(k.length + 1); return null };
const out = {};
const keys = new Set();
for (const e of EDGES) {
  const L = fs.readFileSync(`bench/reg/bench_${e}.txt`, "utf8").split(/\r?\n/);
  let hit = 0;
  for (let i = 0; i < L.length; i++) {
    if (!L[i].startsWith("---") || !L[i].includes(q)) continue;
    const rep = L.slice(i + 1, i + 4).find((x) => x.includes("色阶="));
    if (!rep) continue;
    hit++;
    const m = {};
    for (const tok of rep.trim().split(/\s+/)) { const k = tok.split("=")[0]; if (k !== tok) { m[k] = tok.slice(k.length + 1); keys.add(k); } }
    (out[e] = out[e] || []).push(m);
  }
  if (!hit) console.log(`edge=${e}: 没匹配到 "${q}"`);
}
const names = show.length ? show : [...keys];
console.log("栏".padEnd(10) + EDGES.map((e) => (e + "(" + (out[e] || []).length + ")").padStart(16)).join(""));
for (const k of names)
  console.log(k.padEnd(10) + EDGES.map((e) => ((out[e] || []).map((m) => m[k]).join(",") || "-").padStart(16)).join(""));
