// **普查（只读）**：判据台里每一处**反射调用生产私有方法**的地方，参数个数对不对得上。
//
// 为什么要有它（2026-11-06）：`contentBounds` 加了第 4 个参数（`airOut`）那天，
// `RimDbg` / `PixelDump` / `CellDump` / `FieldMix` 四台子台**同时全废**，抛
// `IllegalArgumentException: wrong number of arguments: 3 expected: 4`，而 21 道闸一台都不碰它们 ——
// 静默烂了不知多久。**反射调用编译期查不出来**，Kotlin 的默认参数在反射里也**不默认**，
// 所以这一类只能靠一台普查机器当场数，不能靠我记得。
//
// 它怎么数（两边都用"顶层逗号"，不看嵌套的括号/引号/泛型尖括号）：
//   ① 生产侧：`app/src/main/kotlin/**/*.kt` 里每个 `fun 名字(…)` 的形参个数；
//   ② 台子侧：`_harness/*.kt` 里每个 `invoke(接收者, 实参…)` 的实参个数，
//      方法名从它前面那个 `"` 引号里取（`method(MM, "groundPass")` / `m2(MM2, "contentBounds")` /
//      `methodOf(MM, "x")` 三种写法都认）。
// 判定：实参数 ≠ 形参数 ⇒ 这一台当场会抛（除非生产那侧有同名重载，那要人工看一眼，脚本会把
// 重载的两个候选都列出来，不替你猜）。
//
// ⚠ **这台子自己也要有牙**：`--selfcheck` 会拿一个**已知会错**的假调用（`contentBounds` 少给一个参数）
//   走同一条判定，必须报出问题；报不出来就是这台子没电（0 命中不等于通过）。
//
// 跑法：`cd _harness && node _refarity.cjs [--verbose] [--selfcheck]`
// 退码：0 = 全部对得上；1 = 有对不上的（点名文件:行 + 两边各几个参数）。

const fs = require("fs");
const path = require("path");

const APP = path.resolve(__dirname, "../app/src/main/kotlin");
const HARNESS = __dirname;
const VERBOSE = process.argv.includes("--verbose");
const SELFCHK = process.argv.includes("--selfcheck");

function listFiles(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) listFiles(p, out);
    else if (e.name.endsWith(".kt")) out.push(p);
  }
  return out;
}

/** 从 `open` 位置的 `(` 开始，取到与之配对的那个 `)`，返回**顶层逗号**数与参数个数。
 *  ⚠ 参数个数**不能直接等于逗号数 + 1**：Kotlin 允许**尾随逗号**（`groundPass` 的声明就以
 *  `plateIsSubject: Boolean = false,` 收尾），第一版就是这么多数了一个，把 5 台**刚验证过能跑**的
 *  子台全报成"参数对不上" —— 已知真值当场把仪器自己抓出来了。所以先把配对括号内的原文取出来，
 *  去掉行尾注释与尾随逗号，再数。 */
function countTopLevelCommas(src, open) {
  let depthRound = 0, depthSquare = 0, depthBrace = 0;
  let quote = null, inLineComment = false, inBlockComment = false;
  let close = -1;
  for (let i = open + 1; i < src.length; i++) {
    const c = src[i], n = src[i + 1];
    if (inLineComment) { if (c === "\n") inLineComment = false; continue; }
    if (inBlockComment) { if (c === "*" && n === "/") { inBlockComment = false; i++; } continue; }
    if (quote) {
      if (c === "\\") { i++; continue; }
      if (c === quote) quote = null;
      continue;
    }
    if (c === "/" && n === "/") { inLineComment = true; i++; continue; }
    if (c === "/" && n === "*") { inBlockComment = true; i++; continue; }
    if (c === '"' || c === "'" || c === "`") { quote = c; continue; }
    if (c === "(") depthRound++;
    else if (c === ")") { if (depthRound === 0) { close = i; break; } depthRound--; }
    else if (c === "[") depthSquare++;
    else if (c === "]") depthSquare--;
    else if (c === "{") depthBrace++;
    else if (c === "}") depthBrace--;
  }
  if (close < 0) throw new Error("括号没配对（脚本读不动这个文件）");
  // 括号内的原文：去掉注释，再去掉尾随逗号，然后只数**顶层**逗号
  let inner = src.slice(open + 1, close)
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/\/\/[^\n]*/g, "");
  inner = inner.replace(/[\s,]+$/, "");          // 尾巴上的空白与那个尾随逗号
  let commas = 0, d1 = 0, d2 = 0, d3 = 0, q = null;
  for (let i = 0; i < inner.length; i++) {
    const c = inner[i];
    if (q) { if (c === "\\") { i++; continue; } if (c === q) q = null; continue; }
    if (c === '"' || c === "'" || c === "`") { q = c; continue; }
    if (c === "(") d1++; else if (c === ")") d1--;
    else if (c === "[") d2++; else if (c === "]") d2--;
    else if (c === "{") d3++; else if (c === "}") d3--;
    else if (c === "," && d1 === 0 && d2 === 0 && d3 === 0) commas++;
  }
  const blank = !/[^\s]/.test(inner);
  return { commas, end: close, params: blank ? 0 : commas + 1 };
}

