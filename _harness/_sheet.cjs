// 把**交付出去的掩码**拼成联表，按真实观感（浅底盘 + 深墨）渲染，用眼睛过一遍整屏。
//
// 为什么要它：判据行只有聚合数（`底占` `字均` `退族`…），它们能报警，但看不出"这 200 张里
// 有没有一张形状是坏的"。2026-11-10 找 `com.algermusic.app` 时就是靠联表才确认"全机没有第二例"；
// 那一轮的脚本写在 %TEMP%，下一个人（或下一轮的我）还得重写一遍 —— 收进仓库。
//
// 跑法：
//   node _harness/_sheet.cjs <掩码目录> <事件日志> <输出前缀> [--cols=12] [--cell=160]
//
//   掩码目录 = 设备上 `cacheDir/opticon-mono/<身份>/`（`adb pull` 下来那份）
//   事件日志 = `opticon-events.log`（**只用来把键对回包名**；对不上的显示 `?`）
//   输出     = `<前缀>_1.png` … 每页 12×10 格，另有同名的 `.txt` 图例（`序号:标签`）
//
// 口径：
//   · `.a8` = 9 字节头（rowBytes / edge / FORMAT）+ rowBytes×edge 的 alpha；
//     ⚠ `_a8cmp.cjs` 还按 8 字节头读，会抛 —— 别拿它当参考实现。
//   · 每格按盒式平均缩到 `--cell`，再按 alpha 在**主题底盘色**与**主题前景色**之间插值
//     （本机浅色是 `200,230,240` / `16,58,70`）。
//   · 键对包名靠日志第 8 列 `键=`（⚠ 不是每行都有，见 `_events.cjs` 头部）。
//
// 退码：
//   0 = 出了联表
//   2 = 一张掩码都没解码出来（目录错了/格错）——**别把 0 张读成"都没问题"**
const fs = require('fs');
const zlib = require('zlib');

const args = process.argv.slice(2);
const opt = (name, dflt) => {
  const a = args.find((x) => x.startsWith('--' + name + '='));
  return a ? a.slice(name.length + 3) : dflt;
};
const plain = args.filter((x) => !x.startsWith('--'));
const [dir, logPath, outPrefix] = plain;
if (!dir || !logPath || !outPrefix) {
  console.log('用法：node _sheet.cjs <掩码目录> <事件日志> <输出前缀> [--cols=12] [--cell=160]');
  process.exit(2);
}
const COLS = parseInt(opt('cols', '12'), 10);
const CELL = parseInt(opt('cell', '160'), 10);
const GAP = 6;
const BG = [200, 230, 240];   // 主题底盘（本机浅色）
const INK = [16, 58, 70];     // 主题前景
const PER_PAGE = COLS * 10;

// ---- 键 → 包名（日志第 8 列的 `键=`）----
const JIAN = '\u952e=';
const labelByKey = new Map();
for (const l of fs.readFileSync(logPath, 'utf8').split(/\r?\n/)) {
  if (!l) continue;
  const f = l.split('\t');
  const kf = f.find((x) => x.startsWith(JIAN));
  if (kf) labelByKey.set(kf.slice(JIAN.length).trim(), f[2]);
}

function decode(p) {
  const b = fs.readFileSync(p);
  if (b.length < 9) throw new Error('太短：' + p);
  const rb = b.readUInt32BE(0), e = b.readUInt32BE(4), fmt = b[8];
  if (b.length !== 9 + rb * e) throw new Error(`长度对不上头部（${b.length} vs 9+${rb}×${e}）：${p}`);
  if (fmt !== 5) throw new Error(`FORMAT=${fmt} 不是 5（旧口径的掩码？）：${p}`);
  const px = new Uint8Array(e * e);
  for (let y = 0; y < e; y++) for (let x = 0; x < e; x++) px[y * e + x] = b[9 + y * rb + x];
  return { e, px };
}

