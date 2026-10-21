// 给"单色合成用同一套几何"造语料：把 1.1.1 那套裁圆窗口**真的套上去**，但**不画圆、不铺白底**。
//
// 口径来源：
//   · 几何 = app/.../circle/CircleIconDrawable.kt 的 scanBoxFraction()（三条分支 + 全部"不动"退出口），
//     本文件那份 `scanBoxKt()` 是它的逐行转写（含 ③ 里 Kotlin 独有的 `far < 1` 退出口）。
//   · 像素原语（PNG 解码/编码、resample、coveredRadius、extents）= **直接复用 `_circab.cjs`**，
//     不重写公式：把它的文件头（到 `const BG_A =` 之前，纯函数无副作用）取出来当场装配成模块，
//     CLI 那半截（含它注入的 5 张合成形状）根本不执行 —— 本脚本一张合成形状都不注入。
//   · 每条输入都拿 `_circab.cjs` 的 `boxFraction()` 再算一遍做**交叉核对**，不一致就报出来。
//
// 用法：node _mk_cropcorpus.cjs --out=<输出目录> [--mirror-dir=<源目录>] [--rescan] <png...>
//   --out         必填，输出目录（不存在会建）
//   --mirror-dir  把该目录里**没被本脚本写过**的文件逐字节补拷过去（index.tsv / *_modelbg.png / 漏网的）
//                 —— "输出目录 = 输入目录同一套文件"这一条是靠构造保证的，不是靠事后核对
//   --rescan      只分类、不写任何文件（用它复量输出目录，验证"窗口真的变成整块画布"）
//   --dump        把逐张明细（分支/放大/窗口/退口）写成 JSON，给核对脚本用
//
// ⚠ 与 `_circab.cjs` 的一处**已知**偏差（本脚本按 Kotlin 走）：Kotlin 的 `cx=(x0+x1)/2` 是 Int 除法，
//   `_circab` 用浮点中心 —— 外框宽/高为偶数时窗口中心差 0.5 个扫描像素（= 画布的 0.4%），
//   纯平移，窗口尺寸/分支/放大倍数两边一致。本轮 62 张里 9 张踩到，跑完会把名单打出来。
//
// 满幅不动 = 窗口为 null（④ 的满幅早退、③ 的 far<1、空框、rCover<1、量不出来）→ **逐字节原样拷贝**，
// 因为那些正是引擎什么都不做的对照组；有窗口 = 按 MaskedDrawable.draw() 的 cover+居中仿射重采样成整块画布。
const fs = require("fs"), path = require("path");

// ---- 装配 `_circab.cjs` 的函数头（只取纯函数那半截）----
const CIRCAB = path.join(__dirname, "_circab.cjs");
function loadCircabHead() {
  const src = fs.readFileSync(CIRCAB, "utf8");
  const cut = src.indexOf("const BG_A =");
  if (cut < 0) throw new Error("_circab.cjs 结构变了（找不到 CLI 段的起点 `const BG_A =`），拒绝猜测");
  const head = src.slice(0, cut) +
    "\nreturn { decodePng, encodePng, resample, coveredRadius, extents, boxFraction, " +
    "CAP, A_MIN, ROW_MIN, ROW_DIV, FULL, COVER_ALPHA, KEEP_RATIO, SAFE_ZONE, TIP_TAIL, TIP_FLOOR, SHRINK_EXTRA, PLATE_INK };";
  // head 里只有函数声明与常数，没有 I/O —— 传一个假的 require 也走得通，但传真的更省事
  return new Function("require", "__dirname", head)(require, path.dirname(CIRCAB));
}
const C = loadCircabHead();

// ---- 与 Kotlin 同一套参数（从 _circab.cjs 那边接过来，两处不许各写一份数）----
const BOX_SCAN_EDGE = C.CAP, BOX_ALPHA_MIN = C.A_MIN, BOX_ROW_MIN_PIXELS = C.ROW_MIN;
const BOX_ROW_DIVISOR = C.ROW_DIV, BOX_FULL_RATIO = C.FULL, BOX_COVER_ALPHA = C.COVER_ALPHA;
const KEEP_RATIO = C.KEEP_RATIO, SAFE_ZONE = C.SAFE_ZONE, TIP_TAIL = C.TIP_TAIL;
const TIP_FLOOR = C.TIP_FLOOR, SHRINK_EXTRA = C.SHRINK_EXTRA, PLATE_INK = C.PLATE_INK;

/** Kotlin 的 (x0+x1)/2 是 Int 除法 = 向下取整；JS 得自己来一刀。 */
const idiv = (a, b) => Math.floor(a / b);

