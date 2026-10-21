// 两趟真机判据逐条对比（**按内容键配对**，同一键取"搬得最多"那一档的读数）。
// 用法：node _pairdiag.cjs <log> <pidA> <pidB> [最少可比条数=10]
//
// ── 一行判据行现在有两种身份（48bbfab 加了按内容再认的缓存，6b2c8bf 给每行补上 键=/state=）──
//   现算：`Mono: diag#N edge=E sig=<12hex> 键=<hex> <label… adaptive=… state=…> <报表…> → 已交出掩码 几何=…`
//   命中：`Mono: diag#N edge=E 键=<hex> <label… adaptive=… state=…> 缓存=内存|磁盘 → 直接交缓存里那张（未重算）`
// 老代码只认 `sig=` 那一条形状，于是热跑（真机 2 现算 / 1880 命中）上只剩两行能匹 ——
// **配对 0** 读起来像"这两趟没差别"，其实是量具自己没跑。本仓已经在"空绿的仪表"上栽过，
// 所以这里把两件事拆开说：命中的内容照样计数（它是对同一份内容的合法观测），
// 但**逐字段比对只有现算行能进**（命中行压根没有报表字段），任一侧可比读数少于 MIN 就非零退出。
//
// ── 配对身份：键= 优先，sig= 兜底 ──
// 键= 是内容哈希（`MonoKey.hex`），跨进程、跨重启都认得同一张；sig= 是 8×8 采样指纹，
// 本来就撞（`MonochromeSynth` 里 translate 那两个尺寸给同一个 sig 的历史教训），只当**旧日志**的把手。
// 两种都加前缀（`键:` / `sig:`）再进 Map：`Long.toHexString` 不补前导零，一个短键完全可能长得像
// 12 位 sig，不加前缀就会把两套身份混成一套。
// ⚠ 一侧只有 sig、另一侧有 键 ⇒ 配不上是**应该的**（两趟不是同一版日志格式），
//   这种情况由下面的 键/sig 覆盖率读数说出来，不许悄悄报"零差异"。
const fs = require("fs");
const [,, LOG, PA, PB, MINA] = process.argv;
if (!LOG || !PA || !PB) { console.error("用法：node _pairdiag.cjs <log> <pidA> <pidB> [min=10]"); process.exit(2); }
const MIN = Number.parseInt(MINA, 10);
const NEED = Number.isInteger(MIN) && MIN >= 0 ? MIN : 10;   // 最少可比条数：可由调用方放宽（乱给一律回 10）
// ⚠ 行尾按 `\r?\n` 切：真机日志是 **CRLF**（`_thumbab.cjs` 就因为留着 `\r` 把"到行尾"的匹法
//   全匹空过）。这里切掉之后，两侧读数才不会因为换行风格不同而对不上。
const L = fs.readFileSync(LOG, "utf8").split(/\r?\n/);

