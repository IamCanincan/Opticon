// 一次性：屏上那一对（彩色 PNG + 单色 ALPHA_8）逐张量幅面比。
// 用法：node _harness/_pairshot.cjs <app_icons.db> [包名子串 …]
//   --zoom=<_manifest.tsv>  只拿**裁圆缩进那一侧**（zoom<1）的图标当裁判，逐张对到"账上那个窗"：
//     --expect=zoom 证的是修之前（幅面比 = 窗），--expect=1 证的是修之后（同一道缩量不再乘两遍）。
//     ⚠ **放大侧（zoom>1）不在射程里**：窗口 cover 到画布外，溢出的墨被画布切掉，屏上外接框由内容
//       而不是由 zoom 决定（真机散在 0.56~1.14，见 MonochromeSynth 那段），拿它判必然假红。
//     ⚠ 判据吃的是 `componentName` 第二段**带类名点**的那一行（应用启动图标）；快捷方式/桌面组件
//       那一批彩色是放行的，天生不该等于 1，只旁列不判。
//       一张都没判到 = 这台子这一轮没吃到数据，**退码非 0**（空表当"没问题"是本仓反复的死法）。
const fs = require('fs'), zlib = require('zlib');
const { DatabaseSync } = require('node:sqlite');

function decodePng(buf) {
  let pos = 8, ihdr = null; const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos), kind = buf.toString('latin1', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (kind === 'IHDR') ihdr = { w: data.readUInt32BE(0), h: data.readUInt32BE(4), color: data[9] };
    else if (kind === 'IDAT') idat.push(data); else if (kind === 'IEND') break;
    pos += len + 12;
  }
  const { w, h, color } = ihdr, chans = { 2: 3, 6: 4 }[color];
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
  return { w, h, chans, out };
}
function boxOf(get, n, thr) {
  let l = n, t = n, r = -1, b = -1, ink = 0;
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    if (!thr(get(y, x))) continue;
    ink++; if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y;
  }
  if (r < 0) return null;
  return { w: r - l + 1, h: b - t + 1, share: ink / (n * n) };
}

const args = process.argv.slice(2);
const dbp = args.shift();
const flag = (name, dflt) => {
  const a = args.find((s) => s.startsWith('--' + name + '='));
  if (!a) return dflt;
  args.splice(args.indexOf(a), 1);
  return a.slice(name.length + 3);
};
const zoomFile = flag('zoom', null);
const expect = flag('expect', 'zoom');
const tol = +flag('tol', '0.05');
// 语料清单（ReframeCorpusKt 产的 `_manifest.tsv`）：包名 -> 缩进侧最小的那个窗
let win = new Map();
if (zoomFile) {
  for (const line of fs.readFileSync(zoomFile, 'utf8').split(/\r?\n/)) {
    const f = line.split('\t');
    if (f.length < 3 || !f[0] || f[2] === '-' || !f[2]) continue;
    const z = +f[2];
    if (!(z < 1)) continue;                       // 放大侧不可观察，见文件头
    const pkg = f[0].split('_')[0];
    if (!win.has(pkg) || z < win.get(pkg)) win.set(pkg, z);
  }
  console.log(`缩进那一侧（窗<1）语料里 ${win.size} 个包：` +
    [...win].map(([p, z]) => `${p} ${z.toFixed(3)}`).join('、'));
}
const target = (pkg) => (expect === 'zoom' ? win.get(pkg) : +expect);
const d = new DatabaseSync(dbp, { readOnly: true });
const rows = d.prepare('SELECT componentName c, label l, icon i, mono_icon m FROM icons').all();
let n = 0, judged = 0, miss = 0;
console.log('组件'.padEnd(46) + ' 彩色幅  单色幅  幅面比  彩色墨%  单色墨%');
for (const r of rows) {
  if (!r.i || !r.m) continue;
  const pkg = r.c.split('/')[0];
  if (args.length && !args.some((s) => r.c.includes(s))) continue;
  const p = decodePng(Buffer.from(r.i));
  if (!p) continue;
  const col = boxOf((y, x) => {
    const i = (y * p.w + x) * p.chans;
    return Math.min(p.out[i], p.out[i + 1], p.out[i + 2]);
  }, p.w, (v) => v < 235);
  const mb = Buffer.from(r.m), side = Math.round(Math.sqrt(mb.length));
  if (side * side !== mb.length) continue;
  const mono = boxOf((y, x) => mb[y * side + x], side, (v) => v > 8);
  if (!col || !mono) continue;
  const cw = col.w / p.w, mw = mono.w / side;
  const ratio = mw / cw;
  let mark = '';
  if (zoomFile && win.has(pkg)) {
    const cls = (r.c.split('/')[1] || '').split('$')[0];
    const isAppIcon = cls.lastIndexOf('.') > 0 || cls.endsWith('.');
    const want = target(pkg);
    if (!isAppIcon) mark = `   [旁列：非启动图标，窗=${win.get(pkg).toFixed(3)} 不判]`;
    else {
      const ok = Math.abs(ratio - want) <= tol;
      judged++;
      mark = `   [窗=${win.get(pkg).toFixed(3)} 期望 ${(+want).toFixed(3)} ${ok ? '✅' : '❌'}]`;
      if (!ok) miss++;
    }
  }
  // ⚠ 名字列**不能截断**：以前 slice(0,44)+padEnd(44) 会把长组件名的第一个数字粘进名字里
  //   （`…easytie0.633  0.363 …`），于是两趟之间只要彩色宽度没变而单色变了，`_psdiff` 的键就对不上，
  //   "变了 2 行"其实是"变了 3 行、其中 1 行被当成新增行"（2026-11-15 抓到）。宽度给到 78。
  console.log('  ' + (r.c + ' ' + (r.l || '')).padEnd(78) +
    (cw).toFixed(3) + '  ' + (mw).toFixed(3) + '  ' + (ratio).toFixed(3) + '   ' +
    (col.share * 100).toFixed(1).padStart(5) + '    ' + (mono.share * 100).toFixed(1).padStart(5) + mark);
  n++;
}
console.log('共', n, '对');
if (zoomFile) {
  if (!judged) {
    console.log('!! 缩进那一侧一张都没判到 —— 这不是"没问题"，是这一轮**没吃到候选**' +
      '（先确认这个包在屏上、且语料与库是同一趟）');
    process.exit(1);
  }
  console.log(miss ? `!! ${miss}/${judged} 张对不上期望 ${expect}` : `✅ ${judged} 张全对上期望 ${expect}`);
  process.exit(miss ? 1 : 0);
}