/**
 * `scanBoxFraction()` 的逐行转写。
 * 返回 { win: null | [l,t,r,b]（画布比例）, cls, zoom, why, sw, sh, bw, bh, keepPct }
 *  cls: full=满幅不动（win=null）｜ inscribe=内切放大（④）｜ shrink=不裁缩进（③）
 */
function scanBoxKt(px, iw, ih, canonical) {
  const out = { win: null, cls: "full", zoom: 1, sw: 0, sh: 0, bw: 0, bh: 0, keepPct: 100 };
  if (iw <= 0 || ih <= 0) { out.why = "固有尺寸非正"; return out; }
  const shrink = canonical
    ? BOX_SCAN_EDGE / Math.max(iw, ih)
    : Math.min(1, BOX_SCAN_EDGE / Math.max(iw, ih));
  const sw = Math.max(1, Math.round(iw * shrink)), sh = Math.max(1, Math.round(ih * shrink));
  out.sw = sw; out.sh = sh;
  // Kotlin 是把 drawable 光栅化到 sw×sh；这里用 _circab 的双线性 resample 当同一趟
  const s = C.resample(px, iw, ih, sw, sh);

  // ① 图案外框
  const rowMin = Math.max(BOX_ROW_MIN_PIXELS, idiv(sw, BOX_ROW_DIVISOR));
  const colMin = Math.max(BOX_ROW_MIN_PIXELS, idiv(sh, BOX_ROW_DIVISOR));
  let x0 = sw, x1 = -1, y0 = sh, y1 = -1;
  for (let y = 0; y < sh; y++) {
    let n = 0; for (let x = 0; x < sw; x++) if (s[(y * sw + x) * 4 + 3] >= BOX_ALPHA_MIN) n++;
    if (n >= rowMin) { if (y < y0) y0 = y; y1 = y; }
  }
  for (let x = 0; x < sw; x++) {
    let n = 0; for (let y = 0; y < sh; y++) if (s[(y * sw + x) * 4 + 3] >= BOX_ALPHA_MIN) n++;
    if (n >= colMin) { if (x < x0) x0 = x; x1 = x; }
  }
  if (x1 < 0 || y1 < 0) { out.why = "外框为空"; return out; }
  const bw = x1 - x0 + 1, bh = y1 - y0 + 1;
  out.bw = bw; out.bh = bh;
  const cx = idiv(x0 + x1, 2), cy = idiv(y0 + y1, 2);   // Kotlin：Int，之后当整数用
  const rCover = C.coveredRadius(s, sw, sh, cx, cy);

  // keepCounts（"按这个圆裁下去还剩几成"）
  let keep = 0, total = 0;
  const rr = rCover * rCover;
  for (let y = 0; y < sh; y++) for (let x = 0; x < sw; x++) {
    if (s[(y * sw + x) * 4 + 3] < BOX_ALPHA_MIN) continue;
    total++;
    const dx = x - cx, dy = y - cy;
    if (dx * dx + dy * dy <= rr) keep++;
  }
  out.keepPct = total ? keep * 100 / total : 100;

  const win = (r) => [
    (cx + 0.5 - r) / sw, (cy + 0.5 - r) / sh,
    (cx + 0.5 + r) / sw, (cy + 0.5 + r) / sh,
  ];
  const zoomOf = (l, t, right, bottom) => Math.max(sw / (right - l), sh / (bottom - t));

  // ③ 裁不动的那档
  if (total > 0 && keep < total * KEEP_RATIO) {
    const ext = C.extents(s, sw, sh, cx, cy);
    const far = ext[0], body = ext[1];
    if (far < 1) { out.why = "far<1（③ 独有退出口）"; return out; }
    const rBox3 = Math.max(far / TIP_FLOOR, body / SAFE_ZONE);
    const rInk = Math.sqrt(total / (4 * PLATE_INK));
    const rWin = Math.max(rBox3, rInk) * SHRINK_EXTRA;
    const f = win(rWin);
    out.cls = "shrink"; out.win = f; out.zoom = zoomOf(f[0] * sw, f[1] * sh, f[2] * sw, f[3] * sh);
    out.why = `不裁缩进 裁完剩${out.keepPct.toFixed(0)}% body/far=${(body / far).toFixed(2)} 墨${(100 * total / (4 * rBox3 * rBox3)).toFixed(0)}%`;
    return out;
  }
  if (rCover < 1) { out.why = "rCover<1（中心自己就是空的）"; return out; }
  // ④ 的满幅早退：图案本来就铺满画布的，逐位不变
  if (bw >= sw * BOX_FULL_RATIO && bh >= sh * BOX_FULL_RATIO) { out.why = "满幅早退（BOX_FULL_RATIO）"; return out; }
  const f = win(rCover);
  out.cls = "inscribe"; out.win = f; out.zoom = zoomOf(f[0] * sw, f[1] * sh, f[2] * sw, f[3] * sh);
  out.why = `内切放大 裁完剩${out.keepPct.toFixed(0)}% rCover=${rCover.toFixed(1)}`;
  return out;
}

