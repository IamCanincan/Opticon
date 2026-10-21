// 对表偏差**分方向**：多画的 36 张里，哪些是"设计师的墨是我们的子集"（我们只是外面多涂一圈），
// 哪些是"涂在互补的地方"（位置根本不同意）。这两种病的修法不同，混在一个 mean 里看不出来。
//
// 为什么要单独一个脚本：`YardKt` 自报的是 `med/mean/iouMed/dAlphaMed` 四个总量，
// 而"多涂一圈"与"涂错地方"在 mean 上长得一模一样（都是差 40 点）。
// 2026-10-09 主人把裁判换成"已适配取色的图标"之后，第一件事就是把这个差拆开 ——
// 拆出来是 28 张子集型 + 少数几张互补型（documentsui `IoU 0.00`、我们 67% vs 人画 18%）。
//
// 跑法：`sh run.sh harness.YardKtKt corpus_audit3 --dump=yard_dump.txt >/dev/null && node _yarddir.cjs yard_dump.txt`
const fs = require("fs");
const f = process.argv[2] || "yard_dump.txt";
const L = fs.readFileSync(f, "utf8").trim().split("\n");
const rows = [];
for (const l of L.slice(L[0].includes("iou") ? 1 : 0)) {
  const p = l.split("\t");
  if (p.length < 5) continue;
  const iou = +p[1], da = +p[2], us = +p[3], them = +p[4];
  if (![iou, da, us, them].every(Number.isFinite)) continue;
  rows.push({ pkg: p[0], iou, da, us, them });
}
const sub = (r) => (Math.min(r.us, r.them) / Math.max(r.us, r.them, 1e-6));
const over = rows.filter((r) => r.us > r.them), under = rows.filter((r) => r.us <= r.them);
const subset = over.filter((r) => sub(r) > 0 && r.iou / sub(r) > 0.8);
const wrong = over.filter((r) => !(sub(r) > 0 && r.iou / sub(r) > 0.8));
console.log(`对表 ${rows.length} 对：我们多画 ${over.length} 张、少画 ${under.length} 张`);
console.log(`  多画里 **子集型**（同一张图外面多涂一圈）：${subset.length} 张` +
  ` —— 平均多 ${(subset.reduce((a, r) => a + r.us - r.them, 0) / Math.max(subset.length, 1)).toFixed(1)} 点墨`);
console.log(`  多画里 **互补型**（位置根本不同意，IoU 远低于面积比）：${wrong.length} 张`);
console.log("\n互补型名单（这一族不是「少涂一点」能修的，是「该上墨的是另一块」）：");
for (const r of wrong.sort((a, b) => b.da - a.da).slice(0, 12))
  console.log(`  ${r.pkg.padEnd(36)} 差 ${r.da.toFixed(1).padStart(5)} 点 · ` +
    `IoU ${r.iou.toFixed(2)}（面积比 ${(sub(r) * 100).toFixed(0)}%）· 我们 ${r.us.toFixed(0)}% vs 人画 ${r.them.toFixed(0)}%`);
console.log("\n子集型最偏的 8 张（这一族是「多涂的那一圈是什么」）：");
for (const r of subset.sort((a, b) => (b.us - b.them) - (a.us - a.them)).slice(0, 8))
  console.log(`  ${r.pkg.padEnd(36)} 多 ${(r.us - r.them).toFixed(1).padStart(5)} 点 · 我们 ${r.us.toFixed(0)}% vs 人画 ${r.them.toFixed(0)}%`);
console.log("\n少画的 26 张里最偏的 6 张（这一族是「该上的没上」）：");
for (const r of under.sort((a, b) => a.da - b.da).slice(0, 6))
  console.log(`  ${r.pkg.padEnd(36)} 差 ${r.da.toFixed(1).padStart(5)} 点 · 我们 ${r.us.toFixed(0)}% vs 人画 ${r.them.toFixed(0)}%`);
