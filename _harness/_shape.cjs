// 第十三闸「对表外形账」—— 拿设计师手画的那一层当对岸，数**外形对不上**的有几张。
//
// 为什么要有这一闸：其余每一项（淡 / 残盘 / 分档 / ρ色 / 平色饼 / 第十二闸的中调被压）量的都是
// **模型自己的口径**。`_harness/bench/reg/yard_dump.tsv` 是逐张的 我墨/人墨/IoU，来自真机上
// 61 个人工画好的单色层 —— 这是台子里唯一一把外部真值。第 5 闸一直只报**中位数**（iouMed 0.940），
// 而中位数会糊掉尾部：44 张像到 0.94+ 与 14 张完全不像（IoU 0.002~0.455）在中位数上是同一个数。
// 主人报过的「还剩一块大色块」「反色」「半边没有」全在尾部那 14 张里，所以数**尾部张数**，不数中位数。
//
// 两个数（都只许降不许涨）：
//   `iou_low` = IoU<0.5 的张数 —— **外形根本对不上**（墨量可以一样，位置是两套）。
//   `over`    = 我墨 ≥ 人画 2× 且差 ≥10pp 的张数 —— 尾部的**主因**：把承载面当墨涂 / 把描边填成实心。
//
// 判据为什么带「且差 ≥10pp」：只看比值有小分母陷阱 —— 人画 0.8% 时我们随手 2% 就"超标"，
// 屏幕上那 1.2 个点什么都看不见。`--scan` 把这一条单独摊出来（只看比值 vs 加上绝对差各掉几张）。
//
// 跑法：
//   node _shape.cjs --scan         # 摊分布、定切点用，**不是闸**
//   node _shape.cjs                # 数两个数 + 名单；涨了 exit 1，装置坏 exit 2
//   node _shape.cjs --floor="iou_low=13 over=5"   # 覆盖地板（变异测试用）
//   node _shape.cjs --pin          # 把这一轮认账写进 bench/reg/shape.pin
const fs = require("fs");
// ⚠ **参数解析必须自己验一次落地**（这文件第一版把 `a.substring(a.length + k.length + 2)` 当成
//   取 `--k=v` 的值，于是**每一个** `--` 都解析成空串：`--in=` 走成"文件不存在"、`--floor=` 走成
//   "地板是空" —— 而空地板被判成「持平或更好」。变异测试当时"通过"了，过的正是这个坏路径。
const arg = (k, d) => {
  const pre = `--${k}=`;
  const a = process.argv.find((x) => x.startsWith(pre));
  return a === undefined ? d : a.slice(pre.length);
};
const IN = arg("in", "bench/reg/yard_dump.tsv");
const PIN = "bench/reg/shape.pin";
const K = +arg("ratio", 2);
const D = +arg("gap", 10);
const IOU = +arg("iou", 0.5);

// 读数文件必须**存在且非空**。这一条不是洁癖：第 5 闸的 YardKt 一旦被谁挪掉 `--dump`，
// 这份文件会停在上一轮 —— 上一轮是绿的，于是这一闸变成一把**永远不响**的尺（本仓踩过两次）。
if (!fs.existsSync(IN)) {
  console.error(`!! 没有 ${IN} —— 第十三闸没跑。缺文件不等于「一张都没有」，第 5 闸必须带 --dump。`);
  process.exit(2);
}
const raw = fs.readFileSync(IN, "utf8").split(/\r?\n/).filter((l) => l.trim());
const head = (raw[0] || "").split("\t");
if (raw.length < 2 || head[0] !== "pkg" || head[1] !== "iou" || head[3] !== "ourPct" || head[4] !== "theirPct") {
  console.error(`!! ${IN} 不可读（${raw.length - 1} 行数据，表头 ${head.join("|")}）`);
  console.error(`   表头必须是 pkg|iou|dAlpha|ourPct|theirPct。零行 = 对表台没出数，**不算「这族一张都没有」**。`);
  process.exit(2);
}
const R = raw.slice(1).map((l) => {
  const p = l.split("\t");
  return { pkg: p[0], iou: +p[1], us: +p[3], them: +p[4] };
});
// 人画那层本身没墨的不算对岸（没有"多涂"可言）。当场报剔了几张，别让它悄悄变分母。
const empty = R.filter((r) => r.them < 0.5);
const V = R.filter((r) => r.them >= 0.5);
const low = V.filter((r) => r.iou < IOU).sort((a, b) => a.iou - b.iou);
const over = V.filter((r) => r.us >= K * r.them && r.us - r.them >= D).sort((a, b) => b.us / b.them - a.us / a.them);

