// 出屏上对比图：把 `app_icons.db` 里那一对（彩色 PNG + 单色 ALPHA_8）画成看得见的图。
//   三联 = [彩色] [单色·旧库里那张] [单色·新库里那张]（只给一份库时退成两联）
// 用法：node _harness/_shotcmp.cjs <标题> <旧.db|-> <新.db> <组件子串> [<组件子串>…]
// ⚠ 库里的彩色 blob 是 colorType 2（alpha 在存盘时丢了），所以它看到的是"桌面上那张成品"，
//   不是我们交付的图层 —— 这正是要比的东西（屏上现在长什么样），不是缺陷。
const fs = require('fs'), zlib = require('zlib');
const { DatabaseSync } = require('node:sqlite');

function decode(buf) {
  let pos = 8, ihdr = null; const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString('latin1', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === 'IHDR') ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), color: data[9] };
    else if (kind === 'IDAT') idat.push(data); else if (kind === 'IEND') break;
    pos += len + 12;
  }
  const { w, h, color } = ihdr, chans = { 0: 1, 2: 3, 4: 2, 6: 4 }[color];
  if (!chans) return null;
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
  return { w, h, chans, out, mono: color === 0 || color === 4 };
}
/** 单色那栏在库里是**裸 ALPHA_8**（不是 PNG）：`_pairshot.cjs` 一开始就按 `sqrt(长度)` 处理。
 *  带 PNG 签名的那份（重抓之后可能有）也认，别把两种格式当成一种。 */
