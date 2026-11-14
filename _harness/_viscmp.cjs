// 出图给主人判：一张「原图 | 三档浓淡之前 | 之后」的对照 PNG。
// 掩码那两栏按主题图标的看法画：底是白的，墨越浓越黑 ⇒ "本来该灰的地方变成整块黑"当场看得出来。
// 用法：node _harness/_viscmp.cjs <标题> <原图.png> <前.png> <后.png> <输出.png>
const fs = require('fs'), zlib = require('zlib');

function decode(buf) {
  let pos = 8, ihdr = null; const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString('latin1', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === 'IHDR') ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), depth: data[8], color: data[9], interlace: data[12] };
    else if (kind === 'IDAT') idat.push(data); else if (kind === 'PLTE') var plte = data; else if (kind === 'tRNS') var trns = data;
    else if (kind === 'IEND') break;
    pos += len + 12;
  }
  const { w, h, color, depth } = ihdr;
  if (depth !== 8 || ihdr.interlace) throw new Error(`这版只吃 8bit 非交错，遇到 depth=${depth} interlace=${ihdr.interlace}`);
  const chans = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[color];
  const raw = zlib.inflateSync(Buffer.concat(idat)), stride = w * chans, out = Buffer.alloc(stride * h);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const filt = raw[y * (stride + 1)], line = Buffer.from(raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride));
    for (let i = 0; i < stride; i++) {
      const a = i >= chans ? line[i - chans] : 0, b = prev[i], c = i >= chans ? prev[i - chans] : 0;
      let p = 0;
      if (filt === 1) p = a; else if (filt === 2) p = b; else if (filt === 3) p = (a + b) >> 1;
      else if (filt === 4) { const q = a + b - c, pa = Math.abs(q - a), pb = Math.abs(q - b), pc = Math.abs(q - c); p = pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
      line[i] = (line[i] + p) & 255;
    }
    line.copy(out, y * stride); prev = line;
  }
  // → RGBA
  const rgba = Buffer.alloc(w * h * 4);
  for (let i = 0, j = 0; i < w * h; i++, j += chans) {
    let r, g, b, al = 255;
    if (color === 3) { const idx = out[j]; r = plte[idx * 3]; g = plte[idx * 3 + 1]; b = plte[idx * 3 + 2]; if (trns && idx < trns.length) al = trns[idx]; }
    else if (color === 0) { r = g = b = out[j]; }
    else if (color === 4) { r = g = b = out[j]; al = out[j + 1]; }
    else if (color === 2) { r = out[j]; g = out[j + 1]; b = out[j + 2]; }
    else { r = out[j]; g = out[j + 1]; b = out[j + 2]; al = out[j + 3]; }
    rgba[i * 4] = r; rgba[i * 4 + 1] = g; rgba[i * 4 + 2] = b; rgba[i * 4 + 3] = al;
  }
  return { w, h, rgba };
}
function alphaOf(img) { const a = new Int32Array(img.w * img.h); for (let i = 0; i < a.length; i++) a[i] = img.rgba[i * 4 + 3]; return a; }
/** 与性质台 `tones()` 同一条：每种浓度至少占 0.5% 的格子才数。 */
function tones(a, n) { const h = new Int32Array(256); for (const v of a) h[v]++; return h.filter((c) => c * 200 >= n).length; }
function encode(w, h, rgba) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 4 + 1)] = 0; rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4); }
  const chunk = (kind, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(kind, 'latin1'), data]);
    const t = Buffer.alloc(4); t.writeUInt32BE(crc32(body));
    return Buffer.concat([len, body, t]);
  };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}
// PNG 要求的 CRC-32（zlib 的 crc32 在部分 Node 版本没有，自己列表最保险）
const CRC_T = (() => { const t = new Int32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c; } return t; })();
function crc32(buf) { let c = ~0; for (const b of buf) c = CRC_T[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ -1) >>> 0; }

const [title, srcP, beforeP, afterP, outP] = process.argv.slice(2);
const src = decode(fs.readFileSync(srcP));
const panels = [src];
for (const f of [beforeP, afterP]) { const m = decode(fs.readFileSync(f)); m.alpha = alphaOf(m); m.tones = tones(m.alpha, m.w * m.h); panels.push(m); }
const S = 256, GAP = 10, W = S * 3 + GAP * 4, H = S + GAP;
const cv = Buffer.alloc(W * H * 4);
for (let i = 0; i < W * H; i++) { cv[i * 4] = 18; cv[i * 4 + 1] = 18; cv[i * 4 + 2] = 22; cv[i * 4 + 3] = 255; }
const put = (x, y, r, g, b) => { if (x < 0 || y < 0 || x >= W || y >= H) return; const i = (y * W + x) * 4; cv[i] = r; cv[i + 1] = g; cv[i + 2] = b; cv[i + 3] = 255; };
panels.forEach((img, k) => {
  const ox = GAP + k * (S + GAP), oy = GAP;
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const sx = Math.min(img.w - 1, Math.floor(x * img.w / S)), sy = Math.min(img.h - 1, Math.floor(y * img.h / S));
    const i = (sy * img.w + sx);
    if (k === 0) { const j = i * 4, a = img.rgba[j + 3] / 255, base = 232; // 原图压在浅灰上（透明那部分就是那块盘）
      put(ox + x, oy + y, Math.round(img.rgba[j] * a + base * (1 - a)), Math.round(img.rgba[j + 1] * a + base * (1 - a)), Math.round(img.rgba[j + 2] * a + base * (1 - a)));
    } else { const a = img.alpha[i] / 255; put(ox + x, oy + y, Math.round(255 * (1 - a) + 25 * a), Math.round(255 * (1 - a) + 27 * a), Math.round(255 * (1 - a) + 30 * a)); }
  }
  for (let y = oy - 2; y < oy + S + 2; y++) { put(ox - 1, y, 70, 70, 80); put(ox + S, y, 70, 70, 80); }
});
fs.writeFileSync(outP, encode(W, H, cv));
console.log(`${title}  栏2 浓度种数=${panels[1].tones}  栏3 浓度种数=${panels[2].tones}  → ${outP}`);
console.log('（左=原图压在浅灰上，中=三档浓淡之前交出的掩码，右=之后；越黑的地方墨越浓）');
