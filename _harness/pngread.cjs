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
  let plte = null, trns = null;
  while (o < b.length) {
    const len = b.readUInt32BE(o), t = b.toString('ascii', o + 4, o + 8), d = b.subarray(o + 8, o + 8 + len);
    if (t === 'IHDR') { w = d.readUInt32BE(0); h = d.readUInt32BE(4); bd = d[8]; ct = d[9]; }
    else if (t === 'IDAT') idat.push(d);
    else if (t === 'PLTE') plte = d;
    else if (t === 'tRNS') trns = d;
    else if (t === 'IEND') break;
    o += 12 + len;
  }
  const data = zlib.inflateSync(Buffer.concat(idat));
  // ct=3 = 索引色（一堆通知/状态栏小图标就是这张，钉钉那张 601 字节的 PNG 就是）：每像素一个调色板下标。
  //   ⚠ 下标可以是 1/2/4/8 bit —— framework 那 14 张 `stat_sys_*_anim`、`stat_sys_warning` 就是 **bd=4**，
  //     先前只收 bd=8 ⇒ 一台分布里**静默丢了 14/77 = 18% 的样本**（恒等值之外另一种假数：样本自己缺角）。
  const idxBits = ct === 3 ? bd : 0;
  const ch = ct === 6 ? 4 : ct === 2 ? 3 : ct === 0 ? 1 : ct === 4 ? 2 : ct === 3 ? 1 : 0;
  if (!ch) throw new Error('不支持的格式 ct=' + ct + ' bd=' + bd);
  if (bd !== 8 && !idxBits) throw new Error('非索引色只支持 bd=8（这张 bd=' + bd + '）');
  // ⚠ 行距：bd<8 的索引色是**一字节塞好几个下标**，`w*ch` 会把每行读宽一倍（整张解成乱码还不报错）。
  const stride = idxBits ? Math.ceil(w * idxBits / 8) : w * ch;
  const out = Buffer.alloc(stride * h);
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
  if (ct === 3) {
    // 索引色 → RGBA（调用方拿到的契约与非索引色的 RGBA 图完全一样：ch=4、data 是 RGBA）
    if (!plte) throw new Error('索引色 PNG 没有 PLTE');
    const rgba = Buffer.alloc(w * h * 4);
    const perByte = 8 / bd;                                  // 每字节几个下标（bd=8→1、4→2、2→4、1→8）
    for (let y = 0; y < h; y++) {
      const rowStart = y * stride;
      for (let x = 0; x < w; x++) {
        const byte = out[rowStart + Math.floor(x / perByte)];
        // bd<8 时高位在左（PNG 规范：一个字节里先出现的是左边那个像素）
        const idx = bd === 8 ? byte : (byte >> (8 - bd * (1 + (x % perByte)))) & (perByte - 1);
        const i = (y * w + x) * 4;
        rgba[i] = plte[idx * 3] || 0;
        rgba[i + 1] = plte[idx * 3 + 1] || 0;
        rgba[i + 2] = plte[idx * 3 + 2] || 0;
        rgba[i + 3] = trns && idx < trns.length ? trns[idx] : 255;
      }
    }
    return { w, h, ch: 4, data: rgba, palette: true, bitDepth: bd };
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
