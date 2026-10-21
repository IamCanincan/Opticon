// 摊「膜」的分布：两批"被叫作底、却还上着墨"的格子，各自按上屏浓度分五档计数。
// 档 = α 1~30 / 31~60 / 61~100 / 101~160 / 161~255。
// 目的：在动任何绝对阈值之前，先看档与档之间有没有一条空的沟。
const fs = require("fs");
const txt = fs.readFileSync(process.argv[2], "utf8").split(/\r?\n/);
const rows = [];
let name = null, chan = null;
for (const L of txt) {
  const m = L.match(/^--- (\S+)\s+\d+ms\s+〔(.*)〕/);
  if (m) { name = m[1].replace(/^icon\d+_/, "").split("_")[0]; chan = m[2]; continue; }
  if (!name || !L.includes("膜底=")) continue;
  const b = L.match(/膜底=(\d+)\/(\d+)\/(\d+)\/(\d+)\/(\d+)/);
  const r = L.match(/膜残=(\d+)\/(\d+)\/(\d+)\/(\d+)\/(\d+)/);
  rows.push({ name, chan, ton: L.includes("调=1"),
    f: +(L.match(/主色=(\d+)/) || [0, "-1"])[1],
    bd: b.slice(1).map(Number), tr: r ? r.slice(1).map(Number) : [0, 0, 0, 0, 0] });
}
const tot = (v) => v.reduce((p, q) => p + q);
const show = (title, key, only) => {
  const sel = rows.filter(only).filter((x) => tot(x[key]) > 0)
    .sort((a, x) => tot(x[key]) - tot(a[key]));
  console.log("\n== " + title + "：有量的 " + sel.length + " / " + rows.filter(only).length + " 张 ==");
  console.log("名称".padEnd(26) + "主色   1-30  31-60 61-100 101-160  161+    合计");
  for (const x of sel) console.log(x.name.padEnd(26) + String(x.f).padStart(3) + "  " +
    x[key].map((n) => String(n).padStart(6)).join(" ") + String(tot(x[key])).padStart(8));
  const s = [0, 0, 0, 0, 0];
  for (const x of rows.filter(only)) x[key].forEach((n, i) => (s[i] += n));
  console.log("合计".padEnd(26) + "     " + s.map((n) => String(n).padStart(6)).join(" ") +
    String(tot(s)).padStart(8) + "   占比 " +
    s.map((n) => (n * 100 / Math.max(1, tot(s))).toFixed(0) + "%").join(" "));
};
show("满幅支 dep 判底仍上墨（=调残那组）", "tr", (x) => x.ton);
show("参照判底仍上墨", "bd", (x) => !x.ton);
show("参照判底仍上墨（满幅支，归零刀未开的那些）", "bd", (x) => x.ton);