/**
 * MaskedDrawable.draw() 那一脚 cover + 窗口居中，采样到输出画布（= 图自身尺寸）。
 * 窗口按 Kotlin 一样先量化成 Rect（round(f*iw)），boxWidth/Height、exactCenter 都从 Rect 来。
 * ⚠ 只搬画布，**不画圆、不铺白底**：圆是彩色那支的事，白底会被取色模型当成盘。
 */
function reframe(px, w, h, f) {
  const bl = Math.round(f[0] * w), bt = Math.round(f[1] * h);
  const br = Math.round(f[2] * w), bb = Math.round(f[3] * h);
  const boxW = Math.max(1, br - bl), boxH = Math.max(1, bb - bt);
  const bcx = (bl + br) / 2, bcy = (bt + bb) / 2;          // Rect.exactCenterX/Y
  const scale = Math.max(w / boxW, h / boxH);              // draw() 里的 scale
  const dst = Buffer.alloc(w * h * 4);
  // 正变换是 dev = (src - bc) * scale + size/2，反解出源坐标（纹素中心系）
  const tex = (buf, W, H, ix, iy, k) => (ix < 0 || iy < 0 || ix >= W || iy >= H) ? 0 : buf[(iy * W + ix) * 4 + k];
  for (let y = 0; y < h; y++) {
    const fy = (y + 0.5 - h / 2) / scale + bcy - 0.5;
    const y0 = Math.floor(fy), ty = fy - y0;
    for (let x = 0; x < w; x++) {
      const fx = (x + 0.5 - w / 2) / scale + bcx - 0.5;
      const x0 = Math.floor(fx), tx = fx - x0;
      // 双线性在**预乘**空间做：透明纹素的 RGB 是垃圾值，直接插值会把脏颜色渗进半透明边
      let outA = 0;
      const acc = [0, 0, 0];
      for (const [dy, wy] of [[0, 1 - ty], [1, ty]]) for (const [dx, wx] of [[0, 1 - tx], [1, tx]]) {
        const wt = wy * wx, a = tex(px, w, h, x0 + dx, y0 + dy, 3);
        outA += wt * a;
        for (let k = 0; k < 3; k++) acc[k] += wt * tex(px, w, h, x0 + dx, y0 + dy, k) * a;
      }
      const d = (y * w + x) * 4;
      // 存 alpha 先取整，颜色按**取整后**的 alpha 决定要不要留：
      // 否则 outA=0.4 这种"存下来是 0"的格子会留着一份插值出来的颜色 —— 屏幕上看不见，
      // 但取色模型读到的就是一堆"透明却带色"的幽灵像素。
      const aR = Math.round(outA);
      dst[d + 3] = aR;
      for (let k = 0; k < 3; k++) dst[d + k] = aR > 0 ? Math.min(255, Math.round(acc[k] / outA)) : 0;
    }
  }
  return { dst, scale, box: [bl, bt, br, bb] };
}

// ---- CLI ----
const args = process.argv.slice(2);
const opt = (name) => args.find(a => a.startsWith(`--${name}=`))?.slice(name.length + 3);
const files = args.filter(a => !a.startsWith("--"));
const outDir = opt("out");
const mirrorDir = opt("mirror-dir");
const rescan = args.includes("--rescan");
if (!files.length || (!outDir && !rescan)) {
  console.log("用法: node _mk_cropcorpus.cjs --out=<目录> [--mirror-dir=<目录>] [--rescan] [--dump=<json>] <png...>");
  process.exit(2);
}
if (!rescan) fs.mkdirSync(outDir, { recursive: true });

const LABEL = { full: "满幅不动", inscribe: "内切放大", shrink: "不裁缩进" };
const count = { full: 0, inscribe: 0, shrink: 0 };
const whyFull = {};
let bytesCopied = 0, bytesRewritten = 0, mismatches = [], centreHalf = [];
const written = new Set(), rows = [];
const dumpPath = opt("dump");
let firstImg = null;

