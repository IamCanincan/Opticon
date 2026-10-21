// 「图案自己越过画布边」的图标，两边该长成哪一种 —— 出四格对照给主人看，不装机。
//   ① 原图（不裁）
//   ② 现状：整张 1:1 内切进圆（= 现在应用信息页那张的样子，天线/下缘会被圆切掉）
//   ③ (b) 缩到刚好装进圆：整张按 far→TIP_FLOOR·R 的比例缩，圆内补背景色
//   ④ (b)+我们那档额外缩量（再乘 SHRINK_EXTRA 的倒数，= 现在桌面那张的观感）
// 判据与线上同源：SAFE_ZONE/TIP_FLOOR/SHRINK_EXTRA 从 IconCropWindow 抄来，改了那边要改这里。
// 用法：node _bleedab.cjs <png...> [--panel=320] [--out=路径]
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

// 与 IconCropWindow 同源（改了那边要改这里）
const TIP_FLOOR = 0.93, SHRINK_EXTRA = 1.19;

/** 背景色 = 外圈 2 像素里出现最多的那个颜色（含"透明"这一档）。 */
function backgroundOf(src) {
  const { w, h, px } = src, tally = new Map();
  const add = (i) => {
    const a = px[i * 4 + 3];
    const key = a < 8 ? "T" : (px[i * 4] >> 3) + "," + (px[i * 4 + 1] >> 3) + "," + (px[i * 4 + 2] >> 3);
    const cur = tally.get(key) || { n: 0, r: px[i * 4], g: px[i * 4 + 1], b: px[i * 4 + 2], a };
    cur.n++;
    tally.set(key, cur);
  };
  for (let x = 0; x < w; x++) for (const y of [0, 1, h - 2, h - 1]) add(y * w + x);
  for (let y = 0; y < h; y++) for (const x of [0, 1, w - 2, w - 1]) add(y * w + x);
  return [...tally.values()].sort((a, b) => b.n - a.n)[0];
}

/** 图案（= 与背景色差得开的像素）的包围盒与"最远半径"。 */
function artOf(src, bg) {
  const { w, h, px } = src;
  let minx = w, maxx = -1, miny = h, maxy = -1, n = 0;
  const isArt = (i) => {
    const a = px[i * 4 + 3];
    if (bg.a < 8) return a >= 8;                                  // 背景透明：不透明就是图案
    if (a < 8) return false;
    return Math.abs(px[i * 4] - bg.r) + Math.abs(px[i * 4 + 1] - bg.g) + Math.abs(px[i * 4 + 2] - bg.b) > 60;
  };
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = y * w + x;
    if (!isArt(i)) continue;
    n++;
    if (x < minx) minx = x; if (x > maxx) maxx = x; if (y < miny) miny = y; if (y > maxy) maxy = y;
  }
  if (maxx < 0) return null;
  const cx = (minx + maxx) / 2, cy = (miny + maxy) / 2;
  let far = 0;
  for (let y = miny; y <= maxy; y++) for (let x = minx; x <= maxx; x++) {
    const i = y * w + x;
    if (!isArt(i)) continue;
    const d = Math.hypot(x - cx, y - cy);
    if (d > far) far = d;
  }
  return { minx, miny, maxx, maxy, cx, cy, far, n };
}

/** 画一格：把 src 按 scale 缩放后在 panel 里居中，圆外擦掉；透明处画棋盘格。 */
function panel(src, scale, S, plate) {
  const buf = Buffer.alloc(S * S * 4);
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const t = (y * S + x) * 4, dx = x - S / 2 + .5, dy = y - S / 2 + .5;
    const inCircle = dx * dx + dy * dy <= (S / 2) * (S / 2);
    const dark = ((x >> 3) + (y >> 3)) % 2 === 0;
    let r = dark ? 0x3a : 0x46, g = dark ? 0x32 : 0x3e, b = dark ? 0x32 : 0x3e;   // 棋盘格（看透明）
    if (inCircle && plate) { r = plate.r; g = plate.g; b = plate.b; }
    if (inCircle) {
      const sx = Math.round((x - S / 2 + .5) / scale + src.w / 2 - .5);
      const sy = Math.round((y - S / 2 + .5) / scale + src.h / 2 - .5);
      if (sx >= 0 && sy >= 0 && sx < src.w && sy < src.h) {
        const s = (sy * src.w + sx) * 4, a = src.px[s + 3] / 255;
        if (a > 0) { r = Math.round(src.px[s] * a + r * (1 - a)); g = Math.round(src.px[s + 1] * a + g * (1 - a)); b = Math.round(src.px[s + 2] * a + b * (1 - a)); }
      }
    }
    buf[t] = r; buf[t + 1] = g; buf[t + 2] = b; buf[t + 3] = 255;
  }
  return buf;
}