/** 生产侧：每个 `fun 名字(` 的形参个数（0 个参数也算）。 */
function prodSignatures(files) {
  const byName = new Map();
  for (const f of files) {
    const src = fs.readFileSync(f, "utf8");
    const re = /\bfun\s+(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(/g;
    let m;
    while ((m = re.exec(src))) {
      const name = m[1];
      const open = src.indexOf("(", m.index + m[0].length - 1);
      let r;
      try { r = countTopLevelCommas(src, open); } catch (e) { continue; }
      const n = r.params;
      const line = src.slice(0, m.index).split("\n").length;
      if (!byName.has(name)) byName.set(name, []);
      byName.get(name).push({ n, file: path.relative(HARNESS, f), line });
    }
  }
  return byName;
}

/** 台子侧：每一处 `invoke(...)`，方法名取自前面那个引号；
 *  取不到就退一步认**变量绑定的 Method**（`val buildOnce = method(MM, "buildOnce")` 那种 ——
 *  `RefPassKt.kt` 就是这一类，不认就等于这一处以后烂了也没人知道）。 */
function harnessCalls(files, sigs) {
  const rows = [];
  for (const f of files) {
    const src = fs.readFileSync(f, "utf8");
    // 变量 → 方法名（同一文件内的绑定）。两种写法都要认：
    //   `val groundPass = method(MM, "groundPass")` 与
    //   `val buildOnce: Method = MM.declaredMethods.first { it.name == "buildOnce" }`
    //   （`RefPassKt.kt` 是后者；第一版只认前者那句 `"...")` 的形状，那一处就一直"人工看一眼"，
    //    而**没人看的清单就是以后烂掉的地方**。）
    const binds = new Map();
    for (const b of src.matchAll(/\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*[A-Za-z0-9_.<>?]+)?\s*=\s*[^;\n]*?"([A-Za-z_][A-Za-z0-9_]*)"/g)) {
      binds.set(b[1], b[2]);
    }
    // ⚠ 扫描式不许要求 `.invoke` 前面是个标识符：绝大多数子台写的是
    //   `method(MM, "groundPass").invoke(...)` —— 前面那个字符是 `)`。第一版这么写把 18 处
    //   扫成 2 处（**只剩变量绑定那种**），是"分母自己变小了却照样报通过"的形状。
    const re = /\.invoke\s*\(/g;
    let m;
    while ((m = re.exec(src))) {
      const open = src.indexOf("(", m.index);
      let r;
      try { r = countTopLevelCommas(src, open); } catch (e) { continue; }
      const passed = r.params - 1;                       // 去掉接收者
      const back = src.slice(Math.max(0, m.index - 200), m.index);
      const names = [...back.matchAll(/"([A-Za-z_][A-Za-z0-9_]*)"/g)].map(x => x[1]);
      let name = names.length ? names[names.length - 1] : null;
      let via = "引号";
      if (!name) {
        const recv = back.match(/([A-Za-z_][A-Za-z0-9_]*)\s*$/);
        if (recv && binds.has(recv[1])) { name = binds.get(recv[1]); via = "变量绑定 " + recv[1]; }
      }
      const line = src.slice(0, m.index).split("\n").length;
      rows.push({ file: path.relative(HARNESS, f), line, name, passed, via });
    }
  }
  return rows;
}

const appFiles = listFiles(APP, []);
const harnessFiles = listFiles(HARNESS, []).filter(f => !/[\\/]/.test(path.basename(f)) || true);
const sigs = prodSignatures(appFiles);

// —— 自检：这台子必须能抓到一个**明知是错的**调用 ——
if (SELFCHK) {
  const fake = 'val x = method(MM, "contentBounds").invoke(MonochromeMask, px, n, n)';
  const open = fake.indexOf("(", fake.indexOf(".invoke"));
  const r = countTopLevelCommas(fake, open);
  const passed = r.params - 1;
  const want = (sigs.get("contentBounds") || [{ n: -1 }])[0].n;
  const ok = passed !== want;
  console.log(`自检 假调用 contentBounds 实参=${passed} 生产形参=${want} ⇒ 普查机器${ok ? "有牙（报了）" : "**没牙（没报）**"}`);
  if (!ok) process.exit(1);
}

const calls = harnessCalls(harnessFiles, sigs);
// ⚠ **分母自证**：这一台最怕的就是"扫描式写错 ⇒ 只看了一小撮 ⇒ 报全部对得上"。
//   2026-11-06 写它时就当场演了一次：把 `.invoke` 前面要求成标识符，18 处扫成 2 处，退码照样 0。
//   所以这里钉一个下限：台子里 `invoke(` 的**原文总数**是地板，扫到的必须等于它。
const rawInvoke = harnessFiles.reduce((a, f) => a + (fs.readFileSync(f, "utf8").match(/\.invoke\s*\(/g) || []).length, 0);
if (calls.length !== rawInvoke) {
  console.log("!! 扫到 " + calls.length + " 处，但台子里原文有 " + rawInvoke +
    " 处 invoke —— 这台普查自己漏了，别信它那句「全部对得上」");
  process.exit(1);
}
const bad = [];
const seen = [];
for (const c of calls) {
  if (!c.name) { seen.push({ ...c, note: "认不出方法名（不是 method(MM,\"x\") 那种写法）" }); continue; }
  const cand = sigs.get(c.name);
  if (!cand) { seen.push({ ...c, note: "生产里没有同名 fun（可能是别的类的方法，人工看一眼）" }); continue; }
  const ns = [...new Set(cand.map(x => x.n))];
  if (ns.length > 1) seen.push({ ...c, note: `生产里 ${c.name} 有重载：${ns.join("/")} 个参数` });
  if (!ns.includes(c.passed)) {
    bad.push({ ...c, want: ns.join("/"), where: cand.map(x => `${x.file}:${x.line}(${x.n})`).join(", ") });
  } else if (VERBOSE) seen.push({ ...c, note: `ok（${c.passed}）` });
}

console.log(`生产 fun 名 ${sigs.size} 个；台子 invoke ${calls.length} 处` +
  `${bad.length ? "  ⇐ " + bad.length + " 处参数个数对不上" : "  全部对得上"}`);
for (const b of bad) {
  console.log(`!! ${b.file}:${b.line}  "${b.name}" 实参 ${b.passed} 个，生产形参 ${b.want} 个  ← 这一处反射当场会抛 IllegalArgumentException`);
  console.log(`     生产签名在：${b.where}`);
}
const notes = seen.filter(s => s.note && !s.note.startsWith("ok"));
if (notes.length) {
  console.log(`—— 认不出/重载/非生产方法 ${notes.length} 处（不判死，人工看一眼）——`);
  for (const s of notes) console.log(`   ${s.file}:${s.line}  ${s.name || "?"}  实参 ${s.passed}  —— ${s.note}`);
}
if (VERBOSE) console.log(`   ok 的 ${seen.filter(s => s.note === undefined || s.note.startsWith("ok")).length} 处`);
process.exit(bad.length ? 1 : 0);
