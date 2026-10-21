// 交付图上那圈"淡羽片"到底在不在源里 —— 按半径分带，比**源亮度**与**交付 α**。
// 用法：node _harness/_ringcheck.cjs <mono目录> <组件文件名前缀> [...]
//   需要 dumpmono.cjs 同目录下的 .gray（交付）与 .src.png（彩色源）
const fs = require('fs'), path = require('path');
const zlib = require('zlib');
const dir = process.argv[2];

/** 解一张 PNG → {w,h,rgba}。只处理 dumpmono 导出的那批（RGB/RGBA、8bit、无交织）。 */
function decode(file) {
  const buf = fs.readFileSync(file);
  let pos = 8, w = 0, h = 0, bitDepth = 0, colorType = 0, idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.slice(pos + 8, pos + 8 + len);
    if (type === 'IHDR') { w = data.readUInt32BE(0); h = data.readUInt32BE(4); bitDepth = data[8]; colorType = data[9]; }
    else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    pos += 12 + len;
  }
  if (bitDepth !== 8) throw new Error(`${file}: 位深 ${bitDepth} 不支持`);
  const ch = colorType === 6 ? 4 : colorType === 2 ? 3 : colorType === 0 ? 1 : (() => { throw new Error(`colorType ${colorType}`); })();
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = w * ch;
  const out = Buffer.alloc(h * stride);
  let rp = 0;
  for (let y = 0; y < h; y++) {
    const filt = raw[rp++];
    const line = raw.slice(rp, rp + stride); rp += stride;
    const prev = y ? out.slice((y - 1) * stride, y * stride) : Buffer.alloc(stride);
    for (let x = 0; x < stride; x++) {
      const a = x >= ch ? out[y * stride + x - ch] : 0, b = prev[x], c = x >= ch ? prev[x - ch] : 0;
      let v;
      if (filt === 0) v = line[x];
      else if (filt === 1) v = line[x] + a;
      else if (filt === 2) v = line[x] + b;
      else if (filt === 3) v = line[x] + ((a + b) >> 1);
      else { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        v = line[x] + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c); }
      out[y * stride + x] = v & 0xff;
    }
  }
  const rgba = Buffer.alloc(w * h * 4);
  for (let i = 0; i < w * h; i++) {
    for (let k = 0; k < ch; k++) rgba[i * 4 + k] = out[i * ch + k];
    rgba[i * 4 + 3] = ch === 4 ? out[i * 4 + 3] : 255;
  }
  return { w, h, rgba };
}

const BANDS = [[0.00, 0.20], [0.20, 0.32], [0.32, 0.42], [0.42, 0.50], [0.50, 0.62]];
for (const pre of process.argv.slice(3)) {
  const grayFile = path.join(dir, pre + '.gray'), srcFile = path.join(dir, pre + '.src.png');
  if (!fs.existsSync(grayFile) || !fs.existsSync(srcFile)) { console.log(`!! 缺文件：${pre}`); continue; }
  const g = fs.readFileSync(grayFile);
  const { w, h, rgba } = decode(srcFile);
  const lumAt = (x, y) => {                       // 源亮度，透明按白底合成（桌面就是这么画的）
    const i = (y * w + x) * 4, a = rgba[i + 3] / 255;
    const mix = (c) => Math.round(c * a + 255 * (1 - a));
    return (mix(rgba[i]) * 299 + mix(rgba[i + 1]) * 587 + mix(rgba[i + 2]) * 114) / 1000;
  };
  console.log(`\n${pre}   源 ${w}x${h}  交付 256²`);
  console.log('  半径带      源亮度 min/中位/max      交付α min/中位/max   淡件(α1..63): 占带内% / 那些格的源亮度中位');
  for (const [r0, r1] of BANDS) {
    const sl = [], dl = [], faintSrc = [];
    for (let y = 0; y < 256; y++) for (let x = 0; x < 256; x++) {
      const dx = (x - 127.5) / 256, dy = (y - 127.5) / 256, r = Math.hypot(dx, dy);
      if (r < r0 || r >= r1) continue;
      const L = lumAt(Math.min(w - 1, x * w / 256 | 0), Math.min(h - 1, y * h / 256 | 0));
      const D = g[y * 256 + x];
      sl.push(L); dl.push(D);
      if (D >= 1 && D <= 63) faintSrc.push(L);
    }
    const st = (arr) => { const s = arr.slice().sort((a, b) => a - b);
      return `${s[0] | 0}`.padStart(4) + '/' + `${s[s.length >> 1] | 0}`.padStart(4) + '/' + `${s[s.length - 1] | 0}`.padStart(4); };
    const fs2 = faintSrc.slice().sort((a, b) => a - b);
    const faint = `${(faintSrc.length * 100 / dl.length).toFixed(1)}% / 源${fs2.length ? (fs2[fs2.length >> 1] | 0) : '-'}`;
    console.log(`  ${r0.toFixed(2)}–${r1.toFixed(2)}   ${st(sl)}            ${st(dl)}        ${faint.padStart(12)}`);
  }
}
