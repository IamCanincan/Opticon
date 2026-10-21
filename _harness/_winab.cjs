// 「外框接近满幅、但内切圆盘很小」的图标（Kodi 这类）过一遍三种处置，出三格对照给主人看，不装机。
//   ① 原图（不裁、不套圆）
//   ② 现状：内切填满 —— 按不透明圆盘放大 ZOOM 倍，再切圆
//   ③ 只套圆 —— 1:1 切圆（= 现在"满幅"那一档的处置）
// ZOOM 由真判据量出来（`sh run.sh harness.WinKtKt <png>` 的 zoom 列），这里只是照着画。
// 用法：node _winab.cjs <png> [--zoom=1.495] [--panel=320] [--out=路径]
const fs = require("fs"), zlib = require("zlib");

function decodePng(buf) {
  let pos = 8, idat = [], ihdr = null;
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString("latin1", pos + 4, pos + 8);
    const d = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === "IHDR") ihdr = { w: d.readUInt32BE(0), h: d.readUInt32BE(4), depth: d[8], color: d[9], interlace: d[12] };
    else if (kind === "IDAT") idat.push(d);
    else if (kind === "IEND") break;
    pos += len + 12;
  }
  const { w, h, color } = ihdr, chans = { 0: 1, 2: 3, 4: 2, 6: 4 }[color];
  const raw = zlib.inflateSync(Buffer.concat(idat)), stride = w * chans, out = Buffer.alloc(stride * h);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)];
    const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride));
    for (let i = 0; i < stride; i++) {
      const a = i >= chans ? line[i - chans] : 0, b = prev[i], c = i >= chans ? prev[i - chans] : 0;
      let p = 0;
      if (f === 1) p = a; else if (f === 2) p = b; else if (f === 3) p = (a + b) >> 1;
      else if (f === 4) { const q = a + b - c, pa = Math.abs(q - a), pb = Math.abs(q - b), pc = Math.abs(q - c); p = pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
      line[i] = (line[i] + p) & 255;
    }
    line.copy(out, y * stride); prev = line;
  }
  const px = new Uint8Array(w * h * 4);
  for (let i = 0, n = w * h; i < n; i++) {
    const s = i * chans, t = i * 4;
    if (color === 6) { px[t] = out[s]; px[t + 1] = out[s + 1]; px[t + 2] = out[s + 2]; px[t + 3] = out[s + 3]; }
    else if (color === 4) { px[t] = px[t + 1] = px[t + 2] = out[s]; px[t + 3] = out[s + 1]; }
    else if (color === 0) { px[t] = px[t + 1] = px[t + 2] = out[s]; px[t + 3] = 255; }
    else if (color === 2) { px[t] = out[s]; px[t + 1] = out[s + 1]; px[t + 2] = out[s + 2]; px[t + 3] = 255; }
  }
  return { w, h, px };
}

const CRC = (() => { const t = new Int32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c; } return b => { let c = -1; for (const x of b) c = t[(c ^ x) & 255] ^ (c >>> 8); return (c ^ -1) >>> 0; }; })();
function encodePng(w, h, px) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 4 + 1)] = 0; px.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4); }
  const chunk = (kind, payload) => { const b = Buffer.concat([Buffer.from(kind, "latin1"), payload]); const o = Buffer.alloc(8 + payload.length + 4); o.writeUInt32BE(payload.length, 0); b.copy(o, 4); o.writeUInt32BE(CRC(b), 8 + payload.length); return o; };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0))]);
}

/** 画一格：src 按 scale 缩放后居中，圆外擦掉（circle=false 就不擦）；透明处画棋盘格。 */
function panel(src, scale, S, { circle = true, fill = null } = {}) {
  const out = Buffer.alloc(S * S * 4), R = S / 2;
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const t = (y * S + x) * 4, dx = x - S / 2 + .5, dy = y - S / 2 + .5;
    const dark = ((x >> 3) + (y >> 3)) % 2 === 0;
    let r = dark ? 0x3a : 0x46, g = dark ? 0x32 : 0x3e, b = dark ? 0x32 : 0x3e;
    if (!circle || dx * dx + dy * dy <= R * R) {
      if (fill) { r = fill.r; g = fill.g; b = fill.b; }
      const sx = Math.round((x - S / 2 + .5) / scale + src.w / 2 - .5);
      const sy = Math.round((y - S / 2 + .5) / scale + src.h / 2 - .5);
      if (sx >= 0 && sy >= 0 && sx < src.w && sy < src.h) {
        const s = (sy * src.w + sx) * 4, a = src.px[s + 3] / 255;
        if (a > 0) { r = Math.round(src.px[s] * a + r * (1 - a)); g = Math.round(src.px[s + 1] * a + g * (1 - a)); b = Math.round(src.px[s + 2] * a + b * (1 - a)); }
      }
    }
    out[t] = r; out[t + 1] = g; out[t + 2] = b; out[t + 3] = 255;
  }
  return out;
}