for (const f0 of files) {
  const name = path.basename(f0);
  let png, box;
  try {
    png = C.decodePng(fs.readFileSync(f0));
    box = scanBoxKt(png.px, png.w, png.h, false);
    if (!firstImg) firstImg = png;
  } catch (e) {
    console.log(`${name.padEnd(62)} 解码失败 → 原样拷贝 (${e.message})`);
    if (!rescan) { fs.copyFileSync(f0, path.join(outDir, name)); bytesCopied += fs.statSync(f0).size; written.add(name); }
    count.full++; whyFull["解码失败"] = (whyFull["解码失败"] || 0) + 1;
    continue;
  }
  // canonical 那一路（固有尺寸未知的自适应图标）在正方形输入上必须与常规路一致 —— 量一刀，不一样就报
  if (Math.abs(Math.max(png.w, png.h) - BOX_SCAN_EDGE) > 0) {
    const alt = scanBoxKt(png.px, png.w, png.h, true);
    const same = (alt.win === null) === (box.win === null) &&
      (!box.win || box.win.every((v, i) => Math.abs(v - alt.win[i]) < 1e-6));
    if (!same) mismatches.push(`${name}: canonical 与常规分支不一致`);
  }
  // 交叉核对 `_circab.cjs` 的 boxFraction
  const ref = C.boxFraction(png.px, png.w, png.h);
  if (!ref !== !(box.win && (box.cls === "inscribe" || box.cls === "shrink"))) {
    mismatches.push(`${name}: null 性不一致 _circab=${ref ? "有窗" : "无窗"} 本脚本=${box.win ? "有窗" : "无窗"} (${box.why})`);
  } else if (ref && box.win) {
    const sw = box.sw, sh = box.sh;
    const dl = (ref.l - box.win[0]) * sw, dt = (ref.t - box.win[1]) * sh;
    const dr = (ref.r - box.win[2]) * sw, db = (ref.b - box.win[3]) * sh;
    const worst = Math.max(Math.abs(dl), Math.abs(dt), Math.abs(dr), Math.abs(db));
    const refBranch = ref.porous ? "shrink" : "inscribe";
    if (worst > 1e-6) {
      // 唯一已知的偏差：**窗口中心差半个扫描像素**。Kotlin 的 cx=(x0+x1)/2 是 Int 除法（向下取整），
      // _circab 用的是浮点 (x0+x1)/2 —— 外框宽/高为偶数时两者差 0.5px（= 画布的 0.5/sw）。
      // 是纯**平移**：左右（或上下）偏移等号同号，窗口**尺寸**两边一致 → 放大倍数与分支都不受影响。
      // 本脚本按 Kotlin 走（引擎是唯一口径），这一条只是把已知偏移如实登记，不当成算错。
      const is0 = (v) => Math.abs(v) < 1e-6;
      const isHalf = (v) => Math.abs(Math.abs(v) - 0.5) < 1e-6;
      const axisShift = (a, b) => (is0(a) && is0(b)) || (isHalf(a) && isHalf(b) && a === b);
      if (axisShift(dl, dr) && axisShift(dt, db)) {
        const ax = !is0(dl) || !is0(dr), ay = !is0(dt) || !is0(db);
        centreHalf.push(`${name}(${ax ? `x${dl > 0 ? "+" : "-"}` : ""}${ay ? `y${dt > 0 ? "+" : "-"}` : ""})`);
      } else mismatches.push(`${name}: 窗口偏差(扫描px) l=${dl.toFixed(3)} t=${dt.toFixed(3)} r=${dr.toFixed(3)} b=${db.toFixed(3)}`);
    }
    if (refBranch !== box.cls) mismatches.push(`${name}: 分支不一致 _circab=${refBranch} 本脚本=${box.cls}`);
    // 窗口宽高比 = 1（正方形），所以 _circab 的 1/(r-l) 与 Kotlin 日志里的 max(sw/(r-l), sh/(b-t)) 应当同值
    const refZoom = 1 / (ref.r - ref.l);
    if (Math.abs(refZoom - box.zoom) > 1e-6) mismatches.push(`${name}: zoom 口径差 本脚本${box.zoom.toFixed(6)} vs 1/(r-l)=${refZoom.toFixed(6)}`);
    // 窗口尺寸本身必须逐位相同（否则就是真的算岔了）
    const sizeBad = Math.abs((ref.r - ref.l) * sw - (box.win[2] - box.win[0]) * sw) > 1e-6 ||
      Math.abs((ref.b - ref.t) * sh - (box.win[3] - box.win[1]) * sh) > 1e-6;
    if (sizeBad) mismatches.push(`${name}: 窗口尺寸不一致（不是中心偏移）`);
  }

  count[box.cls]++;
  if (box.cls === "full") whyFull[box.why || "满幅"] = (whyFull[box.why || "满幅"] || 0) + 1;
  const frac = box.win || [0, 0, 1, 1];
  const bail = box.cls === "full" && (box.why || "") !== "满幅早退（BOX_FULL_RATIO）" ? `   [退口: ${box.why}]` : "";
  console.log(`${name.padEnd(62)} ${LABEL[box.cls]}  zoom=${box.zoom.toFixed(3)}  ` +
    `窗=(${frac.map(v => v.toFixed(4)).join(", ")})${bail}`);

  rows.push({ name, cls: box.cls, zoom: box.zoom, win: box.win, why: box.why, sw: box.sw, sh: box.sh, bw: box.bw, bh: box.bh, keepPct: box.keepPct });
  if (rescan) continue;
  if (box.cls === "full") {                       // 对照组：逐字节不动
    fs.copyFileSync(f0, path.join(outDir, name));
    bytesCopied += fs.statSync(path.join(outDir, name)).size;
  } else {
    const { dst } = reframe(png.px, png.w, png.h, box.win);
    const buf = C.encodePng(png.w, png.h, dst);
    fs.writeFileSync(path.join(outDir, name), buf);
    bytesRewritten += buf.length;
  }
  written.add(name);
}

