// 第十四闸「无对岸半灰账」—— 我们交了 α128~239 的一层灰，而**设计师那一格本来是实心黑或本来是空**。
// 来源：主人 2026-10-13 看图之后那句「就是部分图标有点掉色」。
//
// 为什么"掉色"不能用模型自己的 `顶占比`（那一项 32/62 张 <80%，看着像一大笔债）：
//   层次本来就是靠"不满量程"换的，仓里 `顶平%` 那一项甚至把贴顶太多当坏事。
// 为什么也不能用"两边都上墨的格的平均浓度"：那个数 61 张里只有 1 张 <200 —— **浓度根本没给低**。
// 只有这一把对得上他的话：**那片灰是人画根本没有的**（`YardKt` 的 `greyBadPct` 列）。
//
// ⚠ 判据为什么是**总量**而不是张数：`--scan` 摊过分位数，这条尾巴从 81% 连续降到 5%，
//   **没有一条宽边距的线**（≥5% 是 18 张，而第 18 名 5.24% 与第 19 名 4.49% 只差 0.75pp）。
//   骑在人群上的阈值 = 任何一刀都会让它翻牌，翻牌又不代表变坏。所以：
//     · 红绿灯 = `sum`（Σ 各张的 %，**无阈值**，任何净变差都会动它）
//     · 附一个 `n5`（≥5% 画布的张数 = 当前 18）当尾部名单的计数器，同样只许涨回来时红
//   ⚠ 总量这一类有一个已知盲区：**一张变好一张变坏可以互相抵成零**。所以名单必须逐张印出来。
//
// 跑法：node _grey.cjs [--scan] [--pin] [--floor="sum=3000 n5=17"] [--in=…]
const fs = require("fs");
const arg = (k, d) => {
  const pre = `--${k}=`;
  const a = process.argv.find((x) => x.startsWith(pre));
  return a === undefined ? d : a.slice(pre.length);
};
const IN = arg("in", "bench/reg/yard_dump.tsv");
const PIN = "bench/reg/grey.pin";
const TAIL = +arg("tail", 5);          // 尾部线：≥5% 画布
if (!fs.existsSync(IN)) {
  console.error(`!! 没有 ${IN} —— 第十四闸没跑。缺文件不等于「一张都没有」，第 5 闸必须带 --dump。`);
  process.exit(2);
}
const raw = fs.readFileSync(IN, "utf8").split(/\r?\n/).filter((l) => l.trim());
const head = (raw[0] || "").split("\t");
const iG = head.indexOf("greyBadPct"), iI = head.indexOf("iou"), iU = head.indexOf("ourPct");
// **两把方向尺（2026-10-20 拆的）**：`greyBadPct` 一个数把两件相反的事混在一起 ——
//   人画实心我们交灰 = **掉色**（该往上抬），人画留空我们交灰 = **铺雾**（该往下压）。
//   不拆开的话，一刀"压雾"会把总量做得很好看，同时把 22 对的掉色做得更狠，而这一闸照样绿。
const iF = head.indexOf("theirTopOurGreyPct"), iE = head.indexOf("theirEmptyOurGreyPct");
if (iG < 0 || iU < 0) {
  console.error(`!! ${IN} 里没有 greyBadPct/ourPct 列（表头：${head.join("|")}）—— 对表台比这台脚本旧，重跑 --dump`);
  process.exit(2);
}
if (iF < 0 || iE < 0) {
  console.error(`!! ${IN} 里没有 theirTopOurGreyPct / theirEmptyOurGreyPct 两列 —— 拆不开方向就不许绿` +
    `（旧表头 = 旧口径：拿它当"这一项看过了"是假绿）。重跑第 5 闸的 --dump。`);
  process.exit(2);
}
if (raw.length < 2) { console.error(`!! ${IN} 零行 —— 对表台没出数。零行**不算「这族一张都没有」**。`); process.exit(2); }
const R = raw.slice(1).map((l) => { const p = l.split("\t"); return { pkg: p[0], g: +p[iG], iou: +p[iI], us: +p[iU], f: +p[iF], e: +p[iE] }; })
  .filter((r) => Number.isFinite(r.g) && Number.isFinite(r.f) && Number.isFinite(r.e)).sort((a, b) => b.g - a.g);
// **静默丢掉一行 = 悄悄改了分母。** 第一版的正对照（手造一张 60% 灰）字段数写少了，被
//   `Number.isFinite` 一声不响地滤掉 → 数完全没动、闸"通过"了。那一行不是数据坏了，是**我在测一把
//   会漏的尺**。现在：滤掉任何一行都当场报出来（列数 + 原文），一行都不剩才 exit 2。
const dropped = raw.length - 1 - R.length;
if (dropped > 0) {
  const bad = raw.slice(1).filter((l) => !Number.isFinite(+(l.split("\t")[iG])));
  console.error(`!! ${dropped} 行的 greyBadPct 不是数（它们的列数 ${bad.map((l) => l.split("\t").length).join("/")}，表头 ${head.length} 列）—— 分母被动过：`);
  bad.slice(0, 3).forEach((l) => console.error(`   ${l.slice(0, 90)}`));
  console.error(`   其余 ${R.length} 行仍然数，但**这一闸不能算绿**。`);
}
const DEV_BAD = dropped > 0;   // 装置坏了：哪怕数没涨也不许绿（"少一项指标"在本仓响过两次）
if (R.length === 0) { console.error(`!! 一行的 greyBadPct 都不是数 —— 装置坏了，不算过`); process.exit(2); }
const sum = R.reduce((s, r) => s + r.g, 0);
const n5 = R.filter((r) => r.g >= TAIL).length;
// 掉色 / 铺雾 两个方向各一个总量。它们**必须刚好拼成总量**（定义上 `greyBad = 两类的并`），
// 拼不上就是哪一列被谁改坏了 —— 当场报，不许悄悄继续数。
const sumF = R.reduce((s, r) => s + r.f, 0);
const sumE = R.reduce((s, r) => s + r.e, 0);
const PART = Math.abs(sumF + sumE - sum);
const r1 = (v) => (Math.round(v * 10) / 10).toFixed(1);
const now = `sum=${r1(sum)} n5=${n5} fade=${r1(sumF)} fog=${r1(sumE)}`;

