// 「交出去的墨占整幅」—— 回归台里没有这一列，而它是**屏幕上看不看得见**的直接量。
//
// 为什么单独数一次：对表台（YardKt）那 63 对全部是**自带单色层**的应用，按硬规则根本不进模型，
// 所以它们量的是"如果我们来画"。真正会上屏的是另一半：`rec3u`（真机 dump 通道，模型确实处理过）
// 与 `corpus_runtime` 里 **index 的 mono 列不是"有"** 的那批。这批里谁交出去 ≈0，
// 谁的图标就在屏幕上整个看不见 —— `a148e1e` 那一刀救回来的四张，病的名字就是这个。
//
// 跑法：node _blank.cjs [--floor=N] [--in=<bench.txt>[:标签]]…   不给 --in 就用回归台那两份
// 读数：MaskBench 的 `浓度[...]` 行 —— 淡1-127 / 中128-254 / 实255 三档格子数。
// ⚠ **分母是整幅 384²，不是内容框**：`框内` 的分母是内容框，缩在中间的 logo 可以
//   `框内=0.9` 而占整幅只有 6%（那是正常的）。这里只看"涂了多少屏"。
// ⚠ **这是漂移探测器，不是缺陷清单**（与 ObjKt 的平色饼同一格）：今天点到的两张，
//   逐张看过图 —— Google 翻译交出的就是那个 G，**正确**；拼多多丢了中间的「拼」，
//   那是「图形与底同色又连通」那堵墙。所以它**能可靠地量出"看不见的变多/变少"**，
//   但不能说"这几张是坏的"。
const fs = require("fs");
const CUT = 5;                       // 占整幅 <5% 算"几乎看不见"
let floor = null, cutOverride = null;
const ins = [];
for (const a of process.argv.slice(2)) {
  if (a.startsWith("--floor=")) floor = +a.slice(8);
  else if (a.startsWith("--in=")) ins.push(a.slice(5));
  else if (!isNaN(+a)) cutOverride = +a;
}
const cut = cutOverride ?? CUT;
// 哪些包**已经适配**（自带单色层）：这些不上我们的屏，只当"如果我们来画"的信息。
const adapted = new Set();
const idx = "corpus_runtime/index.tsv";
if (fs.existsSync(idx))
  for (const l of fs.readFileSync(idx, "utf8").split(/\r?\n/).slice(1)) {
    const c = l.split("\t");
    if (c[8] === "有" && c[2]) adapted.add(c[2].split("_")[0]);      // 索引里的 file 列以包名开头
  }
const files = ins.length ? ins : ["bench/reg/bench_384.txt:rec3u", "bench/reg/named_bench.txt:runtime"];
let judgedTotal = 0, judgedBad = 0, hypBad = 0;
for (const spec of files) {
  const [file, tag] = spec.split(":");
  if (!fs.existsSync(file)) { console.log(`!! 没有 ${file} —— 这一份没数，不算 0`); process.exit(1); }
  const txt = fs.readFileSync(file, "utf8").split("\n");
  const rows = [];
  for (let i = 0; i < txt.length; i++) {
    const m = txt[i].match(/^--- (\S+)/);
    if (!m) continue;
    let hist = null, diag = "";
    for (let j = i + 1; j < Math.min(i + 5, txt.length); j++) {
      if (/^\s+浓度\[/.test(txt[j])) hist = txt[j];
      else if (/H=/.test(txt[j])) diag = txt[j];
    }
    if (!hist) continue;
    const g = (k) => { const x = hist.match(new RegExp(k + "=(\\d+)")); return x ? +x[1] : 0 };
    const ink = g("淡1-127") + g("中128-254") + g("实255");
    rows.push({ name: m[1], pct: ink / 147456 * 100,
      box: (diag.match(/框内=([0-9.]+)/) || [])[1], h: (diag.match(/H=(\d+)/) || [])[1],
      plate: (diag.match(/盘实=(\S+)/) || [])[1] });
  }
  // **按包名去重**：同一个图标被 dump 过两次不能算两张（rec3u 里拼多多就有两份）。
  // 两份语料的文件名都是「(iconN_)包名_资源名…」，包名里只有点没有下划线。
  const pkg = (n) => n.replace(/^icon\d+_/, "").split("_")[0];
  const byPkg = new Map();
  for (const r of rows) {
    const p = pkg(r.name);
    const cur = byPkg.get(p);
    if (!cur || r.pct < cur.pct) byPkg.set(p, r);
  }
  const list = [...byPkg.entries()].sort((a, b) => a[1].pct - b[1].pct);
  // runtime 那份混着**已适配**的包（自带单色层，硬规则根本不进模型）—— 那批不上我们的屏，
  // 只当"如果我们来画"的信息；rec3u 是 dump 通道，能进这台子就说明模型真处理过，全部算数。
  const hypOnly = tag === "runtime";
  const visible = hypOnly ? list.filter(([p]) => !adapted.has(p)) : list;
  const bad = visible.filter(([, r]) => r.pct < cut);
  console.log(`\n=== ${tag}：${list.length} 个包（按包名去重），交付墨占整幅 < ${cut}% 的 ${bad.length} 个 ===`);
  for (const [p, r] of bad)
    console.log(`  ${r.pct.toFixed(2).padStart(5)}%  ${p.padEnd(44)} 框内=${r.box ?? "-"} H=${r.h ?? "-"} 盘实=${r.plate ?? "-"}`);
  const med = visible.map(([, r]) => r.pct).sort((a, b) => a - b)[visible.length >> 1];
  console.log(`  中位 ${med === undefined ? "—" : med.toFixed(1)}%` +
    (hypOnly ? `　（另有 ${list.length - visible.length} 个已适配的包不在分母里）` : ""));
  if (hypOnly) hypBad += bad.length; else { judgedBad += bad.length; judgedTotal += visible.length };
}
console.log(`BLANK n=${judgedTotal} bad=${judgedBad} hyp=${hypBad}`);
// ⚠ `bad=0` 配 `n=0` 在底线 0 下以前照样报"过" —— 一张都没进分母 = 这台子什么都没比。
//   与 `_press.cjs` 同一个坑（2026-10-16 一起抓到；那次只修了 _press，这条是补上漏下的那半）。
if (judgedTotal === 0) { console.log("!! 没图标账：一张都没进分母 —— 上游没出可解析的行，这一闸不算过"); process.exit(1); }
if (floor !== null) {
  if (judgedBad > floor) { console.log(`!! 空掩码 ${judgedBad} > 底线 ${floor} —— 这一刀让更多图标在屏幕上看不见`); process.exit(1); }
  console.log(`（底线 ${floor}：${judgedBad <= floor ? "过" : "红"}）`);
}
