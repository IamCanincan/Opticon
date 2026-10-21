// 全量审计的分类点名器：读 MaskBench 的输出，按三类可见坏法各列最狠的几张，
// 并把这一轮的逐张指标存成快照；下一轮再跑就报"谁变好、谁变坏"。
//
// 为什么是这三类：2026-10-03 全量看 113 张真机像素（bench/montage/）时，屏幕上看得见的坏法
// 只归成三类（README「全量清点三类可见坏法」那条）。⚠ **A 与 B 的判据是代理，会把正确答案
// 也算进来**（微信 0.59 的实心气泡本来就该实心、多笔画 logo 本来就该断开）—— 所以这里报的是
// "值得看一眼的名单"，不是"缺陷清单"。这条限定必须跟着输出一起印，否则又变成一个被当成答案的数。
//
// 跑法：node _audit.cjs <bench输出> [--snap=快照目录] [--prev=上一轮快照]
const fs = require("fs");
const path = require("path");

const src = process.argv[2];
if (!src || !fs.existsSync(src)) { console.error("用法：node _audit.cjs <bench输出> [--snap=DIR] [--prev=FILE]"); process.exit(2); }
const snapDir = (process.argv.find(a => a.startsWith("--snap=")) || "--snap=bench/audit").slice(7);
const prevArg = (process.argv.find(a => a.startsWith("--prev=")) || "").slice(7);

let n = null;
const rows = [];
for (const l of fs.readFileSync(src, "utf8").split(/\r?\n/)) {
  const h = l.match(/^---+\s+(\S+)/); if (h) { n = h[1]; continue }
  if (!n || !l.includes("框内=")) continue;
  const g = (k) => { const m = l.match(new RegExp(k + "=(-?[0-9.]+)")); return m ? parseFloat(m[1]) : NaN };
  const name = n.replace(/^icon\d+_/, "").replace(/-src$/, "").replace(/_[0-9a-f]{12}$/, "");
  rows.push({ raw: n, name, fill: g("框内"), cc: g("cc"), z: g("字均净"), top: g("顶占比"),
    bnch: bunch(l),
    res: g("残盘"), vote: l.match(/票=(\d+)抹\/(\d+)抬\/(\d+)块/), voteN: (l.match(/票=(\d+)抹/) || [0, 0])[1] });
  n = null;
}

