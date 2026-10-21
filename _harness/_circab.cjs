// 裁圆「原样 / 现状 / 改后」三格对照 + 逐张的放大倍数清单。
// 这是**几何的镜像实现**（同一套公式），不是真机产物：真机那一版靠装机 + logcat 验。
// 每行三格固定顺序：① 原图（不裁）② 现状=按整块画布内切 ③ 改后=按图案外框内切。
// 透明处画成棋盘格 —— 改前"圆内围一圈透明"就是棋盘格露出来的那一圈。
// 用法：node "D:/Github Repositories/Opticon/_harness/_circab.cjs" <png...> [--panel=300] [--out=路径]
const fs = require("fs"), zlib = require("zlib"), path = require("path");

function decodePng(buf) {
  let pos = 8, idat = [], ihdr = null, plte = null, trns = null;
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString("latin1", pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === "IHDR") ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), depth: data[8], color: data[9], interlace: data[12] };
    else if (kind === "PLTE") plte = data;
    else if (kind === "tRNS") trns = data;
    else if (kind === "IDAT") idat.push(data);
    else if (kind === "IEND") break;
    pos += len + 12;
  }
  if (!ihdr || ihdr.depth !== 8 || ihdr.interlace) throw new Error("unsupported png");
  const { w, h, color } = ihdr;
  const chans = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[color];
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = w * chans, out = Buffer.alloc(stride * h);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const filt = raw[y * (stride + 1)];
    const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride));
    for (let i = 0; i < stride; i++) {
      const a = i >= chans ? line[i - chans] : 0, b = prev[i], c = i >= chans ? prev[i - chans] : 0;
      let p = 0;
      if (filt === 1) p = a; else if (filt === 2) p = b; else if (filt === 3) p = (a + b) >> 1;
      else if (filt === 4) { const q = a + b - c, pa = Math.abs(q - a), pb = Math.abs(q - b), pc = Math.abs(q - c); p = pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
      line[i] = (line[i] + p) & 255;
    }
    line.copy(out, y * stride); prev = line;
  }
  const px = Buffer.alloc(w * h * 4);
  for (let i = 0, n = w * h; i < n; i++) {
    const s = i * chans, d = i * 4;
    if (color === 6) { px[d] = out[s]; px[d + 1] = out[s + 1]; px[d + 2] = out[s + 2]; px[d + 3] = out[s + 3]; }
    else if (color === 2) { px[d] = out[s]; px[d + 1] = out[s + 1]; px[d + 2] = out[s + 2]; px[d + 3] = 255; }
    else if (color === 4) { px[d] = px[d + 1] = px[d + 2] = out[s]; px[d + 3] = out[s + 1]; }
    else if (color === 0) { px[d] = px[d + 1] = px[d + 2] = out[s]; px[d + 3] = 255; }
    else { const k = out[s] * 3; px[d] = plte[k]; px[d + 1] = plte[k + 1]; px[d + 2] = plte[k + 2]; px[d + 3] = trns && out[s] < trns.length ? trns[out[s]] : 255; }
  }
  return { w, h, px };
}

const CRC = (() => { const t = new Int32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c; } return b => { let c = -1; for (const x of b) c = t[(c ^ x) & 255] ^ (c >>> 8); return (c ^ -1) >>> 0; }; })();
function encodePng(w, h, px) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 4 + 1)] = 0; px.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4); }
  const chunk = (kind, payload) => { const b = Buffer.concat([Buffer.from(kind, "latin1"), payload]); const out = Buffer.alloc(8 + payload.length + 4); out.writeUInt32BE(payload.length, 0); b.copy(out, 4); out.writeUInt32BE(CRC(b), 8 + payload.length); return out; };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0))]);
}

// ---- 与 Kotlin 同一套参数（改了那边要改这里）----
const CAP = 128, A_MIN = 8, ROW_MIN = 2, ROW_DIV = 64, FULL = 0.99;
const COVER_ALPHA = 128, KEEP_RATIO = 0.6;
const SAFE_ZONE = 66 / 72, TIP_TAIL = 0.02, TIP_FLOOR = 0.93, SHRINK_EXTRA = 1.19, PLATE_INK = 0.5;   // 与 Kotlin 同一套

