// 把 `named.txt`（主人肉眼报的缺陷账）对到判据台的读数上：
// **逐张问一遍"台子看见你了没有"**，再按族汇总。
// 跑法：node _named.cjs [bench输出] --tsv=<LedgerKt 的逐张侧档>
//   默认 bench/audit_bench.txt；侧档**必给**，不给直接 exit 1（F/G 两把尺就没跑，见下）。
const fs = require("fs");
const arg = (k, d) => { const a = process.argv.find((x) => x.startsWith(k)); return a ? a.slice(k.length) : d };
const src = process.argv[2] || "bench/audit_bench.txt";
const L = fs.readFileSync(src, "utf8").split(/\r?\n/);
const t = (l, k) => { for (const x of l.split(" ")) if (x.startsWith(k + "=")) return x.slice(k.length + 1); return null };
const num = (l, k) => { const v = t(l, k); const n = v === null ? NaN : +v; return Number.isFinite(n) ? n : NaN };

// 语料里每张图标的行
const rows = [];
let n = null;
for (let i = 0; i < L.length; i++) {
  const h = L[i].match(/^---+\s+(\S+)/); if (h) { n = h[1]; continue }
  if (!n || !L[i].includes("色阶=")) continue;
  rows.push({ n, fill: num(L[i], "框内"), share: num(L[i], "底占"), hyp: t(L[i], "H"), lv: t(L[i], "分档"),
    top: num(L[i], "顶占比"), z: num(L[i], "字均净"), cc: num(L[i], "cc"),
    // E 尺：`反相=<铺墨>/<镂空>` —— 极性别名那一刀交出去的两块数。**两个都 >0 才算开火**：
    // 只有铺墨没有镂空 = 一张平盘被整片让给卡，不是这一刀的形状。
    inv: (L[i].match(/反相=(\d+)\/(\d+)/) || [0, 0, 0]).slice(1).map(Number),
    vote: (L[i].match(/票=(\d+)抹\/(\d+)抬/) || [0, 0, 0, 0]).slice(1).map(Number) });
  n = null;
}

// ── F 擦空尺 / G 平台跨尺（`LedgerKt --tsv=`）：这两把不在判据行里，从侧档逐张读 ──
// 为什么是这两把（2026-10-12）：主人报的三族在 `MaskBench` 那一行的 40 个键上**一个都不极端**
//   —— 逐字段扫过 122 行确认过。所以缺的不是阈值，是**尺子本身**。见 `LedgerKt.kt` 文件头。
// ⚠ 阈值不是为那 4 张调的，两条都借仓里已有的约定：
//   · F「擦空」= 模型判为墨侧、源实心、交付 0 的格连成看得见的一块（≥25 格 = 5×5，`DustKt` 那条）
//     在 corpus_runtime 上开火 34/123 行、在 rec_model 上 32/62 行；
//   · G「平台跨」= 交付一整片同浓度平台（含实心 255）里，源自己跨了 ≥**30** 明度
//     30 是**模型自己的档距**（`LevelsKt` 头部：档每步至少让 30、两档看得出差别要差 ~30）；
//     在 corpus_runtime 上开火 18/123 行、在 rec_model 上 17/62 行。
//   两把都不是"只在主人点的那一张上开火"—— 输出末尾把逐把的开火张数印出来，自己核。
const LEDGER = (() => {
  const f = arg("--tsv=", "");
  if (!f || !fs.existsSync(f)) return null;
  const m = new Map();
  for (const l of fs.readFileSync(f, "utf8").split(/\r?\n/).slice(1)) {
    const c = l.split("\t"); if (c.length < 9) continue;
    // 名称 擦空格% 半擦格% 台阶格% 墨内台阶% 平台跨% 最大平台源跨度 加权平台源跨度 擦空处数
    m.set(c[0], { er: +c[1], eh: +c[2], st: +c[3], sti: +c[4], pl: +c[5], pw: +c[6], pa: +c[7], erb: +c[8] });
  }
  return m;
})();
if (!LEDGER) {
  console.error("!! 没有族尺侧档（--tsv=…，`LedgerKt` 逐张出的那几个数）—— F/G 两把这一轮**一张都没量**。");
  console.error("   少了侧档的覆盖账只剩 A~E，而「没跑」与「跑了没话说」在这张表上是同一种响法。不许当过。");
  process.exit(1);
}
const ledOf = (name) => LEDGER.get(name);
const F_LV = 1;      // 擦空处数 ≥1 处（每处 ≥25 格）
const G_LV = 1.0;    // 平台跨 ≥1% 框（与 `TerrKt --flat` 同一条"看得见的平台"线）

