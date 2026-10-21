// 抬顶那一层在满幅支上开火，值多少、伤没伤 —— 逐张比 `字均净`（字形平均浓度）与 `净顶`（≥250 的占比）。
// 只比**走了满幅那一支**的行：参照那一支本来就在跑这一层，比它等于比没变的东西。
const fs = require("fs");
const parse = (p) => {
  const m = new Map(); let n = null;
  for (const L of fs.readFileSync(p, "utf8").split(/\r?\n/)) {
    const h = L.match(/^--- (\S+)/);
    if (h) { n = h[1]; continue; }
    if (n && L.includes("字均净=")) {
      const g = (re) => { const x = L.match(re); return x ? +x[1] : -1; };
      m.set(n, { z: g(/字均净=(-?\d+)/), t: g(/净顶=(-?\d+)/), ton: L.includes("调=1"),
        soft: g(/淡=([0-9.]+)/),档: (L.match(/分档=[^ ]+/) || [""])[0] });
      n = null;
    }
  }
  return m;
};
const [a, b] = [parse(process.argv[2]), parse(process.argv[3])];
const rows = [];
for (const [k, v] of b) {
  if (!a.has(k) || !v.ton) continue;
  const u = a.get(k);
  rows.push([k.replace(/^icon\d+_/, "").replace(/_.*/, ""), u.z, v.z, u.t, v.t]);
}
rows.sort((x, y) => (y[2] - y[1]) - (x[2] - x[1]));
console.log("满幅支 " + rows.length + " 张，字均净 变化：");
for (const r of rows) console.log("  " + String(r[1]).padStart(4) + " -> " + String(r[2]).padStart(4) +
  "  " + ((r[2] - r[1] >= 0 ? "+" : "") + (r[2] - r[1])).padStart(4) +
  "   净顶 " + String(r[3]).padStart(3) + "% -> " + String(r[4]).padStart(3) + "%   " + r[0].slice(0, 42));
const d = rows.map((r) => r[2] - r[1]);
console.log("升 " + d.filter((x) => x > 0).length + " 降 " + d.filter((x) => x < 0).length +
  " 不动 " + d.filter((x) => x === 0).length + "  中位 " + d.slice().sort((x, y) => x - y)[Math.floor(d.length / 2)] +
  "  最大降 " + Math.min(...d) + "  最大升 " + Math.max(...d));