/** 以 (cx,cy) 为心、整个落在"不透明区"里的最大圆盘半径（画布外算外）—— 同 coveredRadius。 */
function coveredRadius(px, w, h, cx, cy) {
  const inf = w + h, d = new Float64Array(w * h);
  for (let i = 0; i < d.length; i++) d[i] = (px[i * 4 + 3] >= COVER_ALPHA) ? inf : 0;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = y * w + x; if (d[i] === 0) continue;
    let v = d[i];
    if (x > 0) v = Math.min(v, d[i - 1] + 1);
    if (y > 0) v = Math.min(v, d[i - w] + 1);
    if (x > 0 && y > 0) v = Math.min(v, d[i - w - 1] + 1.414);
    if (x < w - 1 && y > 0) v = Math.min(v, d[i - w + 1] + 1.414);
    d[i] = v;
  }
  for (let y = h - 1; y >= 0; y--) for (let x = w - 1; x >= 0; x--) {
    const i = y * w + x; if (d[i] === 0) continue;
    let v = d[i];
    if (x < w - 1) v = Math.min(v, d[i + 1] + 1);
    if (y < h - 1) v = Math.min(v, d[i + w] + 1);
    if (x < w - 1 && y < h - 1) v = Math.min(v, d[i + w + 1] + 1.414);
    if (x > 0 && y < h - 1) v = Math.min(v, d[i + w - 1] + 1.414);
    d[i] = v;
  }
  return Math.min(d[cy * w + cx], Math.min(cx, cy, w - 1 - cx, h - 1 - cy)) - 1;
}

function resample(src, w, h, sw, sh) {      // 双线性：Kotlin 那趟是把 drawable 画进小图
  const dst = Buffer.alloc(sw * sh * 4);
  for (let y = 0; y < sh; y++) for (let x = 0; x < sw; x++) {
    const fx = (x + 0.5) * w / sw - 0.5, fy = (y + 0.5) * h / sh - 0.5;
    const x0 = Math.max(0, Math.min(w - 1, Math.floor(fx))), y0 = Math.max(0, Math.min(h - 1, Math.floor(fy)));
    const x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1), tx = fx - x0, ty = fy - y0;
    for (let k = 0; k < 4; k++) {
      const a = src[(y0 * w + x0) * 4 + k] * (1 - tx) + src[(y0 * w + x1) * 4 + k] * tx;
      const b = src[(y1 * w + x0) * 4 + k] * (1 - tx) + src[(y1 * w + x1) * 4 + k] * tx;
      dst[(y * sw + x) * 4 + k] = Math.round(a * (1 - ty) + b * ty);
    }
  }
  return dst;
}

