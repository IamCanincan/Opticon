// 两版之间，**交出去那张掩码**在屏幕上占多大 —— 逐张对。
//
// 为什么用缩略图而不是 `MonoDump` 带回来的 PNG：那条通道带的是 `out.mask`，而甲是在它**之后**
// 才搬位置（`reframe(ramp…)`），所以 dump 里的两张永远逐字节相同 —— 量不到这一刀（证过：21/21 same）。
// 唯一带"搬完之后"那一张的，是 `noteDiagnosis` 打在判据行之后的 8 行 ASCII 缩略图。
//
// 缩略图的 `diag#` 用的是它**自己**的计数（不是判据行那个 id），所以归属只能靠**邻接**：
// 一个 mask 块之前最近的那条判据行就是它的主人。块被别的判据行打断 → 丢弃并计数。
//
// ── 2026-10-15：判据行有了两种身份，归属这条得跟着改（48bbfab 缓存 + 6b2c8bf 补 键=/state=）──
//   现算：`diag#N edge=E sig=<12hex> 键=<hex> <label…state=…> <报表…> → 已交出掩码 几何=…`
//   命中：`diag#N edge=E 键=<hex> <label…state=…> 缓存=内存|磁盘 → 直接交缓存里那张（未重算）`
// 命中行也走同一个 `noteDiagnosis`，所以**它后面同样挂着一张缩略图**，而热跑上它占了绝大多数
// （真机一趟：2 现算 / 1880 命中）。老代码只让 `sig=` 行当主人，于是两个都是坏的：
//   ① 命中行的那张块被**记到上一个 sig 行头上**（`last` 只由 sig 行设置、又从不清空）——
//      跨趟一比就把 A 的图标对到 B 的图标上，看着像"缩略图变了"；
//   ② 热跑上只剩 2 个 sig 行，配对数塌成 0 或个位数 —— **空绿的仪表**。
// 现在：两种判据行都能当主人；配对身份用 `键=`（旧日志没有 键= 的退回 `sig=`，各加前缀防混）。
// 一块用完就把 `last` 清空 —— 连着两张块而中间没有判据行，第二张就是**无主块**，计数并说出来。
//
// ⚠ 还要避开第三种行：`noteDiagnosis` 在打块之前先打一条 `… in=WxH a0=… out=… fill=…` 的**前奏**，
//   它带 label、却**不带 sig/键**。它不能被当主人（会把身份弄丢），也不能清掉主人（它就是主人家的）。
//   它的 `diag#` 与块自己的 `diag#` **相同**，所以顺手记下 `前奏id → 主人`：块优先按这个对，
//   对不上再退回邻接 —— 加载线程不止一条，两条判据行交错时邻接会认错人，这个 id 能救回来。
//
// 用法：node _thumbab.cjs <log.txt> <pidA> <pidB> [最少配对张数=1]
const fs = require('fs');

const LOG = process.argv[2], PA = process.argv[3], PB = process.argv[4];
const argMin = Number.parseInt(process.argv[5], 10);
const NEED = Number.isInteger(argMin) && argMin >= 0 ? argMin : 1;   // 调用方可要求"至少配上几张"
if (!LOG || !PA || !PB) { process.stderr.write('用法：node _thumbab.cjs <log> <pidA> <pidB> [min=1]\n'); process.exit(2); }
// ⚠ 行尾必须按 `\r?\n` 切：真机日志是 **CRLF**（adb 落地再过一遍编辑器），
//   按 `split('\n')` 切的话每行末尾留着 `\r`，而 JS 里 `.` 不匹 `\r`、无 m 标志的 `$`
//   只认串尾 —— 于是 `mask (\d+)\|(.*)$` **一条都匹不到**，缩略图全被当空块（本仓的仪表
//   就是这么"安静地不跑"的，2026-10-15 在 30 MB 的 perf_cold.txt 上直接跑成死循环）。
const lines = fs.readFileSync(LOG, 'utf8').split(/\r?\n/);

