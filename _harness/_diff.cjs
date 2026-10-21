// 把两份判据台报告按图标逐列对比，只报**真的变了**的那些张、变了哪几列。
// 为什么要它：`regress.sh` 只给合计与中位，一个中位从 0.962 掉到 0.935 看不出是
//   "一整族被带坏"还是"就是那一张换了支" —— 而这两种结论对下一步完全相反。
// 用法：node _diff.cjs old.txt new.txt [关注的列名 …]
const fs = require("fs");
function load(p) {
  const out = new Map();
  let name = null, buf = [];
  for (const L of fs.readFileSync(p, "utf8").split(/\r?\n/)) {
    const m = L.match(/^--- (.+?)\s*$/);
    if (m) { if (name) out.set(name, buf.join(" ")); name = m[1].replace(/\s+\d+ms\s*$/, "").trim(); buf = []; continue; }
    if (name && L.trim()) buf.push(L.trim());
  }
  if (name) out.set(name, buf.join(" "));
  return out;
}
const a = load(process.argv[2]), b = load(process.argv[3]);
const watch = process.argv.slice(4);
const keys = new Set([...a.keys(), ...b.keys()]);
let changed = 0;
for (const k of [...keys].sort()) {
  const ra = a.get(k), rb = b.get(k);
  if (ra === rb) continue;
  changed++;
  if (!ra || !rb) { console.log(`\n${k}  只在一边有`); continue; }
  const pa = Object.fromEntries((ra.match(/(\S+?)=(-?\[[^\]]*\]|-?[0-9./~%]+|-?[一-龥A-Za-z0-9.%]+)/g) || []).map(s => {
    const i = s.indexOf("="); return [s.slice(0, i), s.slice(i + 1)];
  }));
  const pb = Object.fromEntries((rb.match(/(\S+?)=(-?\[[^\]]*\]|-?[0-9./~%]+|-?[一-龥A-Za-z0-9.%]+)/g) || []).map(s => {
    const i = s.indexOf("="); return [s.slice(0, i), s.slice(i + 1)];
  }));
  const diffs = [...new Set([...Object.keys(pa), ...Object.keys(pb)])]
    .filter(k2 => pa[k2] !== pb[k2])
    .map(k2 => `${k2} ${pa[k2]}→${pb[k2]}`);
  const rel = watch.length === 0 || watch.some(w => diffs.some(d => d.startsWith(w + " ")));
  if (rel) console.log(`\n${k}\n  ` + diffs.slice(0, 14).join("\n  "));
}
console.log(`\n== 共 ${changed}/${keys.size} 张的输出变了 ==`);
