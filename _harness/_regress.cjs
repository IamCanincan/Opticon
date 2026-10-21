// 回归台的判分器：读 bench/reg/ 里那一堆输出，算成几个数，和基线比，**红了就非零退出**。
// 跑法：node _regress.cjs [--update]
const fs = require("fs");
const R = "bench/reg", BF = "bench/baseline.json";
const t = (l, k) => { for (const x of l.split(" ")) if (x.startsWith(k + "=")) return x.slice(k.length + 1); return null };
const num = (l, k) => { const v = t(l, k); const n = v === null ? NaN : +v; return Number.isFinite(n) ? n : NaN };
const med = a => { const s = a.filter(Number.isFinite).sort((x, y) => x - y); return s.length ? s[s.length >> 1] : NaN };

// —— 语料：每个边长一份 ——
// ⚠ **按数值排，不按文件名字典序**：字典序会把 96 排到 768 后面
//   （`bench_192, bench_384, bench_768, bench_96`），于是下面 drift.tsv 里那一串
//   各档框内看着像"96→192→384→768"，其实是乱序的 —— 读它的人只会照着标头信它。
const edges = fs.readdirSync(R).filter(f => f.startsWith("bench_")).map(f => f.slice(6, -4))
  .sort((a, b) => (+a) - (+b));
const per = {};
const boxFill = new Map();      // 图标 → [各边长的 框内]，用来量"随边长漂移"
for (const e of edges) {
  const L = fs.readFileSync(`${R}/bench_${e}.txt`, "utf8").split(/\r?\n/);
  let n = null, faint = 0, tot = 0, res = 0, lv = 0, zl = [], seen = 0;
  for (const l of L) {
    const h = l.match(/^---+\s+(\S+)/); if (h) { n = h[1]; continue }
    if (!n || !l.includes("色阶=")) continue;
    seen++;
    const z = num(l, "字均净"); if (z < 200) faint++; zl.push(z);
    // `残盘=62075/128` 是"格数/均值"两半，先切开再取数 —— 直接 +() 会得到 NaN，
    // 而 `NaN ? a : b` 走 b 那一支，于是这一列**永远是 0**（第一版基线就这么错着写进去了）。
    const rs = (t(l, "残盘") || "0/0").split("/")[0];
    res += Number.isFinite(+rs) ? +rs : 0;
    lv += +(t(l, "分档") || "0/0").split("/")[0];
    const bf = num(l, "框内");
    if (Number.isFinite(bf)) { if (!boxFill.has(n)) boxFill.set(n, []); boxFill.get(n).push(bf); }
    n = null;
  }
  per[e] = { n: seen, faint, medZ: med(zl), res, lv };
}
const drift = [...boxFill.values()].filter(a => a.length > 1).map(a => Math.max(...a) - Math.min(...a));
const driftN = drift.filter(d => d > 0.15).length;
// ⚠ 只跑一档（`sh regress.sh` 的快模式，EDGES=384）时**没有任何图标被量过两次**，
//   `drift` 是空数组，`driftN` 恒为 0 —— 而 0 在这张表里长得和"一格都没漂"一模一样，
//   基线却是全扫记的 14，于是判定列写出"持平/更好"。那不是通过，是没测。
//   （基线里那一栏曾经就是这么被 0 覆盖掉的。）
const driftMeasured = drift.length > 0;
// **把"谁漂"落到文件上。** 表里那一行 `漂移>0.15 = 14` 只够知道有没有变好，不够知道**下一步看谁**；
// 而"看谁"这件事每次都要现捞一遍语料输出，捞的人就是我。2026-10-04 那次一捞就捞出新东西：
// 14 行其实是 5 种不同的换支，而且**该信哪一侧逐张不一样**（拼多多 384 对、Azure/驾考宝典/抖音 96 对）
// —— 这句话只有点名表看得出来，合计看不出来。所以每次全扫都顺手存一份。
if (driftMeasured) {
  const short = (s) => s.replace(/^icon\d+_/, "").replace(/-src$/, "").replace(/_[0-9a-f]{12}$/, "");
  // **按像素去重，不按名字。** `rec3` 是逐轮 dump 攒下来的，同一个 drawable 每抓一次换一个
  // 后缀，于是**同一张像素在语料里占好几行**（2026-10-04 量到：113 个文件里 51 个与别人
  // 逐字节相同，真正不同的只有 62 张）。这不作废 A/B —— 两边同一份语料，比得动 ——
  // 但它作废"14 张"这句话：那是 14 **行**，读成"14 个应用"就错了。
  const crypto = require("crypto");
  const CORPUS = (process.argv.find((a) => a.startsWith("--corpus=")) || "--corpus=rec3u").slice(9);
  const digest = new Map();
  for (const [n] of boxFill) {
    const f = `${CORPUS}/${n}.png`;   // 与 regress.sh 第 3/6 段喂给 MaskBench 的是同一份
    if (!fs.existsSync(f)) continue;
    digest.set(n, crypto.createHash("md5").update(fs.readFileSync(f)).digest("hex"));
  }
  const uniq = (list) => new Set(list.map((n) => digest.get(n) || n)).size;
  const dl = [...boxFill.entries()].filter(([, a]) => a.length > 1)
    .map(([n, a]) => ({ n, d: Math.max(...a) - Math.min(...a), a }))
    .filter((r) => r.d > 0.15).sort((x, y) => y.d - x.d);
  const all = [...boxFill.keys()];
  fs.writeFileSync(`${R}/drift.tsv`,
    `# 漂移>0.15：${dl.length} 行 / ${uniq(dl.map((r) => r.n))} 张不同像素` +
    `（全语料 ${all.length} 行 / ${uniq(all)} 张不同）。列：漂移\t` + edges.join("\t") + "\t图标\n" +
    dl.map((r) => r.d.toFixed(2) + "\t" + r.a.map((x) => x.toFixed(2)).join("\t") + "\t" + short(r.n)).join("\n") + "\n");
  console.log(`   点名表：${dl.length} 行 / ${uniq(dl.map((r) => r.n))} 张不同像素 → ${R}/drift.tsv`);
  console.log(`   ⚠ 语料 ${all.length} 行里只有 ${uniq(all)} 张是不同的像素 —— 合计栏数的是**行**，不是应用`);
}

