// dial 的代价落在"几块"的图上？—— 从两份 MaskBench 对照里把 字均净 和 分档 两块一起读出来。
// 跑法：node _gate_parts.cjs <改前> <改后> [闸门块数]
const fs = require("fs");
function parse(p) {
  const L = fs.readFileSync(p, "utf8").split(/\r?\n/); const m = new Map(); let n = null;
  for (const l of L) {
    const h = l.match(/^---+\s+(\S+)/); if (h) { n = h[1]; continue }
    if (l.includes("色阶=") && n) { m.set(n, l); n = null }
  }
  return m
}
const t = (l, k) => { for (const x of l.split(" ")) if (x.startsWith(k + "=")) return x.slice(k.length + 1); return "-" };
const [,, fa, fb, gmin] = process.argv;
const GMIN = +(gmin || 5);
const A = parse(fa), B = parse(fb);
const short = k => (k.match(/icon\d+_(.*)-src/) || [, k])[1].slice(0, 44);
const crossed = [], gained = [];
let fA = 0, fB = 0;
for (const k of A.keys()) {
  const a = A.get(k), b = B.get(k); if (!b) continue;
  const za = +t(a, "字均净"), zb = +t(b, "字均净");
  const s = t(a, "分档"), q = t(b, "分档");   // 日志口径是 `档数/块数(...)`，别把第一个数当块数
  const la = +s.split("/")[0], pa = +s.replace(/^\d+\//, "").replace(/\).*/, "").split(",").length;
  const lb = +q.split("/")[0], pb = +q.replace(/^\d+\//, "").replace(/\).*/, "").split(",").length;
  const sa = s.replace(/^\d+\/\d+/, ""), sb = q.replace(/^\d+\/\d+/, "");
  if (za >= 0 && za < 200) fA++;
  if (zb >= 0 && zb < 200) fB++;
  if (za >= 200 && zb < 200) crossed.push({ n: short(k), za, zb, la, pa, lb, pb, sa, sb });
  else if (lb > la) gained.push({ n: short(k), za, zb, la, pa, lb, pb, sa, sb });
}
crossed.sort((x, y) => y.za - x.za); gained.sort((x, y) => (y.lb - y.la) - (x.lb - x.la) || y.za - x.za);
const byPart = xs => { const c = {}; for (const x of xs) c[x.pa] = (c[x.pa] || 0) + 1; return Object.entries(c).sort((a, b) => a[0] - b[0]).map(([k, v]) => `${k}块×${v}`).join("  ") };
console.log(`淡(<200) ${fA}→${fB}   张数=${A.size}`);
console.log(`\n代价侧：新掉进淡的 ${crossed.length} 张，按**改前块数**分布 → ${byPart(crossed)}`);
for (const x of crossed) console.log(`   ${String(x.za).padStart(4)}→${String(x.zb).padStart(4)}  ${x.la}档/${x.pa}块${x.sa.padEnd(26)} → ${x.lb}档/${x.pb}块${x.sb.padEnd(26)} ${x.n}`);
console.log(`\n收益侧：分档(档数)变多的 ${gained.length} 张，按改前块数分布 → ${byPart(gained)}`);
for (const x of gained.slice(0, 16)) console.log(`   ${x.la}→${x.lb}档/${x.pa}块  字均净${x.za}→${x.zb}  ${x.n}`);
const costExcluded = crossed.filter(x => x.pa < GMIN).length;
const benefitKept = gained.filter(x => x.pa >= GMIN).length;
console.log(`\n闸门「只有改前块数>=${GMIN} 才放开地板」：挡掉代价 ${costExcluded}/${crossed.length} 张，保住收益 ${benefitKept}/${gained.length} 张`);
for (let g = 2; g <= 6; g++) {
  const ce = crossed.filter(x => x.pa < g).length, bk = gained.filter(x => x.pa >= g).length;
  console.log(`   门槛>=${g}：免掉 ${ce}/${crossed.length} 张代价，留下 ${bk}/${gained.length} 张收益`);
}