if (process.argv.includes("--scan")) {
  console.log(`样本 ${R.length} 对（人画 <0.5% 剔掉 ${empty.length}：${empty.map((r) => r.pkg).join(", ") || "无"}）`);
  console.log("\nIoU 升序 = 外形最不像的在前；比值 = 我墨/人墨")
  for (const r of [...V].sort((a, b) => a.iou - b.iou))
    console.log(
      `  ${r.iou.toFixed(3)}  我 ${r.us.toFixed(1).padStart(5)}%  人 ${r.them.toFixed(1).padStart(5)}%  ×${(r.us / r.them).toFixed(2).padStart(5)}  差 ${(r.us - r.them).toFixed(1).padStart(6)}pp   ${r.pkg}`
    );
  console.log("\n切点扫描（比值线 vs 比值+绝对差 → 看假阳有多少）");
  for (const k of [1.5, 2, 2.5, 3, 4])
    for (const d of [0, 5, 10, 15]) {
      const hit = V.filter((r) => r.us >= k * r.them && r.us - r.them >= d).length;
      const only = V.filter((r) => r.us >= k * r.them).length;
      console.log(`  比值 ≥${String(k).padEnd(4)} 且 差 ≥${String(d).padStart(2)}pp → ${String(hit).padStart(2)} / ${V.length}（只看比值 ${only}，假阳 ${only - hit}）`);
    }
  console.log("\n切点扫描（IoU 线）");
  for (const x of [0.3, 0.4, 0.5, 0.6, 0.7])
    console.log(`  IoU <${x} → ${V.filter((r) => r.iou < x).length} / ${V.length}`);
  process.exit(0);
}

console.log(`对表外形（${V.length} 对，人画没墨的 ${empty.length} 张已剔）`);
console.log(`  IoU<${IOU}（外形对不上）：${low.length} 张`);
for (const r of low) console.log(`    ${r.pkg.padEnd(44)} IoU ${r.iou.toFixed(3)}  我 ${r.us.toFixed(1)}% vs 人 ${r.them.toFixed(1)}%  ×${(r.us / r.them).toFixed(2)}`);
console.log(`  其中「涂整张」（我墨 ≥${K}× 且 差 ≥${D}pp）：${over.length} 张 —— ${over.map((r) => r.pkg).join(", ") || "无"}`);