const args = process.argv.slice(2);
const S = +(args.find(a => a.startsWith("--panel="))?.slice(8) ?? 320);
const outArg = args.find(a => a.startsWith("--out="))?.slice(6);
const files = args.filter(a => !a.startsWith("--"));
const GAP = 10;

for (const f of files) {
  const src = decodePng(fs.readFileSync(f));
  const bg = backgroundOf(src);
  const art = artOf(src, bg);
  const R = S / 2;
  const sc = art ? Math.min(1, (TIP_FLOOR * R) / art.far) : 1;      // (b) 刚好装进
  const sc2 = Math.min(1, sc / SHRINK_EXTRA);                        // (b) + 那档额外缩量
  const panels = [
    { s: 1, plate: null },
    { s: 1, plate: bg, maskCircle: true },   // 现状：整张 1:1 之后被圆切
    { s: sc, plate: bg },
    { s: sc2, plate: bg },
  ].map(p => {
    if (p.maskCircle) {
      // 现状那一格：整张铺满圆、圆外擦掉（不做额外缩放）
      const out = Buffer.alloc(S * S * 4);
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const t = (y * S + x) * 4, dx = x - S / 2 + .5, dy = y - S / 2 + .5;
        const dark = ((x >> 3) + (y >> 3)) % 2 === 0;
        let r = dark ? 0x3a : 0x46, g = dark ? 0x32 : 0x3e, b = dark ? 0x32 : 0x3e;
        if (dx * dx + dy * dy <= R * R) {
          const sx = Math.round(x * src.w / S), sy = Math.round(y * src.h / S);
          if (sx < src.w && sy < src.h) {
            const s = (sy * src.w + sx) * 4, a = src.px[s + 3] / 255;
            r = Math.round(src.px[s] * a + r * (1 - a)); g = Math.round(src.px[s + 1] * a + g * (1 - a)); b = Math.round(src.px[s + 2] * a + b * (1 - a));
          }
        }
        out[t] = r; out[t + 1] = g; out[t + 2] = b; out[t + 3] = 255;
      }
      return out;
    }
    return panel(src, p.s, S, p.plate);
  });

  const W = S * panels.length + GAP * (panels.length + 1), H = S + GAP * 2;
  const canvas = Buffer.alloc(W * H * 4);
  for (let i = 0; i < W * H; i++) { canvas[i * 4] = 0x20; canvas[i * 4 + 1] = 0x1c; canvas[i * 4 + 2] = 0x1c; canvas[i * 4 + 3] = 255; }
  panels.forEach((p, k) => {
    const ox = GAP + k * (S + GAP);
    for (let y = 0; y < S; y++) p.copy(canvas, ((y + GAP) * W + ox) * 4, y * S * 4, (y + 1) * S * 4);
  });
  const out = outArg ?? f.replace(/\.png$/i, "") + "_ab.png";
  fs.writeFileSync(out, encodePng(W, H, canvas));
  console.log(`${f}: 背景 ${bg.a < 8 ? "透明" : `rgb(${bg.r},${bg.g},${bg.b})`}，图案 ${art ? `${art.maxx - art.minx + 1}x${art.maxy - art.miny + 1} far=${art.far.toFixed(1)}` : "无"} → 缩放 ③${sc.toFixed(3)} ④${sc2.toFixed(3)}`);
  console.log(`  四格：①原图 ②现状1:1+圆 ③刚好装进 ④再乘1/${SHRINK_EXTRA} | 写出 ${out}`);
}
