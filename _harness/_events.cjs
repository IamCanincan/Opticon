#!/usr/bin/env node
// 真机事件日志（`opticon-events.log`，统计页「导出」拿到的那个）的分桶器。
//
// 用途：把"这台机器上每张图标走了哪条路"压成几个可动的桶，回答的是
//   「这一轮值不值得动算法、候选是哪几张」，不回答"阈值该定多少"（那要靠判据台 + 看图）。
//
// 跑法：
//   node _harness/_events.cjs <opticon-events.log>
//
// 行的两种形状（制表符分列）：
//   裁圆：进程 时刻 包名 裁圆 框=… 缩=… 白底=0/1 细环=0/1 尺寸=WxH
//   单色：进程 时刻 标签 sig 边 mask|null 判据行 键=<hex>
//         （键 = 设备上 `cache/opticon-mono/<身份>/<键>_<边长>.a8` 的文件名，能直接对到掩码）
//
// 退码：0 = 正常；2 = 文件读不到或一行都解析不出来（别把"看不清"读成"没问题"）。
//
// ## 第一次体检（2026-11-08，本机 598 行）的结论，别重复跑同样的怀疑
// - `退族>0` 恰好是 able.wisdomtree 与 taobao —— 横幅刀真在开火，不是死分支。
// - `底占>=0.93` 的 15 张里抽查了 chrome(0.97) / outlook(0.97) / breezyweather(0.95)，
//   拿语料里的 `_mono.png` 对过：**三张都是正确的字形**（浏览器圆盘 / 信封折角 / 风车）。
//   ⇒ "底占高"本身**不是**缺陷信号：这几家的图案本来就是一整块实心形。
//   以后要看这一桶，得换一个能把"实心形"与"字形被当成底"分开的判据，不能只看这个数。
// - `族=0` 的 44 张多是纯线稿（termux / contacts / wisdomgame 这类），属正常。
const fs = require("fs");

const file = process.argv[2];
if (!file) { console.log("用法：node _harness/_events.cjs <opticon-events.log>"); process.exit(2); }
let raw;
try { raw = fs.readFileSync(file, "utf8"); } catch (e) {
  console.log("!! 读不到 " + file + "：" + e.message); process.exit(2);
}
const lines = raw.split("\n").filter((l) => l.trim().length > 0);
if (lines.length === 0) { console.log("!! 文件是空的：" + file); process.exit(2); }

const crop = [];
const mono = [];
const notify = [];
for (const l of lines) {
  const f = l.split("\t");
  if (f.length < 5) continue;
  if (f[3] === "裁圆") crop.push(f);
  else if (f[3] === "通知") notify.push(f);
  else if (f.length >= 7) mono.push(f);
}
if (crop.length + mono.length === 0) {
  console.log("!! 一行都没解析出来（格式变了？）行数=" + lines.length);
  process.exit(2);
}

// 判据行里的 `键=值`；值只取 [-.0-9]（负号、小数点、数字）。
const num = (report, key) => {
  const m = report.match(new RegExp(key + "=([-.0-9]+)"));
  return m ? parseFloat(m[1]) : null;
};
const pkgOf = (label) => (label || "?").split("/")[0].split(" ")[0] || "?";

// ---- 裁圆 ----
let plate = 0, ring = 0, boxed = 0, grown = 0;
const byProc = {};
for (const f of crop) {
  const s = f.join("\t");
  if (/白底=1/.test(s)) plate++;
  if (/细环=1/.test(s)) ring++;
  if (!/框=-/.test(s)) boxed++;
  const m = s.match(/缩=([0-9.]+)/);
  if (m && parseFloat(m[1]) > 1.01) grown++;
  byProc[f[0]] = (byProc[f[0]] || 0) + 1;
}

// ---- 单色 ----
const buckets = { nullMask: [], zeroFam: [], veto: [], highGround: [], veryHighGround: [] };
for (const f of mono) {
  const rep = f[6] || "";
  const pkg = pkgOf(f[2]);
  const g = num(rep, "底占");
  const fam = num(rep, "族");
  const v = num(rep, "退族");
  if (f[5] === "null") buckets.nullMask.push(pkg);
  if (fam === 0) buckets.zeroFam.push(pkg);
  if (v > 0) buckets.veto.push(pkg + " 退族=" + v);
  if (g !== null && g >= 0.85) buckets.highGround.push(pkg + " 底占=" + g);
  if (g !== null && g >= 0.93) buckets.veryHighGround.push(pkg + " 底占=" + g);
}

const show = (name, arr, cap = 6) => {
  console.log("  " + name.padEnd(14, " ") + String(arr.length).padStart(4) +
    (arr.length ? "   " + arr.slice(0, cap).join(" · ") : ""));
};

console.log("事件日志：" + file);
console.log("  行数 " + lines.length + "（裁圆 " + crop.length + " / 单色 " + mono.length +
  " / 通知 " + notify.length + "）");
console.log("");
console.log("[裁圆] 按进程：" + Object.entries(byProc).map(([p, n]) => p + "=" + n).join(" · "));
console.log("  白底=1        " + plate);
console.log("  细环=1        " + ring);
console.log("  有框（非满幅）" + boxed);
console.log("  缩>1.01       " + grown);
console.log("");
console.log("[单色] 异常/值得看的桶：");
show("mask=null", buckets.nullMask);
show("族=0", buckets.zeroFam);
show("退族>0", buckets.veto);
show("底占>=0.85", buckets.highGround);
show("底占>=0.93", buckets.veryHighGround);
console.log("");
// ---- 通知 ----
if (notify.length) {
  const kinds = {};
  const patched = [];
  for (const f of notify) {
    const kind = f[3] || "?";
    kinds[kind] = (kinds[kind] || 0) + 1;
    if (kind === "被补") patched.push(pkgOf(f[2]));
  }
  console.log("[通知] 分支：" +
    Object.entries(kinds).map(([k, n]) => k + "=" + n).join(" · "));
  if (patched.length) show("被补（我们改的）", patched);
  console.log("");
}
console.log("说明：这些是**候选**，不是「要改」的清单 —— 阈值怎么动要看判据台与看图。");