// ⚠ **`*_mono.png` 不是模型的输出，是"应用自带的单色层"那份参照**（YardKt 的对岸）。
//   按硬规则，自带单色层的应用在真机上**根本不进我们的模型**（getter 返回非 null，我们原样
//   透传），所以拿模型去跑它们、再把结果列进 A/B/C/D 名单，是把"我们没碰过的像素"报成缺陷。
//   2026-10-06 第一次真机全量抓出来才看见：189 行里 **64 行是 `_mono`**，[A] 前十占了四行
//   （discord / spotify / audiofx / edge）。与当年"语料混进判据台自己的 `*_preview.png`"同族。
const scored = rows.filter((r) => !r.raw.includes("_mono"));
const nMono = rows.length - scored.length;
// **交付的档数有多少是虚的**：`分档=a/b(α1,α2,…)` 里 ≥3 个档挤在同一个 α。
// 只数**中间调**（8<α<247）—— 挤在 0 的是底、挤在 255 的是实心，那两种本来就该重合。
// 用**函数声明**而不是 const 箭头：上面那个解析循环在它之前就调用了 `bunch`，
// 箭头函数会撞暂时性死区直接抛。
function bunch(l) {
  const m = l.match(/分档=\d+\/(\d+)\(([^)]*)\)/);
  if (!m) return 0;
  const c = {};
  for (const v of m[2].split(",").map(Number)) c[v] = (c[v] || 0) + 1;
  return Object.entries(c).filter(([v, k]) => k >= 3 && +v > 8 && +v < 247)
    .reduce((s, [, k]) => s + k, 0);
}
const cls = {
  A: scored.filter(r => r.fill >= 0.55).sort((a, b) => b.fill - a.fill),
  B: scored.filter(r => r.fill < 0.15 && r.cc >= 9).sort((a, b) => b.cc - a.cc),
  C: scored.filter(r => r.top < 55 && r.z < 235 && r.fill >= 0.15).sort((a, b) => a.top - b.top),
  // [D] **flatVote 在这张上加的墨比减的多**。A/B/C 三把都看不见这一族：它们读的是
  //   框内 / cc / 顶占比 / 字均净，而"往干净地板上刷一层 4% 的膜"四样都不动 ——
  //   icbc 就是明证：抬 788 抹 510，改完之后 183 张里逐张 diff 报的是**变好 0、变坏 0**。
  //   这一把读的是 `票=` 自己，是唯一能看见"这一刀往上加东西"的名单。
  D: scored.filter(r => r.vote && +r.vote[2] > +r.vote[1]).sort((a, b) => b.vote[2] - a.vote[2]),
  // [E] **交付的"档数"是虚的**：≥3 个档挤在同一个中间调 α 上。
  //   这是这份名单里**第一个不是全局聚合的判据** —— A/B/C/D 读的都是占比/中位/计数一类的整体量，
  //   而记忆与 README 都写着"全局量不出局部丢失"。这条读的是**这一张自己的色阶结构**。
  //   只数中间调：挤在 α=0 的是底、挤在 255 的是实心，那两种本来就该重合（icbc 就是前者）。
  //   真机 117 张里只有 2 张命中（jiakao、songpal），**两张都在主人的肉眼缺陷账里**，
  //   抱怨分别是「线条不清晰」与「颜色过度生硬」—— 基率 1.7%、在缺陷清单里 13%，富集 8 倍。
  E: scored.filter(r => r.bnch >= 3).sort((a, b) => b.bnch - a.bnch),
};
console.log("全量审计 " + scored.length + " 张进模型" + (nMono ? "（另有 " + nMono + " 行是应用**自带**的单色层，按硬规则不进模型，已从五份名单与快照里剔除）" : "") + "；同一张图标的多次捕获各算一行");
console.log("⚠ 下面五份名单是「值得看一眼」的排序，不是缺陷清单：A/B 的代理判据会把正确答案");
console.log("  一起算进来（实心气泡 logo 的 `框内` 天然高、多笔画 logo 的 `cc` 天然大）。");
for (const k of ["A", "B", "C", "D", "E"]) {
  const why = { A: "框内≥0.55 —— 盘被点亮成实心饼？", B: "框内<0.15 且 cc≥9 —— 交出来的是一把碎点？",
                C: "顶占比<55 且 字均净<235 —— 同一物体被切成几个灰档？",
                D: "票 抬>抹 —— 这一刀在这张上**加**的墨比抹的多？",
                E: "≥3 个档挤在同一个中间调 —— 档数是虚的，过渡必然生硬？" }[k];
  console.log("\n  [" + k + "] " + why + "  命中 " + cls[k].length + " 行");
  if (k === "D") console.log(
    "     ⚠ [D] 只说这一刀往上加了东西，**不说屏幕上那个毛病是它造的**。驾考宝典 2026-10-06\n" +
    "     就是反例：[D] 报它 抬942/抹0，看着像元凶；把改前改后逐格一比，那 942 格只是沿保险杠与\n" +
    "     横幅上沿描了一圈 1~2px 的边（Δα≤103），而屏幕上真正刺眼的那块**实心暗楔在改前就有**，\n" +
    "     是上游把红横幅判成了墨。落到 [D] 中 → 去看那张图 → 结论是别人的账，这一步不能省。");
  for (const r of cls[k].slice(0, 10)) {
    const v = k === "D" ? "抹=" + String(r.vote[1]).padStart(5) + " 抬=" + String(r.vote[2]).padStart(5) + " " :
      "框内=" + String(r.fill).padStart(4) + " cc=" + String(r.cc).padStart(3) +
      " 顶占比=" + String(r.top).padStart(4) + " 字均净=" + String(r.z).padStart(3) +
      (k === "E" ? " 挤" + r.bnch + "档" : "") + " ";
    console.log("     " + v + r.name.slice(0, 54));
  }
}
const fired = scored.filter(r => r.vote && (+r.vote[1] || +r.vote[2]));
console.log("\n  这一轮 flatVote 开火：" + fired.length + " 行  " +
  fired.map(r => r.name.split("_")[0] + "(" + (r.vote ? r.vote[1] + "抹/" + r.vote[2] + "抬" : "") + ")").slice(0, 8).join("  "));

// 快照与逐张 diff：这一条才是"不用主人一张一张报"的关键
fs.mkdirSync(snapDir, { recursive: true });
const stamp = new Date().toISOString().slice(0, 16).replace(/[:T]/g, "-");
const cur = path.join(snapDir, "audit_" + stamp + ".json");
fs.writeFileSync(cur, JSON.stringify(scored.map(r => ({ n: r.name, fill: r.fill, z: r.z, top: r.top, cc: r.cc,
  // `票=` 也进快照：不加这一栏，逐张 diff 就永远看不见"这一刀往上加墨"这一族（见 [D]）。
  er: r.vote ? +r.vote[1] : 0, ra: r.vote ? +r.vote[2] : 0, bc: r.bnch })), null, 0));
const prev = prevArg || (fs.readdirSync(snapDir).filter(f => f.startsWith("audit_") && f.endsWith(".json"))
  .map(f => path.join(snapDir, f)).sort().reverse()[1]);
