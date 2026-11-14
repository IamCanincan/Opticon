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
// `窗=/交=` 是 2026-11-15 晚上补的第三、第四个数：**彩色的 scale 是相对原始内容的，而掩码相对原始内容
//   已经落在 `窗`（裁圆自己那道缩量）上了，所以交付真正乘的必须是 `交 = scale / 窗`。**
//   这一格是可选的 —— 留档里旧行没这两个数，旧行照样要能配上对（否则台子对历史静默失明）。
const MONO_RE = /Mono: 跟彩色(\(留档\))? scale=(-?[\d.]+) fill=(-?[\d.]+) 支=(延伸|铺底)(?: 窗=(-?[\d.]+) 交=(-?[\d.]+))? edge=(\d+) label=(\S+)/;

/** `label=` 后面是 describe() 整串（`名字 盘=… intrinsic=…`），第一段才是 IconNames.of(...)。 */
const firstTok = (s) => (s || '').split(' ')[0];

function pair(lines, tol) {
  const color = new Map(), mono = new Map();
  for (const l of lines) {
    const c = COLOR_RE.exec(l);
    if (c) color.set(c[5], { fill: +c[2], scale: +c[3], branch: c[4], line: l });
    const m = MONO_RE.exec(l);
    if (m) {
      const name = firstTok(m[8]);
      const e = {
        fill: +m[3], scale: +m[2], branch: m[4], edge: +m[7], line: l, arch: !!m[1],
        win: m[5] === undefined ? null : +m[5], apply: m[6] === undefined ? null : +m[6],
      };
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
  // 两类不一致分开报：**A** 两边算出的几何不同（判断分家）；**B** 单色自己那三个数乘不回去
  //   （`窗 × 交 ≠ scale` = 交付时同一道缩量乘了两遍，或除错了对象）。B 与彩色无关，
  //   只在带 `窗/交` 的行上才问 —— 旧留档没这两个数，不能拿"没有证据"当"证据为否"。
  const why = (r) => {
    if (r.c.branch !== r.m.branch) return '分支';
    if (Math.abs(r.c.scale - r.m.scale) > tol) return 'scale';
    if (Math.abs(r.c.fill - r.m.fill) > tol) return 'fill';
    if (r.m.win != null && Math.abs(r.m.win * r.m.apply - r.m.scale) > tol) return '窗×交≠scale';
    return null;
  };
  const bad = all.map((r) => ({ r, why: why(r) })).filter((x) => x.why);
  return { all, bad, colorOnly: color.size - all.length, monoOnly: mono.size - all.length, exact: rows.length };
}

function report(tag, res) {
  console.log(`${tag}：配上 ${res.all.length} 对（同名 ${res.exact}、按包兜底 ${res.all.length - res.exact}）、` +
    `只有彩色 ${res.colorOnly}、只有单色 ${res.monoOnly}、**不一致 ${res.bad.length}**`);
  for (const { r, why } of res.bad) {
    console.log(`  ${r.name.slice(0, 52).padEnd(52)} [${why}] 彩 ${r.c.branch}×${r.c.scale.toFixed(3)}/fill ${r.c.fill}` +
      ` | 单 ${r.m.branch}×${r.m.scale.toFixed(3)}/fill ${r.m.fill}` +
      (r.m.win == null ? '' : ` 窗=${r.m.win.toFixed(3)} 交=${r.m.apply.toFixed(3)}`) + ` (edge=${r.m.edge})`);
  }
  return res;
}

if (args.includes('--selftest')) {
  // 样本**逐字按真日志的形状写**（`fill=`/`scale=` 都是小数，`host=` 是宿主包名，
  // `label=` 后面还跟着 ` 盘=… intrinsic=…`）—— 自证位要是用自己编的形状，配上对只证了我抄的那份。
  // good 里 **c 故意不带 `窗/交`**：它是"旧留档行"的负对照 —— 新格式一写坏，
  // 这台子会对历史静默失明（配上 0 对却报"全绿"），这一行就是来抓它的。
  const good = [
    '自适应几何 host=com.android.launcher3 fill=0.88 scale=1.42 支=延伸 : a.pkg/drawable/ic_x',
    'Mono: 跟彩色 scale=1.42 fill=0.88 支=延伸 窗=1.42 交=1.0 edge=384 label=a.pkg/drawable/ic_x 盘=0 intrinsic=108x108',
    '自适应几何 host=com.android.launcher3 fill=0.91 scale=1.0 支=延伸 : c.pkg/drawable/ic_v',
    'Mono: 跟彩色(留档) scale=1.0 fill=0.91 支=延伸 edge=384 label=c.pkg/drawable/ic_v 盘=0',
    // 缩进那一侧（easytier 真机那行的数）：窗×交 = 0.57×1.12 = 0.6384 ≈ scale ✓
    '自适应几何 host=com.android.launcher3 fill=0.37 scale=0.64 支=铺底 : com.kkrainbow.easytier/drawable/ic_launcher',
    'Mono: 跟彩色 scale=0.64 fill=0.37 支=铺底 窗=0.57 交=1.12 edge=384 label=com.kkrainbow.easytier/drawable/ic_launcher 盘=1'];
  const evil = [
    '自适应几何 host=com.android.launcher3 fill=0.61 scale=0.88 支=铺底 : b.pkg/drawable/ic_y',
    'Mono: 跟彩色 scale=1.06 fill=0.93 支=延伸 窗=1.06 交=1.0 edge=384 label=b.pkg/drawable/ic_y 盘=0',
    // fill 单独错：scale/分支都对，只有占比分了家（这条以前**测不出来** —— 容差写成 tol×100=2，
    // 而 fill 天生是 0..1 的小数，任何两张图都"过"；2026-11-15 才发现这把闸是空的）
    '自适应几何 host=com.android.launcher3 fill=0.37 scale=1.0 支=铺底 : d.pkg/drawable/ic_w',
    'Mono: 跟彩色(留档) scale=1.0 fill=0.52 支=铺底 edge=384 label=d.pkg/drawable/ic_w 盘=0',
    // 这一条就是 2026-11-15 之前生产**真在做**的事：彩色算 0.64、窗口已经缩了 0.57，
    // 交付还照着 0.64 再乘一遍 ⇒ 单色上屏比彩色小 43%。彩色的数全对，只有乘回去不对。
    '自适应几何 host=com.android.launcher3 fill=0.42 scale=0.64 支=铺底 : g.pkg/drawable/ic_k',
    'Mono: 跟彩色 scale=0.64 fill=0.42 支=铺底 窗=0.57 交=0.64 edge=384 label=g.pkg/drawable/ic_k 盘=0'];
  const g = report('自证·该绿的样本', pair(good, 0.02));
  const e = report('自证·该红的样本', pair(evil, 0.02));
  // 三条都得吃满 3 对：`(留档)` 或新格式要是解析不上，样本会**静默少配**而"全绿"——那正是这台子的死法。
  const reasons = e.bad.map((x) => x.why).sort().join('+');
  const ok = g.all.length === 3 && g.bad.length === 0 &&
    e.all.length === 3 && reasons === 'fill+分支+窗×交≠scale';
  console.log(ok
    ? '自证通过：冷/留档/旧格式三路都配上对，三条各红在一条不同的律上（分支 / fill / 窗×交）'
    : `自证失败：台子坏了，别信它的读数（绿 ${g.all.length}/3、红 ${e.all.length}/3，红因=${reasons}）`);
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
