// **第 19 闸的对照语料（2026-10-21）**：造一张**两本账判定本来就不同**的图。
//
// 为什么要有它：`rec_model`+`plate_model` 那 132 张上，极性翻 **0 张**、
// `只给前景层` 那一支里连"够得着 40%"的候选都 **0 张**（`fgNear=0`）——
// 也就是说拿真语料钉出来的第 19 闸，对"把极性退回判据那一本"这个变异**不会响**。
// 那正是本仓骂过的那族：**空跑的闸长得和通过的闸一模一样**。
//
// 这一张的设计（384² 透明底 + 一条 **0.7 宽高比**的实心横条）：
//  · **判据那一本**（量喂进模型那一张 = 这条前景层）：条 280×196 → 128 格上 93×65，
//    圆盘能整块盖住的只有 `65/2` 那一档 ⇒ `keep ≈ π·65/(4·93) = 54.9%` → **过 plate 档（byKeep）且 ≥50**
//    且不 spiky ⇒ `polaritySubject = 真`；
//  · **彩色那一本**（index 标成 `bg=ColorDrawable` ⇒ 只给前景层那一支）：背景那块盘把整张画布铺满
//    ⇒ **恒判满幅** ⇒ 没有窗口 ⇒ `polaritySubject = 假`。
// ⇒ 一台子同时看到"老口径开火、新口径不开火"，`flip=1` 钉进 pin，
//   谁把 `IconCropWindow.polarityOf` 的取数改回去，`开火集` 与 `名单` 当场动。
//
// 用法：`node _mk_twobook_ctrl.cjs [输出目录]`（默认 `twobook_ctrl`）
const fs = require('fs'), path = require('path'), zlib = require('zlib');
// CRC32 表必须**在使用之前**定义：这文件第一版把它写在最底下，于是 `const T` 落在 TDZ 里，
// 第一口 `chunk('IHDR')` 就 `ReferenceError: Cannot access 'T' before initialization` —— 报得清楚，
// 但这一族（"末尾的定义前面就用"）在 shell 里会被 `;` 吞掉退码，所以放在最上面。
const T = (() => { const t = new Int32Array(256); for (let n = 0; n < 256; n++) { let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c; } return t; })();
function crc32(buf) { let c = -1; for (let i = 0; i < buf.length; i++) c = T[(c ^ buf[i]) & 0xff] ^ (c >>> 8); return c ^ -1; }
const OUT = path.resolve(process.argv[2] || 'twobook_ctrl');
const E = 384, BAR_W = 280, BAR_H = 196;   // 0.70 宽高比：算出来正好落在 [50,60) 那条带里

// ── 手写一张 RGBA PNG（colorType 6、filter 0）：语料必须可复现，不能靠"我机器上有某张图" ──
const rows = [];
const x0 = (E - BAR_W) / 2, y0 = (E - BAR_H) / 2;
for (let y = 0; y < E; y++) {
  const row = Buffer.alloc(1 + E * 4);        // 首字节 = filter type 0
  for (let x = 0; x < E; x++) {
    const inBar = x >= x0 && x < x0 + BAR_W && y >= y0 && y < y0 + BAR_H;
    if (!inBar) continue;                      // 透明（全 0）
    const o = 1 + x * 4;
    row[o] = 0x40; row[o + 1] = 0x60; row[o + 2] = 0x80; row[o + 3] = 0xff;
  }
  rows.push(row);
}
const raw = Buffer.concat(rows);
const chunk = (type, data) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body) >>> 0);
  return Buffer.concat([len, body, crc]);
};
const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(E, 0); ihdr.writeUInt32BE(E, 4);
ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0)),
]);
fs.mkdirSync(OUT, { recursive: true });
const FILE = 'ctrl.fgonlybar_ic_launcher_aaaa0000bbbb.png';
fs.writeFileSync(path.join(OUT, FILE), png);

// ── index.tsv：19 列与 `CorpusCapture` 同形，缺列会让台子按"非自适应"跑（那是旧口径）──
const head = ['pkg', 'label', 'file', 'edge', 'sig', 'cls', 'fg', 'bg', 'mono', 'kb', 'fgAlpha', 'bgAlpha',
  'monoFile', 'monoAlpha', 'bgColor', 'fgFile', 'bgFile', 'modelFile', 'modelBgFile'];
//        0      1       2       3      4      5                    6     7              8       9    10        11
//  ⚠ `cls` 里必须有 `Adaptive`、`bg` 必须是 `ColorDrawable`、`modelBgFile` 必须是 `-` ——
//    这三样合起来才是"自适应 + 背景是纯色盘 + 模型只吃前景层"那一支（第 19 闸要的那一支）。
const row = ['ctrl.fgonlybar', '对照横条', FILE, '384', 'aaaa0000bbbb', 'AdaptiveIconDrawable', '-', '-',
  '无', '-', '-', '-', '-', '-', '#FF808080', '-', '-', FILE, '-'];
fs.writeFileSync(path.join(OUT, 'index.tsv'), head.join('\t') + '\n' + row.join('\t') + '\n');
console.log(`写出 ${OUT}/${FILE}（${png.length} 字节，实心 ${BAR_W}×${BAR_H} 居中）+ index.tsv`);
console.log('128 格上 93×65 ⇒ keep≈54.9%（plate 档、≥50、不 spiky）；彩色那一本按构造恒满幅 ⇒ 极性翻 1 张');
