// 把**已有的**安全区尺子（SAFE_ZONE = 66/72）架到交付的剪影上，出"现状 / 归一后"对照图。
// 只缩不放：scale = min(1, 安全区半径 / 剪影主体半径)。
// 用法：node _silhnorm.cjs <目录> <输出png> [--cols=N]
const fs = require("fs"), path = require("path");
const src = fs.readFileSync(path.join(__dirname, "_circab.cjs"), "utf8");
const cut = src.indexOf("const BG_A =");
if (cut < 0) throw new Error("_circab.cjs 结构变了，拒绝猜测");
const C = new Function("require", "__dirname",
  src.slice(0, cut) + "\nreturn { decodePng, encodePng, resample, extents };")(require, __dirname);

const SAFE_ZONE = 66 / 72;      // 与 Kotlin 同一个数（自适应图标内容该落在 66dp 安全区里）
const dir = process.argv[2], outPng = process.argv[3];

function loadMask(p) {
  const png = C.decodePng(fs.readFileSync(p));
  const { w, h, px } = png;
  const a = new Int32Array(w * h);
  for (let i = 0; i < w * h; i++) {
    let v = px[i * 4 + 3];
    if (v < 8) v = Math.max(px[i * 4], px[i * 4 + 1], px[i * 4 + 2]);   // 兼容 RGB 画法
    a[i] = v;
  }
  return { w, h, a };
}
function bodyRadius(a, w, h) {
  // 转成 RGBA 好复用现成的 extents()：alpha 放进 alpha 通道
  const px = Buffer.alloc(w * h * 4);
  for (let i = 0; i < w * h; i++) { px[i * 4 + 3] = a[i]; }
  const ext = C.extents(px, w, h, w >> 1, h >> 1);
  return { far: ext[0], body: ext[1] };
}
function scaleAbout(a, w, h, s) {
  const out = new Int32Array(w * h);
  if (s >= 0.999) { out.set(a); return out; }
  const c = (w - 1) / 2;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const sx = (x - c) / s + c, sy = (y - c) / s + c;
    if (sx < 0 || sy < 0 || sx > w - 1 || sy > h - 1) continue;
    out[y * w + x] = a[Math.round(sy) * w + Math.round(sx)];
  }
  return out;
}
function plate(a, w, h, side) {
  // 画成"主题盘 + 墨"：浅灰盘 + 深墨，只画可见区（中间 side）
  const px = Buffer.alloc(w * h * 4);
  const R = side / 2, cx = w / 2, cy = h / 2;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = (y * w + x) * 4;
    const inPlate = Math.hypot(x - cx, y - cy) <= R;
    const ink = a[y * w + x] / 255;
    const bg = inPlate ? 232 : 255;
    const v = Math.round(bg * (1 - ink) + 32 * ink);
    px[i] = px[i + 1] = px[i + 2] = v; px[i + 3] = 255;
  }
  return px;
}

const seen = new Set(), items = [];
for (const f of fs.readdirSync(dir).filter(n => n.endsWith("-out.png")).sort()) {
  const key = f.replace(/^icon\d+_/, "").replace(/_(mipmap|drawable|app_icon|ic|logo|icon).*$/, "");
  if (seen.has(key)) continue;
  seen.add(key);
  const m = loadMask(path.join(dir, f));
  const side = Math.min(m.w, m.h) * 2 / 3;
  const { far, body } = bodyRadius(m.a, m.w, m.h);
  const want = SAFE_ZONE * side / 2;
  const s = body > 0 ? Math.min(1, want / body) : 1;
  items.push({ key, m, side, body, s });
}
items.sort((a, b) => b.s - a.s);      // 缩得最狠的排前面
console.log("包名".padEnd(38) + "主体半径/盘半径   缩放");
items.forEach(it => console.log(it.key.slice(0, 36).padEnd(38) +
  ((it.body / (it.side / 2)) * 100).toFixed(0).padStart(6) + "%" + "  " + it.s.toFixed(3)));
const nShrink = items.filter(it => it.s < 0.999).length;
console.log(`\n${items.length} 张里 ${nShrink} 张需要缩（主体出安全区），其余逐像素不动`);

const cell = 150, lab = 22, cols = Math.min(items.length, 12);
const rowsN = Math.ceil(items.length / cols);
const W = cols * cell, H = rowsN * cell * 2 + rowsN * lab * 2;
const big = Buffer.alloc(W * H * 4, 255);
for (let r = 0; r < rowsN; r++) for (let half = 0; half < 2; half++) {
  for (let c = 0; c < cols; c++) {
    const it = items[r * cols + c]; if (!it) continue;
    const a = half === 0 ? it.m.a : scaleAbout(it.m.a, it.m.w, it.m.h, it.s);
    const px = plate(a, it.m.w, it.m.h, it.side);
    const ox = c * cell, oy = r * (cell * 2 + lab * 2) + half * (cell + lab);
    for (let y = 0; y < cell; y++) for (let x = 0; x < cell; x++) {
      const sx = Math.floor(x * it.m.w / cell), sy = Math.floor(y * it.m.h / cell);
      const si = (sy * it.m.w + sx) * 4, di = ((oy + y) * W + ox + x) * 4;
      big[di] = px[si]; big[di + 1] = px[si + 1]; big[di + 2] = px[si + 2];
    }
  }
}
fs.writeFileSync(outPng, C.encodePng(W, H, big));
console.log("写出 " + outPng + "  每格上=现状 下=按安全区缩（只缩不放）");
