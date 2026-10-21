// 各家**交付的剪影**占盘多少：一张表看清楚"大小不一"到底散到什么程度。
// 吃的是 Replay 带回来的 `-out.png`（设备真交付的那张掩码，数据在 alpha 通道）。
// 用法：node _inkspan.cjs <目录> [...目录]
const fs = require("fs"), path = require("path"), zlib = require("zlib");
const _src = fs.readFileSync(path.join(__dirname, "_circab.cjs"), "utf8");
const _cut = _src.indexOf("const BG_A =");
if (_cut < 0) throw new Error("_circab.cjs 结构变了（找不到 `const BG_A =`），拒绝猜测");
const C = new Function("require", "__dirname",
  _src.slice(0, _cut) + "\nreturn { decodePng };")(require, __dirname);

const rows = [];
const seen = new Set();     // 同一张图标一次抓取里会被带回来好几次（不同进程 / 不同尺寸变体）
for (const d of process.argv.slice(2)) {
  for (const f of fs.readdirSync(d).filter(n => n.endsWith("-out.png")).sort()) {
    const png = C.decodePng(fs.readFileSync(path.join(d, f)));
    const { w, h, px } = png;
    // 数据在 alpha 通道（RGB=0）；也兼容 RGB 有值的画法
    let x0 = w, x1 = -1, y0 = h, y1 = -1, n = 0, sum = 0;
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4;
      let a = px[i + 3];
      if (a < 8 && (px[i] > 8 || px[i + 1] > 8 || px[i + 2] > 8)) a = Math.max(px[i], px[i + 1], px[i + 2]);
      if (a < 128) continue;
      n++; sum += a;
      if (x < x0) x0 = x; if (x > x1) x1 = x;
      if (y < y0) y0 = y; if (y > y1) y1 = y;
    }
    if (x1 < 0) { rows.push([f.replace(/_mipmap_.*?-p[0-9a-f]+-out\.png$/, "").replace(/^icon\d+_/, ""), 0, 0]); continue; }
    // ⚠ 分母必须是**可见区**（画布中间 2/3），不是整张 108dp 图层：桌面只画那块盘。
    //   用整张当分母会把一堆图标读成"100% 占满"（其实它只是碰到了图层边），
    //   第一版就错在这里 —— 那把尺量出来的"大小不一"是假的。
    const side = Math.min(w, h) * 2 / 3;
    const span = Math.max(x1 - x0 + 1, y1 - y0 + 1) / side;            // 墨的外接边 / 可见盘边
    const ink = n / (side * side);                                     // 墨占可见盘几成
    const key = f.replace(/_drawable_.*$/, "").replace(/_mipmap_.*$/, "").replace(/^icon\d+_/, "");
    if (seen.has(key)) continue;                                       // 同一张被多个进程/变体重复带回来，只算一次
    seen.add(key);
    rows.push([key.replace(/_[0-9a-f]{12}.*$/, ""), span, ink]);
  }
}
rows.sort((a, b) => a[1] - b[1]);
console.log("包名（截断）".padEnd(42) + "  墨占盘边  墨占面积");
rows.forEach(r => console.log(r[0].slice(0, 40).padEnd(42) + (r[1] * 100).toFixed(1).padStart(7) + "%" + (r[2] * 100).toFixed(1).padStart(8) + "%"));
const s = rows.map(r => r[1]).filter(v => v > 0).sort((a, b) => a - b);
const q = p => s[Math.min(s.length - 1, Math.floor(s.length * p))];
console.log(`\nn=${s.length}  占盘边：最小 ${(q(0) * 100).toFixed(0)}%  p25 ${(q(.25) * 100).toFixed(0)}%  中位 ${(q(.5) * 100).toFixed(0)}%  p75 ${(q(.75) * 100).toFixed(0)}%  p90 ${(q(.9) * 100).toFixed(0)}%  最大 ${(q(1) * 100).toFixed(0)}%`);
console.log(`散布：中位的 0.6 倍以下 ${s.filter(v => v < q(.5) * 0.6).length} 张，1.3 倍以上 ${s.filter(v => v > q(.5) * 1.3).length} 张 —— 这就是"大小不一"的账`);