// 把一行 logcat 拆成"属于这个 pid 的消息体"。两种格式都要认：
//   threadtime：`10-15 04:53:28.153  9009  9033 D Opticon : Mono: diag#…`（第 3 列是 pid，第 4 列是 tid）
//   brief    ：`10-14 20:15:26.656 D/Opticon ( 8892): Mono: diag#…`
// ⚠ **别用 ('(' + pid + ')') 去 includes**：brief 会把 pid 右对齐补空格 —— `( 9009)`，
//   拿 `(9009)` 匹会一条都匹不到（本仓真吃过一次）。threadtime 里 pid 与 tid 是两个字段，
//   匹错字段就会把别的线程的行算进来，所以这里只取 pid 那一列。
// ⚠ 写这次又当场踩到的两个：① `D/Opticon ( 9009):` 在**标签与括号之间还有一个空格**，
//   把标签写成"一路吃到括号"的 `[^\s/(]*\(` 就一条都匹不到（合成语料上少掉的就是这几行）；
//   ② 这两种匹法都必须**锚在行首** —— 真机日志里有 2031 条上千字符的 base64 分片行，
//   未锚定的正则会在每一行上从头试到尾，30 MB 的日志直接跑成平方级卡死。
function body(ln, pid) {
  let m = ln.match(/^[^\s]+ [^\s]+ +(\d+) +(\d+) [VDIWEFA] [^:]+: ([\s\S]*)$/);
  if (m) return Number(m[1]) === pid ? m[3] : null;
  m = ln.match(/^(?:[^\s]+ [^\s]+ )?[VDIWEFA]\/[^\s(]* *\( *(\d+)\): ([\s\S]*)$/);
  if (m) return Number(m[1]) === pid ? m[2] : null;
  return null;
}

// 解剖一条 `Mono: diag#` 行。认不出的行返回 null（不当一条判据，也不动任何状态）。
function diagOf(t) {
  const h = /^Mono: diag#(\d+) edge=(\d+)\b/.exec(t);
  if (!h) return null;
  const k = / 键=([0-9a-f]+)/.exec(t);
  const s = / sig=([0-9a-f]+)/.exec(t);
  const id = k ? "键:" + k[1] : (s ? "sig:" + s[1] : null);
  // 身份：命中 / 现算 / noteDiagnosis 的 in= 前奏 / 放弃合成 / 认不出
  let kind;
  if (/ 缓存=(?:内存|磁盘)/.test(t)) kind = "hit";
  else if (id) kind = "rec";
  else if (/ in=\d+x\d+/.test(t)) kind = "heavy";
  else if (/放弃合成|没交出掩码/.test(t)) kind = "give";
  else kind = "plain";
  // label 的第一个 token 是包名/资源名（`IconNames.of`），`?` 表示解不出来。
  const rest = t.replace(/^Mono: diag#\d+ edge=\d+ +/, "").replace(/^(?:(?:sig|键)=[0-9a-f]+ +)+/, "");
  const pkg = (/^(\S+)/.exec(rest) || [null, "?"])[1];
  return { n: h[1], id, kind, pkg, edge: Number(h[2]) };
}

function grab(pid) {
  const P = Number(pid);
  const m = new Map();
  let rec = 0, hit = 0, cmp = 0, withKey = 0, withSig = 0;
  for (const ln of L) {
    if (ln.indexOf("Mono: diag#") < 0) continue;   // 便宜的前置闸：只有判据行值得走正则（见上面那条平方级的教训）
    const t = body(ln, P); if (t === null) continue;
    const d = diagOf(t); if (!d) continue;
    if (d.kind === "hit") { hit++; if (d.id) withKey++; continue; }
    if (d.kind !== "rec") continue;                     // in= 前奏 / 放弃合成：不是一条可比读数
    rec++;
    if (d.id.startsWith("键:")) withKey++; else withSig++;   // rec 行的身份必有（sig 或 键），二者取一
    const g = /几何=(\S+)/.exec(t); if (!g) continue;    // 没有几何那一截 = 报表不全，不进比对
    const f = (k) => { const x = t.match(new RegExp(k + "=([0-9./+-]+)")); return x ? x[1] : "-"; };
    const r = { pkg: d.pkg, geo: g[1], H: f("H"), 底: f("底占"), 框: f("框内"), 拦: f("拦"), 档: f("分档"), 带: f("带占") };
    const z = (s) => { const x = /([0-9.]+)/.exec(s); return x ? parseFloat(x[1]) : 0; };
    cmp++;
    const cur = m.get(d.id);
    if (!cur || z(r.geo) > z(cur.geo)) m.set(d.id, r);
  }
  return { m, rec, hit, cmp, withKey, withSig };
}

const A = grab(PA), B = grab(PB);
const keys = ["geo", "H", "底", "框", "拦", "档", "带"];
let pairs = 0, diffs = 0; const out = [];
for (const [id, b] of B.m) {
  const a = A.m.get(id); if (!a) continue;
  pairs++;
  const df = keys.filter((x) => a[x] !== b[x]);
  if (!df.length) continue;
  diffs++;
  out.push(b.pkg.slice(0, 46) + " [" + (id.startsWith("键:") ? "键" : "sig") + "]  " +
    df.map((x) => x + " " + a[x] + "→" + b[x]).join("  "));
}
console.error(`A=${PA} 现算 ${A.rec} / 命中 ${A.hit}（可比读数 ${A.cmp}，键 ${A.withKey}·sig ${A.withSig}）`);
console.error(`B=${PB} 现算 ${B.rec} / 命中 ${B.hit}（可比读数 ${B.cmp}，键 ${B.withKey}·sig ${B.withSig}）`);

// ⚠ **空跑与"零差异"必须能分开。** 仪表没量到东西时报"字段变了 0"是假绿 ——
// 少于一侧 MIN 条可比读数就拒绝下这个结论。
if (A.cmp < NEED || B.cmp < NEED) {
  console.error(`跑不了：可比读数 A=${A.cmp} B=${B.cmp}，门槛 ${NEED}。` +
    `这一趟绝大多数图标走的是缓存命中（命中行没有报表字段），逐字段比对的样本不够 —— ` +
    `这不是"没差别"，这是**没量到**。冷跑一趟（或先 pm clear）再来，或确认 pid 给对没给错。`);
  process.exit(1);
}
if (pairs === 0) {
  console.error(`跑不了：两侧各有 ${A.cmp}/${B.cmp} 条可比读数，但**一条都配不上**。` +
    `先查身份对不对：A 侧 键 ${A.withKey}·sig ${A.withSig}，B 侧 键 ${B.withKey}·sig ${B.withSig} —— ` +
    `一侧只有 sig、另一侧只有 键 的话，这两趟根本不是同一版日志格式，配不上是应该的。`);
  process.exit(1);
}
console.error(`→ 配对 ${pairs}，字段变了 ${diffs}`);
out.sort(); console.log(out.slice(0, 20).join("\n"));
