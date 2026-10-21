// 从真机 logcat 里把"走了满幅插画那一支"的图标挑出来，按 sig 去重，
// 并把每一行的关键列扫成 map 再按**列名**取（不数第几列 —— 这条 Bash 通道会吞反斜杠，
// 带转义的正则在这里是"跑通了但一个字段都没匹配上"的那种假 0）。
const { execSync } = require("child_process");
const txt = execSync("adb logcat -d -s Opticon:V", { encoding: "utf8", maxBuffer: 1 << 28 });
const bySig = new Map();
let total = 0, tonal = 0;
for (const l of txt.split("\n")) {
  if (!l.includes("Mono: diag")) continue;
  total++;
  const m = {};
  for (const [, k, v] of l.matchAll(/([^\s=]+)=([^\s]*)/g)) m[k] = v;
  const isTonal = l.includes("调=1");
  if (isTonal) tonal++;
  const sig = m.sig || "?";
  if (!bySig.has(sig)) bySig.set(sig, { line: l, m, tonal: isTonal, n: 1 });
  else { const e = bySig.get(sig); e.n++; if (isTonal) e.tonal = true; }
}
console.log("diag 行 " + total + " 条 · 唯一 sig " + bySig.size + " 个 · 带 调=1 的行 " + tonal +
  " 条 · 走这一支的图标 " + [...bySig.values()].filter(e => e.tonal).length + " 个");
console.log("");
console.log("包名/资源".padEnd(52) + "边长  次数  纹理 主色 跨   框内   字均净 淡    cc   H");
for (const e of [...bySig.values()].filter(x => x.tonal)) {
  const head = e.line.match(/com\.[^\s]+?\/\S+/) || ["?"];
  console.log(head[0].padEnd(52) + (e.m.edge || "?").padStart(4) + (String(e.n).padStart(6)) +
    "  " + (e.m["纹理"] || "?").padStart(4) + (e.m["主色"] || "?").padStart(5) +
    (e.m["跨"] || "?").padStart(4) + "  " + (e.m["框内"] || "?").padStart(5) + "  " +
    (e.m["字均净"] || "?").padStart(5) + " " + (e.m["淡"] || "?").padStart(5) + " " +
    (e.m.cc || "?").padStart(4) + "  " + (e.m.H || "?"));
}
console.log("");
const named = ["browndust2", "sgameGlobal", "tmgp.sgame", "wisdomgame", "feimo", "translate"];
console.log("点名的那几个在不在真机这一轮里：");
for (const n of named) {
  const hits = [...bySig.values()].filter(e => e.line.includes(n));
  console.log("   " + n.padEnd(14) + (hits.length ? hits.map(e => (e.tonal ? "走新支" : "没走") + " sig=" + e.line.match(/sig=(\S+)/)?.[1]).join(", ") : "这一轮没被问到"));
}
