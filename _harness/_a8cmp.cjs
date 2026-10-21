// 同一个图标在**两种 canvas 边长**上各自压出来的两张掩码（磁盘缓存里那两个 .a8），到底是不是同一张画。
//
// 为什么去拉文件而不是读日志：判据行只有 `字均净 / 顶占比` 这类**聚合数**，
// 它们能报警（抖音 228→26）却不能回答"屏幕上看得出吗"。掩码本身才是交付的东西，
// 两张 .a8 逐格一比才有资格说"这轴要不要修"。
//
// 192 那张要**最近邻放大**到 384 再比 —— 不许用平滑：平滑会把"边缘一格的差"抹平成假一致。
// ⚠ 代价：放大本身会让边带错位，所以 IoU 不是 1.0 不等于"画得不一样"。
//   正因如此必须配**正对照**（两张不同图标）—— `_a8sweep.cjs` 干的就是这件事。
//
// 单对：node _a8cmp.cjs <小.a8> <大.a8> <输出前缀>
const fs = require('fs');
const zlib = require('zlib');

function load(p) {
  const b = fs.readFileSync(p);
  const rowBytes = b.readUInt32BE(0), edge = b.readUInt32BE(4);
  if (b.length !== 8 + rowBytes * edge) throw new Error(p + ' 长度对不上头部（rowBytes=' + rowBytes + ' edge=' + edge + '）');
  if (rowBytes < edge) throw new Error(p + ' 自相矛盾的头部：rowBytes < edge');
  const px = new Uint8Array(edge * edge);
  for (let y = 0; y < edge; y++) for (let x = 0; x < edge; x++) px[y * edge + x] = b[8 + y * rowBytes + x];
  return { edge, px };
}
const stat = (p) => {
  const h = new Array(256).fill(0);
  let sum = 0;
  for (const v of p) { h[v]++; sum += v; }
  const levels = h.map((c, v) => [v, c]).filter(([, c]) => c / p.length >= 0.003);
  return {
    mean: sum / p.length,
    ink: p.filter(v => v >= 128).length / p.length,
    cover: p.filter(v => v > 4).length / p.length,
    nLv: levels.length,
    lv: levels.map(([v, c]) => `${v}:${((c / p.length) * 100).toFixed(1)}%`).join(' '),
  };
};
/** 把 a 最近邻放大到 b 的边长后逐格比。返回的 `iou` / `dMed` 才是"同一张画吗"的读数。 */
function compare(pa, pb) {
  const A = load(pa), B = load(pb);
  const n = B.edge;
  const Au = new Uint8Array(n * n);
  if (A.edge === n) Au.set(A.px);
  else for (let y = 0; y < n; y++) for (let x = 0; x < n; x++)
    Au[y * n + x] = A.px[Math.floor(y * A.edge / n) * A.edge + Math.floor(x * A.edge / n)];
  const N = n * n;
  let both = 0, onlyA = 0, onlyB = 0;
  const dd = new Int32Array(N);
  for (let i = 0; i < N; i++) {
    const a = Au[i] >= 128, b = B.px[i] >= 128;
    if (a && b) both++; else if (a) onlyA++; else if (b) onlyB++;
    dd[i] = Math.abs(Au[i] - B.px[i]);
  }
  const sorted = Int32Array.from(dd).sort();
  return {
    edgeA: A.edge, edgeB: B.edge,
    a: stat(Au), b: stat(B.px),
    iou: both / (both + onlyA + onlyB || 1), both, onlyA, onlyB,
    dMed: sorted[N >> 1], dP90: sorted[Math.floor(N * 0.9)], dMax: sorted[N - 1],
  };
}
/** 左 = 小边长放大、中 = 大边长、右 = |Δα|×4（全白 = 两张一模一样）。 */
function render(pa, pb, out) {
  const A = load(pa), B = load(pb), E = B.edge, N = E * E;
  const Au = new Uint8Array(N);
  if (A.edge === E) Au.set(A.px);
  else for (let y = 0; y < E; y++) for (let x = 0; x < E; x++)
    Au[y * E + x] = A.px[Math.floor(y * A.edge / E) * A.edge + Math.floor(x * A.edge / E)];
  const W = E * 3 + 8, H = E;
  const img = Buffer.alloc(W * H * 3, 255);
  const put = (x, y, r, g, b) => { const o = (y * W + x) * 3; img[o] = r; img[o + 1] = g; img[o + 2] = b; };
  for (let y = 0; y < H; y++) for (let x = 0; x < E; x++) {
    const v = Au[y * E + x], u = B.px[y * E + x];
    put(x, y, 255 - v, 255 - v, 255 - v);
    put(E + 4 + x, y, 255 - u, 255 - u, 255 - u);
    const e = Math.min(255, Math.abs(v - u) * 4);
    put(E * 2 + 8 + x, y, 255, 255 - e, 255 - e);
  }
  let T = null;
  const crc32 = (buf) => {
    if (!T) { T = []; for (let n2 = 0; n2 < 256; n2++) { let c = n2; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; T[n2] = c >>> 0; } }
    let c = 0xffffffff;
    for (const b of buf) c = T[(c ^ b) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  };
  const chunk = (t, d2) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(d2.length);
    const td = Buffer.concat([Buffer.from(t, 'ascii'), d2]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, crc]);
  };
  const raw = Buffer.alloc((W * 3 + 1) * H);
  for (let y = 0; y < H; y++) { raw[y * (W * 3 + 1)] = 0; img.copy(raw, y * (W * 3 + 1) + 1, y * W * 3, (y + 1) * W * 3); }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(H, 4); ihdr[8] = 8; ihdr[9] = 2;
  fs.writeFileSync(out, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]));
  return { W, H };
}

if (require.main === module) {
  const [pa, pb, out] = process.argv.slice(2);
  if (!pa || !pb || !out) { console.log('用法：node _a8cmp.cjs <小.a8> <大.a8> <输出前缀>'); process.exit(2); }
  const r = compare(pa, pb);
  console.log(`== ${r.edgeA}(放大到 ${r.edgeB}) vs ${r.edgeB}：同一张图标的两张**交付掩码** ==`);
  console.log(`  均值 α   ${r.a.mean.toFixed(1)}  vs  ${r.b.mean.toFixed(1)}`);
  console.log(`  墨(α≥128) ${(r.a.ink * 100).toFixed(1)}%  vs  ${(r.b.ink * 100).toFixed(1)}%`);
  console.log(`  非空(α>4) ${(r.a.cover * 100).toFixed(1)}%  vs  ${(r.b.cover * 100).toFixed(1)}%`);
  console.log(`  档数(≥0.3% 的 α) ${r.a.nLv}  vs  ${r.b.nLv}`);
  console.log(`  IoU(α≥128) ${r.iou.toFixed(3)}   同墨 ${r.both} / 只 ${r.edgeA} 有 ${r.onlyA} / 只 ${r.edgeB} 有 ${r.onlyB}`);
  console.log(`  |Δα| 中位 ${r.dMed}  p90 ${r.dP90}  最大 ${r.dMax}`);
  console.log(`  ${r.edgeA} 档 ${r.a.lv}\n  ${r.edgeB} 档 ${r.b.lv}`);
  const { W, H } = render(pa, pb, out + '.png');
  console.log(`  对照图（左 ${r.edgeA} 放大 / 中 ${r.edgeB} / 右 |Δα|×4）→ ${out}.png  ${W}x${H}`);
}
module.exports = { load, stat, compare, render };
