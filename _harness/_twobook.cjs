// **装机后唯一的"两本账"对表（2026-10-21）**：彩色那行 `裁了只剩` 与判据行 `判剩` 必须是同一个数。
//
// 为什么要它：`PLATE_SUBJECT_MIN = 50` 这条线是在**彩色那本账**上标定的（wenku8 真机 55%），
// 而极性一直由**判据那一本**去比（量喂进模型那张，`MonochromeSynth.winMeasured`）——
// 装机现量两侧差 3 个点：wenku8 55→52、`com.android.contacts` 彩色 **50** / 判据 **49**。
// 只差 1 个点，同一个模块就会对同一张图给两个答案（彩色把那块缩进圆里保住、染色把它当底摘掉）。
// 生产已改成"极性读彩色那一本"（`MonochromeSynth.colourWindow`），**这一条就是它的验收线**：
// 两行不等 = 量具坏了 = 这一刀没落地。
//
// 跑法：
//   node _twobook.cjs <logcat 文件>        # 判定（0=逐包相同；1=有不等；2=没配对上，什么都没比）
//   node _twobook.cjs --selftest           # 三条控制：相同→0 / 故意差 1 个点→1 / 没配对→2
//
// ⚠ **退码 0 只有在 `配对>0` 时才算话**（本仓那一族："0 命中"常常是仪器读不了，不是没有）。
// ⚠ 彩色那两行是**限流**的（`BOX_LOG_LIMIT=200`，每实例一次），判据行按内容走 —— 所以
//   "只有彩色行没有判据行"是**正常**的（自带单色层的应用根本不上模型）；反过来才是账。
const fs = require('fs'), path = require('path');

// 彩色那支的两条出口（`CircleIconDrawable.measureWindow`）：plate = `裁了只剩`，内切 = `裁了还剩`。
// ⚠ 两处**只有真日志能教**的写法，第一版都踩了：
//   ① 交替必须写 `(只剩|还剩)`（写成 `(剩|还剩)` ⇒ plate 那一支一条都配不上）；
//   ② `）` 与 `裁了只剩` 之间**有一个空格**（那条消息是 `…（扫描 …）" + " 裁了只剩 …"` 两段拼的，
//      内切那一支没有空格）⇒ 不写 `\s*` 就是**plate 这一支整条瞎**。
//   而这两条错都在"报 0 命中"底下藏着：第一版对着真日志跑，报的是"彩色行 0 包 / 没配对"（退码 2），
//   看起来像"今天没有 plate 档图标"，其实当天真机上就有 `com.eg.android.AlipayGphone` 那一条。
//   ⇒ 三条控制的样本现在**逐字从 2026-10-21 的真日志里抠**，不再由我自己编（对着源文件验探针 ≠ 对着容器验）。
const COLOUR = /裁圆外框: (\S+).*?（扫描 (\d+)x(\d+)）\s*裁了(只剩|还剩) (\d+)%/;
const ENTRY = /入口=([^\s，]+)/;
// 判据行拆成三段各配各的（以前一条大正则里带 `\S+?` + 可选尾巴，`几何=缩1.42× 档=plate` 那种
// 中间还有空格的读数**一条都配不上** —— 于是判据侧恒 0 行，这台子会永远报"没配对"）。
// ⚠ 标签**不在 sig 后面**：真机两种形状都要认 ——
//   `diag#N edge=… sig=… 键=… <标签> …`（重算）与 `diag#N edge=… 键=… <标签> … 缓存=内存`（命中，没有 sig）。
//   第一版按 `sig=(\S+) (\S+)` 取标签，于是每个包都被登记成 `键=201d9f888acef19b` ——
//   配对恒 0，而这正是"控制1 能数到"那一条要抓的：**探针瞎了，报出来像"今天没数据"**。
const DIAG_HEAD = /Mono: diag#\d+ edge=(\d+)(?: sig=\S+)?(?: 键=\S+)? (\S+)/;
const DIAG_POL = /接管=(开|关) 判剩(\d+)% 入口=([^\s]+)/;
const DIAG_GEO = /几何=(\S+)/;

function parse(text) {
  const colour = new Map();   // pkg → {pct, plate, scan, entry}
  const mono = new Map();     // pkg → [{pct, entry, edge, geo}]
  let notJudged = 0;          // 带 `Mono: diag#` 前缀但**没重算**的行（命中 / 重诊断）
  for (const line of text.split(/\r?\n/)) {
    let m = COLOUR.exec(line);
    if (m) {
      const e = ENTRY.exec(line);
      colour.set(m[1], {
        pct: +m[5], plate: m[4] === '只剩', scan: m[2] + 'x' + m[3], entry: e ? e[1] : '-',
      });
      continue;
    }
    m = DIAG_HEAD.exec(line);
    if (!m) continue;
    // ⚠ **只配真重算过的那一行**。`Mono: diag#` 这个前缀底下有三种行：
    //   ① 重算行（`… → 已交出掩码 几何=… 接管=… 判剩=…`）—— 只有它有极性读数；
    //   ② 命中行（`… → 直接交缓存里那张（未重算）`）—— 没重算就没有判据，缺 `判剩` 是**对的**；
    //   ③ 重诊断行（直方图 / ASCII 那一段，没有 `→` 出口）。
    //   第一版三种都当判据行收，于是真日志上凭空报出 **21 行"彩色是 plate 而判据行没窗口"** ——
    //   那是我把"没重算"读成了"两本账不一致"。
    if (!/→ (已交出掩码|判据不成立)/.test(line)) { notJudged++; continue; }
    const pol = DIAG_POL.exec(line);
    const p = m[2];
    // 同一个包会有好几张（快捷方式 / 多尺寸）：**只要有一行不等就算不等**，所以收进数组。
    if (!mono.has(p)) mono.set(p, []);
    mono.get(p).push({
      pct: pol ? +pol[2] : null, entry: pol ? pol[3] : '-', edge: +m[1],
      geo: (DIAG_GEO.exec(line) || [, '-'])[1],
    });
  }
  return { colour, mono, notJudged };
}