// —— 归因侧档（**只报数、不当闸**）：尾部那几张各走了哪一支。红绿灯只数张数，它不回答"该动哪一刀"；
//   而这一族最容易被读成"一律涂多了" —— 实际里面一半走满幅那一支（已知是墙）、两张方向相反。
//   吃的是 `YardKt --why` 那份读数（模型自报行 + 对表的 IoU/墨占比）；缺文件就整段跳过、不红：
//     java -cp … harness.YardKtKt yard_model --input=model --why > bench/yard_why.txt
const WHYF = "bench/yard_why.txt";
if (fs.existsSync(WHYF)) {
  const W = {};
  for (const l of fs.readFileSync(WHYF, "utf8").split(/\r?\n/))
    // `WHY <pkg>\t<iou>\t<us%>\t<them%>\t<模型自报行>` —— 去掉前缀后 report 在 **p[4]**。
    // （第一版写成 p[5]，于是整段侧档印成一片 `-`：解析错不会自己喊出来，只能靠"这一列怎么全空"看出来。）
    if (l.startsWith("WHY ")) { const p = l.slice(4).split("\t"); W[p[0]] = { rep: p[4] || "" }; }
  const tok = (rep, k) => { const m = new RegExp("(?:^| )" + k + "=([^ ]*)").exec(rep); return m ? m[1] : "-"; };
  const pk = Object.keys(W);
  // **侧档不会自己变旧，但它会。** 这份读数不在 `bench/reg/`（那里每轮 `rm *.txt` 会扫掉它），
  //   所以模型改过之后它还是上一轮的归因 —— 那比没有更危险。比一下 mtime，旧了就当场说。
  const MASK = "../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt";
  if (fs.existsSync(MASK) && fs.statSync(MASK).mtimeMs > fs.statSync(WHYF).mtimeMs)
    console.log(`   ⚠ ${WHYF} 比模型**旧** —— 下面这段归因是上一版的，别拿它定这一刀（重跑见本文件注释）`);
  else {
  // **侧档全空 = 解析坏了，不是"这些图标没有判据行"。** 没有这一句，一次列位错会印出一整屏 `-`
  // 而下面那行"满幅 0 张"看着像个结论（本仓那条老规矩：0 hits 是关于量具的陈述，不是关于语料的）。
  const parsed = pk.filter((p) => W[p].rep.includes("底占=")).length;
  if (pk.length > 0 && parsed === 0)
    console.log(`   !! ${WHYF} 有 ${pk.length} 行，但**一列都没解析出来** —— 自报行的格式变了，侧档全部不可信`);
  if (pk.length === 0) console.log(`   ⚠ ${WHYF} 里没有 WHY 行 —— 归因侧档这轮没数（不算红，但别读成"没有分歧"）`);
  else {
    const ton = (p) => W[p] && W[p].rep.includes(" 调=1");
    const inW = (p) => W[p] !== undefined;
    const tail = low.map((r) => r.pkg).filter(inW);
    const tT = tail.filter(ton).length;
    const allT = pk.filter(ton).length;
    console.log(
      `   按支路分尾部（${tail.length}/${low.length} 张有自报行）：满幅那一支 ${tT} 张、参照那一支 ${tail.length - tT} 张` +
      `　｜全语料 满幅 ${allT} / 参照 ${pk.length - allT} → 掉进尾部的概率 满幅 ${(tT * 100 / Math.max(allT, 1)).toFixed(0)}% vs 参照 ${((tail.length - tT) * 100 / Math.max(pk.length - allT, 1)).toFixed(0)}%`
    );
    for (const r of low)
      if (inW(r.pkg))
        console.log(
          `     ${(ton(r.pkg) ? "满幅" : "参照").padEnd(4)} ${r.pkg.padEnd(42)} IoU ${r.iou.toFixed(3)} ×${(r.us / r.them).toFixed(2).padStart(5)}  ` +
          `底占=${tok(W[r.pkg].rep, "底占")} 盘层=${tok(W[r.pkg].rep, "盘层")} 盘候=${tok(W[r.pkg].rep, "盘候")} 盘刀=${tok(W[r.pkg].rep, "盘刀")} 反相=${tok(W[r.pkg].rep, "反相")} 跨=${tok(W[r.pkg].rep, "跨")}`
        );
  }
  }
} else console.log(`   （没有 ${WHYF} —— 归因侧档跳过，红绿灯不受影响。造它：见本文件注释）`);

const now = `iou_low=${low.length} over=${over.length}`;
if (process.argv.includes("--pin")) {
  fs.writeFileSync(PIN, now + "\n");
  console.log(`   已认账：${PIN} = ${now}`);
  process.exit(0);
}
const floorArg = arg("floor", null);
const pin = floorArg !== null ? floorArg : (fs.existsSync(PIN) ? fs.readFileSync(PIN, "utf8").trim() : null);
if (pin === null) {
  console.error(`!! 没有 ${PIN} —— 没地板不算过（先 node _shape.cjs --pin 认一次账）`);
  process.exit(2);
}
// **地板读不懂 = 装置坏了，不是"没有约束"。** 且两个键都必须齐：少一个键就是那一半在裸奔
// （第一版这里用 `Object.fromEntries(...map(Number))`，格式不对时得到 `{"0":undefined}`，
//  而 `14 > undefined` 是 false —— 于是**空地板 / 坏地板全绿**，一把永远不响的尺。）
const base = {};
for (const kv of pin.split(/\s+/).filter(Boolean)) {
  const m = /^([a-z_]+)=(\d+)$/.exec(kv);
  if (!m) { console.error(`!! 地板格式不认识：「${pin}」（要的是 "iou_low=N over=N"）—— 坏地板不当约束`); process.exit(2); }
  base[m[1]] = +m[2];
}
const got = { iou_low: low.length, over: over.length };
for (const k of Object.keys(got))
  if (base[k] === undefined) { console.error(`!! 地板「${pin}」里没有 ${k}= —— 这一半没人在守，不算过`); process.exit(2); }
for (const k of Object.keys(base))
  if (got[k] === undefined) { console.error(`!! 地板里有个不认识键 ${k}= —— 判据改过名，先把两边对上再跑`); process.exit(2); }