const args = process.argv.slice(2);
const f = args.find(a => !a.startsWith("--"));
const argOf = (k, d) => { const a = args.find(x => x.startsWith("--" + k + "=")); return a ? Number(a.split("=")[1]) : d; };
const S = argOf("panel", 320), GAP = 14, ZOOM = argOf("zoom", 1.495);
const src = decodePng(fs.readFileSync(f));
const one = S / src.w;                       // 源 → 面板：图标 1:1 铺满画布那一步

// 图案的墨迹统计（外框 + 最远半径），口径与判据一致：alpha ≥ 8 算有像素
function inkStats(s) {
  let minx = s.w, maxx = -1, miny = s.h, maxy = -1, n = 0;
  for (let y = 0; y < s.h; y++) for (let x = 0; x < s.w; x++) {
    if (s.px[(y * s.w + x) * 4 + 3] < 8) continue;
    n++; if (x < minx) minx = x; if (x > maxx) maxx = x; if (y < miny) miny = y; if (y > maxy) maxy = y;
  }
  const cx = (minx + maxx) / 2, cy = (miny + maxy) / 2; let far = 0;
  for (let y = miny; y <= maxy; y++) for (let x = minx; x <= maxx; x++) {
    if (s.px[(y * s.w + x) * 4 + 3] < 8) continue;
    const d = Math.hypot(x - cx, y - cy); if (d > far) far = d;
  }
  return { n, bw: maxx - minx + 1, bh: maxy - miny + 1, far };
}
const ink = inkStats(src);
// 第四格（"缩进 + 补白"那一档）的缩量：与 _bleedab.cjs 同源（TIP_FLOOR / SHRINK_EXTRA 从 IconCropWindow 抄）
const TIP_FLOOR = 0.93, SHRINK_EXTRA = 1.19;
const sc4 = Math.min(1, (TIP_FLOOR * (S / 2)) / (ink.far * one)) / SHRINK_EXTRA;
const hex = (h) => ({ r: parseInt(h.slice(1, 3), 16), g: parseInt(h.slice(3, 5), 16), b: parseInt(h.slice(5, 7), 16) });
// --fills=#RRGGBB,#RRGGBB：只出"缩进 + 补底"这一档，每格换一种底色（比"补什么颜色"用）
const fillsArg = (args.find(a => a.startsWith("--fills=")) || "").split("=").slice(1).join("=");
const panels = fillsArg
  ? fillsArg.split(",").map((h) => panel(src, sc4, S, { circle: true, fill: hex(h.trim()) }))
  : [
      panel(src, one, S, { circle: false }),         // ① 原图
      panel(src, one * ZOOM, S, { circle: true }),   // ② 现状：放大 ZOOM 倍再切圆
      panel(src, one, S, { circle: true }),          // ③ 只套圆（1:1）
      panel(src, sc4, S, { circle: true, fill: { r: 255, g: 255, b: 255 } }),  // ④ 缩进 + 圆内补白
    ];
const W = S * panels.length + GAP * (panels.length + 1), H = S + GAP * 2;
const canvas = Buffer.alloc(W * H * 4);
for (let i = 0; i < W * H; i++) { canvas[i * 4] = 0x20; canvas[i * 4 + 1] = 0x1c; canvas[i * 4 + 2] = 0x1c; canvas[i * 4 + 3] = 255; }
panels.forEach((p, k) => {
  const ox = GAP + k * (S + GAP);
  for (let y = 0; y < S; y++) p.copy(canvas, ((y + GAP) * W + ox) * 4, y * S * 4, (y + 1) * S * 4);
});
const out = (args.find(a => a.startsWith("--out=")) || "").split("=").slice(1).join("=")
  || f.replace(/\.png$/i, "") + "_winab.png";
fs.writeFileSync(out, encodePng(W, H, canvas));
console.log(`${f}: 源 ${src.w}x${src.h}，面板 ${S}px，②按 zoom ${ZOOM} 画`);
console.log(`  四格：①原图 ②现状（放大 ${ZOOM}×） ③只套圆 ④缩进补白（${sc4.toFixed(2)}×） | 写出 ${out}`);
