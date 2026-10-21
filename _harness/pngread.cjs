// 最小 PNG 读码（8bit、非隔行、RGB/RGBA/灰度）+ 从判据台 shot 三联图里切出中间那张掩码。
// 用法：
//   node _harness/pngread.cjs <png> [x y w h]          整张 dump 成 PPM/PGM 或按区域
//   node _harness/pngread.cjs --mid=384 <shot.png>     切三联图中间那张（判据台 --shot 产物）
const fs = require('fs');
const zlib = require('zlib');

function png(p) {
  const b = fs.readFileSync(p);
  let o = 8, w = 0, h = 0, bd = 0, ct = 0;
  const idat = [];
  while (o < b.length) {
    const len = b.readUInt32BE(o), t = b.toString('ascii', o + 4, o + 8), d = b.subarray(o + 8, o + 8 + len);
    if (t === 'IHDR') { w = d.readUInt32BE(0); h = d.readUInt32BE(4); bd = d[8]; ct = d[9]; }
    else if (t === 'IDAT') idat.push(d);
    else if (t === 'IEND') break;
    o += 12 + len;
  }
  const data = zlib.inflateSync(Buffer.concat(idat));
  const ch = ct === 6 ? 4 : ct === 2 ? 3 : ct === 0 ? 1 : ct === 4 ? 2 : 0;
  if (!ch || bd !== 8) throw new Error('不支持的格式 ct=' + ct + ' bd=' + bd);
  const stride = w * ch, out = Buffer.alloc(stride * h);
  let prev = Buffer.alloc(stride), q = 0;
  for (let y = 0; y < h; y++) {
    const f = data[q++];
    const row = Buffer.from(data.subarray(q, q + stride)); q += stride;
    for (let i = 0; i < stride; i++) {
      const a = i >= ch ? row[i - ch] : 0, bb = prev[i], c = i >= ch ? prev[i - ch] : 0;
      let v = row[i];
      if (f === 1) v += a; else if (f === 2) v += bb; else if (f === 3) v += (a + bb) >> 1;
      else if (f === 4) { const pp = a + bb - c, pa = Math.abs(pp - a), pb = Math.abs(pp - bb), pc = Math.abs(pp - c); v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? bb : c); }
      row[i] = v & 255;
    }
    row.copy(out, y * stride); prev = row;
  }
  return { w, h, ch, data: out };
}
module.exports = { png };

if (require.main === module) {
  const args = process.argv.slice(2);
  const mid = args.find(a => a.startsWith('--mid='));
  const file = args.find(a => !a.startsWith('--')) || null;
  if (!file) { console.log('缺文件'); process.exit(1); }
  const p = png(file);
  if (mid) {
    const edge = parseInt(mid.split('=')[1], 10);
    const x0 = edge + 8;
    console.log(`三联图 ${p.w}x${p.h}，切中间掩码 x=${x0}..x${x0 + edge - 1}`);
    let line = [];
    const cols = 64, S = Math.floor(edge / cols);
    const ch = (v) => v === 0 ? ' ' : v < 64 ? '.' : v < 160 ? '+' : v < 224 ? '#' : '@';
    for (let gy = 0; gy < cols; gy++) {
      let s = '';
      for (let gx = 0; gx < cols; gx++) {
        let sum = 0, n = 0;
        for (let y = gy * S; y < (gy + 1) * S; y++) for (let x = gx * S; x < (gx + 1) * S; x++) {
          const i = (y * p.w + x0 + x) * p.ch, l = (p.data[i] * 299 + p.data[i + 1] * 587 + p.data[i + 2] * 114) / 1000;
          sum += l; n++;
        }
        // 浅盘深墨：越黑 = 越实的墨
        s += ch(255 - Math.round(sum / n));
      }
      console.log('|' + s + '|');
    }
  } else {
    console.log(`size ${p.w}x${p.h} ch=${p.ch}`);
  }
}