/** 裁剪窗口（画布比例），与 Kotlin 的 scanBoxFraction 逐条同规则；null = 满幅。 */
function boxFraction(px, w, h) {
  const shrink = Math.min(1, CAP / Math.max(w, h));
  const sw = Math.max(1, Math.round(w * shrink)), sh = Math.max(1, Math.round(h * shrink));
  const s = resample(px, w, h, sw, sh);
  const rowMin = Math.max(ROW_MIN, Math.floor(sw / ROW_DIV)), colMin = Math.max(ROW_MIN, Math.floor(sh / ROW_DIV));
  let x0 = sw, x1 = -1, y0 = sh, y1 = -1;
  for (let y = 0; y < sh; y++) { let n = 0; for (let x = 0; x < sw; x++) if (s[(y * sw + x) * 4 + 3] >= A_MIN) n++; if (n >= rowMin) { if (y < y0) y0 = y; y1 = y; } }
  for (let x = 0; x < sw; x++) { let n = 0; for (let y = 0; y < sh; y++) if (s[(y * sw + x) * 4 + 3] >= A_MIN) n++; if (n >= colMin) { if (x < x0) x0 = x; x1 = x; } }
  if (x1 < 0 || y1 < 0) return null;
  const bw = x1 - x0 + 1, bh = y1 - y0 + 1;
  const cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
  const icx = Math.floor(cx), icy = Math.floor(cy);
  const rBox = Math.min(bw, bh) / 2;
  const rCover = coveredRadius(s, sw, sh, icx, icy);
  // 裁下去还剩几成图案（基准：正方形按圆形裁 = 78.5%）
  let keep = 0, total = 0;
  for (let y = 0; y < sh; y++) for (let x = 0; x < sw; x++) {
    if (s[(y * sw + x) * 4 + 3] < A_MIN) continue;
    total++;
    if ((x - icx) * (x - icx) + (y - icy) * (y - icy) <= rCover * rCover) keep++;
  }
  let r, porous = false, ext = [0, 0], inkPct = 0, altR = 0;
  if (total > 0 && keep < total * KEEP_RATIO) {
    porous = true;
    ext = extents(s, sw, sh, icx, icy);
    // 三条约束取最保守的：主体进安全区、尖角不贴边、**墨量对齐**（治"形状不同看着大小不一"）
    const rExt = Math.max(ext[0] / TIP_FLOOR, ext[1] / SAFE_ZONE);
    const rInk = Math.sqrt(total / (4 * PLATE_INK));
    r = Math.max(rExt, rInk) * SHRINK_EXTRA;
    altR = rInk * SHRINK_EXTRA;   // B 案：只按墨量放大（外缘会被圆切掉）
    inkPct = 100 * total / (4 * (r / SHRINK_EXTRA) * (r / SHRINK_EXTRA));   // 对齐前的墨量（面板占比）
  } else {
    if (x1 - x0 + 1 >= sw * FULL && y1 - y0 + 1 >= sh * FULL) return null;   // 满幅 → 不动（逐位不变）
    if (rCover < 1) return null;
    r = rCover;   // 裁得好的：恢复原样（内切到圆、填满、无白边）
  }
  if (r < 1) return null;
  return {
    l: (cx + 0.5 - r) / sw, t: (cy + 0.5 - r) / sh,
    r: (cx + 0.5 + r) / sw, b: (cy + 0.5 + r) / sh,
    sw, sh, rBox, rCover, porous, keepPct: total ? keep * 100 / total : 100,
    bodyFar: ext[0] ? ext[1] / ext[0] : 0, inkPct,
    alt: altR ? { l: (cx + 0.5 - altR) / sw, t: (cy + 0.5 - altR) / sh, r: (cx + 0.5 + altR) / sw, b: (cy + 0.5 + altR) / sh } : null,
  };
}

/** 图案的"体量"：[0]=最远像素半径；[1]=外侧只占 ≤TIP_TAIL 的尖角之外的半径（同 Kotlin extents）。 */
function extents(px, w, h, cx, cy) {
  let far = 0, total = 0;
  const hist = new Int32Array(Math.min(w + h, 4096));
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    if (px[(y * w + x) * 4 + 3] < A_MIN) continue;
    const r = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
    if (r > far) far = r;
    hist[Math.min(hist.length - 1, Math.floor(r))]++;
    total++;
  }
  let tail = 0, body = far;
  for (let r = hist.length - 1; r >= 0; r--) {
    tail += hist[r];
    if (tail > total * TIP_TAIL) { body = r + 1; break; }
  }
  return [far, body];
}

const BG_A = [200, 205, 214], BG_B = [228, 231, 237];   // 棋盘格 = 透明
const args = process.argv.slice(2);
const panel = +(args.find(a => a.startsWith("--panel="))?.slice(8) ?? 300);
const outPath = args.find(a => a.startsWith("--out="))?.slice(6) ?? "D:/Github Repositories/Opticon/_harness/_circab.png";
const files = args.filter(a => !a.startsWith("--"));