// ── H 细桥尺（`BridgeKt --take=999`）：`merge` 那一族（主人 2026-10-12 报的「粘连」）对得上的是它 ──
// 判据：源图里两块**本来不靠细线相连**，交付之后靠细线连起来了 —— Δ 就是"我们凭空搭出来的桥"数。
// 阈值 Δ≥2 借的是分布，不是为易班调的：rec_model 54 张里 Δ≥1 有 13 张、**Δ≥2 有 10 张**、Δ≥5 有 6 张，
// 而负 Δ（我们把连着的弄断）0 张。易班是 **Δ=26**，比第二名高一截，正是他说的那句「带子与熊粘连」。
// ⚠ 另一把现成的 `GlueKt`（"源里分开的两块被同一块墨连起来"）**不能当判据**：它在同一份语料上
//   开火 **67/82 张** —— 一把对八成图标都报警的尺不叫判据，那叫形状描述。
const BRIDGE = (() => {
  const f = arg("--bridge=", "");
  if (!f || !fs.existsSync(f)) return null;
  const m = new Map();
  for (const l of fs.readFileSync(f, "utf8").split(/\r?\n/)) {
    const c = /^(\S.*?)\s+(\d+)\s+(\d+)\s+(-?\d+)\s+(\d+)\s*$/.exec(l);
    if (c && c[1].includes(".")) m.set(c[1].trim(), { src: +c[2], ink: +c[3], d: +c[4], thin: +c[5] });
  }
  return m.size ? m : null;
})();
const brOf = (name) => { if (!BRIDGE) return null; return BRIDGE.get(name) || null };
const H_LV = 2;

// 名单的口径：A~D 与 _audit.cjs 保持一致（改了那边要改这里），E/F/G/H 是 2026-10-12 补的四把尺（H 细桥对的是主人新报的 merge 那一族）
const inList = (r) => {
  const mono = r.n.includes("_mono");
  const k = [];
  if (mono) return ["(自带单色层，不进模型)"];
  if (r.fill >= 0.55) k.push("A");
  if (r.fill < 0.15 && r.cc >= 9) k.push("B");
  if (r.top < 55 && r.z < 235 && r.fill >= 0.15) k.push("C");
  if (r.vote[1] > r.vote[0]) k.push("D");
  if (r.inv[0] > 0 && r.inv[1] > 0) k.push("E");            // 极性别名那一刀开火（半边/镂空）
  const l = ledOf(r.n);
  if (l && l.erb >= F_LV) k.push("F");                      // 擦空：该实的东西被交成 0，且成了一块
  if (l && l.pl >= G_LV) k.push("G");                       // 平台跨：源跨得比模型自己的档距还多，交付只给一档
  const b = brOf(r.n);
  if (b && b.d >= H_LV) k.push("H");                        // 细桥：交付里凭空多出来的连接（粘连）
  return k.length ? k : ["—"];
};
const named = fs.readFileSync("named.txt", "utf8").split(/\r?\n/)
  .filter((l) => l && !l.startsWith("#")).map((l) => l.split("\t"));