// —— 单调性审计 ——
const au = (fs.existsSync(`${R}/audit.txt`) ? fs.readFileSync(`${R}/audit.txt`, "utf8") : "").trim();
const audit = {};
for (const kv of au.replace(/^AUDIT\s*/, "").split(" ")) { const i = kv.indexOf("="); if (i > 0) audit[kv.slice(0, i)] = +kv.slice(i + 1) || kv.slice(i + 1) }

// —— 兼容性台 ——
const cw = (fs.existsSync(`${R}/compat.txt`) ? fs.readFileSync(`${R}/compat.txt`, "utf8") : "").trim();
const compat = { pass: +(cw.match(/PASS=(\d+)/) || [0, 0])[1], gap: +(cw.match(/GAP=(\d+)/) || [0, 0])[1], fail: +(cw.match(/FAIL=(\d+)/) || [0, 0])[1] };

// —— 对表台：真机上设计师**自己画的**单色层，是台子里唯一一把不是我们自己定的尺子 ——
const yd = (fs.existsSync(`${R}/yard.txt`) ? fs.readFileSync(`${R}/yard.txt`, "utf8") : "").trim();
const yline = (yd.match(/^YARD .*$/m) || [""])[0];
const yard = { n: num(yline, "n"), blank: num(yline, "blank"),
  med: num(yline, "med"), mean: num(yline, "mean"),
  // `iouMed` = 两边墨格的交并比（只看墨量对不对、不看位置对不对的尺子，是 `3c735bb` 加出来的；
  // 它比 med/mean 更接近"屏幕上像不像"，因为两张毫不相干的遮罩墨量可以一样）。
  iou: num(yline, "iouMed"), da: num(yline, "dAlphaMed") };
