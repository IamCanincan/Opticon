// 比两份 MaskBench 的输出：按图标名对齐，逐列比，报"变了哪些张、往哪边变"。
// 为什么不用 shell join：判据行里有空格、两个版本字段数还可能不一样（本仓踩过）。
// ⚠ 这里**不写带反斜杠的正则** —— 这条 Bash 通道会把 \\ 收成一个，量具会静默没牙。
const fs = require("fs");
const COLS = ["框内", "淡", "字均净", "净顶", "顶占比", "空洞", "cc", "残盘", "分档", "降", "力度", "底占"];
const [fa, fb] = process.argv.slice(2, 4);
const only = process.argv[4] ? process.argv[4].split(",") : null;

function parse(file) {
  const map = new Map();
  let cur = null;
  for (const l of fs.readFileSync(file, "utf8").split("\n")) {
    if (l.startsWith("--- ")) { cur = l.slice(4).trim().split("  ")[0]; continue; }
    if (!cur || !l.includes("框内=")) continue;
    const m = {};
    for (const [, k, v] of l.matchAll(/([^\s=]+)=([^\s]*)/g)) m[k] = v;
    m["_fired"] = l.includes("调=1") ? "1" : "";
    map.set(cur, m); cur = null;
  }
  return map;
}
const A = parse(fa), B = parse(fb);
let changed = 0, same = 0;
const rows = [];
for (const [name, a] of B) {
  const b = A.get(name);
  if (!b) { console.log("!! 只在一份里有: " + name); continue; }
  const diffs = [];
  for (const c of COLS) if ((b[c] || "-") !== (a[c] || "-")) diffs.push(c + " " + (b[c] ?? "-") + "→" + (a[c] ?? "-"));
  if (!diffs.length) { same++; continue; }
  changed++;
  if (!only || only.some(o => name.toLowerCase().includes(o.toLowerCase())))
    rows.push(name.padEnd(46) + (a["_fired"] ? "[新支]" : "      ") + "  " + diffs.join("  "));
}
console.log("对齐 " + (A.size) + " / " + B.size + " 张：变了 " + changed + " 张，逐字相同 " + same + " 张");
for (const r of rows) console.log("  " + r);
