// 零点那一刀的 A/B 对照：按图标名配对，报每一列的变化，并把"变化最大的"排在前面。
// ⚠ 语料列是**位置**读的旧坑，这里一律按 key=value 扫成 map（本仓踩过两次的那条）。
const fs = require("fs");
function parse(p) {
  const t = fs.readFileSync(p, "utf8").split("\n");
  let cur = null; const o = {};
  for (const l of t) {
    if (l.startsWith("--- ")) { cur = l.slice(4).trim().split("  ")[0]; continue; }
    if (!cur || !l.includes("框内=")) continue;
    const m = {};
    for (const [, k, v] of l.matchAll(/([^\s=]+)=([^\s]*)/g)) m[k] = v;
    o[cur] = m;
  }
  return o;
}
const A = parse(process.argv[2]), B = parse(process.argv[3]);
const cols = process.argv.slice(4);
const keys = Object.keys(A).filter(k => B[k]);
let changed = 0;
const rows = [];
for (const k of keys) {
  const d = {};
  let moved = false;
  for (const c of cols) {
    const a = parseFloat(A[k][c]), b = parseFloat(B[k][c]);
    if (isNaN(a) || isNaN(b)) continue;
    d[c] = b - a;
    if (Math.abs(b - a) > 1e-9) moved = true;
  }
  if (moved) { changed++; rows.push([k, d]); }
}
console.log("配对 " + keys.length + " 张，其中被改动的 " + changed + " 张");
// 汇总：每列的净变化、变好/变坏张数（约定：淡/残盘/空洞 越小越好，字均净/分档/框内 越大越好）
const worse = { "淡": 1, "残盘": 1, "空洞": 1, "小片": 1 }, better = { "字均净": 1, "分档": 1, "框内": 1, "top": 1 };
for (const c of cols) {
  let sum = 0, up = 0, dn = 0;
  for (const [, d] of rows) { const v = d[c]; if (v === undefined) continue; sum += v; if (v > 0) up++; if (v < 0) dn++; }
  const dir = worse[c] ? (sum > 0 ? "变坏" : sum < 0 ? "变好" : "不动") : better[c] ? (sum > 0 ? "变好" : sum < 0 ? "变坏" : "不动") : "-";
  console.log("  " + c.padEnd(8) + " 净=" + sum.toFixed(3).padStart(9) + "  升" + up + "/降" + dn + "   " + dir);
}
console.log("\n改动最大的 14 张：");
rows.sort((x, y) => Math.abs(y[1]["淡"] || 0) + Math.abs(y[1]["字均净"] || 0) / 50 -
  (Math.abs(x[1]["淡"] || 0) + Math.abs(x[1]["字均净"] || 0) / 50));
for (const [k, d] of rows.slice(0, 14)) {
  const s = cols.filter(c => d[c] !== undefined && d[c] !== 0)
    .map(c => c + ":" + A[k][c] + "→" + B[k][c]).join("  ");
  console.log("  " + k.split("_")[0].padEnd(34) + " " + s);
}