console.log(`主人报的 ${named.length} 张，对 ${rows.length} 行语料：\n`);
console.log("族        包名 + 现象（逐字）                            框内  底占  H  分档  顶  净   票抹/抬   反相铺/镂  擦空格%(处)  平台跨%   名单");
const fired = { E: 0, F: 0, G: 0, H: 0 };
for (const r of rows) { const k = inList(r); for (const x of ["E", "F", "G", "H"]) if (k.includes(x)) fired[x]++ }
const byFam = {};
for (const [pkg, ver, fam] of named) {
  byFam[fam] = byFam[fam] || { n: 0, seen: 0 };
  byFam[fam].n++;
  const hits = rows.filter((r) => r.n.startsWith(pkg + "_") || r.n === pkg);
  if (!hits.length) { console.log(`  ${fam.padEnd(8)} ${pkg.slice(0, 46).padEnd(47)} ** 语料里没有这一张 **`); continue }
  for (const r of hits) {
    const k = inList(r);
    if (!k.includes("—") || k.some((x) => x !== "—")) byFam[fam].seen++;
    const mono = k.includes("(自带单色层，不进模型)");
    const l = ledOf(r.n); const b = brOf(r.n);
    console.log(`  ${fam.padEnd(8)} ${(pkg + " " + ver).slice(0, 46).padEnd(47)} ` +
      (mono ? "这一张是应用自带的单色层，模型根本没跑" :
        String(r.fill).padStart(4) + String(r.share).padStart(6) + r.hyp.padStart(3) +
        (r.lv || "").padStart(6) + String(r.top).padStart(4) + String(r.z).padStart(5) +
        ("  " + r.vote[0] + "/" + r.vote[1]).padStart(11) +
        ("  " + r.inv[0] + "/" + r.inv[1]).padStart(14) +
        ("  " + (l ? l.er.toFixed(2) + "(" + l.erb + ")" : "-")).padStart(12) +
        ("  " + (l ? l.pl.toFixed(2) : "-")).padStart(9) + ("  " + (b ? b.d : "-")).padStart(7) + "   " + k.join(",")));
  }
}
console.log("\n按族：台子点名了几张 / 主人报了几张");
for (const [f, v] of Object.entries(byFam))
  console.log(`  ${f.padEnd(8)} ${v.seen}/${v.n} 张被现有名单(A/B/C/D + E 反相 / F 擦空 / G 平台跨)点到`);
const tot = Object.values(byFam).reduce((s, v) => s + v.n, 0), seen = Object.values(byFam).reduce((s, v) => s + v.seen, 0);
// 覆盖满了就别再印"剩下 0 张，每一项指标都无话可说" —— 那句话在 0 张的时候是假话，
// 而一张表里最贵的东西就是"读起来像结论、其实不成立"的那一行。
console.log(tot - seen === 0 ? `  合计     ${seen}/${tot} —— **主人报过的每一张，台子现在都数得出一个数**` :
  `  合计     ${seen}/${tot} —— 剩下 ${tot - seen} 张，**现有每一项指标对它们都无话可说**`);
// ⚠ "被点到"不等于"这一族有尺在说它"：`merge`（粘连）这一族直到 2026-10-12 才有对得上的尺 —— H 细桥。
//   所以每一族单独报"对得上的尺是哪几把"，对不上就明说 0 —— 别拿别的尺的开火把它刷成已覆盖。
const FAM_RULER = { missing: "A 满框 / B 碎 / D 抬票 / E 反相 / F 擦空", tonal: "C 淡 / G 平台跨",
  stroke: "B 碎 / F 擦空", merge: "H 细桥（交付里凭空多出来的连接）" };
console.log("\n每一族**对得上的尺**（对不上=这一族仍然说不出数，与上面那个覆盖数是两件事）：");
for (const f of Object.keys(byFam))
  console.log(`  ${f.padEnd(8)} ${FAM_RULER[f] ? "有：" + FAM_RULER[f] : "**没有对得上的尺**"}`);
// 逐包的"没被点到"名单（第七闸拿两份口径求并集用；也是"这一刀让哪几张更瞎了"的直接答案）
console.log("UNCOVERED " + named.filter(([p]) => {
  const hits = rows.filter((r) => r.n.startsWith(p + "_") || r.n === p);
  return hits.length === 0 || hits.every((r) => inList(r).every((x) => x === "—"));
}).map((x) => x[0]).join(","));
// 逐把尺的开火张数**必须跟着输出印**：一把只在主人点的那一张上开火的尺就是换了名字的特例，
// 抬不了覆盖率 —— 不印出来，下一轮读这张表的人没有当场识破它的办法。
const extra = (tag) => named.filter(([p]) => {
  const hits = rows.filter((r) => r.n.startsWith(p + "_"));
  return hits.length && hits.some((r) => inList(r).includes(tag)) &&
    !hits.some((r) => inList(r).some((x) => "ABCD".includes(x)));
}).map((x) => x[0]).join(",") || "无";
console.log(`\n新接的三把尺在整份语料上开火几张（这就是"它不是为某一张写的"的证据）：` +
  `\n  E 反相那一刀 ${fired.E}/${rows.length} 行   F 擦空≥${F_LV} 处 ${fired.F} 行   G 平台跨≥${G_LV}% 框 ${fired.G} 行   H 细桥≥${H_LV} 座 ${fired.H} 行` +
  `\n  只靠新尺才被说到的（A~D 之外多出来的）：E ${extra("E")}   F ${extra("F")}   G ${extra("G")}   H ${extra("H")}`);
