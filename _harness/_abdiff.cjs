// 一次性对照：把 bench/reg/{old,new}_<边长>.txt 按图标对齐，列出**变坏的**和**变好的**。
// 为什么要它：`_regress.cjs` 只报总量（淡 25→26、顶平 39.7→43.0），
// 而"总量+1"既可能是"一张真坏了"，也可能是"边界上抖了一下" —— 只有点名到图才分得开。
const fs = require("fs");
const t = (l, k) => { for (const x of l.split(" ")) if (x.startsWith(k + "=")) return x.slice(k.length + 1); return null };
const num = (l, k) => { const v = t(l, k); const n = v === null ? NaN : +v; return n };

function parse(f) {
  const m = new Map();
  let n = null, art = [], hist = "";
  const flush = () => { if (n && (art.length || hist)) m.set(n + "@sig", hist + "\n" + art.join("\n")); };
  for (const l of fs.readFileSync(f, "utf8").split(/\r?\n/)) {
    const h = l.match(/^---+\s+(\S+)/); if (h) { flush(); n = h[1]; art = []; hist = ""; continue }
    if (!n) continue;
    if (/^\s+浓度\[/.test(l)) { hist = l.trim(); continue }
    if (/^\s+\|/.test(l)) { art.push(l.trim()); continue }
    if (!l.includes("色阶=")) continue;
    m.set(n, { z: num(l, "字均净"), faint: num(l, "淡"), top: num(l, "顶占比"),
               lv: +(t(l, "分档") || "0/0").split("/")[0], bf: num(l, "框内"),
               H: num(l, "H"), fam: num(l, "族") });
  }
  flush();
  return m;
}
// **形状指纹**：`浓度[...]` + 那张 ASCII 图。为什么不能只看判据数字 —— `a148e1e` 那一轮 62 张里
// 有 10 张的 `拦=` 位变了（那是诊断位，不是掩码），逐张比数字会报"变了 10 张"，
// 而比形状才是"变了 0 张"。**反过来也一样**：数字不动、形状整块换掉，只有指纹抓得住。
const shapeOf = M => (k => { const v = M.get(k + "@sig"); return v === undefined ? null : require("crypto").createHash("md5").update(v).digest("hex").slice(0, 8) });
const short = s => s.replace(/-src$/, "").replace(/^icon\d+_/, "").slice(0, 46);

// ⚠ **「这张变好了」不等于「这张会上屏」。** 2026-10-08 为这件事翻过一次车：
//   `a148e1e` 报"真机全量 123 张里只有那 5 张空掩码的输出变了"，据此说"四张整个看不见的图标回来了"。
//   后来查 `IconHooks.kt:1296` 那条硬规则（应用自带单色层 → `chain.proceed()` 非 null → **原样透传，
//   一个像素都不碰**）才发现那 5 张**全部** `mono=有` —— 它们在自己的手机上从来不走我们的合成路径。
//   同一批旧/新字节码逐张比：`rec3u`（桌面真问到过的 62 张）**62/62 掩码逐位相同**，
//   变的 5 张全在混着已适配图标的 `corpus_runtime` 里。**装置说"修好了 5 张"，屏幕上一张没变。**
//   所以这里每点一张名，都必须同时说它**能不能上屏**。
// 依据 = 各抓取目录 `index.tsv` 的 `mono` 列（`有`/`无`）。那一列是**应用自己的**单色层，
// 不是我们的输出：抓取跑在本应用进程里，而钩子只装在桌面/system_server —— 当场验过：
// 抓 123 张那个进程（pid 9094）日志里 `Mono:` 一行都没有，`getMonochrome()` 反射拿到的是框架的真答案。
const adapted = new Map();          // 包名 → true/false（有没有自带单色层）
for (const d of fs.readdirSync(".")) {
  const p = `${d}/index.tsv`;
  if (!fs.existsSync(p) || !fs.statSync(p).isFile()) continue;
  const head = fs.readFileSync(p, "utf8").split(/\r?\n/)[0].split("\t");
  const iPkg = head.indexOf("pkg"), iMono = head.indexOf("mono");
  if (iPkg < 0 || iMono < 0) continue;
  for (const l of fs.readFileSync(p, "utf8").split(/\r?\n/).slice(1)) {
    const c = l.split("\t");
    if (!c[iPkg]) continue;
    if (c[iMono] === "有") adapted.set(c[iPkg], true);
    else if (c[iMono] === "无" && !adapted.has(c[iPkg])) adapted.set(c[iPkg], false);
  }
}
const pkgOf = s => s.replace(/-src$/, "").replace(/^icon\d+_/, "").split("_")[0];
// **证据分等级**：`rec3u` 里有这个包 = 桌面真问过我们、模型真处理过它 —— 这是**直接**证据，
// 比 `mono` 那一格强（`mono=-` 的包有 6 个，只是非自适应图标或那轮没抓到，
// 其中 zjol / yiban 就在 rec3u 里，显然会上屏）。所以先看 rec3u，再看 mono 列。
const dumped = new Set(fs.existsSync("rec3u")
  ? fs.readdirSync("rec3u").filter(f => f.endsWith(".png")).map(f => pkgOf(f)) : []);
const tag = k => {
  const p = pkgOf(k);
  if (dumped.has(p)) return "〔会上屏·桌面真合成过〕";
  if (!adapted.has(p)) return "〔没抓过·不知道上不上屏〕";
  return adapted.get(p) ? "〔已适配·透传，**不上屏**〕" : "〔未适配·会上屏〕";
};

for (const e of (process.argv[2] || "384").split(",")) {
  const A = parse(`bench/reg/old_${e}.txt`), B = parse(`bench/reg/new_${e}.txt`);
  const sA = shapeOf(A), sB = shapeOf(B);
  // **形状变了几张** —— 这才是"这一刀动了哪些图"的那一栏，判据数字不动不代表没动。
  const shape = [];
  for (const k of A.keys()) {
    if (k.endsWith("@sig") || !B.has(k)) continue;
    const x = sA(k), y = sB(k);
    if (x && y && x !== y) shape.push(`${short(k)}${tag(k)}  指纹 ${x}→${y}`);
  }
  const onScreen = shape.filter(s => s.includes("会上屏")).length;
  const passthru = shape.filter(s => s.includes("不上屏")).length;
  const worse = [], better = [];
  for (const [k, a] of A) {
    if (k.endsWith("@sig")) continue;
    const b = B.get(k); if (!b) continue;
    const G = tag(k);
    if (a.z >= 200 && b.z < 200) worse.push(`淡了   ${short(k)}${G}  净 ${a.z}→${b.z}  H ${a.H}→${b.H} 框内 ${a.bf}→${b.bf} 顶占比 ${a.top}→${b.top}`);
    else if (b.z - a.z < -8) worse.push(`退 ${b.z - a.z}  ${short(k)}${G}  净 ${a.z}→${b.z}`);
    if (a.z < 200 && b.z >= 200) better.push(`不淡了 ${short(k)}${G}  净 ${a.z}→${b.z}  H ${a.H}→${b.H} 框内 ${a.bf}→${b.bf} 顶占比 ${a.top}→${b.top}`);
    else if (b.z - a.z > 8) better.push(`进 ${b.z - a.z}  ${short(k)}${G}  净 ${a.z}→${b.z}`);
  }
  const objs = M => [...M.values()].filter(x => typeof x === "object");   // 指纹是字符串，别混进统计
  const med = (M, key) => { const s = objs(M).map(x => x[key]).filter(Number.isFinite).sort((x, y) => x - y); return s[s.length >> 1] };
  console.log(`\n===== edge=${e}  分母 ${objs(A).length}/${objs(B).length} =====`);
  console.log(`掩码形状：变了 ${shape.length} 张 —— 其中**会上屏 ${onScreen}**、已适配透传（不上屏）${passthru}、` +
    `没抓过不知道 ${shape.length - onScreen - passthru}`);
  for (const l of shape) console.log("   形状变 " + l);
  console.log(`中位：净 ${med(A, "z")}→${med(B, "z")}   顶占比 ${med(A, "top")}→${med(B, "top")}   档数 ${objs(A).reduce((s, x) => s + x.lv, 0)}→${objs(B).reduce((s, x) => s + x.lv, 0)}   淡张数 ${objs(A).filter(x => x.z < 200).length}→${objs(B).filter(x => x.z < 200).length}`);
  console.log(`-- 变坏 ${worse.length} --`); for (const l of worse) console.log("   " + l);
  console.log(`-- 变好 ${better.length} --`); for (const l of better) console.log("   " + l);
}
