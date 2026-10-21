// 「不太干净」到底是谁：把每张图的**源色分桶**摊开，看每一桶被交出去多少墨。
// 一桶一桶看才知道淡的是"整张都淡"还是"某一整块被当成中间调"。
// 半墨 = 均 α 落在 40..169：屏幕上就是一层灰，既不是盘也不是墨。
const fs = require("fs");
const txt = fs.readFileSync(process.argv[2], "utf8").split(/\r?\n/);
let n = null;
const agg = new Map();
for (const L of txt) {
  const m = L.match(/^=== (\S+)/);
  if (m) { n = m[1].replace(/^icon\d+_/, "").replace(/-src$/, "").replace(/_[0-9a-f]{6,}$/, "").replace(/_.*$/, ""); continue; }
  const b = L.match(/均色#([0-9A-F]{6}) 占\s*(\d+)% 均α=\s*(\d+)/);
  if (!b || !n) continue;
  const o = agg.get(n) || { mid: 0, ink: 0, air: 0, rows: [] };
  const share = +b[2], a = +b[3];
  if (a < 40) o.air += share; else if (a < 170) o.mid += share; else o.ink += share;
  o.rows.push([b[1], share, a]);
  agg.set(n, o);
}
const list = [...agg].sort((x, y) => y[1].mid - x[1].mid);
console.log("包名".padEnd(40) + "半墨%  实墨%  空%   最脏的那一桶（源色 占% 均α）");
for (const [k, v] of list) {
  if (v.mid < 8) continue;
  const worst = v.rows.filter((r) => r[2] >= 40 && r[2] < 170).sort((a, b) => b[1] - a[1])[0];
  console.log(k.padEnd(40) + String(v.mid).padStart(4) + "  " + String(v.ink).padStart(5) +
    "  " + String(v.air).padStart(4) + "    #" + worst[0] + " 占" + worst[1] + "% α=" + worst[2]);
}
console.log("\n（干净的那些：半墨 <8% 的共 " + list.filter((x) => x[1].mid < 8).length + " 张）");
