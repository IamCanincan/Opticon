// 从 bench/live61.txt 里按图标名挑几行，把判据列成 map 再按列名取。
// ⚠ 这条 Bash 通道会把反斜杠收成一个，所以这里**不写正则转义**：
//   一次 matchAll(/([^\s=]+)=([^\s]+)/g) 扫成 map，再按 key 取值（本仓踩过两次的那条）。
const fs = require("fs");
const want = process.argv.slice(2);
const lines = fs.readFileSync("bench/live61.txt", "utf8").split("\n");
let cur = null;
for (const l of lines) {
  if (l.startsWith("--- ")) { cur = l.slice(4).trim().split("  ")[0]; continue; }
  if (!cur || !l.includes("框内=")) continue;
  if (!want.some(w => cur.toLowerCase().includes(w.toLowerCase()))) continue;
  const m = {};
  for (const [, k, v] of l.matchAll(/([^\s=]+)=([^\s]*)/g)) m[k] = v;
  console.log(cur);
  console.log("   H=" + m.H + " 底占=" + m["底占"] + " 族=" + m["族"] + " 拦=" + m["拦"] +
    " 盘实=" + m["盘实"] + " 零=" + m["零"] + " 实=" + m["实"] + " 明暗=" + m["明暗"] +
    " 色驱=" + m["色驱"]);
  console.log("   框内=" + m["框内"] + " 淡=" + m["淡"] + " cc=" + m.cc + " top=" + m.top +
    " 空洞=" + m["空洞"] + " 字均净=" + m["字均净"] + " 净顶=" + m["净顶"] +
    " 残盘=" + m["残盘"] + " 分档=" + (m["分档"] || "") + (l.includes("调=1") ? "  走了新支" : ""));
  console.log("");
}
