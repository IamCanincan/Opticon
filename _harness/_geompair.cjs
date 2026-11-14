// 几何配表台：把**同一张图标**在彩色与单色两边各自算出的三个数配到一起。
//   彩色：`自适应几何 host=… fill=… scale=… 支=延伸|铺底 : <名字>`（CircleIconDrawable）
//   单色：`Mono: 跟彩色 scale=… fill=… 支=… edge=… label=<名字> …`（MonochromeSynth）
// 两边吃的是同一份 `IconGeom.planOf`，所以这几个数**必须相等**（只剩分辨率带来的 ±2% 抖动）。
// 配上 0 对 = 台子没牙（不是"没问题"），本脚本会红；`--selftest` 自己造一条该抓的，证明它抓得住。
// 用法：node _harness/_geompair.cjs <logcat 文件> [--tol=0.02]  |  node _harness/_geompair.cjs --selftest
const fs = require('fs');

const args = process.argv.slice(2);

const COLOR_RE = /自适应几何 host=(\S+) fill=(-?[\d.]+) scale=(-?[\d.]+) 支=(延伸|铺底) : (\S+)/;
// `(留档)` = 命中路径从 `geom.tsv` 读回来的同一组数（2026-11-15 补：真机一趟重启是 命中=1200 未命中=0，
//  只打冷那一路的话这台子每次开机都吃不到数据）。
const MONO_RE = /Mono: 跟彩色(\(留档\))? scale=(-?[\d.]+) fill=(-?[\d.]+) 支=(延伸|铺底) edge=(\d+) label=(\S+)/;

/** `label=` 后面是 describe() 整串（`名字 盘=… intrinsic=…`），第一段才是 IconNames.of(...)。 */
const firstTok = (s) => (s || '').split(' ')[0];

function pair(lines, tol) {
  const color = new Map(), mono = new Map();
  for (const l of lines) {
    const c = COLOR_RE.exec(l);
    if (c) color.set(c[5], { fill: +c[2], scale: +c[3], branch: c[4], line: l });
    const m = MONO_RE.exec(l);
    if (m) {
      const name = firstTok(m[6]);
      const e = { fill: +m[3], scale: +m[2], branch: m[4], edge: +m[5], line: l, arch: !!m[1] };
      const prev = mono.get(name);
      // 同一张既有冷的也有留档：留冷的（冷那一路就是当下算出来的）
      if (!prev || (prev.arch && !e.arch)) mono.set(name, e);
    }
  }
  const rows = [];
  for (const [name, c] of color) {
    const m = mono.get(name);
    if (m) rows.push({ name, c, m });
  }
  // 名字不同但同包的（彩色按资源名、单色按入口名）：兜一层包名再配
  const pkg = (s) => s.split('/')[0];
  const monoByPkg = new Map();
  for (const [n, m] of mono) if (!rows.some((r) => r.m === m)) monoByPkg.set(pkg(n), m);
  const loose = [];
  for (const [name, c] of color) {
    if (rows.some((r) => r.name === name)) continue;
    const m = monoByPkg.get(pkg(name));
    if (m) loose.push({ name, c, m, byPkg: true });
  }
  const all = rows.concat(loose);
  const bad = all.filter((r) => r.c.branch !== r.m.branch ||
    Math.abs(r.c.scale - r.m.scale) > tol || Math.abs(r.c.fill - r.m.fill) > tol * 100);
  return { all, bad, colorOnly: color.size - all.length, monoOnly: mono.size - all.length, exact: rows.length };
}

function report(tag, res) {
  console.log(`${tag}：配上 ${res.all.length} 对（同名 ${res.exact}、按包兜底 ${res.all.length - res.exact}）、` +
    `只有彩色 ${res.colorOnly}、只有单色 ${res.monoOnly}、**不一致 ${res.bad.length}**`);
  for (const r of res.bad) {
    console.log(`  ${r.name.slice(0, 52).padEnd(52)} 彩 ${r.c.branch}×${r.c.scale.toFixed(3)}/fill ${r.c.fill}` +
      ` | 单 ${r.m.branch}×${r.m.scale.toFixed(3)}/fill ${r.m.fill} (edge=${r.m.edge})`);
  }
  return res;
}

if (args.includes('--selftest')) {
  const good = ['自适应几何 host=L fill=88 scale=1.42 支=延伸 : a.pkg/ic_x',
    'Mono: 跟彩色 scale=1.42 fill=88 支=延伸 edge=384 label=a.pkg/ic_x 盘=0',
    '自适应几何 host=L fill=91 scale=1.0 支=延伸 : c.pkg/ic_v',
    'Mono: 跟彩色(留档) scale=1.0 fill=91 支=延伸 edge=384 label=c.pkg/ic_v 盘=0'];
  const evil = ['自适应几何 host=L fill=61 scale=0.88 支=铺底 : b.pkg/ic_y',
    'Mono: 跟彩色 scale=1.06 fill=93 支=延伸 edge=384 label=b.pkg/ic_y 盘=0',
    '自适应几何 host=L fill=42 scale=0.64 支=铺底 : d.pkg/ic_w',
    'Mono: 跟彩色(留档) scale=1.0 fill=42 支=铺底 edge=384 label=d.pkg/ic_w 盘=0'];
  const g = report('自证·该绿的样本', pair(good, 0.02));
  const e = report('自证·该红的样本', pair(evil, 0.02));
  // 两条都得是 2 对：`(留档)` 那一路要是解析不上，样本会**静默少配一对**而"全绿"——那正是这台子的死法。
  const ok = g.all.length === 2 && g.bad.length === 0 && e.all.length === 2 && e.bad.length === 2;
  console.log(ok ? '自证通过：冷/留档两路都配得上对、也都判得红' : '自证失败：台子坏了，别信它的读数');
  process.exit(ok ? 0 : 1);
}

const file = args.find((a) => !a.startsWith('--'));
if (!file) { console.log('用法：node _harness/_geompair.cjs <logcat 文件> [--tol=0.02] | --selftest'); process.exit(2); }
const tol = +((args.find((a) => a.startsWith('--tol=')) || '--tol=0.02').split('=')[1]);
const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
const res = report(file, pair(lines, tol));
if (res.all.length === 0) {
  console.log('!! 一张都没配上 —— 这不是"没问题"，是这台子这一轮**没吃到数据**（先确认装的是新包、桌面重启过）');
  process.exit(1);
}
if (res.bad.length) { console.log(`!! ${res.bad.length} 张单色与彩色的几何对不上（容差 ${tol}）`); process.exit(1); }
console.log(`✅ ${res.all.length} 张逐张一致（容差 ${tol}）`);
