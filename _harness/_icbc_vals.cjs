const fs = require("fs");
const E = 384, N = E * E;
const a = fs.readFileSync("_ea/384/com.icbc_icon_8d0a0996d4c9.alpha"); // after
const b = fs.readFileSync("_eb/384/com.icbc_icon_8d0a0996d4c9.alpha"); // before
const stat = (xs) => (xs.length ? `n=${xs.length} min=${Math.min(...xs)} max=${Math.max(...xs)}` : "n=0");
const up = { from: [], to: [] }, dn = { from: [], to: [] };
for (let i = 0; i < N; i++) {
  const d = a[i] - b[i];
  if (d > 0) { up.from.push(b[i]); up.to.push(a[i]); }
  else if (d < 0) { dn.from.push(b[i]); dn.to.push(a[i]); }
}
console.log("抬  前α:", stat(up.from), " 后α:", stat(up.to));
console.log("抹  前α:", stat(dn.from), " 后α:", stat(dn.to));
// 抬到的目标值是不是"就一个值"（= 整块统一到一个多数 α）
const tgt = {};
for (const v of up.to) tgt[v] = (tgt[v] || 0) + 1;
const top = Object.entries(tgt).sort((x, y) => y[1] - x[1]).slice(0, 6);
console.log("抬到的目标 α 直方（前 6）:", top.map(([v, c]) => `${v}×${c}`).join("  "));
// 全图的 α 分布，看盘/墨两族在哪
const all = {};
for (let i = 0; i < N; i++) { const v = a[i]; const k = Math.round(v / 32) * 32; all[k] = (all[k] || 0) + 1; }
console.log("后版全图 α 直方(按32归):", Object.entries(all).sort((x, y) => x[0] - y[0]).map(([v, c]) => `${v}:${c}`).join(" "));
