// 第十闸的"为什么"：把换支的那几张图标，在每一档边长上的入场读数摊开。
// _branchstab.cjs 只报"谁换支、几张"，不报"为什么换" —— 而 5/5 骑在底线上这件事，
// 只有看到每张离入场线多远才知道该抬底线还是该查刀。
// 用法：node _branchwhy.cjs <名单，逗号分隔> bench/reg/bench_96.txt bench/reg/bench_192.txt ...
const fs = require("fs");
const names = (process.argv[2] || "").split(",").filter(Boolean);
const files = process.argv.slice(3);
if (!names.length || files.length < 2) {
  console.log("用法: node _branchwhy.cjs <包名片段,片段> bench/reg/bench_*.txt"); process.exit(2);
}
const nm = f => f.replace(/^.*bench_/, "").replace(/\.txt$/, "");
// 每档：图标 → { 调, 色驱, 分档, 框内, 底占 }
const per = files.map(f => {
  const m = new Map(); let cur = null;
  for (const l of fs.readFileSync(f, "utf8").split(/\r?\n/)) {
    const h = /^--- (\S+)/.exec(l); if (h) { cur = h[1]; continue }
    if (!cur || !l.includes("色驱=")) continue;
    const g = k => { const r = new RegExp(k + "=([^ ]+)").exec(l); return r ? r[1] : "-" };
    m.set(cur, { tonal: l.includes("调=1") ? "1" : "0", span: g("色驱"), lv: g("分档"), box: g("框内"), ground: g("底占") });
  }
  return { edge: nm(f), m };
});
console.log("图标".padEnd(34) + per.map(p => p.edge.padStart(9) + "支".padStart(3)).join("") + "   每档：调=1 即走满幅那一支");
for (const want of names) {
  const key = [...new Set(per.flatMap(p => [...p.m.keys()]).filter(k => k.includes(want)))];
  if (!key.length) { console.log(`  ?? 没有图标名含「${want}」`); continue }
  for (const k of key.slice(0, 2)) {
    let line = k.replace(/^icon\d+_/, "").slice(0, 33).padEnd(34);
    for (const p of per) {
      const r = p.m.get(k);
      line += (r ? r.tonal : "·").padStart(6) + (r ? r.span : "").padStart(6);
    }
    console.log(line);
  }
}
console.log("\n（列宽：每档两小列 = 走没走那一支 / 该档的跨度读数）");
