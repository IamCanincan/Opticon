// 那 14 张「色阶说已满、字均净却 <200」的，到底谁在说谎？
// 只读真机日志。跑法：node _faint_full.cjs
const fs = require("fs");
const files = ["_buf_20260929.log", "_live_20260929.log", "_buf2_1630.log", "_buf_1621.log", "_buf2.log"];
const bySig = new Map();
for (const p of files) {
  if (!fs.existsSync(p)) continue;
  for (const l of fs.readFileSync(p, "utf8").split(/\r?\n/)) {
    const s = l.match(/sig=([0-9a-f]+)/);
    if (!s || !l.includes("Mono: diag#") || !l.includes("框内=")) continue;
    bySig.set(s[1], l);
  }
}
const tok = (l, name) => {
  for (const t of l.split(" ")) if (t.startsWith(name + "=")) return t.slice(name.length + 1);
  return "-";
};
const rows = [];
// `色阶=<第一层 全局LUT>+<第二层 抬字形>` —— 两段要分开算。上一版用 includes 分组，
// "已满+渐变并档不抬" 这种串两边都命中，于是 14/8/10 与 27 两个数互相打脸。
const seg2 = (tone) => {
  const parts = tone.split("+");
  return parts.length > 1 ? parts[parts.length - 1] : tone;
};
const causeCount = {};
for (const [, l] of bySig) {
  const zj = Number(tok(l, "字均净"));
  if (tok(l, "字均净") === "-" || zj < 0 || zj >= 200) continue;
  const s = seg2(tok(l, "色阶"));
  const key = s.includes("不抬") ? "第二层拒抬" : s.includes("p70=") ? "第二层部分抬" : s.includes("已满") ? "第二层说已满" : s.slice(0, 14);
  causeCount[key] = (causeCount[key] || 0) + 1;
}
console.log("淡 32 张按【第二层（抬字形）】分组:", causeCount, "\n");
const g1 = {};
for (const [, l] of bySig) {
  const zj = Number(tok(l, "字均净"));
  if (tok(l, "字均净") === "-" || zj < 0 || zj >= 200) continue;
  const t = tok(l, "色阶").split("+")[0];
  const key = t.includes("p99=") ? "第一层抬过(p99→255)" : t.includes("已满") ? "第一层说已满" : t.slice(0, 14);
  g1[key] = (g1[key] || 0) + 1;
}
console.log("同样 32 张按【第一层（全局 LUT）】分组:", g1, "\n");

const list = [];
for (const [, l] of bySig) {
  const zj = Number(tok(l, "字均净"));
  if (tok(l, "字均净") === "-" || zj < 0 || zj >= 200) continue;
  if (!seg2(tok(l, "色阶")).includes("已满")) continue;
  const 分档 = tok(l, "分档");
  const 档数 = Number((分档.match(/\/(\d+)/) || [, "0"])[1]);
  const 档值 = (分档.match(/\(([^)]*)\)/) || [, ""])[1];
  list.push({
    lab: (l.match(/sig=[0-9a-f]+ (\S+)/) || [, "?"])[1],
    zj, 净顶: tok(l, "净顶"), 淡: tok(l, "淡"), H: tok(l, "H"),
    底占: tok(l, "底占"), 盘实: tok(l, "盘实"), 零: tok(l, "零"), 实: tok(l, "实"),
    明暗: tok(l, "明暗"), 档数, 档值, 框内: tok(l, "框内"), cc: tok(l, "cc"),
  });
}
list.sort((a, b) => a.zj - b.zj);
console.log("『已满但淡』的张数 =", list.length, "\n");
console.log("字均净 净顶 淡   H 底占  明暗 档数  零/实   交付的几档          图标");
for (const r of list) {
  console.log(
    String(r.zj).padStart(4), String(r.净顶).padStart(4), String(r.淡).padStart(5),
    String(r.H).padStart(2), String(r.底占).padStart(5), String(r.明暗).padStart(5),
    String(r.档数).padStart(3), (r.零 + "/" + r.实).padStart(8),
    " " + r.档值.padEnd(22), r.lab.slice(0, 44)
  );
}
const jt = rows.map(r => Number(r.净顶)).sort((a, b) => a - b);
console.log("\n净顶 中位 =", jt[Math.floor(jt.length / 2)], " 最大 =", jt[jt.length - 1]);
console.log("其中 净顶>=50 的 =", jt.filter(x => x >= 50).length, "张（= 一半字形其实到顶，淡的是另一半）");
console.log("其中 净顶<10 的 =", jt.filter(x => x < 10).length, "张（= 整块都没到顶，『已满』这条说法可疑）");