// **配对总数 = 进了台的 + 被剔掉的。** `blank` 那一栏是"输入整张透明"的那些（图形在背景层里、
// 模型收到的是空气，见 `YardKt` 里 chrome 那段）—— 它们不是模型的账，可是**对岸的完整性**
// 还得有人看着：删了 `*_mono.png` 要红，抓取通道开始交空图层也要红。所以判"总数"，
// 再单独判 `blank` 不许变多；中位数那几把只在真正可比的那些上算。
yard.pairs = (Number.isFinite(yard.n) ? yard.n : 0) + (Number.isFinite(yard.blank) ? yard.blank : 0);
if (!Number.isFinite(yard.n) || !Number.isFinite(yard.blank)) yard.pairs = NaN;

// 标准档：真机上 99.8% 的图标就是 384（2154 条 edge=384、4 条 192），所以基线数**必须**取 384，
// 不能取"目录里最后一个边长"—— 那样基线会随扫描顺序漂，768 那一档的数字当时就被当成标准过。
const e0 = edges.includes("384") ? "384" : edges[edges.length - 1];
// ⚠ 以前没有 384 档时**静默拿最后一档去比 384 的基线**（`edge` 不进 RULES，也不报口径）——
//   2026-10-16 全量检查抓的，而且上一轮真就停在那个状态过：基线是 384 那一档钉的，
//   换一档去比等于"没有基线"，却照样能打「全绿」。现在这是硬失败。
const EDGE_MISMATCH = !edges.includes("384");
if (EDGE_MISMATCH) console.log(`!! 本轮 EDGES=[${edges.join(",")}] 里**没有 384**：基线是 384 那一档钉的，` +
  `现在拿 ${e0} 的读数比 384 的基线 —— 这些比对不构成证据，不算绿。`);
// —— 平色饼：源有层次而交付塌了。只数模型真会处理的那些（ObjKt 按 index.tsv 排除已适配）——
const fl = (fs.existsSync(`${R}/flat.txt`) ? fs.readFileSync(`${R}/flat.txt`, "utf8") : "").trim();
const flines = fl.split(/\n/).filter(l => l.startsWith("FLAT "));
const flat = { flatN: 0, flatDen: 0 };
for (const l of flines) { flat.flatN += num(l, "flat"); flat.flatDen += num(l, "n"); }

const now = {
  edge: e0, n: per[e0].n, faint: per[e0].faint, medZ: +per[e0].medZ.toFixed(1), res: per[e0].res, lv: per[e0].lv,
  driftN, rho: +audit.rho, rhoBad: +audit.rhoBad, cap: +audit.cap, jitterBad: +audit.jitterBad,
  jitterMax: +audit.jitterMax,
  yardN: yard.n, yardPairs: yard.pairs, yardBlank: yard.blank,
  yardMed: yard.med, yardMean: yard.mean, yardIou: yard.iou, yardDa: yard.da,
  flatN: flat.flatN, flatDen: flat.flatDen,
  ...compat,
};

const has = fs.existsSync(BF);
const base = has ? JSON.parse(fs.readFileSync(BF, "utf8")) : null;
const pad = (s, n) => String(s).padEnd(n);
const lpad = (s, n) => String(s).padStart(n);
console.log();
console.log(pad("指标", 26) + lpad("这一轮", 11) + lpad("基线", 11) + "  判定");
if (!has) console.log("!! 没有基线 —— 用 `node _regress.cjs --update` 把这一轮认账成基线");

