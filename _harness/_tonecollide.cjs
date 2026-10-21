// 「两块被分到几乎同一浓度」在语料里有多大 —— 动手写规则之前先量它的爆炸半径。
// ⚠ 这里**还没算空间分离**，所以是上限：真正会动的只会更少。
// 用法：node _tonecollide.cjs [--gap=40] [--area=1000]
const fs = require("fs");
const arg = (k, d) => {
  const pre = `--${k}=`;
  const a = process.argv.find((x) => x.startsWith(pre));
  return a === undefined ? d : +a.slice(pre.length);
};
const GAP = arg("gap", 40), AREA = arg("area", 1000);
const rows = fs.readFileSync("bench/parts_all.txt", "utf8").trim().split(/\r?\n/);
let icons = 0, hit = 0, pairs = 0;
const list = [];
for (const r of rows) {
  const parts = r.split(";").slice(1).map((x) => {
    const [n, t] = x.split(":");
    return { n: +n, t: +t };
  }).filter((p) => p.n >= AREA);
  if (parts.length < 2) continue;
  icons++;
  const coll = [];
  for (let i = 0; i < parts.length; i++)
    for (let j = i + 1; j < parts.length; j++)
      if (Math.abs(parts[i].t - parts[j].t) <= GAP) {
        coll.push(`${parts[i].t}~${parts[j].t}`);
        pairs++;
      }
  if (coll.length) { hit++; list.push([coll.length, r.split(";")[0], coll.join(" ")]); }
}
console.log(`语料 ${icons} 张有 ≥2 块（每块 ≥${AREA} 格）：其中**有"两块浓度差 ≤${GAP}"的 ${hit} 张**，共 ${pairs} 对`);
console.log(`（上限值 —— 还没叠加"这两块在空间上分得开"这个条件）\n`);
for (const [n, name, coll] of list.sort((a, b) => b[0] - a[0]).slice(0, 16))
  console.log(`  ${String(n).padStart(2)} 对  ${name.slice(0, 46).padEnd(48)} ${coll}`);
const yi = rows.find((r) => r.includes("yiban"));
if (yi) {
  console.log(`\n易班全部块：`);
  for (const x of yi.split(";").slice(1)) {
    const [n, t, lab] = x.split(":");
    console.log(`  面积 ${String(n).padStart(6)}  交付 ${String(t).padStart(3)}  源色 ${lab}`);
  }
}
