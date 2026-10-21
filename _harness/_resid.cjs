// 从日志里把「**残余灰膜**」摊开：α 落在 40..160、而且**离真墨（α≥170）超过一圈带宽**的格子。
// 这一把不看模型把什么标成了底 —— 主人报"不干净"的时候，他看到的正是"一大片灰、旁边没有东西"。
// 上一轮那把刀是按 `plate`/`dep` 标签下刀的，所以**标签没盖到的那些灰**就是它的覆盖缺口；
// 这一列量的就是这个缺口本身。
const fs = require("fs");
const txt = fs.readFileSync(process.argv[2], "utf8").split(/\r?\n/);
let n = null;
const rows = [];
for (const L of txt) {
  const m = L.match(/^--- (\S+)/);
  if (m) { n = m[1]; continue; }
  if (!n || !L.includes("灰内=")) continue;
  const g = (re) => { const x = L.match(re); return x ? +x[1] : -1; };
  rows.push({ n: n.replace(/^icon\d+_/, "").replace(/-src$/, "").replace(/_[0-9a-f]{6,}$/, "").replace(/_.*$/, ""),
    far: g(/灰内=(\d+)/), tot: g(/灰总=(\d+)/), cut: g(/膜刀=(\d+)/), ton: L.includes("调=1"),
    fill: g(/框内=/), soft: (L.match(/淡=([0-9.]+)/) || [0, 0])[1] });
  n = null;
}
rows.sort((a, b) => b.far - a.far);
console.log("包名".padEnd(40) + "灰内格  灰总格  已清  淡    支");
for (const r of rows) if (r.far > 0) console.log(r.n.padEnd(40) + String(r.far).padStart(6) +
  String(r.tot).padStart(8) + String(r.cut).padStart(7) + "  " + String(r.soft).padEnd(5) + (r.ton ? "满幅" : "参照"));
const t = rows.reduce((a, r) => a + r.far, 0);
console.log("合计残余灰内 " + t + " 格，分布在 " + rows.filter((r) => r.far > 0).length + " 张上；" +
  "  满幅 " + rows.filter((r) => r.ton && r.far > 0).length + " 张 / 参照 " +
  rows.filter((r) => !r.ton && r.far > 0).length + " 张");