// 两张合成图：非方形的（宽幅 banner）与"小 logo 居中"，语料里没有这两类
function synth(kind) {
  const S = 384, px = Buffer.alloc(S * S * 4);
  const put = (x, y, r, g, b, a = 255) => { if (x < 0 || y < 0 || x >= S || y >= S) return; const d = (y * S + x) * 4; px[d] = r; px[d + 1] = g; px[d + 2] = b; px[d + 3] = a; };
  if (kind === "banner") { for (let y = 144; y < 240; y++) for (let x = 0; x < S; x++) put(x, y, 210, 60, 70); }
  else if (kind === "triangle") {
    for (let y = 20; y < S - 20; y++) {
      const half = (y - 20) / (S - 40) * (S - 40) / 2;
      for (let x = S / 2 - half; x <= S / 2 + half; x++) put(Math.round(x), y, 40, 90, 180);
    }
  } else if (kind === "star") {
    const pts = [];
    for (let i = 0; i < 10; i++) {
      const a = -Math.PI / 2 + i * Math.PI / 5, rad = i % 2 ? 70 : 170;
      pts.push([S / 2 + rad * Math.cos(a), S / 2 + rad * Math.sin(a)]);
    }
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      let inside = false;
      for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
        const xi = pts[i][0], yi = pts[i][1], xj = pts[j][0], yj = pts[j][1];
        if ((yi > y) !== (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
      }
      if (inside) put(x, y, 235, 175, 35);
    }
  } else if (kind === "ring") {
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const d = Math.hypot(x - S / 2, y - S / 2);
      if (d < 150 && d > 70) put(x, y, 60, 140, 90);
    }
  } else { for (let y = 96; y < 288; y++) for (let x = 96; x < 288; x++) put(x, y, 40, 110, 200); }
  return { w: S, h: S, px };
}

const items = files.map(f => ({ name: path.basename(f), png: decodePng(fs.readFileSync(f)) }));
items.push({ name: "合成: 宽幅 banner（外框 4:1）", png: synth("banner") });
items.push({ name: "合成: 小 logo 居中 50%", png: synth("logo") });
items.push({ name: "合成: 三角形（外框角是空的）", png: synth("triangle") });
items.push({ name: "合成: 圆环（中心就是空的）", png: synth("ring") });
items.push({ name: "合成: 五角星（尖角多）", png: synth("star") });

// --size：同一个图案画成不同边长时，"裁完剩几成"飘不飘 —— 真机上同一张图标确实会被画成
//         79/95/96/192 好几种尺寸，比例数如果跟着尺寸翻，判据就会在边界上瞎切。
if (args.includes("--size")) {
  for (const it of items) {
    const out = [64, 96, 128, 192, 384].map(S => {
      const s = resample(it.png.px, it.png.w, it.png.h, S, S);
      const f = boxFraction(s, S, S);
      return `${S}:${f ? f.keepPct.toFixed(0) + "%" + (f.porous ? "缩" : "填") : "满幅"}`;
    });
    console.log(`  ${it.name.slice(0, 44).padEnd(46)} ${out.join("  ")}`);
  }
  process.exit(0);
}

// --scan：只按实现那把尺子报数（不渲染）
if (args.includes("--scan")) {
  let changed = 0;
  const rowsOut = items.map(it => {
    const f = boxFraction(it.png.px, it.png.w, it.png.h);
    if (!f) return `  ${it.name.slice(0, 50).padEnd(52)} 满幅（不动）`;
    changed++;
    const zoom = 1 / (f.r - f.l);
    return `  ${it.name.slice(0, 50).padEnd(52)} 裁完剩 ${f.keepPct.toFixed(0)}%  放大 ${zoom.toFixed(3)}` +
      (f.porous ? "  → 不裁+白底" : "  → 放大填满");
  });
  console.log(`共 ${items.length} 张：满幅（不动）${items.length - changed} 张，会放大 ${changed} 张`);
  rowsOut.filter(l => !l.includes("满幅（不动）")).forEach(l => console.log(l));
  process.exit(0);
}