// 自检：恒等窗口（整块画布）重采样必须逐位还原原图 —— 否则仿射的像素中心约定就错了。
// 唯一的例外是 alpha=0 的格子：那里的 RGB 本来就是垃圾值，本脚本一律清成 0（计数单列，不算失败）。
if (firstImg) {
  const { dst } = reframe(firstImg.px, firstImg.w, firstImg.h, [0, 0, 1, 1]);
  let bad = 0, cleaned = 0;
  for (let i = 0; i < dst.length; i += 4) {
    if (dst[i + 3] !== firstImg.px[i + 3]) { bad++; continue; }
    for (let k = 0; k < 3; k++) {
      if (dst[i + k] === firstImg.px[i + k]) continue;
      if (firstImg.px[i + 3] === 0) cleaned++; else bad++;
    }
  }
  console.log(`\n自检: 恒等窗口重采样与原图逐位差 ${bad} 个通道值 ${bad === 0 ? "✓" : "✗✗✗"}` +
    `（另：alpha=0 的幽灵颜色清掉 ${cleaned} 个，与显示无关）`);
  if (bad) process.exitCode = 1;
}

let mirrorBytes = 0, mirrorList = [];
if (mirrorDir && !rescan) {
  for (const name of fs.readdirSync(mirrorDir)) {
    const s = path.join(mirrorDir, name);
    if (!fs.statSync(s).isFile() || written.has(name)) continue;
    fs.copyFileSync(s, path.join(outDir, name));
    mirrorBytes += fs.statSync(s).size;
    mirrorList.push(name);
  }
}

console.log(`\n共 ${files.length} 张：满幅不动 ${count.full} ｜ 内切放大 ${count.inscribe} ｜ 不裁缩进 ${count.shrink}`);
console.log("满幅那一档的退口构成: " + Object.entries(whyFull).map(([k, v]) => `${k}=${v}`).join(" ｜ "));
console.log(`逐字节原样拷贝：输入里 ${count.full} 张 ${bytesCopied} 字节` +
  (mirrorDir ? ` ＋ 补拷 ${mirrorList.length} 个 ${mirrorBytes} 字节（${mirrorList.slice(0, 3).join(",")}${mirrorList.length > 3 ? "…" : ""}）` : ""));
console.log(`重画并编码：${count.inscribe + count.shrink} 张 ${bytesRewritten} 字节`);
console.log(centreHalf.length
  ? `中心偏移（已知，本脚本按 Kotlin 取整）: ${centreHalf.length} 张外框宽/高为偶 → 窗口中心比 _circab 偏 0.5 扫描px: ${centreHalf.join(", ")}`
  : "与 _circab.cjs 的窗口中心逐张一致");
console.log(mismatches.length ? "!! 与 _circab.cjs 的真分歧:\n  " + mismatches.join("\n  ") : "交叉核对: 分支/窗口尺寸/放大倍数与 _circab.cjs 逐张一致 ✓");
if (dumpPath) { fs.writeFileSync(dumpPath, JSON.stringify(rows, null, 1) + "\n"); console.log(`写出明细 ${dumpPath}（${rows.length} 行）`); }