// 把一行 logcat 拆成"属于这个 pid 的消息体"。两种格式都要认：
//   threadtime：`10-15 04:53:28.153  9009  9033 D Opticon : Mono: diag#…`（第 3 列 pid、第 4 列 tid）
//   brief    ：`10-14 20:15:26.656 D/Opticon ( 8892): Mono: diag#…`
// ⚠ **别用 ('(' + pid + ')') 这种 includes**：brief 把 pid 右对齐补空格 —— `( 9009)`，
//   用 `(9009)` 去匹一条都匹不到（本仓真吃过一次）；threadtime 里 pid/tid 是两个字段，
//   匹错字段会把别的线程的行当成这个进程的。
// ⚠ 写这次又当场踩到的两个：① `D/Opticon ( 9009):` 在**标签与括号之间还有一个空格**，
//   把标签写成"一路吃到括号"的 `[^\s/(]*\(` 就一条都匹不到。
//   ② 两种匹法都必须**锚在行首**：真机日志里有 2031 条上千字符的 base64 分片行，
//   未锚定的正则会在每一行上从头试到尾 —— 30 MB 的 log 直接跑成平方级卡死（外面再加一道
//   `indexOf('Mono: diag#')` 的前置闸，只有判据行值得走正则）。
function body(ln, pid) {
  let m = ln.match(/^[^\s]+ [^\s]+ +(\d+) +(\d+) [VDIWEFA] [^:]+: ([\s\S]*)$/);
  if (m) return Number(m[1]) === pid ? m[3] : null;
  m = ln.match(/^(?:[^\s]+ [^\s]+ )?[VDIWEFA]\/[^\s(]* *\( *(\d+)\): ([\s\S]*)$/);
  if (m) return Number(m[1]) === pid ? m[2] : null;
  return null;
}

// 解剖一条 `Mono: diag#` 行。认不出的行返回 null（不当判据行，也不动归属状态）。
function diagOf(t) {
  const h = /^Mono: diag#(\d+) edge=(\d+)\b/.exec(t);
  if (!h) return null;
  const k = / 键=([0-9a-f]+)/.exec(t);
  const s = / sig=([0-9a-f]+)/.exec(t);
  const id = k ? '键:' + k[1] : (s ? 'sig:' + s[1] : null);
  let kind;
  if (/ 缓存=(?:内存|磁盘)/.test(t)) kind = 'hit';
  else if (id) kind = 'rec';
  else if (/ in=\d+x\d+/.test(t)) kind = 'heavy';        // noteDiagnosis 的前奏，不是主人
  else if (/放弃合成|没交出掩码/.test(t)) kind = 'give';
  else kind = 'plain';
  const rest = t.replace(/^Mono: diag#\d+ edge=\d+ +/, '').replace(/^(?:(?:sig|键)=[0-9a-f]+ +)+/, '');
  const pkg = (/^(\S+)/.exec(rest) || [null, '?'])[1];
  return { n: h[1], id, kind, pkg };
}

function grab(pid) {
  const P = Number(pid);
  const byKey = new Map();      // 键(旧日志为 sig) -> {pkg, side, ink, kind}
  const byId = new Map();       // noteDiagnosis 自己的 diag# -> 主人（块按它认领，比邻接稳）
  let last = null;              // 最近一条**有身份**的判据行 = 下一张块的主人
  let blocks = 0, broken = 0, orphan = 0, keylessHit = 0, rescued = 0;
  let rec = 0, hit = 0, withKey = 0, withSig = 0;
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].indexOf('Mono: diag#') < 0) continue;   // 前置闸，见 body() 上面那条
    const t = body(lines[i], P);
    if (t === null) continue;
    const d = diagOf(t);
    if (d) {
      if (d.kind === 'rec' || d.kind === 'hit') {
        if (d.kind === 'rec') rec++; else hit++;
        if (!d.id) {
          // 命中行不带身份 = 那是 6b2c8bf 之前的中间版，它那张块**不能**记到别人头上。
          if (d.kind === 'hit') keylessHit++;
          last = null;
        } else {
          if (d.id.startsWith('键:')) withKey++; else withSig++;
          last = { id: d.id, pkg: d.pkg, kind: d.kind };
        }
      } else if (d.kind === 'heavy') {
        if (last) byId.set(d.n, last);                  // 前奏：记下 id→主人，last 不动
        else last = null;
      } else {
        last = null;                                    // 放弃合成/认不出：别把块留给上一个人
      }
      continue;
    }
    const first = /^Mono: diag#(\d+) mask 0\|/.exec(t);
    if (!first) continue;
    const rows = [];                                    // 从这块的第一行往下收
    let j = i;
    while (j < lines.length) {
      if (lines[j].indexOf('Mono: diag#') < 0) break;              // 不是判据行 = 这块到为止
      const b = body(lines[j], P); if (b === null) break;
      const m = /^Mono: diag#(\d+) mask (\d+)\|([\s\S]*)$/.exec(b);
      if (!m || m[1] !== first[1]) break;               // 换了 diag# = 被别的判据行打断
      rows[+m[2]] = m[3]; j++;
    }
    // ⚠ **不许倒回去**：一张都没收到时 j === i，写 `i = j - 1` 会被外层 i++ 顶回原地 ——
    //   那就是一个不产任何读数的死循环。这里只在真的往前走了才跳。
    if (j > i) i = j - 1;
    blocks++;
    // 归属：先按前奏 id 认领（多线程交错时邻接会认错人），没有再退回邻接。
    const owner = byId.get(first[1]) || null;
    if (owner && last && owner !== last) rescued++;
    byId.delete(first[1]);
    const who = owner || last;
    last = null;                                        // **一块一主人**：用完就清，别留给下一张
    if (!who) { orphan++; broken++; continue; }
    const g = rows.filter(Boolean);
    if (g.length < 4) { broken++; continue; }
    const w = g[0].length, h = g.length;
    let x0 = w, y0 = h, x1 = -1, y1 = -1, ink = 0;
    for (let y = 0; y < h; y++) {
      if (g[y].length !== w) { x1 = -1; break; }         // 行宽不齐 = 被截断，这张不要
      for (let x = 0; x < w; x++) if (g[y][x] !== '.') { ink++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
    }
    if (x1 < 0 || ink === 0) { broken++; continue; }
    const e = { side: Math.max((x1 - x0 + 1) / w, (y1 - y0 + 1) / h), ink: ink / (w * h), pkg: who.pkg, kind: who.kind };
    if (!byKey.has(who.id) || byKey.get(who.id).side < e.side) byKey.set(who.id, e);
  }
  return { byKey, blocks, broken, orphan, rescued, rec, hit, keylessHit, withKey, withSig };
}