function decodeMono(buf) {
  if (buf.length > 8 && buf[0] === 137 && buf[1] === 80) return decode(buf);
  const side = Math.round(Math.sqrt(buf.length));
  if (side * side !== buf.length) return null;
  const out = Buffer.alloc(side * side * 2);          // 伪装成 colorType 4（灰+alpha），alpha 在 +1
  for (let i = 0; i < side * side; i++) { out[i * 2] = buf[i]; out[i * 2 + 1] = buf[i]; }
  return { w: side, h: side, chans: 2, out, mono: true };
}
// 取一格的灰度：彩色那栏直接看亮度（alpha 已丢），单色那栏看 alpha 当墨浓
function lum(img, x, y) {
  const i = (y * img.w + x) * img.chans;
  if (img.mono) return img.chans === 4 ? img.out[i + 1] : img.out[i];
  return (img.out[i] + img.out[i + 1] + img.out[i + 2]) / 3 | 0;
}
function ink(img, x, y) {
  if (!img.mono) return 255;
  const i = (y * img.w + x) * img.chans;
  return img.chans === 4 ? img.out[i + 1] : img.out[i];
}
const CRC_T = (() => { const t = new Int32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c; } return t; })();
function crc32(b) { let c = ~0; for (const x of b) c = CRC_T[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ -1) >>> 0; }
function encode(w, h, rgba) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (w * 4 + 1)] = 0; rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4); }
  const chunk = (k, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const body = Buffer.concat([Buffer.from(k, 'latin1'), d]); const t = Buffer.alloc(4); t.writeUInt32BE(crc32(body)); return Buffer.concat([l, body, t]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}
function box(img, thr) {
  let l = img.w, t = img.h, r = -1, b = -1;
  for (let y = 0; y < img.h; y++) for (let x = 0; x < img.w; x++) { if (!thr(img, x, y)) continue; if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y; }
  return r < 0 ? 0 : (r - l + 1) / img.w;
}

const args = process.argv.slice(2);
const title = args.shift();
const oldDb = args.shift(), newDb = args.shift();
const needles = args;
const grab = (p) => {
  if (!p || p === '-') return new Map();
  const d = new DatabaseSync(p, { readOnly: true });
  const m = new Map();
  for (const r of d.prepare('SELECT componentName c, icon i, mono_icon m FROM icons').all()) {
    if (!r.i || !r.m) continue;
    if (!m.has(r.c)) m.set(r.c, { c: Buffer.from(r.i), m: Buffer.from(r.m) });   // 同组件多行取第一行
  }
  return m;
};
const A = grab(oldDb), B = grab(newDb);
const three = A.size > 0;                      // 没有旧库就出两联（彩色 | 单色），不拿同一张凑数
const S = 236, GAP = 8;
const picks = [];
const seenPkg = new Set();
for (const [c, v] of B) if (needles.some((n) => c.includes(n))) {
  // 同一个包在库里有一堆行（启动图标 + 各种快捷方式/组件），只画第一次见的那一行，
  // 否则四格里全是同一个图标，"还剩几张没修"当场就被重复项冒充掉。
  const pkg = c.split('/')[0];
  if (seenPkg.has(pkg)) continue;
  seenPkg.add(pkg); picks.push([c, v]);
}
if (!picks.length) { console.log('!! 库里没配上这些子串：' + needles.join(' ')); process.exit(1); }
const NROWS = Math.min(picks.length, 6);
const W = (S + GAP) * (three ? 3 : 2) + GAP, H = (S + GAP) * NROWS + GAP;
const cv = Buffer.alloc(W * H * 4);
for (let i = 0; i < W * H; i++) { cv[i * 4] = 20; cv[i * 4 + 1] = 20; cv[i * 4 + 2] = 24; cv[i * 4 + 3] = 255; }
const put = (x, y, r, g, b) => { if (x < 0 || y < 0 || x >= W || y >= H) return; const i = (y * W + x) * 4; cv[i] = r; cv[i + 1] = g; cv[i + 2] = b; cv[i + 3] = 255; };
const rows = [];
picks.slice(0, 6).forEach(([c, v], k) => {
  const col = decode(Buffer.from(v.c)), mon = decodeMono(Buffer.from(v.m));
  const prev = A.get(c);
  const oldMon = prev ? decodeMono(Buffer.from(prev.m)) : null;
  const panels = three
    ? [['彩色（屏上那张）', col, 'color'], ['单色·修前', oldMon, 'ink'], ['单色·修后', mon, 'ink']]
    : [['彩色（屏上那张）', col, 'color'], ['单色（现在）', mon, 'ink']];
  const cw = box(col, (im, x, y) => Math.min(im.out[(y * im.w + x) * im.chans], im.out[(y * im.w + x) * im.chans + 1], im.out[(y * im.w + x) * im.chans + 2]) < 235);
  panels.forEach((p, i) => {
    const ox = GAP + i * (S + GAP), oy = GAP + k * (S + GAP), img = p[1];
    if (!img) return;
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const sx = Math.min(img.w - 1, Math.floor(x * img.w / S)), sy = Math.min(img.h - 1, Math.floor(y * img.h / S));
      if (p[2] === 'color') { const j = (sy * img.w + sx) * img.chans; put(ox + x, oy + y, img.out[j], img.out[j + 1], img.out[j + 2]); }
      else { const a = ink(img, sx, sy) / 255; put(ox + x, oy + y, Math.round(250 * (1 - a) + 25 * a), Math.round(250 * (1 - a) + 27 * a), Math.round(250 * (1 - a) + 30 * a)); }
    }
    for (let y = oy - 2; y < oy + S + 2; y++) { put(ox - 1, y, 90, 90, 100); put(ox + S, y, 90, 90, 100); }
  });
  const mw = box(mon, (im, x, y) => ink(im, x, y) > 8);
  const ow = oldMon ? box(oldMon, (im, x, y) => ink(im, x, y) > 8) : NaN;
  rows.push(`${c.slice(0, 46).padEnd(47)} 彩色幅 ${cw.toFixed(3)}  单色幅 ` +
    (isNaN(ow) ? '' : `${ow.toFixed(3)} → `) + `${mw.toFixed(3)}   幅面比 ` +
    (isNaN(ow) ? '' : `${(ow / cw).toFixed(3)} → `) + `${(mw / cw).toFixed(3)}`);
});
const out = `D:/tmp/shotcmp_${title.replace(/[^\w]+/g, '_')}.png`;
fs.writeFileSync(out, encode(W, H, cv));
console.log(`${title}：${picks.length} 张候选，画了 ${rows.length} 行 → ${out}`);
for (const r of rows) console.log('  ' + r);