function writePng(img, W, H, out) {
  let T = null;
  const crc32 = (buf) => {
    if (!T) { T = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; T[n] = c >>> 0; } }
    let c = 0xffffffff;
    for (const b of buf) c = T[(c ^ b) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  };
  const chunk = (t, d) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(d.length);
    const td = Buffer.concat([Buffer.from(t, 'ascii'), d]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, crc]);
  };
  const raw = Buffer.alloc((W * 3 + 1) * H);
  for (let y = 0; y < H; y++) { raw[y * (W * 3 + 1)] = 0; img.copy(raw, y * (W * 3 + 1) + 1, y * W * 3, (y + 1) * W * 3); }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(H, 4); ihdr[8] = 8; ihdr[9] = 2;
  fs.writeFileSync(out, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]));
}

const files = fs.readdirSync(dir).filter((f) => f.endsWith('.a8')).sort();
const items = files.map((f) => {
  const key = f.split('_')[0];
  return { key, path: dir + '/' + f, label: labelByKey.get(key) || '?' };
});
if (items.length === 0) { console.log('!! 目录里没有 .a8：' + dir); process.exit(2); }

// ---- 先出数：墨量分布 + 自检（全零/近空/满幅），再出图 ----
const inkOf = (px) => { let ink = 0; for (const a of px) if (a >= 128) ink++; return ink / px.length * 100; };
const inks = [];
for (const it of items) { const { px } = decode(it.path); it.ink = inkOf(px); inks.push(it.ink); }
const sorted = [...inks].sort((a, b) => a - b);
const med = sorted[sorted.length >> 1];
const zero = items.filter((i) => i.ink === 0);
const nearEmpty = items.filter((i) => i.ink > 0 && i.ink < 1);
const solid = items.filter((i) => i.ink > 60);
console.log(`掩码 ${items.length} 张；墨(α≥128) 中位 ${med.toFixed(1)}%  最小 ${sorted[0].toFixed(1)}%  最大 ${sorted[sorted.length - 1].toFixed(1)}%`);
console.log(`  全零 ${zero.length} / 近空(<1%) ${nearEmpty.length} / 满幅(>60%) ${solid.length}`);
for (const i of zero) console.log(`  !! 全零：${i.key}  ${i.label}`);
for (const i of solid) console.log(`  !! 满幅：${i.ink.toFixed(1)}%  ${i.key}  ${i.label}`);

// ---- 联表 ----
const pages = Math.ceil(items.length / PER_PAGE);
for (let p = 0; p < pages; p++) {
  const chunk = items.slice(p * PER_PAGE, (p + 1) * PER_PAGE);
  const rows = Math.ceil(chunk.length / COLS);
  const W = COLS * (CELL + GAP) + GAP, H = rows * (CELL + GAP) + GAP;
  const img = Buffer.alloc(W * H * 3, 245);
  chunk.forEach((it, i) => {
    const { e, px } = decode(it.path);
    const cx = GAP + (i % COLS) * (CELL + GAP), cy = GAP + Math.floor(i / COLS) * (CELL + GAP);
    const step = e / CELL;
    for (let y = 0; y < CELL; y++) for (let x = 0; x < CELL; x++) {
      let sum = 0, n = 0;
      const y1 = Math.min(e, Math.ceil((y + 1) * step)), x1 = Math.min(e, Math.ceil((x + 1) * step));
      for (let sy = Math.floor(y * step); sy < y1; sy++)
        for (let sx = Math.floor(x * step); sx < x1; sx++) { sum += px[sy * e + sx]; n++; }
      const a = n ? sum / n / 255 : 0;
      const o = ((cy + y) * W + cx + x) * 3;
      img[o] = Math.round(BG[0] * (1 - a) + INK[0] * a);
      img[o + 1] = Math.round(BG[1] * (1 - a) + INK[1] * a);
      img[o + 2] = Math.round(BG[2] * (1 - a) + INK[2] * a);
    }
  });
  const out = `${outPrefix}_${p + 1}.png`;
  writePng(img, W, H, out);
  fs.writeFileSync(`${outPrefix}_${p + 1}.txt`,
    chunk.map((it, i) => `${p * PER_PAGE + i}:${it.label}`).join('  '));
  console.log(`  第 ${p + 1} 页：${chunk.length} 格 → ${out}（${W}×${H}，图例 ${outPrefix}_${p + 1}.txt）`);
}