const A = grab(PA), B = grab(PB);
const show = (tag, r) => `${tag}: 现算 ${r.rec} / 命中 ${r.hit}` +
  `（身份 键 ${r.withKey}·sig ${r.withSig}${r.keylessHit ? '、无身份命中 ' + r.keylessHit : ''}）` +
  ` 块 ${r.blocks}（丢 ${r.broken}，其中无主 ${r.orphan}${r.rescued ? '、靠前奏 id 救回 ' + r.rescued : ''}）`;
process.stderr.write(show('A', A) + '\n' + show('B', B) + '\n');
if (A.orphan || B.orphan) {
  process.stderr.write(`⚠ 有 ${A.orphan + B.orphan} 张块前面找不到判据行（前一张的块被吃干名额、或日志被截断）——` +
    `这些张**没有算进比对**，别把它们的缺席当"没变"。\n`);
}
let pairs = 0; const rows = [];
for (const [id, b] of B.byKey) {
  const a = A.byKey.get(id);
  if (!a) continue;
  pairs++;
  const d = b.side - a.side;
  if (Math.abs(d) < 0.02) continue;
  rows.push({ d, txt: `Δ${(d > 0 ? '+' : '') + d.toFixed(3)}  占盘 ${a.side.toFixed(3)}→${b.side.toFixed(3)}  墨 ${a.ink.toFixed(2)}→${b.ink.toFixed(2)}  ${b.pkg}` });
}
// ⚠ 空跑的三种样子都得**响**，各有各的话：① 一张块都没收到（量具根本没跑）；
//   ② 一块都配不上（两侧身份不是一个版的日志）；③ 配上的不足门槛（热跑上缩略图名额
//   只有 HIT_DIAG_LIMIT=8 / ASCII_DUMP_LIMIT=8 张，样本本来就薄，得由调用方点名要几张）。
if (A.blocks === 0 || B.blocks === 0) {
  process.stderr.write('跑不了：有一张 ASCII 缩略图都没收到 —— 名额（HEAVY/HIT/ASCII_DUMP）没放行，' +
    '或 pid 给错了。这不是"没差别"，这是**没量到**。\n');
  process.exit(1);
}
if (pairs < NEED) {
  process.stderr.write(`跑不了：只配上 ${pairs} 张，门槛 ${NEED} —— 这个结论太薄，不算通过。` +
    `两侧身份：A 键 ${A.withKey}·sig ${A.withSig}，B 键 ${B.withKey}·sig ${B.withSig}；` +
    `一侧只有 sig、另一侧只有 键 的话两趟压根不是同一版日志格式。` +
    `热跑上块本来就只放行 8 张，要厚样本就冷跑一趟（或先 pm clear）。\n`);
  process.exit(1);
}
rows.sort((x, y) => Math.abs(y.d) - Math.abs(x.d));
process.stderr.write(`可比对 ${pairs} 张，其中占盘差 ≥0.02 的 ${rows.length} 张\n`);
console.log(rows.map(r => r.txt).join('\n'));
