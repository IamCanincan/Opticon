// 临时：逐张比两份 MaskBench 输出里"会被这一刀吃到的那几列"（走哪支/框内/字均净/淡/分档）
const fs = require("fs");
function rows(p) {
  const L = fs.readFileSync(p, "utf8").split(/\r?\n/);
  let n = null; const m = new Map();
  for (const l of L) {
    if (l.startsWith("---")) { n = l.slice(3).trim().split(/\s+/)[0]; continue; }
    if (!n) continue;
    const t = l.trim();
    if (!t.includes("票=")) continue;
    const g = (k) => { const a = new RegExp("(?:^| )" + k + "=([^ ]*)").exec(t); return a ? a[1] : "-"; };
    m.set(n, { 调: t.includes("调=1") ? "满" : "参", 框: g("框内"), 净: g("字均净"), 淡: g("淡"), 分: g("分档") });
    n = null;
  }
  return m;
}
const a = rows(process.argv[2]), b = rows(process.argv[3]);
let ch = 0, branch = 0;
for (const [k, v] of a) {
  const w = b.get(k);
  if (!w) { console.log("只在一侧:", k); continue; }
  const d = [];
  for (const f of ["调", "框", "净", "淡", "分"]) if (v[f] !== w[f]) d.push(`${f}:${v[f]}→${w[f]}`);
  if (d.length) { ch++; if (v.调 !== w.调) branch++; console.log(`${k}\t${d.join("  ")}`); }
}
console.log(`—— 判据行变动 ${ch} 张，其中**换支** ${branch} 张`);