// --gap：把每张按新裁剪放进 192 的圆里，数**圆内**白底露出多少 —— 裁得好的图标应≈0，
//        多孔的那些"不裁+白底"必然露一片（那正是补上去的底）
if (args.includes("--gap")) {
  const P = 192, R = P / 2;
  for (const it of items) {
    const src = it.png, f = boxFraction(src.px, src.w, src.h);
    const l = f ? f.l : 0, t = f ? f.t : 0, rr = f ? f.r : 1, bb = f ? f.b : 1;
    const k = Math.min(rr - l, bb - t), cx = (l + rr) / 2, cy = (t + bb) / 2;
    let n = 0, white = 0, clear = 0;
    for (let y = 0; y < P; y++) for (let x = 0; x < P; x++) {
      if (Math.hypot(x + 0.5 - R, y + 0.5 - R) > R) continue;
      const sxf = cx + ((x + 0.5) / P - 0.5) * k, syf = cy + ((y + 0.5) / P - 0.5) * k;
      const x1 = Math.max(0, Math.min(src.w - 1, Math.floor(sxf * src.w))), y1 = Math.max(0, Math.min(src.h - 1, Math.floor(syf * src.h)));
      const a = src.px[(y1 * src.w + x1) * 4 + 3];
      n++; white += 1 - a / 255; if (a < 8) clear++;
    }
    console.log(`  ${it.name.slice(0, 50).padEnd(52)} ${f ? (f.porous ? "不裁+白底" : "放大填满") : "满幅不动"}  圆内白底露出 ${(white / n * 100).toFixed(1)}%（完全没像素 ${(clear / n * 100).toFixed(1)}%）`);
  }
  process.exit(0);
}

