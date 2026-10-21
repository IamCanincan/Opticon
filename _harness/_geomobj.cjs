// 判一件事：染色那一路量的"对象"，和彩色那一路量的对象是不是同一张。
//   彩色：MaskedDrawable 量的是**整个 AdaptiveIconDrawable**（子层按 1+2f 铺出去、裁掉四边）
//   染色：MonochromeSynth 吃的是**前景层铺满画布**
// 若两者不同，同一个窗口判据会给出不同的倍数 —— 那"按裁好的图取色"就是句空话。
// 用法：node _geomobj.cjs <png...>   （f = extraInset，本机 0.25）
const fs = require("fs"), path = require("path"), zlib = require("zlib");

const CIRCAB = path.join(__dirname, "_circab.cjs");
function loadCircabHead() {
  const src = fs.readFileSync(CIRCAB, "utf8");
  const cut = src.indexOf("const BG_A =");
  if (cut < 0) throw new Error("_circab.cjs 结构变了（找不到 `const BG_A =`），拒绝猜测");
  return new Function("require", "__dirname",
    src.slice(0, cut) + "\nreturn { decodePng, boxFraction, CAP };")(require, path.dirname(CIRCAB));
}
const C = loadCircabHead();
const F = 0.25;   // AdaptiveIconDrawable.getExtraInsetFraction()，本机实测 1/4

/** 把"子层"按 AdaptiveIconDrawable.draw() 的摆法铺成"整图标"：层占 1.5 倍画布、居中、四边裁掉。 */
function composite(px, w, h) {
  const out = Buffer.alloc(w * h * 4);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const u = (x / w + F) / (1 + 2 * F), v = (y / h + F) / (1 + 2 * F);
    const o = (y * w + x) * 4;
    if (u < 0 || u > 1 || v < 0 || v > 1) continue;      // 层外 = 透明
    const sx = Math.min(w - 1, Math.round(u * (w - 1))), sy = Math.min(h - 1, Math.round(v * (h - 1)));
    const i = (sy * w + sx) * 4;
    out[o] = px[i]; out[o + 1] = px[i + 1]; out[o + 2] = px[i + 2]; out[o + 3] = px[i + 3];
  }
  return out;
}
const zoomOf = (win) => win ? 1 / Math.max(win.r - win.l, win.b - win.t) : null;
const clsOf = (win) => win == null ? "满幅不动" : (win.porous ? "缩进" : "内切");

// 只有**自适应图标**才有 1.5 倍那层展开（`AdaptiveIconDrawable.draw()` 干的）；
// 非自适应（老式位图）彩色那支就是 cover 到视口，没有展开 —— 不分支会把那两张算错。
const IDX = path.join(__dirname, "rec_model", "index.tsv");
const CLS = new Map();
if (fs.existsSync(IDX)) for (const line of fs.readFileSync(IDX, "utf8").split("\n").slice(1)) {
  const c = line.split("\t");
  if (c.length > 5 && c[2]) CLS.set(c[2], c[5]);
}

console.log(["图标", "染色吃的(现层)", "彩色量的(整图标)", "差"].map(s => s.padEnd(24)).join(" "));
let same = 0, diff = 0, onlyMono = 0, movesNow = 0;
for (const p of process.argv.slice(2)) {
  const png = C.decodePng(fs.readFileSync(p));
  const adaptive = (CLS.get(path.basename(p)) || "").includes("Adaptive");
  const a = C.boxFraction(png.px, png.w, png.h);
  const comp = adaptive ? composite(png.px, png.w, png.h) : png.px;
  const b = C.boxFraction(comp, png.w, png.h);
  const za = zoomOf(a), zb = zoomOf(b);
  if (zb) movesNow++;
  const name = path.basename(p).replace(/_[0-9a-f]{12}\.png$/, "").slice(0, 44);
  const ratio = (za && zb) ? (za / zb).toFixed(2) + "×" : (za && !zb ? "彩色不动" : (!za && zb ? "彩色反而动" : "—"));
  if (za && !zb) onlyMono++;
  if (za && zb && Math.abs(za - zb) < 0.02) same++; else if (za || zb) diff++;
  console.log([name.padEnd(46),
    ((adaptive ? "" : "非自适应 ") + clsOf(a) + " " + (za ? za.toFixed(3) : "—")).padEnd(24),
    (clsOf(b) + " " + (zb ? zb.toFixed(3) : "—")).padEnd(24),
    ratio].join(" "));
}
console.log(`\n两遍一致的 ${same} 张，不一致 ${diff} 张；"染色动、彩色不动"的 ${onlyMono} 张`);
console.log(`按彩色那支的口径，真正会动的 = ${movesNow} 张（改之前是 ${movesNow + onlyMono} 张那一档）`);
