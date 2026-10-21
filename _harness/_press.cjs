// 第十二闸的合计器：把 `PartsKt` 的逐块三元组变成一条账 —— **中调被压**。
//
// 判据（2026-10-13 立，来源：主人报的知到「底部波浪带 + Treenity 首字母看不见」）：
//   一块源色（≥300 格）`dep₁ ≥ 100`（模型说它该有墨），交付却 < `dep₁ × 0.6`（压剩不到六成）。
//   ⚠ 与"交成 0"分开数：交成 0（<10%）是**已有 F 擦空尺**管的那一族（"没交"），
//     这一把管的是"**交少了**"—— 两族修法不同（归属 vs 曲线），混在一起就分不清该动哪。
//
// ⚠ **2026-10-13 再拆一层：按"走哪一支"分开数（`标准` vs `调`=满幅插画）。**
//   这把尺的判词是「`dep₁` 说它该有墨」，可 `dep₁` 在**满幅那一支**上不是这个意思：
//   那一支参照=空气（β≈0）→ dep 退成覆盖率 → 一块实心色不管离参照多远都是 **dep≈255**，
//   而它真正交多少由**亮度斜坡**（`ta = ramp×255/跨度`）定 —— 那是这一支的**设计**。
//   实测（62 张语料，逐块三元组）：11 块债里 **9 块在满幅那一支**、标准那一支只有 **2 块**（知到）。
//   于是"抬中调增益"（`CURVE[t]`，只在 `tonal == null` 时用）**只碰得到那 2 块** ——
//   单看总数会把一个"改 2/11 块"的刀当成"改 6 张"的刀。分开印，下一刀才知道自己够得到谁。
//
// 用法：node _press.cjs <PartsKt 的输出文件>      输出 `PRESS icons=N blocks=M zero=Z` + `SPLIT …`
const fs = require("fs");
const L = fs.readFileSync(process.argv[2], "utf8").replace(/\r/g, "").split(/\r?\n/);
let name = null, tonal = false, scanned = 0;
const per = {}, zero = {}, perT = {}, perS = {};
for (const l of L) {
  const h = /^---+\s+(\S+)/.exec(l);
  if (h) { name = h[1]; tonal = false; scanned++; continue; }
  if (!name) continue;
  // 判据行的标志：`调=1`（满幅插画那一支）。这一行本身不参与计数，只定这一张走哪一支。
  if (l.includes("零=")) { tonal = / 调=1/.test(l); continue; }
  const m = /n=\s*(\d+)\s+ΔE=\s*([0-9.]+)\s+dep₁=\s*(\d+)\s+墨=\s*(\d+)/.exec(l);
  if (!m) continue;
  const n = +m[1], dep = +m[3], ink = +m[4];
  if (n < 300 || dep < 100) continue;
  const r = ink / dep;
  if (r < 0.10) { (zero[name] = zero[name] || []).push(dep + "→" + ink); continue; }
  if (r < 0.62) {
    (per[name] = per[name] || []).push(dep + "→" + ink + "(" + n + ")");
    const bag = tonal ? perT : perS;
    (bag[name] = bag[name] || []).push(dep + "→" + ink);
  }
}
const k1 = Object.keys(per), k2 = Object.keys(zero);
const b1 = k1.reduce((s, k) => s + per[k].length, 0);
console.log("中调被压（dep≥100 而交 <60%）：" + k1.length + " 张 / " + b1 + " 块");
for (const k of k1) console.log("   " + k.slice(0, 44).padEnd(46) + per[k].join("  "));
console.log("（对照·交成 0 那一族，归 F 擦空尺管：" + k2.length + " 张）");
// ⚠ 一张都没扫到 = PartsKt 换了格式或语料空了，**不是**「债清零」。以前零行照样打
//   `PRESS icons=0 blocks=0`，而 regress.sh 只查「不许涨」→ 0 < 5 被判成「持平或更好」。
//   2026-10-16 全量检查抓的；同一个坑 `_grey.cjs` 早就修过（零行不算「一张都没有」），这一闸没跟上。
if (scanned === 0) { console.error("!! _press: 一行 `--- 图标` 头都没扫到 —— 驱动或上游格式坏了，这一闸不算过"); process.exit(1); }
console.log("PRESS_SCANNED " + scanned);
console.log("PRESS icons=" + k1.length + " blocks=" + b1 + " zero=" + k2.length);
const kt = Object.keys(perT), ks = Object.keys(perS);
const bt = kt.reduce((s, k) => s + perT[k].length, 0), bs = ks.reduce((s, k) => s + perS[k].length, 0);
console.log("SPLIT 标准=" + ks.length + "张/" + bs + "块  调=" + kt.length + "张/" + bt + "块" +
  "  （`调`那一支 dep 退成覆盖率≈255，交付由亮度斜坡定 —— 抬曲线够不到它）");

// **`--pin=<文件>`：判定搬进来，退码就是判词。**（2026-10-21）
// 为什么：这段"只许降不许涨"原来抄在 `regress.sh` 第 12 段里（十行 shell 比字符串）。
// 抄在 shell 里的代价是**语义变异台没法只量这一道闸** —— 它只能整台重跑（十几分钟），
// 而整台重跑会把"谁先红"记到**第一个接住改动的闸**头上，不是我要验的那一道
// （今天 `EMAX 100→200` 那版就是这么红在第 11 闸的）。判据在哪把尺上，判定就该在哪把尺的退出码里 ——
// 与 `_flat.cjs --floor=` 同一条例外规则。`regress.sh` 现在只读它的退码。
const pinArg = process.argv.find(a => a.startsWith("--pin="));
if (pinArg) {
  const pinFile = pinArg.substring(6);
  if (!fs.existsSync(pinFile)) {
    console.error("!! 没有 " + pinFile + " —— 没基线不算过（先 `sh regress.sh --update` 或手工钉一次）");
    process.exit(2);
  }
  const pin = fs.readFileSync(pinFile, "utf8").replace(/\r/g, "").trim();
  const pi = +(pin.match(/icons=(\d+)/) || [])[1], pb = +(pin.match(/blocks=(\d+)/) || [])[1];
  if (!Number.isFinite(pi) || !Number.isFinite(pb)) {
    console.error("!! " + pinFile + " 里没有 `icons=/blocks=` 这两个键 —— 基线格式变了，不算过");
    process.exit(2);
  }
  if (k1.length > pi || b1 > pb) {
    console.error(`!! 中调被压**涨了**：基线 icons=${pi} blocks=${pb} → 这轮 icons=${k1.length} blocks=${b1}`);
    console.error("   名单见上面，挨个看是不是这一刀带来的");
    process.exit(1);
  }
  console.log(`PRESS-PIN OK icons=${k1.length}/${pi} blocks=${b1}/${pb} —— 持平或更好`);
}