const GAP = 10, rows = items.length, cols = 4;
const W = GAP + cols * (panel + GAP), H = GAP + rows * (panel + GAP);
const canvas = Buffer.alloc(W * H * 4);
for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
  const c = ((x / 16 | 0) + (y / 16 | 0)) % 2 ? BG_A : BG_B, d = (y * W + x) * 4;
  canvas[d] = c[0]; canvas[d + 1] = c[1]; canvas[d + 2] = c[2]; canvas[d + 3] = 255;
}
// 面板底色（图标画在它上面）：棋盘格只在圆外/透明处露出来
const report = [];
items.forEach((it, r) => {
  const oy = GAP + r * (panel + GAP);
  const src = it.png, frac = boxFraction(src.px, src.w, src.h);
  const zoom = frac ? 1 / (frac.r - frac.l) : 1;
  report.push({ name: it.name, size: `${src.w}x${src.h}`, box: frac ? `${frac.porous ? "不裁+白底" : "放大填满"}（裁完剩${frac.keepPct.toFixed(0)}%${frac.porous ? `, body/far=${(frac.bodyFar || 0).toFixed(2)} 墨=${(frac.inkPct || 0).toFixed(0)}%` : ""}）` : "满幅", zoom: zoom.toFixed(3) });
  // ②③ 各画一次：同一套 cover，只换基准（null = 整块画布）
  const render = (col, base, withPlate) => {
    const ox = GAP + col * (panel + GAP);
    // 先把这一格铺成本格自己的棋盘格 —— 三格底色必须逐位相同，否则自检量到的是底色相位差
    for (let y = 0; y < panel; y++) for (let x = 0; x < panel; x++) {
      const c = ((x / 16 | 0) + (y / 16 | 0)) % 2 ? BG_A : BG_B, d = ((oy + y) * W + ox + x) * 4;
      canvas[d] = c[0]; canvas[d + 1] = c[1]; canvas[d + 2] = c[2];
    }
    // 新代码无条件在圆内铺一层白底：裁得好的图标看不见，多孔的上面露出来的就是它
    if (withPlate) {
      const R = panel / 2;
      for (let y = 0; y < panel; y++) for (let x = 0; x < panel; x++) {
        if (Math.hypot(x + 0.5 - R, y + 0.5 - R) > R) continue;
        const d = ((oy + y) * W + ox + x) * 4;
        canvas[d] = 255; canvas[d + 1] = 255; canvas[d + 2] = 255;
      }
    }
    const l = base ? base.l : 0, t = base ? base.t : 0, rr = base ? base.r : 1, bb = base ? base.b : 1;
    // cover：采样窗口 = 外框内能放下的最大**正方形**（= min(外框宽, 外框高)），铺满面板
    const k = Math.min(rr - l, bb - t);
    const cx = (l + rr) / 2, cy = (t + bb) / 2;
    for (let y = 0; y < panel; y++) for (let x = 0; x < panel; x++) {
      const u = (x + 0.5) / panel, v = (y + 0.5) / panel;
      const sxf = cx + (u - 0.5) * k, syf = cy + (v - 0.5) * k;
      const px_ = Math.max(0, Math.min(src.w - 1, Math.floor(sxf * src.w)));
      const py_ = Math.max(0, Math.min(src.h - 1, Math.floor(syf * src.h)));
      const s = (py_ * src.w + px_) * 4, a = src.px[s + 3] / 255, d = ((oy + y) * W + ox + x) * 4;
      for (let j = 0; j < 3; j++) canvas[d + j] = Math.round(src.px[s + j] * a + canvas[d + j] * (1 - a));
    }
    // 圆形遮罩：圆外擦成透明（= 露出棋盘格）
    const R = panel / 2, c0 = panel / 2;
    for (let y = 0; y < panel; y++) for (let x = 0; x < panel; x++) {
      const cov = Math.max(0, Math.min(1, R - Math.hypot(x + 0.5 - c0, y + 0.5 - c0) + 0.5));
      if (cov >= 1) continue;
      const d = ((oy + y) * W + ox + x) * 4;
      const bg = ((x / 16 | 0) + (y / 16 | 0)) % 2 ? BG_A : BG_B;
      for (let j = 0; j < 3; j++) canvas[d + j] = Math.round(canvas[d + j] * cov + bg[j] * (1 - cov));
    }
  };
  render(0, null, false);   // ① 原图（不裁）
  render(1, null, false);   // ② 现状：按整块画布内切（改前，没有白底）
  render(2, frac, !!(frac && frac.porous));   // ③ A 案（现状）：裁得好的恢复原样；裁不动的缩进圆里 + 圆内白底
  render(3, (frac && frac.alt) || frac, !!(frac && frac.porous));          // ④ B 案：只按墨量放大 —— 块头一样了，但外缘被圆切掉
  // 自检：①②必须逐位相同（同一套基准），②③的差异占比就是这张图改动的量
  const grab = col => { const ox = GAP + col * (panel + GAP), b = Buffer.alloc(panel * panel * 4); for (let y = 0; y < panel; y++) canvas.copy(b, y * panel * 4, ((oy + y) * W + ox) * 4, ((oy + y) * W + ox + panel) * 4); return b; };
  const p1 = grab(0), p2 = grab(1), p3 = grab(2);
  const diff = (a, b) => { let n = 0; for (let i = 0; i < a.length; i += 4) if (Math.abs(a[i] - b[i]) > 2 || Math.abs(a[i + 1] - b[i + 1]) > 2 || Math.abs(a[i + 2] - b[i + 2]) > 2) n++; return n / (a.length / 4); };
  report[report.length - 1].d12 = diff(p1, p2);
  report[report.length - 1].d23 = diff(p2, p3);
});
fs.writeFileSync(outPath, encodePng(W, H, canvas));
console.log("写出 " + outPath + ` (${W}x${H})，每行四格：原图 / 旧(按画布) / A案=现状(完整但偏小) / B案=只按墨量放大(一样大但外缘被切)`);
for (const x of report) console.log(`  ${x.name.slice(0, 46).padEnd(48)} ${x.size.padEnd(9)} 外框 ${x.box.padEnd(14)} 放大 ${x.zoom}  ①↔② 差异 ${(x.d12 * 100).toFixed(1)}%  ②↔③ 差异 ${(x.d23 * 100).toFixed(1)}%`);

