// 「掉色」= 交付的墨没贴到顶（屏幕上是一片浅灰而不是一块黑）。
// 主人 2026-10-13 看图之后报的就是这一句，所以先把"哪几张、差多少"数出来，再谈修不修。
//
// 三个量都来自模型自报行（`YardKt --why` 那份读数），口径与对表台完全一样：
//   · 顶占比 = 交了墨的格里有百分之几贴到 255 —— **这一列直接就是"掉不掉色"**
//   · 字均净 = 图形格的平均交付浓度（<200 就是现有语料指标里那个「淡」）
//   · 分档   = 交付实际用了哪几档浓度（`(174,255)` 这种 = 一半墨停在 174）
// ⚠ 它和第十三闸的 IoU 是**两件事**：外形对得上照样能整片发灰，外形对不上也可以墨是实心黑。
// 用法：node _fade.cjs [--worst=18] [--only=a,b]
const fs = require("fs");
const arg = (k, d) => {
  const pre = `--${k}=`;
  const a = process.argv.find((x) => x.startsWith(pre));
  return a === undefined ? d : a.slice(pre.length);
};
const F = "bench/yard_why.txt";
if (!fs.existsSync(F)) {
  console.error(`!! 没有 ${F} —— 造它：java -cp … harness.YardKtKt yard_model --input=model --why > ${F}`);
  process.exit(2);
}
const tok = (rep, k) => { const m = new RegExp("(?:^| )" + k + "=([^ ]*)").exec(rep); return m ? m[1] : null; };
const only = arg("only", "").split(",").filter(Boolean);
const R = [];
for (const l of fs.readFileSync(F, "utf8").split(/\r?\n/)) {
  if (!l.startsWith("WHY ")) continue;
  const p = l.slice(4).split("\t");
  const rep = p[4] || "";
  if (only.length && !only.includes(p[0])) continue;
  R.push({
    pkg: p[0], iou: +p[1], us: +p[3], them: +p[4], rep,
    top: +(tok(rep, "顶占比") ?? -1), net: +(tok(rep, "字均净") ?? -1),
    netTop: +(tok(rep, "净顶") ?? -1), soft: +(tok(rep, "淡") ?? -1),
    sep: tok(rep, "分档") || "-", tones: (tok(rep, "分档") || "").split("(")[1] || "",
    tonal: rep.includes(" 调=1") ? "满幅" : "参照",
  });
}
if (R.length === 0) { console.error("!! 一行都没读到（--only 全没匹配上？）"); process.exit(2); }
const bad = R.filter((r) => r.top >= 0);
if (bad.length < R.length) console.log(`⚠ ${R.length - bad.length} 行没读到「顶占比」—— 自报行格式变了，先核对再数`);
const W = +arg("worst", 18);
console.log(`样本 ${R.length} 对（yard_model，装机那一张 + 换算回可见区）`);
console.log(`  顶占比 <80%：**${bad.filter((r) => r.top < 80).length} 张**　<50%：${bad.filter((r) => r.top < 50).length} 张　<30%：${bad.filter((r) => r.top < 30).length} 张`);
console.log(`  字均净 <200（现有语料指标那个「淡」）：${R.filter((r) => r.net >= 0 && r.net < 200).length} 张`);
console.log(`\n掉色最狠的 ${W} 张（顶占比升序 = 贴到 255 的墨最少）`);
console.log("  顶占比 字均净 净顶   淡   支    分档用了哪几档浓度                        IoU    包名");
for (const r of [...bad].sort((a, b) => a.top - b.top).slice(0, W))
  console.log(
    `  ${String(r.top).padStart(5)}% ${String(r.net).padStart(5)} ${String(r.netTop).padStart(4)}% ${String(r.soft).padStart(4)} ${r.tonal}  ${r.sep.padEnd(34)} ${r.iou.toFixed(3)}  ${r.pkg}`
  );
