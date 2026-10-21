// 从 MaskBench 的日志里按"名字 + 膜底/膜残 五档"配对，算每张的**深比**：
//   深比 = 这一批"被叫作底、还上着墨"的格子里，α>160 的占多少。
// 想验的假设：膜（一层浅灰）与图（真的暗部）在这一层比例上分得开，在绝对 α 上分不开。
const fs = require("fs");
const txt = fs.readFileSync(process.argv[2], "utf8").split(/\r?\n/);
const rows = [];
let name = null;
const band = (s) => (s.match(/(\d+)\/(\d+)\/(\d+)\/(\d+)\/(\d+)/) || [0, 0, 0, 0, 0, 0]).slice(1).map(Number);
for (const L of txt) {
  const m = L.match(/^--- (\S+)/);
  if (m) { name = m[1].replace(/^icon\d+_/, "").replace(/_.*$/, ""); continue; }
  if (!name || !L.includes("膜底=")) continue;
  const bd = band(L.slice(L.indexOf("膜底=") + 3));
  const ri = L.indexOf("膜残=");
  rows.push({ name, ton: L.includes("调=1"), f: +(L.match(/主色=(\d+)/) || [0, -1])[1],
    d: (L.match(/淡=([\d.]+)/) || [0, 0])[1],
    bd, tr: ri < 0 ? [0, 0, 0, 0, 0] : band(L.slice(ri + 3)),
    rim: +(L.match(/长边=(\d+)/) || [0, 0])[1] });
  name = null;
}
const T = (v) => v.reduce((a, b) => a + b);
const ratio = (v) => (T(v) === 0 ? -1 : (v[4] * 100) / T(v));
const sel = rows.filter((r) => r.ton);
console.log("满幅支 " + sel.length + " 张；按 **膜底深比** 排序（膜=比例低，图=比例高）");
console.log("包名".padEnd(34) + "主色  长边  膜底格  深比%   膜底五档              |  膜残格 深比%");
for (const r of sel.slice().sort((a, b) => ratio(a.bd) - ratio(b.bd))) {
  console.log(r.name.padEnd(34) + String(r.f).padStart(3) + String(r.rim).padStart(5) +
    String(T(r.bd)).padStart(8) + ratio(r.bd).toFixed(1).padStart(7) + "   " +
    r.bd.join("/").padEnd(24) +
    String(T(r.tr)).padStart(8) + ratio(r.tr).toFixed(1).padStart(7));
}
console.log("\n非满幅支（参照那一条路）");
for (const r of rows.filter((x) => !x.ton && (T(x.bd) > 0 || T(x.tr) > 0))) {
  console.log(r.name.padEnd(34) + String(r.f).padStart(3) + String(r.d).padStart(6) +
    String(T(r.tr)).padStart(8) + ratio(r.tr).toFixed(1).padStart(7) +
    String(T(r.bd)).padStart(9) + ratio(r.bd).toFixed(1).padStart(7) + "   " + r.tr.join("/"));
}
