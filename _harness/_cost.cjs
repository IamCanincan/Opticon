// 一把新刀要先量它值多少毫秒 —— 「只报数的列也涨 18ms/张，当天就撤过」。
// 按文件名配对，只比**走了满幅那一支**的图（新代码只在这些图上跑），并报两遍基线自己的抖动。
const fs = require("fs");
const parse = (p) => {
  const m = new Map(); let n = null;
  for (const L of fs.readFileSync(p, "utf8").split(/\r?\n/)) {
    const h = L.match(/^--- (\S+)\s+(\d+)ms/);
    if (h) { n = h[1]; m.set(n, { ms: +h[2], ton: false }); continue; }
    if (n && m.has(n) && L.includes("调=1")) m.get(n).ton = true;
  }
  return m;
};
const q = (a) => { a = a.slice().sort((x, y) => x - y); return a[Math.floor(a.length / 2)]; };
const [b1, b2, c] = [process.argv[2], process.argv[3], process.argv[4]].map(parse);
const rows = [];
for (const [k, v] of c) if (v.ton && b1.has(k) && b2.has(k)) rows.push([k, b1.get(k).ms, b2.get(k).ms, v.ms]);
console.log("满幅支 " + rows.length + " 张");
console.log("基线两遍中位 " + q(rows.map((r) => r[1])) + "ms / " + q(rows.map((r) => r[2])) + "ms" +
  "   候选中位 " + q(rows.map((r) => r[3])) + "ms");
const d = rows.map((r) => r[3] - Math.max(r[1], r[2]));
console.log("逐个差值（候选 − 基线两遍里较慢的那个）：中位 " + q(d) + "ms，最大 +" + Math.max(...d) +
  "ms，最小 " + Math.min(...d) + "ms");
console.log("涨得最多的 6 张：");
for (const r of rows.map((x) => [x[0].replace(/^icon\d+_/, "").replace(/_.*$/, ""), x[3] - Math.max(x[1], x[2])])
  .sort((a, b) => b[1] - a[1]).slice(0, 6)) console.log("   +" + String(r[1]).padStart(4) + "ms  " + r[0]);
