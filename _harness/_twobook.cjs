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
const fs = require('fs');

// 彩色那支的两条出口（`CircleIconDrawable.measureWindow`）：plate = `裁了只剩`，内切 = `裁了还剩`。
// ⚠ 交替必须写 `(只剩|还剩)`：第一版写成 `(剩|还剩)`，于是 **plate 那一支一条都没配上线**
//   （真机打的是 `裁了只剩`），而内切那一支照配 —— 这台子当场把"两本账都读不出"判成"没配对"。
//   三条控制（相同→0 / 差 1 个点→1 / 没配对→2）就是为抓这个而设的，它**第一次跑就抓到了**。
const COLOUR = /裁圆外框: (\S+).*?（扫描 (\d+)x(\d+)）裁了(只剩|还剩) (\d+)%/;
const ENTRY = /入口=([^\s，]+)/;
// 判据行拆成三段各配各的（以前一条大正则里带 `\S+?` + 可选尾巴，`几何=缩1.42× 档=plate` 那种
// 中间还有空格的读数**一条都配不上** —— 于是判据侧恒 0 行，这台子会永远报"没配对"）。
const DIAG_HEAD = /Mono: diag#\d+ edge=(\d+) sig=(\S+) (\S+)/;
const DIAG_POL = /接管=(开|关) 判剩(\d+)% 入口=([^\s]+)/;
const DIAG_GEO = /几何=(\S+)/;

function parse(text) {
  const colour = new Map();   // pkg → {pct, plate, scan, entry}
  const mono = new Map();     // pkg → {pct, entry, edge, geo, edgeOnly}
  for (const line of text.split(/\r?\n/)) {
    let m = COLOUR.exec(line);
    if (m) {
      const e = ENTRY.exec(line);
      colour.set(pkgOf(m[1]), {
        pct: +m[5], plate: m[4] === '只剩', scan: m[2] + 'x' + m[3], entry: e ? e[1] : '-',
      });
      continue;
    }
    m = DIAG_HEAD.exec(line);
    if (!m) continue;
    const pol = DIAG_POL.exec(line);
    const p = pkgOf(m[3]);
    // 同一个包会有好几张（快捷方式 / 多尺寸）：**只要有一行不等就算不等**，所以收进数组。
    if (!mono.has(p)) mono.set(p, []);
    mono.get(p).push({
      pct: pol ? +pol[2] : null, entry: pol ? pol[3] : '-', edge: +m[1],
      geo: (DIAG_GEO.exec(line) || [, '-'])[1],
    });
  }
  return { colour, mono };
}

/** 标签两种形式都要认（`包名/类型/入口名` 与 `pm:包名`）—— 与生产 `IconNames.matchesPkg` 同一口径。 */
function pkgOf(label) {
  if (label.startsWith('pm:')) return label.split('/')[0];
  return label.split('/')[0];
}

function judge(text, tag) {
  const { colour, mono } = parse(text);
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
  console.log(`${tag} 配对 ${pair} 行｜不等 ${bad} 行｜彩色是 plate 档而判据行没窗口 ${noWindow} 行｜彩色行 ${colour.size} 包 / 判据行 ${mono.size} 包`);
  if (bad || noWindow) return 1;
  if (!pair) return 2;
  return 0;
}

// ── 三条控制：这台子必须先证明自己读得懂"相同/不同/没配对" ──
const FIX_SAME = [
  'D/Opticon: 裁圆外框: org.mewx.wenku8/BitmapDrawable/ic_launcher 画布 192x192 图案 150x170（扫描 128x128）裁了只剩 55%（<60%） → 不裁、缩进圆里 body/far=0.90 放 1.42×，圆内补白 入口=keep',
  'D/Opticon: Mono: diag#7 edge=384 sig=abcdef123456 org.mewx.wenku8/BitmapDrawable/ic_launcher H=2 底占=0.00 框内=0.93 → 已交出掩码 几何=缩1.42× 档=plate 接管=开 判剩55% 入口=keep',
].join('\n');
// 就是真机那个 contacts 形状：彩色 50 / 判据 49 —— 差 1 个点，正是这一刀要消灭的那一种
const FIX_BAD = FIX_SAME.replace('判剩55%', '判剩49%').replace(/wenku8/g, 'com.android.contacts');
const FIX_NOPAIR = 'D/Opticon: 裁圆外框: a.b/BitmapDrawable/ic_launcher 画布 96x96 图案 90x90（扫描 128x128）裁了还剩 71% 不透明圆盘 r=63.0 → 放 1.02×\n';

const arg = process.argv[2];
if (arg === '--selftest') {
  const got = [judge(FIX_SAME, '[控制1 相同]'), judge(FIX_BAD, '[控制2 差1点]'), judge(FIX_NOPAIR, '[控制3 没配对]')];
  const want = [0, 1, 2];
  let wrong = 0;
  for (let i = 0; i < got.length; i++) if (got[i] !== want[i]) { wrong++; console.log(`!! 控制${i + 1} 退码 ${got[i]}，应为 ${want[i]} —— 这台子读不出${['相同', '不等', '没配对'][i]}`); }
  console.log(wrong ? `!! ${wrong}/3 条控制不过` : '三条控制全过 ✓（相同→0 / 差 1 个点→1 / 没配对→2）');
  process.exit(wrong ? 1 : 0);
}
if (!arg) { console.log('用法: node _twobook.cjs <logcat 文件> | --selftest'); process.exit(2); }
if (!fs.existsSync(arg)) { console.log(`!! 没有 ${arg}`); process.exit(2); }
process.exit(judge(fs.readFileSync(arg, 'utf8'), '两本账'));