// —— **以设计师那层为对岸的掉色尺**（比模型自己的 `顶占比` 硬）：
//   人画贴到顶(α≥240)的那些格里，我们平均交了多少 / 其中多少我们也贴到顶。
//   读的是 `YardKt --dump` 末尾那三列（refTopN / refTopOur / refTopTop）。
const DUMP = arg("dump", "bench/reg/yard_dump.tsv");
if (fs.existsSync(DUMP)) {
  const lines = fs.readFileSync(DUMP, "utf8").split(/\r?\n/).filter((l) => l.trim());
  const head = lines[0].split("\t");
  const iN = head.indexOf("refTopN"), iAvg = head.indexOf("refTopOur"), iTop = head.indexOf("refTopTop");
  const iInk = head.indexOf("refInkN"), iInkAvg = head.indexOf("refInkAvg");
  if (iN < 0) console.log(`\n（${DUMP} 里没有 refTopN 列 —— 对表台那份读数比这台脚本旧，重跑一次 YardKt --dump）`);
  else {
    const MIN = +arg("minref", 1000);   // 人画实心格太少（<1000 格 ≈ 画布 0.7%）时平均值没意义
    const D = lines.slice(1).map((l) => { const p = l.split("\t"); return { pkg: p[0], n: +p[iN], avg: +p[iAvg], top: +p[iTop], inkN: iInk < 0 ? 0 : +p[iInk], inkAvg: iInkAvg < 0 ? -1 : +p[iInkAvg] }; })
      .filter((r) => r.n > 0);
    const ok = D.filter((r) => r.n >= MIN), few = D.filter((r) => r.n < MIN);
    const byPkg = Object.fromEntries(R.map((r) => [r.pkg, r]));
    const P = ok.filter((r) => r.inkAvg >= 0 && r.inkN >= 300);   // 两边都上墨的格太少 → 纯掉色这一列不排名
    console.log(`\n以人画那层为对岸（人画 α≥240 的格 = 分母；≥${MIN} 格的 ${ok.length} 张参与排名，${few.length} 张人画本来就没什么实心格）`);
    console.log(`  【纯掉色】两边**都上了墨**的那些格里我们平均交多少（${P.length} 张可排）：`);
    console.log(`     交够 250：**${P.filter((r) => r.inkAvg >= 250).length}**　200~250 一层薄灰：${P.filter((r) => r.inkAvg >= 200 && r.inkAvg < 250).length}　　<200 明显发灰：**${P.filter((r) => r.inkAvg < 200).length}**`);
    console.log("\n   掉色最狠的前 12（位置对了、浓度没给够）");
    console.log("   我们平均α  共上的墨格  人画实心格  我们也贴顶%   支   IoU    包名");
    for (const r of [...P].sort((a, b) => a.inkAvg - b.inkAvg).slice(0, 12)) {
      const m = byPkg[r.pkg] || {};
      console.log(`   ${r.inkAvg.toFixed(1).padStart(10)} ${String(r.inkN).padStart(11)} ${String(r.n).padStart(12)} ${String(r.top).padStart(12)}%  ${m.tonal || "?"}  ${(m.iou ?? NaN).toFixed(3)}  ${r.pkg}`);
    }
    console.log("\n  【混着位置一起算】人画实心的那些格里我们平均交多少（含「根本没上墨」的那些，所以掉色与反色混在一起）：");
    console.log(`     平均 ≥250 的 ${ok.filter((r) => r.avg >= 250).length} 张；<150 的 ${ok.filter((r) => r.avg < 150).length} 张 —— 后者多半是第十三闸那 14 张（位置错），不是这一族。`);

    // —— **第三把：无对岸的半灰**（主人那句「掉色」最贴近的一个数）。
    const iGN = head.indexOf("greyN"), iGB = head.indexOf("greyBadPct"), iTM = head.indexOf("theirMidPct");
    if (iGN < 0) console.log(`（${DUMP} 里没有 greyN/greyBadPct 两列 —— 对表台读数比这台脚本旧，重跑一次 --dump）`);
    else {
      const G = lines.slice(1).map((l) => { const p = l.split("\t"); return { pkg: p[0], n: +p[iGN], bad: +p[iGB], iou: +p[1], us: +p[3], mid: iTM < 0 ? NaN : +p[iTM] }; });
      if (iTM >= 0) {
        const m = G.map((r) => r.mid).sort((a, b) => a - b);
        console.log(`\n【这把尺的量程】设计师自己那层的中间调（α16~239）占画布：中位 ${m[m.length >> 1].toFixed(2)}%  p90 ${m[Math.floor(m.length * 0.9)].toFixed(2)}%  最大 ${m[m.length - 1].toFixed(2)}%  完全二值的 ${G.filter((r) => r.mid === 0).length}/${G.length} 张`);
        console.log(`   → 人画**不是**纯二值，所以"有灰"本身不算错；这一闸只数"人画在那一格是黑或空、我们却给了灰"的那些格。`);
      }
      console.log(`\n【无对岸的半灰】我们交了 α 128~239、而人画那一格本来是**实心黑或本来空**的格，占画布多少：`);
      console.log(`   ≥1% 画布的：**${G.filter((r) => r.bad >= 1).length} 张**　≥3%：${G.filter((r) => r.bad >= 3).length} 张　≥5%：${G.filter((r) => r.bad >= 5).length} 张　我们压根没半灰的：${G.filter((r) => r.n === 0).length} 张`);
      console.log(`\n   最脏的前 12（这一列 >0 = 「人画要么黑要么空，我们给了一层灰」）`);
      console.log("   无对岸半灰%  我们半灰总格  IoU    我们墨%   支   包名");
      for (const r of [...G].sort((a, b) => b.bad - a.bad).slice(0, 12)) {
        const m = byPkg[r.pkg] || {};
        console.log(`   ${r.bad.toFixed(2).padStart(11)}% ${String(r.n).padStart(13)} ${r.iou.toFixed(3).padStart(7)} ${r.us.toFixed(1).padStart(9)}%  ${m.tonal || "?"}   ${r.pkg}`);
      }
    }
    if (few.length) console.log(`\n   不排名的 ${few.length} 张（人画实心格 <${MIN}）：${few.map((r) => `${r.pkg.split(".").pop()}=${r.n}`).slice(0, 14).join(", ")}`);
  }
} else console.log(`\n（没有 ${DUMP} —— 对岸那把尺跳过。造它：java -cp … harness.YardKtKt yard_model --input=model --dump=${DUMP}）`);