if (prev && fs.existsSync(prev)) {
  // ⚠ **口径变了就不许逐张 diff。** 2026-10-06 审计线从"合成图"改成"模型真吃的前景层"，
  //   第一跑就报"变好 30 / 变坏 20" —— 那是换了输入，不是像素变了。把"换了尺子"记成"变好/变坏"
  //   是这台机子今天第四次犯的同一种错，不能让它留在自己的工具里。
  const mark = path.join(snapDir, "last_corpus.txt");
  const thisCorpus = (process.argv.find((a) => a.startsWith("--corpus=")) || "--corpus=?").slice(9);
  const prevCorpus = fs.existsSync(mark) ? fs.readFileSync(mark, "utf8").trim() : "";
  const comparable = prevCorpus === thisCorpus;
  if (!comparable)
    console.log("\n  ⚠ **口径变了（" + (prevCorpus || "未知") + " → " + thisCorpus + "）—— 下面的逐张 diff 不可比，" +
      "这一轮只重新立参照。** 合成图与前景层是两种输入：15 张缺陷账里有 7 张 `框内` 差 1.7~6 倍。");
  fs.writeFileSync(mark, thisCorpus);
  const before = new Map(JSON.parse(fs.readFileSync(prev, "utf8")).map(r => [r.n, r]));
  const after = new Map(JSON.parse(fs.readFileSync(cur, "utf8")).map(r => [r.n, r]));
  const worse = [], better = [], added = [], gone = [];
  if (![...before.values()].some((r) => typeof r.ra === "number"))
    console.log("\n  ⚠ 上一轮快照没有 `票抬` 这一栏 —— 加墨这一族 [D] 的逐张 diff **从下一轮起**才有对照。");
  for (const [k, a] of after) {
    const b = before.get(k);
    // **新装的应用不是"变坏"。** 2026-10-06 第一次真机全量抓取，diff 报了"变坏 6 张"，
    // 逐条点开全是 `本轮新增的一行` —— 那是手机这三天装了新软件，不是像素退步。
    // 把"多了样本"记成"坏了"，看的人就会开始不信这张表。
    if (!b) { added.push(k); continue }
    // 变坏：淡了（字均净掉 ≥20）或 顶占比掉 ≥15；变好反之
    const dz = a.z - b.z, dt = a.top - b.top;
    // 加墨这一族单独记账：`票 抬` 一次多过 200 格 = 这一刀在这张上往上刷的东西变多了。
    // ⚠ 上一轮快照**没有这一栏**时不能当 0 用 —— 那样每张有抬的图都会被报成"变坏"，
    //   是 schema 变更造的假账，不是像素变的。缺就明说缺，这一族从下一轮起才有对照。
    const hasRaise = typeof b.ra === "number";
    const dRaise = a.ra - b.ra;
    if (dz <= -20 || dt <= -15) worse.push([k, "字均净 " + b.z + "→" + a.z + "  顶占比 " + b.top + "→" + a.top]);
    else if (dz >= 20 || dt >= 15) better.push([k, "字均净 " + b.z + "→" + a.z + "  顶占比 " + b.top + "→" + a.top]);
    else if (hasRaise && dRaise >= 200) worse.push([k, "加墨 票抬 " + b.ra + "→" + a.ra]);
    else if (hasRaise && dRaise <= -200) better.push([k, "少加墨 票抬 " + b.ra + "→" + a.ra]);
  }
  for (const k of before.keys()) if (!after.has(k)) gone.push(k);
  console.log("\n  与上一轮（" + path.basename(prev) + "）比：" +
    (comparable ? "变好 " + better.length + " 张、变坏 " + worse.length + " 张" :
      "变好/变坏 **不报**（口径不同）—— 累计差异 变好 " + better.length + "、变坏 " + worse.length + " 行，仅存档"));
  if (comparable) {
    for (const r of worse.slice(0, 10)) console.log("    变坏 " + String(r[0]).slice(0, 46).padEnd(48) + "  " + r[1]);
    for (const r of better.slice(0, 10)) console.log("    变好 " + String(r[0]).slice(0, 46).padEnd(48) + "  " + r[1]);
  }
  // 样本集变了要单独说：它不是判据，但它决定了上面那两个数能不能信。
  if (added.length || gone.length)
    console.log("    （样本集变了：新增 " + added.length + " 行、消失 " + gone.length +
      " 行 —— 装/卸了应用，**或者这一轮改了名单口径**；两种都与像素无关，别读成退步/进步。" +
      "新增：" + (added.slice(0, 6).map((s) => s.split("_")[0]).join(" ") || "无") +
      (added.length > 6 ? " …" : "") + "）");
} else {
  console.log("\n  没有上一轮快照，这一轮只存基线：" + cur);
}
console.log("  本轮快照：" + cur);