if (process.argv.includes("--scan")) {
  const s = R.map((r) => r.g);
  console.log(`${R.length} 张，无对岸半灰 % 画布：合计 ${sum.toFixed(1)}　中位 ${s[s.length >> 1].toFixed(2)}　p90 ${s[Math.floor(s.length * 0.9)].toFixed(2)}`);
  for (const t of [1, 2, 3, 5, 8, 10, 15]) console.log(`  ≥${String(t).padStart(2)}% → ${String(R.filter((r) => r.g >= t).length).padStart(2)} / ${R.length}`);
  console.log("\n前 20 名：");
  R.slice(0, 20).forEach((r, i) => console.log(`  ${String(i + 1).padStart(2)}. ${r.g.toFixed(2).padStart(6)}%  IoU ${r.iou.toFixed(3)}  我墨 ${r.us.toFixed(1)}%  ${r.pkg}`));
  process.exit(0);
}

console.log(`无对岸半灰（人画是黑或空、我们给 α128~239 的格，占画布%）：${R.length} 张　合计 ${sum.toFixed(1)}pp　≥${TAIL}% ${n5} 张`);
console.log(`  拆成两个**相反**的方向：掉色（人画实心我们交灰，该抬）${sumF.toFixed(1)}pp ${R.filter(r => r.f >= 1).length} 张 >1%　|　` +
  `铺雾（人画留空我们交灰，该压）${sumE.toFixed(1)}pp ${R.filter(r => r.e >= 1).length} 张 >1%`);
for (const [k, nm] of [["f", "掉色"], ["e", "铺雾"]]) {
  const g = [...R].sort((a, b) => b[k] - a[k]).slice(0, 4);
  console.log(`  ${nm} 前 4：` + g.map(r => `${r[k].toFixed(1)}%(IoU ${r.iou.toFixed(2)}) ${r.pkg}`).join("　"));
}
R.slice(0, Math.max(n5, 6)).forEach((r) =>
  console.log(`    ${r.g.toFixed(2).padStart(6)}%  IoU ${r.iou.toFixed(3)}  我墨 ${r.us.toFixed(1).padStart(5)}%  ${r.pkg}`));
if (n5 < R.length) console.log(`    …其余 ${R.length - n5} 张在 ${TAIL}% 线以下（合计 ${(sum - R.slice(0, n5).reduce((s, r) => s + r.g, 0)).toFixed(1)}pp，仍然计进总量）`);

if (process.argv.includes("--pin")) { fs.writeFileSync(PIN, now + "\n"); console.log(`   已认账：${PIN} = ${now}`); process.exit(0); }
const floorArg = arg("floor", null);
const pin = floorArg !== null ? floorArg : (fs.existsSync(PIN) ? fs.readFileSync(PIN, "utf8").trim() : null);
if (pin === null) { console.error(`!! 没有 ${PIN} —— 没地板不算过（先 node _grey.cjs --pin 认一次账）`); process.exit(2); }
// **地板读不懂 / 缺键 = 装置坏了。** 少了这两句，`14 > undefined` 会把坏地板判成"持平"（本仓踩过）。
const base = {};
for (const kv of pin.split(/\s+/).filter(Boolean)) {
  const m = /^([a-z0-9]+)=(-?[\d.]+)$/.exec(kv);
  if (!m) { console.error(`!! 地板格式不认识：「${pin}」（要的是 "sum=N n5=N"）—— 坏地板不当约束`); process.exit(2); }
  base[m[1]] = +m[2];
}
const got = { sum: r1(sum), n5, fade: r1(sumF), fog: r1(sumE) };
if (PART > 0.6) { console.error(`!! 两方向拼不回总量（${r1(sumF)} + ${r1(sumE)} ≠ ${r1(sum)}，差 ${PART.toFixed(2)}）—— 有一列被改坏了，这一闸不算数`); process.exit(2); }
for (const k of Object.keys(got))
  if (base[k] === undefined) { console.error(`!! 地板「${pin}」里没有 ${k}= —— 这一半没人在守，不算过`); process.exit(2); }
const worse = Object.keys(got).filter((k) => got[k] > base[k] + 1e-9);
console.log(`   这轮 ${now} / 地板 ${pin}`);
if (DEV_BAD) { console.error("!! 有行被滤掉（分母不可信）—— 就算数没涨也**不算绿**"); process.exit(2); }
if (worse.length) {
  console.error(`!! 无对岸半灰**涨了**（${worse.map((k) => `${k} ${base[k]}→${got[k]}`).join(", ")}）—— 名单在上面，逐张看是哪一刀带来的`);
  console.error(`   ⚠ 总量这一类会把"一张变好 + 一张变坏"抵成零，所以**必须逐张看名单**再认账。`);
  process.exit(1);
}
console.log(`PRESS-GREY ${now} ≤ 基线 ${pin} —— 持平或更好`);
