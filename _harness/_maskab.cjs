// 把设备日志里 `Mono: dump … out Pxxx/NNN` 的分片重拼成 PNG，逐张量"交出去的墨占了多大"。
// 为什么要它：两个包的 `几何=` 读数逐张相同，不等于屏上逐张相同 —— 搬的位置（目标矩形、采样）
// 不进那个数字。唯一能把两版输出对上办法就是把两边真交出去的那张掩码都带回来比。
// 用法：node _maskab.cjs <log.txt> <pidA> <pidB>
const fs = require('fs');
const path = require('path');

const CIRCAB = path.join(__dirname, '_circab.cjs');
const srcTxt = fs.readFileSync(CIRCAB, 'utf8');
const cut = srcTxt.indexOf('const files');
const C = (new Function('require', 'dirname', srcTxt.slice(0, cut) +
  '\nreturn { decodePng };'))(require, __dirname);

const LOG = process.argv[2], PA = process.argv[3], PB = process.argv[4];
const lines = fs.readFileSync(LOG, 'utf8').split('\n');

// BEGIN 行给出图标名（含 sig）；out 分片按 Pnnn/NNN 归并。
function collect(pid) {
  const acc = new Map();
  let cur = null;
  for (const ln of lines) {
    if (!ln.includes('(' + pid + ')') || !ln.includes('Mono: dump')) continue;
    const m = ln.match(/BEGIN id=\d+ what=out parts=(\d+) (\S+)/);
    if (m) { cur = { name: m[2].replace(/\|.*$/, '').trim(), parts: parseInt(m[1]), got: [] }; continue; }
    const d = ln.match(/what=out|^.*\s out (P)(\d{3})\/(\d{3}) (\S+)/);
    const p = ln.match(/ out P(\d{3})\/(\d{3}) (\S+)/);
    if (p && cur) {
      cur.got[parseInt(p[1])] = p[2];
      if (cur.got.filter(Boolean).length === cur.parts) {
        acc.set(cur.name, cur.got.join(''));
        cur = null;
      }
    }
  }
  return acc;
}

// 量一张：交付的是 ALPHA_8（Android 存成 RGBA，浓度在 **alpha** 那一通道，RGB 只有两个值 ——
// 自检就卡在这儿：拿错通道的话 ink 会是 0 或 1，一眼假）。返回不透明像素的**外接框**占画布的比例
// （"屏上多大"的那把尺）与墨量占比。
function measure(b64, tag) {
  let d;
  try { d = C.decodePng(Buffer.from(b64, 'base64')); }
  catch (e) { throw new Error('解不开（' + tag + '）：' + e.message); }
  const { w, h, px } = d;
  let aSum = 0, aVals = new Set();
  for (let i = 0; i < w * h; i++) { aSum += px[i * 4 + 3]; if (aVals.size < 300) aVals.add(px[i * 4 + 3]); }
  if (aVals.size < 2 || aSum === 0) throw new Error('自检失败（' + tag + '）：alpha 通道没有层次，读错通道了');
  let minX = w, minY = h, maxX = -1, maxY = -1, ink = 0;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const v = px[(y * w + x) * 4 + 3];
    if (v >= 8) {
      ink++;
      if (x < minX) minX = x; if (x > maxX) maxX = x;
      if (y < minY) minY = y; if (y > maxY) maxY = y;
    }
  }
  if (maxX < 0) throw new Error('自检失败（' + tag + '）：整张 alpha<8');
  const bw = (maxX - minX + 1) / w, bh = (maxY - minY + 1) / h;
  return { ink: ink / (w * h), bw, bh, side: Math.max(bw, bh), w, h };
}

// 分片可能被 logcat 自己截断（单条 4068 字节），拼出来就是坏 PNG —— 那种张**跳过并计数**，
// 不许悄悄算"相同"。可比对子少于一半就非零退出。
function tryMeasure(b64, tag) { try { return measure(b64, tag); } catch (e) { return null; } }

const A = collect(PA), B = collect(PB);
let pairs = 0, skipped = 0, diff = 0;
const out = [];
for (const [name, b64] of B) {
  if (!A.has(name)) { skipped++; continue; }
  const ma = tryMeasure(A.get(name), name + ' [A]'), mb = tryMeasure(b64, name + ' [B]');
  if (!ma || !mb) { skipped++; continue; }
  pairs++;
  if (ma.w !== mb.w) { console.error('尺寸不同（拼错了？）：' + name); process.exit(1); }
  const d = mb.side - ma.side;
  if (Math.abs(d) >= 0.005) diff++;
  out.push([Math.abs(d) >= 0.005 ? 'DIFF' : 'same', name,
    `${ma.side.toFixed(3)}→${mb.side.toFixed(3)}`, `Δ边 ${d >= 0 ? '+' : ''}${d.toFixed(3)}`,
    `墨 ${ma.ink.toFixed(3)}→${mb.ink.toFixed(3)}`].join('\t'));
}
process.stderr.write(`可比对 ${pairs} 张，跳过 ${skipped} 张，其中有差 ${diff} 张\n`);
if (pairs === 0) { console.error('一张都对不上 —— 这个结论是空的，不算通过'); process.exit(1); }
out.sort((x, y) => (x.startsWith('DIFF') ? -1 : 1) - (y.startsWith('DIFF') ? -1 : 1));
console.log(out.join('\n'));