/**
 * ⚠ **配对键 = 整个标签**（`包名/类型/资源名` 或 `pm:包名/id`），**不是包名** ——
 * 原来这里有个 `pkgOf()` 取第一段，真机上就错了：`com.android.contacts` 一个包下三张快捷方式，
 * 彩色读数分别是 **50 / 50 / 49**，按包拼会把它们串成一对"49 ↔ 50"的**假不等**
 * （第一版就是这么报的，差点让我把红灯交给一个不存在的问题）。
 * 两边标签都出自生产同一个 `IconNames.of`，所以能逐张配；配不上就是真没成对，不许退成按包。
 */

function judge(text, tag) {
  const { colour, mono, notJudged } = parse(text);
  let pair = 0, bad = 0, noWindow = 0;
  for (const [pkg, c] of colour) {
    const rows = mono.get(pkg);
    if (!rows || !rows.length) continue;
    for (const r of rows) {
      // 判据行没打 `判剩`（不是 plate 档 / 彩色判满幅）而彩色这行是 plate 档 —— 两边档位就不一致
      if (r.pct === null) { if (c.plate) { noWindow++; console.log(`!! ${tag} ${pkg} 彩色=plate${c.pct}% 而判据行没有 判剩=`); } continue; }
      pair++;
      const okPct = r.pct === c.pct;
      const okEntry = r.entry === c.entry;
      if (!okPct || !okEntry) {
        bad++;
        console.log(`!! ${tag} ${pkg} 两本账不等：彩色 ${c.plate ? '只剩' : '还剩'}${c.pct}% 入口=${c.entry}（扫描 ${c.scan}）` +
          ` ↔ 判据 判剩${r.pct}% 入口=${r.entry}（edge=${r.edge} ${r.geo}）` +
          (okPct ? '' : ` 差 ${r.pct - c.pct} 个点`) + (okEntry ? '' : ' 入口也不同'));
      }
    }
  }
  console.log(`${tag} 配对 ${pair} 行｜不等 ${bad} 行｜彩色是 plate 档而判据行没窗口 ${noWindow} 行｜彩色行 ${colour.size} 包 / 判据行 ${mono.size} 包 / 没重算的 diag 行 ${notJudged} 行（不参与配对）`);
  if (bad || noWindow) return 1;
  if (!pair) return 2;
  return 0;
}

// ── 四条控制：这台子必须先证明自己读得懂"能数到 / 相同 / 不同 / 没配对" ──
// ⚠ 样本**不是我自己编的**，是从 2026-10-21 真机日志里抠出来的三行（`bench/twobook_sample.txt`：
//   `com.eg.android.AlipayGphone/drawable/appicon_push` 那条彩色 plate 行 + 它两条判据行，
//   两本账都是 `0%`、入口都是 `keep+spiky`）。第一版的样本是我按"我以为日志长什么样"写的，
//   于是三条控制全绿、而对着真日志跑**一条都配不上**（`）` 后面那个空格）——
//   这就是"对着源文件验探针 ≠ 对着容器验"，本仓写过，今天又写一次。
const SAMPLE = path.join(__dirname, 'bench', 'twobook_sample.txt');
if (!fs.existsSync(SAMPLE)) {
  console.log(`!! 没有 ${SAMPLE} —— 控制没有真样本可用（不许退回我自己编的字符串）`);
  process.exit(2);
}
const real = fs.readFileSync(SAMPLE, 'utf8');
const colourLine = (real.match(/^[^\n]*裁圆外框[^\n]*$/) || [''])[0];
const withPair = (pct) => real.replace(/判剩(\d+)%/g, `判剩${pct}%`);
const controls = [
  // ① **能数到非零**：拿真样本跑，配对必须 ≥1（这一条专抓"探针瞎了报 0 命中"）
  [judge(real, '[控制1 能数到]'), 0, '真样本配不上对（探针瞎了）'],
  // ② 相同 → 0
  [judge(withPair('0'), '[控制2 相同]'), 0, '把两边改成相同却还判红'],
  // ③ 只差 1 个点 → 1（就是 contacts 那一种）
  [judge(withPair('1'), '[控制3 差1点]'), 1, '差 1 个点读不出来'],
  // ④ 只有彩色行、没有判据行 → 2（"没配对"必须与"通过"分得开）
  [judge(colourLine, '[控制4 没配对]'), 2, '没配对被读成了通过'],
];
if (process.argv[2] === '--selftest') {
  const want = [0, 0, 1, 2];
  let wrong = 0;
  for (let i = 0; i < controls.length; i++) {
    if (controls[i][0] !== want[i]) { wrong++; console.log(`!! 控制${i + 1} 退码 ${controls[i][0]}，应为 ${want[i]} —— ${controls[i][2]}`); }
  }
  console.log(wrong ? `!! ${wrong}/4 条控制不过` : '四条控制全过 ✓（能数到→0 / 相同→0 / 差 1 个点→1 / 没配对→2）');
  process.exit(wrong ? 1 : 0);
}
const arg = process.argv[2];
if (!arg || arg === '--help') { console.log('用法: node _twobook.cjs <logcat 文件> | --selftest'); process.exit(2); }
if (!fs.existsSync(arg)) { console.log(`!! 没有 ${arg}`); process.exit(2); }
process.exit(judge(fs.readFileSync(arg, 'utf8'), '两本账'));
