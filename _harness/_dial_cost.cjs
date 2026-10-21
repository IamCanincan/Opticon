// 比较两份 MaskBench 输出：淡的代价落在谁身上。跑法：node _dial_cost.cjs <旧文件> <新文件>
const fs = require("fs");
function parse(p) {
  const L = fs.readFileSync(p, "utf8").split(/\r?\n/); const m = new Map(); let n = null;
  for (const l of L) {
    const h = l.match(/^---+\s+(\S+)/); if (h) { n = h[1]; continue }
    if (l.includes("色阶=") && n) { m.set(n, l); n = null }
  }
  return m
}
const t = (l, k) => { for (const x of l.split(" ")) if (x.startsWith(k + "=")) return x.slice(k.length + 1); return "-" };
const [,, fa, fb] = process.argv;
const A = parse(fa), B = parse(fb);
let fA = 0, fB = 0, lvA = 0, lvB = 0, more = 0, jtA = 0, jtB = 0, resA = 0, resB = 0;
const crossed = [];
for (const k of A.keys()) {
  const a = A.get(k), b = B.get(k); if (!b) continue;
  const za = +t(a, "字均净"), zb = +t(b, "字均净");
  if (za >= 0 && za < 200) fA++;
  if (zb >= 0 && zb < 200) fB++;
  if (za >= 200 && zb < 200) crossed.push([za, zb, (k.match(/icon\d+_(.*)-src/) || [, k])[1].slice(0, 42)]);
  lvA += +(t(a, "分档").split("/")[0] || 0); lvB += +(t(b, "分档").split("/")[0] || 0);
  if (+t(b, "分档") > +t(a, "分档")) more++;
  if (+t(a, "净顶") >= 50) jtA++; if (+t(b, "净顶") >= 50) jtB++;
  resA += +((t(a, "残盘") || "0/0").split("/")[0]); resB += +((t(b, "残盘") || "0/0").split("/")[0]);
}
console.log(`淡 ${fA}→${fB}   分开的档数 ${lvA}→${lvB}（变多 ${more} 张）   净顶≥50 ${jtA}→${jtB}   残盘 ${resA}→${resB}`);
crossed.sort((x, y) => y[0] - x[0]);
console.log(`新掉进淡的 ${crossed.length} 张（改前的 字均净 → 改后）：`);
crossed.slice(0, 14).forEach(c => console.log(`   ${String(c[0]).padStart(4)} → ${String(c[1]).padStart(4)}  ${c[2]}`));
const near = crossed.filter(c => c[0] >= 190).length;
console.log(`其中改前就已在 190~199（本来就几乎看不见差别）的 = ${near} / ${crossed.length}`);
