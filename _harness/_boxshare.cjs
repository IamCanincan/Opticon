// **H2 的前提"轮廓那圈颜色是承载面"，要求内容真的铺到画布边。**
// 内容框只占画布一半时，那圈"轮廓"就是物体自己的剪影 —— 拿它当底会把物体擦掉。
// `盒布` 是几何量，不是颜色统计。这台子量的就是：低盒布是不是真的预示着"判走的底太多"。
// 跑法：node _boxshare.cjs <bench输出>
const fs = require("fs");
const L = fs.readFileSync(process.argv[2] || "bench/runtime_full.txt", "utf8").split(/\r?\n/);
let n = null;
const rows = [];
for (let i = 0; i < L.length; i++) {
  const h = L[i].match(/^---+\s+(\S+)/); if (h) { n = h[1]; continue }
  if (!n || !L[i].includes("盒布=")) continue;
  const g = (k) => { const m = L[i].match(new RegExp(k + "=([0-9.]+)")); return m ? +m[1] : NaN };
  rows.push({ n: n.replace(/^icon\d+_/, ""), box: g("盒布"), H: g("H"), share: g("底占"), fill: g("框内") });
  n = null;
}
const med = (a) => (a.length ? a.map((x) => x.share).sort((p, q) => p - q)[a.length >> 1] : NaN);
const byH = {};
for (const r of rows) { const k = "H" + r.H; (byH[k] = byH[k] || []).push(r); }
console.log(`共 ${rows.length} 张。按赢的那一支分组，看 盒布 与 底占 的关系：`);
for (const [k, v] of Object.entries(byH).sort()) {
  const lo = v.filter((r) => r.box < 80), hi = v.filter((r) => r.box >= 80);
  const f = (x) => (isNaN(x) ? "—" : x.toFixed(2));
  console.log(`  ${k}  n=${String(v.length).padStart(3)}   盒布<80: ${String(lo.length).padStart(3)} 张 中位底占 ${f(med(lo))}` +
    `   |  盒布≥80: ${String(hi.length).padStart(3)} 张 中位底占 ${f(med(hi))}`);
}
console.log("\n盒布<80 且走 H2 的（这一族就是 azure / 混元 的形状）：");
const sus = rows.filter((r) => r.box < 80 && r.H === 2).sort((a, b) => a.box - b.box);
for (const r of sus.slice(0, 16))
  console.log(`  盒布=${String(r.box).padStart(3)}  底占=${r.share.toFixed(2)}  框内=${r.fill.toFixed(2)}  ${r.n.slice(0, 50)}`);
if (sus.length > 16) console.log(`  …共 ${sus.length} 张`);
const hi = rows.filter((r) => r.box >= 95 && r.H === 2);
console.log(`\n对照：盒布≥95 且走 H2 的 ${hi.length} 张，中位底占 ${med(hi).toFixed(2)}、中位框内 ${(hi.map(r=>r.fill).sort((a,b)=>a-b)[hi.length>>1]||0).toFixed(2)}`);