// 每一项：方向写清楚（越大好 / 越小坏），阈值就是"不许变差"
const RULES = [
  ["语料张数（分母）", "n", 0, "少一张就是分母被动过"],
  // 「淡 / 字均净」两行**移到只报不判**（REF），理由写在下面 REF 那一段 —— 主人 2026-10-19 裁定。
  ["残盘合计（格）", "res", -1, "越大越坏"],
  ["分开的档数合计", "lv", 1, "越大越细"],
  ["ρ色 中位（单调性）", "rho", 1, "越低越乱"],
  ["ρ色<0.95 的张数", "rhoBad", -1, "越大越坏"],
  ["同色起伏>40 的张数", "jitterBad", -1, "违约的张数"],
  ["顶平% 中位", "cap", -1, "贴顶的越多越没处放细节"],
  ["框内随边长漂移>0.15", "driftN", -1, "越大越不稳（只在全扫时有意义）"],
  ["兼容性台 PASS", "pass", 1, "越大越好"],
  ["兼容性台 FAIL", "fail", -1, "越大越坏"],
  // ⚠ GAP 以前**算出来了却没人判**（2026-10-16 全量检查抓的）：它 = "这台子压根没判据可比的包数"，
  //   涨了就说明兼容性台瞎了一块 —— 而瞎掉是不会让 PASS/FAIL 变红的，只会让"全绿"越来越便宜。
  ["兼容性台 GAP（没判据的包）", "gap", -1, "越大越瞎：台子看不见的东西不算通过"],
  // ⚠ **对表那三项只报不判**（主人 2026-09-30 问出来的）：有真答案的 61 个应用**全部自带
  //   单色层**，而"已适配的一个像素都不碰"是硬规则 —— 模型在真机上根本不处理它们。
  //   所以这把尺子在结构上**不可能**当产品判据：它量的是"如果我们来画，会离人画的差多少"。
  //   它的正当用途是设计参考与改动方向的对表（今天用它抓到过 Chrome 那类平色饼），
  //   但拿它当红绿灯，就会像今天这样：优化出一串"永远不上屏"的改善。
  //   配对数仍然**必须**有数（下面 missing 那条照旧拦），只是不参与"变好/变坏"。
  ["对表 配对总数（含剔掉的）", "yardPairs", 1, "少了就是对岸被动过 —— 不许悄悄变 0"],
  ["对表 空输入被剔", "yardBlank", -1, "多了就是抓取通道在交「整张透明的前景层」，不是模型坏了"],
  ["平色饼（源有层次而交付塌了）", "flatN", -1, "越小越好（只数未适配的那半）"],
  ["平色饼分母", "flatDen", 1, "少了就是语料被动过"],
];
const REF = [["淡（字均净<200）", "faint", "参考，不判定（主人 2026-10-19：「图标其实都没什么问题，色度广才更有细节」）"
             + " —— 满幅那一支交的就是这张自己的亮度斜坡，中间调多=层次多；这一支该不该实心它答不了"
             + "（见 KNOWN-LIMITS「字均净 在这一支上不是淡不淡的判据」那条）。界在别处：层次留在主体里算细节，铺到底上就是糊"],
             ["字均净 中位", "medZ", "参考，不判定 —— 与上面那行同一把尺的另一半，只判它等于还在偷偷判「淡」"],
             ["同色起伏 最大值（阈值 40）", "jitterMax", "参考：max 远小于 40 ⇒ `jitterBad` 恒 0 是**量程外**，不是干净"],
             ["对表 平均偏差（点）", "yardMean", "越小越像人画的（参考，不判定）"],
             ["对表 中位偏差（点）", "yardMed", "越小越像人画的（参考，不判定）"],
             // 看位置的那两把同样**只报不判**，理由与上面两行一样：这批对岸全部自带单色层，
             // 按硬规则根本不进模型 —— 它们指方向，不当红绿灯。
             // 但它们必须**每轮都印出来**：`iouMed` 是台子里唯一看位置的对外尺子，
             // 而"没跑出来"在表里长得和"满分"一样，所以缺数会走 missing 那条当场红。
             ["对表 墨格 IoU 中位", "yardIou", "重合度（参考，不判定）—— 唯一看位置的一把"],
             ["对表 逐格|Δα| 中位", "yardDa", "浓淡差（参考，不判定）"]];