const worse = Object.keys(got).filter((k) => got[k] > base[k]);
console.log(`   这轮 ${now} / 地板 ${pin}`);
if (worse.length) {
  console.error(`!! 对表外形**涨了**（${worse.map((k) => `${k} ${base[k]}→${got[k]}`).join(", ")}）`);
  console.error(`   名单在上面，挨个看是哪一刀带来的；确认真的变好了要 --pin 认账。`);
  process.exit(1);
}
console.log(`PRESS-SHAPE ${now} ≤ 基线 ${pin} —— 持平或更好`);

// ── 第十三闸的**逐张**那一位（2026-10-21 加，`--ioupin=<文件>`）──────────────────────────
// 为什么必须补：这一闸原本只数**两个尾部张数**（iou_low / over）。2026-10-21 语义变异现量它有多粗：
//   把选支的淡膜门槛 `PICK_SOFT_MAX` 从 4 收到 0 ⇒ **62 张里 4 张 IoU 退让、最差一张 -22.7pp**，
//   而 `iou_low=14 over=5` **一格没动** ⇒ 老判据绿。它不是"没牙"，是**只咬跨过那条线的**：
//   "某一张的外形变差了"这件事它结构性看不见（中位数与张数都会糊掉逐张，这条台子里写过两遍了）。
// ⚠ 阈值取 **0**（任何一张退让都算红），因为**不拍常数**：`yard_model` 的输入是语料里的固定 PNG，
//   YardKt 是纯函数 ⇒ 同一份代码跑两遍逐张 IoU 一模一样，退让只可能是代码改动的结果。
//   （要接受一张的退让，就用 `--iouwrite` 重新认账 —— 那是人的动作，不是量具的默认。）
// ⚠ 三件装置自证：基线文件缺/坏 ⇒ 退 2（不算过）；**张数与本轮对不上 ⇒ 退 2**（分母被动过）；
//   本轮某张在基线里没有 ⇒ 退 2（新加了一张对岸，先认账再判）。
const IOUP = arg("ioupin", null);
if (IOUP !== null) {
  if (process.argv.includes("--iouwrite")) {
    fs.writeFileSync(IOUP, R.map(r => `${r.pkg}\t${r.iou.toFixed(6)}`).join("\n") + "\n");
    console.log(`   已认账逐张基线：${IOUP}（${R.length} 张，Σ=${R.reduce((s, r) => s + r.iou, 0).toFixed(4)}）`);
    process.exit(0);
  }
  if (!fs.existsSync(IOUP)) { console.error(`!! 没有 ${IOUP} —— 逐张那一位没基线，不算过（先 --iouwrite 认一次）`); process.exit(2); }
  const base = new Map();
  fs.readFileSync(IOUP, "utf8").replace(/\r/g, "").split("\n").filter(l => l.trim()).forEach(l => {
    const c = l.split("\t"); if (c.length < 2) { console.error(`!! ${IOUP} 有一行不是「包\t数」：${l}`); process.exit(2); }
    base.set(c[0], +c[1]);
  });
  if (base.size !== R.length) {
    console.error(`!! 逐张基线 ${base.size} 张，本轮 ${R.length} 张 —— **分母对不上**（对表语料被动过），不算过`);
    process.exit(2);
  }
  const drops = [];
  for (const r of R) {
    const b = base.get(r.pkg);
    if (b === undefined) { console.error(`!! 本轮出现基线里没有的一张：${r.pkg} —— 先 --iouwrite 认账`); process.exit(2); }
    if (r.iou < b - 1e-9) drops.push(`   ${r.pkg.padEnd(44)} IoU ${b.toFixed(3)} → ${r.iou.toFixed(3)}  (${((r.iou - b) * 100).toFixed(2)}pp)`);
  }
  const sumNow = R.reduce((s, r) => s + r.iou, 0), sumBase = [...base.values()].reduce((s, v) => s + v, 0);
  console.log(`   逐张：${R.length} 张里退让 ${drops.length} 张 / ΣIoU ${sumBase.toFixed(4)} → ${sumNow.toFixed(4)}`);
  if (drops.length) {
    console.error(`!! 逐张外形**退让**（阈值 0，输入是确定性的 ⇒ 退让只可能是代码改的）：`);
    drops.slice(0, 12).forEach(l => console.error(l));
    console.error(`   真要接受这张的退让：node _shape.cjs --iouwrite 认账（那是人的决定）`);
    process.exit(1);
  }
  console.log("PRESS-SHAPE-IOU OK —— 逐张不退让");
}
