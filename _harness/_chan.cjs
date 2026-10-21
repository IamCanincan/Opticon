// 屏幕上真正那张（dump 通道） vs 我今晚拿来判"已修"的那张（capture 通道），逐列并排。
const fs = require("fs");
function p(f) {
  const t = fs.readFileSync(f, "utf8").split("\n");
  let cur = null; const o = {};
  for (const l of t) {
    if (l.startsWith("--- ")) { cur = l.slice(4).trim().split("  ")[0]; continue; }
    if (!cur || !l.includes("框内=")) continue;
    const m = {};
    for (const [, k, v] of l.matchAll(/([^\s=]+)=([^\s]*)/g)) m[k] = v;
    o[cur] = m;
  }
  return o;
}
const R = p(process.argv[2]), A = p(process.argv[3]);
const QS = ["azure", "jiakao", "pinduoduo", "wisdomtree", "translate", "yiban", "me.ele",
  "zjol", "jingdong", "songpal", "weibo", "hunyuan"];
const key = (s) => QS.find((q) => s.toLowerCase().includes(q.toLowerCase())) || "?";
const fmt = (m) => "H=" + m.H + " 底占=" + m["底占"] + " 零=" + m["零"] + " 框内=" + m["框内"] +
  " 淡=" + m["淡"] + " 字均净=" + m["字均净"] + " cc=" + m.cc + " 盘2=" + String(m["盘2"]).split("/")[0];
const AR = {}; for (const k in A) AR[key(k)] = A[k];
let diff = 0;
for (const k in R) {
  const q = key(k); const a = AR[q];
  const same = a && a.H === R[k].H && a["底占"] === R[k]["底占"] && a["框内"] === R[k]["框内"];
  if (!same) diff++;
  console.log(q + (same ? "  两张一致" : "  ** 两张不是同一张像素 **"));
  console.log("  屏幕上 " + fmt(R[k]));
  if (a) console.log("  我用的 " + fmt(a));
}
console.log("\n7 张里 " + diff + " 张两条通道给的数不同");