// ⚠ **NaN 不能算"持平/更好"**：`NaN > b` 与 `NaN < b` 都是 false，于是"这一项没跑出来"
//   在表里长得和"这一项满分"一模一样 —— 本仓那条"跑不起来的检查报通过，和没跑一样"在这里也会犯。
let missing = 0;
for (const [, k] of RULES.concat(REF.map(r => [r[0], r[1], 0]))) {
  if (k === "driftN" && !driftMeasured) continue;   // 未测走下面那条 `未测`，不占"没跑出来"的名额
  if (!Number.isFinite(now[k])) { console.log(`!! 指标 ${k} 这一轮**没跑出来**（不是通过，是没数）`); missing++; }
}
let bad = 0;
for (const [name, k, sign, why] of RULES) {
  const v = now[k], b = base ? base[k] : null;
  let verdict = "—";
  if (k === "driftN" && !driftMeasured) {
    // 未测就印未测：既不计退步，也不许它冒充"更好"
    console.log(pad(name, 26) + lpad("未测", 11) + lpad(b === undefined ? "—" : b, 11) + "  " + pad("—", 22) + "这一轮只跑了一档边长，漂移没量（要 EDGES=96,192,384,768）");
    continue;
  }
  if (b !== null && b !== undefined) {
    const worse = sign < 0 ? v > b : v < b;
    verdict = worse ? `** 退步（基线 ${b}）` : "持平/更好";
    if (worse) bad++;
  }
  console.log(pad(name, 26) + lpad(v, 11) + lpad(b === undefined ? "—" : b, 11) + "  " + pad(verdict, 22) + why);
}
if (process.argv.includes("--update")) {
  // 没有数的轮次**不配当基线** —— 否则"对表台坏了"那一轮会被写成标准，以后谁都不再发现。
  if (missing) { console.log(`\n!! 有 ${missing} 项没跑出来 —— 拒绝把它写成基线`); process.exit(1); }
  const keep = { ...now };
  if (!driftMeasured && base && Number.isFinite(base.driftN)) {
    // 快模式（只跑 384）**不许把基线的漂移栏改成 0** —— 那一栏只有全扫才有力气
    keep.driftN = base.driftN;
    console.log(`\n（漂移这一栏没测，基线里沿用 ${base.driftN}；要更新它得 EDGES=96,192,384,768 sh regress.sh --update）`);
  }
  fs.writeFileSync(BF, JSON.stringify({ ...keep, at: new Date().toISOString().slice(0, 16).replace("T", " ") }, null, 1) + "\n");
  console.log("\n已把这一轮写成基线：", BF);
  process.exit(0);
}
// ⚠ 基线里**没有这一栏**时印 `未钉`，不印 `undefined`：参考行的旧值允许缺（它是参考、不参与红绿灯），
//   但 `undefined` 混在满屏数字里，读的人分不清"没钉基线"与"算坏了"。
//   （`base ? … : "—"` 只兜住了"整份基线不存在"，兜不住"基线在、这一栏不在"。）
for (const [name, k, why] of REF) console.log(pad(name, 26) + lpad(now[k] ?? "无量", 11) +
  lpad(base && base[k] !== undefined ? base[k] : "未钉", 11) + "  参考     " + why);
// ⚠ 本轮"设计上跳过"的闸必须活到这句里：以前默认档每轮都跳过第十闸（换支账），
//   而整机最后只印一句"全绿" —— 读的人以为 16 道都过了（2026-10-16 全量检查抓的）。
let skipNote = "";
try {
  const s = fs.readFileSync("bench/reg/skipped.txt", "utf8").split("\n").filter(x => x.trim());
  if (s.length) skipNote = "\n   ⚠ 另有 " + s.length + " 道闸**本轮没跑**：" + s.join("；") + "（" + s.length + " 道没跑不等于过了）";
} catch (e) { /* 没有名单文件 = 一道都没跳 */ }
// ⚠ 没有基线 = 上面那些数一个都没被比过。以前这里只 print 一句提示，然后照样
//   走"bad=0 / missing=0" → 印「全绿」退 0（删掉 bench/baseline.json 就把 3~6 闸一起变成哑的）。
if (!has) {
  console.log("\n!! 没有基线文件 " + BF + " —— 上面每一项都是**裸数**，没有任何一格被比过。不许报绿。");
  console.log("   要认账就明确跑一次：node _regress.cjs --update" + skipNote);
  process.exit(1);
}
console.log((EDGE_MISMATCH ? "\n!! 口径不对（本轮 EDGES 里没有 384）—— 拿别的档比 384 的基线不算绿"
  : (bad ? `\n!! ${bad} 项比基线差 —— 这条不绿。要么修，要么想清楚再 --update`
  : (missing ? `\n!! ${missing} 项没有数 —— 不算绿` : "\n全绿"))) + skipNote);
process.exit(bad || missing || EDGE_MISMATCH ? 1 : 0);