console.log("\n读法：顶占比 0% = 交了墨的格**一格都没贴到 255**（整片是灰）；分档那列里出现 128~235 的档，就是屏幕上那层灰。");

// —— `--diff=<另一份读数>`：两版模型逐张比顶占比。**装机版 vs 仓里版**必须用这条回答，
//   不然"掉色"到底还要不要动刀，会把"已经修好但没上屏"的那几张也算成债。
const DF = arg("diff", null);
if (DF !== null) {
  if (!fs.existsSync(DF)) { console.error(`!! 没有对照读数 ${DF}`); process.exit(2); }
  const rd = (f) => {
    const m = {};
    for (const l of fs.readFileSync(f, "utf8").split(/\r?\n/))
      if (l.startsWith("WHY ")) { const p = l.slice(4).split("\t"); m[p[0]] = { top: +(tok(p[4] || "", "顶占比") ?? -1), iou: +p[1] }; }
    return m;
  };
  const A = rd(DF), B = rd(F);
  const keys = Object.keys(B).filter((k) => A[k] !== undefined && A[k].top >= 0 && B[k].top >= 0);
  if (keys.length === 0) { console.error(`!! ${DF} 与 ${F} 没有共同的包名/共同的可读数 —— 对照不成立`); process.exit(2); }
  const d = keys.map((k) => ({ pkg: k, a: A[k].top, b: B[k].top, d: B[k].top - A[k].top, iouA: A[k].iou, iouB: B[k].iou }))
    .sort((x, y) => y.d - x.d);
  const up = d.filter((x) => x.d > 0), dn = d.filter((x) => x.d < 0);
  console.log(`\n对照（${DF.split("/").pop()} → ${F.split("/").pop()}，${keys.length} 张可比）`);
  console.log(`  顶占比涨了 ${up.length} 张、掉了 ${dn.length} 张、没动 ${d.length - up.length - dn.length} 张`);
  for (const x of [...up, ...dn.slice().sort((p, q) => p.d - q.d)])
    console.log(`    ${x.d > 0 ? "涨" : "掉"} ${String(x.d).padStart(4)}pp  ${String(x.a).padStart(3)}% → ${String(x.b).padStart(3)}%   IoU ${x.iouA.toFixed(3)}→${x.iouB.toFixed(3)}   ${x.pkg}`);
}
