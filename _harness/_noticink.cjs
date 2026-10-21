// **通知小图标"大小不一"的那把尺：量每张图的墨外接框占自己画布（或视口）多少。**
//
// 为什么按这个量：状态栏那个槽位是**固定的** `dimen/status_bar_icon_size`（本机 = 默认档 22dp，630dpi ⇒ ≈87px），
//   而槽位里是 `ImageView` + `scaleType=fitCenter` ⇒ **交出去的位图是 48 还是 96 会被同一个系数吃掉**，
//   观感大小只剩一个来源：**墨在外接框里占了多少**（设计者自己那圈留白）。
//   所以"大小不一"要量的不是像素边长，是这个**比例**。
//
// 用法：
//   node _noticink.cjs --vec=<aapt2 xmltree 的 dump 文件>   视口 + 所有 pathData 的点集外接框
//   node _noticink.cjs --png=<png 文件> [标签]                alpha 外接框（>0 算墨，另报 ≥128 的实心框）
// 一条命令可以带多个 --vec/--png，逐张打，最后一行给中位与极差。
const fs = require('fs');
const path = require('path');

/* ---------- 路径点集：够用的子集（M/L/H/V/C/S/Q/T/Z + 相对坐标；A 用端点+中点近似） ---------- */
function pathPoints(d) {
  const pts = [];
  const toks = String(d).match(/[MmLlHhVvCcSsQqTtAaZz]|-?\d*\.?\d+(?:[eE][-+]?\d+)?/g) || [];
  let i = 0, cx = 0, cy = 0, sx = 0, sy = 0, cmd = '', pen = null;
  const num = () => parseFloat(toks[i++]);
  const push = (x, y) => pts.push([x, y]);
  while (i < toks.length) {
    if (/^[MmLlHhVvCcSsQqTtAaZz]$/.test(toks[i])) { cmd = toks[i++]; pen = cmd === cmd.toUpperCase(); if (cmd === 'Z' || cmd === 'z') { cx = sx; cy = sy; } continue; }
    const rel = cmd === cmd.toLowerCase(), C = cmd.toUpperCase();
    if (C === 'M' || C === 'L' || C === 'T') {
      const x = num(), y = num(); cx = rel ? cx + x : x; cy = rel ? cy + y : y; push(cx, cy);
      if (C === 'M') { sx = cx; sy = cy; cmd = rel ? 'l' : 'L'; }
    } else if (C === 'H') { const x = num(); cx = rel ? cx + x : x; push(cx, cy);
    } else if (C === 'V') { const y = num(); cy = rel ? cy + y : y; push(cx, cy);
    } else if (C === 'C' || C === 'S' || C === 'Q') {
      // ⚠ 每条命令的参数个数不一样：C=6、S=4、Q=4。先前 S 也按 6 读，
      //   于是 `s-3.0,1.34` 后面**把下一条命令的数字吞掉两个、再把命令字母当数读** ⇒ 整条路径 NaN
      //   （三张图标静默变成 NaN，中位数被污染 —— 又一个"错的是尺不是图"）。
      const n = C === 'C' ? 6 : 4, a = [];
      for (let k = 0; k < n; k++) a.push(num());
      const o = a.map((v, k) => (k % 2 ? (rel ? cy + v : v) : (rel ? cx + v : v)));
      if (C === 'Q') { push(cx + (o[0] - cx) * .6, cy + (o[1] - cy) * .6); } // 二次贝塞尔的控制多边形采样近似
      for (let k = 0; k < o.length; k += 2) push(o[k], o[k + 1]);
      cx = o[o.length - 2]; cy = o[o.length - 1];
    } else if (C === 'A') {
      const rx = num(), ry = num(), rot = num(), la = num(), sf = num(), x = num(), y = num();
      const nx = rel ? cx + x : x, ny = rel ? cy + y : y;
      push(nx, ny); push((cx + nx) / 2, (cy + ny) / 2);      // 弧只用端点 + 中点近似（状态图标里没有大半圆）
      cx = nx; cy = ny; void rx; void ry; void rot; void la; void sf;
    } else { i++; }
  }
  return pts;
}

