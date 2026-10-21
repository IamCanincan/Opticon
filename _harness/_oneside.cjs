// 数"墨整个落在参照暗侧"的图标有多少张 —— 这是"让亮度轴参与排序"那条改动的**作用面**。
// 端=lo/hi 是**有符号** ΔL（参照 − 源亮度）的 p5/p95：lo>0 ⇒ 连最亮的那 5% 墨都比参照暗
// ⇒ dep 排序与亮度排序同向 ⇒ 用亮度去展开拓扑不会推翻极性。
// ⚠ 不写带反斜杠的正则（这条 Bash 通道会把 \\ 收成一个，量具会静默没牙）。
const fs = require("fs");
const COLS = ["端", "零", "实", "框内", "顶占比", "字均净", "淡", "明暗", "色驱", "底占", "H"];
function parse(file) {
  const out = [];
  let cur = null;
  for (const l of fs.readFileSync(file, "utf8").split("\n")) {
    if (l.startsWith("--- ")) { cur = l.slice(4).trim().split("  ")[0]; continue; }
    if (!cur || !l.includes("端=")) continue;
    const m = {};
    for (const [, k, v] of l.matchAll(/([^\s=]+)=([^\s]*)/g)) m[k] = v;
    out.push([cur, m]); cur = null;
  }
  return out;
}
for (const f of process.argv.slice(2)) {
  const rows = parse(f);
  const one = rows.filter(([, m]) => (m["端"] || "-1/-1").split("/")[0] > 0);
  const wide = one.filter(([, m]) => {
    const [a, b] = (m["端"] || "0/0").split("/").map(Number);
    return b - a >= 30;                       // 亮度那一侧真有 30 个 L* 可铺
  });
  const sat = one.filter(([, m]) => +m["顶占比"] >= 55);
  console.log("== " + f + " 共 " + rows.length + " 张");
  console.log("   墨全在暗侧(端 lo>0)：" + one.length + " 张；其中跨度≥30：" + wide.length +
    " 张；其中顶占比≥55：" + sat.length + " 张");
  console.log("   跨度≥30 的名单（端 / 零-实 / 顶占比 / 字均净 / 淡 / 明暗）：");
  for (const [n, m] of wide.sort((x, y) => (+y[1]["顶占比"]) - (+x[1]["顶占比"])).slice(0, 16))
    console.log("     " + n.padEnd(46) + " 端=" + m["端"] + " 窗=" + m["零"] + "-" + m["实"] +
      " 顶=" + m["顶占比"] + " 净=" + m["字均净"] + " 淡=" + m["淡"] + " 明暗=" + m["明暗"]);
}