function vecBox(file) {
  const t = fs.readFileSync(file, 'utf8');
  // aapt2 xmltree 的行长这样：`A: …:viewportWidth(0x01010402)=48`；文本 XML 是 `viewportWidth="48"`。
  // ⚠ 别用 `[^=]*=` 那类去跳——十六进制 id 里就带数字，会停在 `0x…` 里面（本仓"正则自己画的边界"又一家）。
  const grab = name => {
    const m = new RegExp(name + '\\(0x[0-9a-fA-F]+\\)\\s*=\\s*"?([\\d.]+)"?').exec(t) ||
              new RegExp(name + '\\s*=\\s*"([\\d.]+)"').exec(t);
    return m ? parseFloat(m[1]) : NaN;
  };
  const vw = grab('viewportWidth'), vh = grab('viewportHeight');
  const datas = [...t.matchAll(/pathData(?:\(0x[0-9a-fA-F]+\))?\s*=\s*"([^"]+)"/g)].map(m => m[1]);
  if (!Number.isFinite(vw) || !Number.isFinite(vh) || !datas.length) return null;
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity, n = 0;
  for (const d of datas) for (const [x, y] of pathPoints(d)) {
    n++; x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
  }
  return { w: x1 - x0, h: y1 - y0, vw, vh, pts: n, paths: datas.length };
}

/* ---------- PNG：按 alpha 量（>0 算墨；另给 ≥128 的实心框） ---------- */
function pngBox(p) {
  const { png } = require(path.join(__dirname, 'pngread.cjs'));
  const im = png(p);
  // ⚠ pngread 交回的 `ch` 是**通道数**（4=RGBA、3=RGB、2=灰+α、1=灰），不是 PNG 的 colorType。
  //   第一版把它当 colorType 用（`ct===6?4:…`），于是 RGBA 图被按"灰+α"取字节、alpha 读到了错的偏移上，
  //   **77 张框架图标一律报 1.000** —— 恒等值不是分布，是尺断了（本仓那一族）。
  const chan = im.ch, hasAlpha = chan === 4 || chan === 2;
  const px = im.data;
  if (!px || !im.w) { console.error('!! pngread 没解出这张'); process.exit(9); }
  const at = (x, y) => {
    const o = (y * im.w + x) * chan;
    if (hasAlpha) return px[o + chan - 1];
    // 没有 alpha 通道的（整张不透明）：墨 = 非近白
    const lum = chan === 3 ? (px[o] * 299 + px[o + 1] * 587 + px[o + 2] * 114) / 1000 : px[o];
    return lum < 248 ? 255 : 0;
  };
  const box = (test) => {
    let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1, n = 0;
    for (let y = 0; y < im.h; y++) for (let x = 0; x < im.w; x++) {
      const a = at(x, y);
      if (!test(a)) continue;
      n++; if (x < x0) x0 = x; if (y < y0) y0 = y; if (x > x1) x1 = x; if (y > y1) y1 = y;
    }
    return n ? { w: x1 - x0 + 1, h: y1 - y0 + 1, n } : null;
  };
  return { cw: im.w, chh: im.h, ink: box(a => a > 0), solid: box(a => a >= 128), chan, hasAlpha };
}

const args = process.argv.slice(2);
const rows = [];
for (let i = 0; i < args.length; i++) {
  const a = args[i];
  if (a.startsWith('--vec=')) {
    const f = a.slice(6), r = vecBox(f);
    if (!r) { console.log(`  跳过 ${f}：没解析出视口或 pathData`); continue; }
    rows.push({ name: path.basename(f), kind: 'vector', ratio: Math.max(r.w / r.vw, r.h / r.vh),
      detail: `视口 ${r.vw}×${r.vh} 墨框 ${r.w.toFixed(1)}×${r.h.toFixed(1)}（${r.paths} 条路径/${r.pts} 个点）` });
  } else if (a.startsWith('--png=')) {
    const f = a.slice(6), r = pngBox(f), tag = (args[i + 1] && !args[i + 1].startsWith('--')) ? args[++i] : path.basename(f);
    if (!r.ink) { console.log(`  ${tag}: 整张透明`); continue; }
    rows.push({ name: tag, kind: 'png', ratio: Math.max(r.ink.w / r.cw, r.ink.h / r.chh),
      detail: `画布 ${r.cw}×${r.chh} 墨框 ${r.ink.w}×${r.ink.h} 实心(≥128) ${r.solid ? r.solid.w + '×' + r.solid.h : '无'}` });
  }
}
if (!rows.length) { console.error('用法见文件头：--vec=<xmltree dump> / --png=<png> [标签]'); process.exit(2); }
for (const r of rows) console.log(`  ${r.ratio.toFixed(3)}  ${r.kind.padEnd(6)} ${r.name.padEnd(34)} ${r.detail}`);
const rs = rows.map(r => r.ratio).sort((a, b) => a - b);
const med = rs[Math.floor(rs.length / 2)];
console.log(`\n  n=${rs.length} 中位=${med.toFixed(3)} 最小=${rs[0].toFixed(3)} 最大=${rs[rs.length - 1].toFixed(3)} ⇒ 同一行里视觉直径最大差 ${(rs[rs.length - 1] / rs[0]).toFixed(2)}×`);
