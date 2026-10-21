// **同一张 drawable，桌面在同一次加载里按两种 intrinsic 各画了一遍** —— 直接从真机日志量这一轴。
//
// 为什么这条能结案而 `_pairvar.cjs` 不能：盘上那 13 对"多真张"跨了好几轮抓取，
// 两轮之间**模块本身也换过版本**，所以它们的差里混着构建漂移。而**同一次加载**里的两个
// `Mono: diag` 行是同一个 APK、同一分钟、同一个图标，唯一的差别就是桌面递进来的那张多大。
// 那正是"分档会不会随尺寸掉"要的那个 A/B，而且它不用改代码、不用重抓。
//
// 用法：node _intrvar.cjs [日志] [--pid=12576] [--tsv=快照文件]
//
// ⚠ `--tsv` 是把这一轮**每个有名字的图标的喂入尺寸与判据**钉成一张表。为什么要有这张表：
//   "同一图标被桌面按两种尺寸各画一遍"这件事只能在**同一个构建**的两轮加载之间比较才干净
//   （盘上那些跨轮的多 sig 样本混着模块自己的改动，量出来的差不能归给尺寸）。
//   而 logcat 缓冲一重启就没了 —— 不此刻落盘，下一轮就没有对手。
const fs = require('fs');
const LOG = process.argv[2] && !process.argv[2].startsWith('--') ? process.argv[2] : '_devlog.txt';
const ONLY_PID = (process.argv.find(a => a.startsWith('--pid=')) || '').split('--pid=')[1] || null;

const g = (s, k) => ((s.match(new RegExp('(?:^| )' + k + '=([^ ]*)')) || [])[1] || '-');
const rows = fs.readFileSync(LOG, 'utf8').split('\n')
  .filter(l => /Mono: diag/.test(l) && l.includes('盘实='))
  .filter(l => !ONLY_PID || l.split(/\s+/)[2] === ONLY_PID);
if (rows.length === 0) { console.log('!! 这个 pid 里没有带判据的 diag 行（分母 0，不当结论）'); process.exit(1); }

// 键 = 包名/资源名；值 = sig -> {intrinsic, bounds, cls, 判据}
const F = ['分档', '底占', '淡', '框内', '色驱', '盘实', 'H', '调跨'];
const by = new Map();
let noName = 0, sawIntr = 0;
for (const l of rows) {
  const m = l.match(/sig=([0-9a-f]{12}) 键=\S+ (.*?) 票=/);
  if (!m) { noName++; continue; }
  const [, sig, tail] = m;
  const name = tail.split(' ')[0], cls = tail.split(' ')[1] || '?';
  if (!/\//.test(name)) { noName++; continue; }        // 解不出包名的快捷方式一律丢，别混进同一键
  // 喂入尺寸在这一段里有两种写法：非自适应是 `intrinsic=256x256`，自适应是
  // `fg=BitmapDrawable@432x432 intrinsic=288x288` —— 两个都要收，少收一个就把"真的换了尺寸"读成"没换"。
  const sizes = [...new Set((tail.match(/intrinsic=\d+x\d+|@\d+x\d+/g) || []))].sort().join('+');
  if (/intrinsic=/.test(tail)) sawIntr++;
  if (!by.has(name)) by.set(name, new Map());
  const s = by.get(name);
  if (!s.has(sig)) s.set(sig, { sizes, cls, tail, f: Object.fromEntries(F.map(k => [k, g(l, k)])) });
}
// ⚠ 正对照：这把尺必须看得见 `intrinsic=`，也必须在某处解出过尺寸 —— 否则下面的"0 个"是仪器瞎，不是没有。
if (sawIntr === 0) { console.log('!! 一条 `intrinsic=` 都没解析到 —— 这一台的"0 个"不算结论'); process.exit(1); }

console.log(`== 同一图标、同一次加载、两种喂入尺寸（pid ${ONLY_PID || '全部'}，${rows.length} 行有判据，${noName} 行标签解不出来）==`);
const multi = [...by].filter(([, s]) => s.size >= 2);
console.log(`  **同一个 包/资源 在日志里给出 ≥2 个 sig 的：${multi.length} 个**`);
let sizeDiff = 0, sameSize = 0;
for (const [name, s] of multi) {
  const list = [...s.entries()];
  const sizes = new Set(list.map(([, r]) => r.sizes));
  if (sizes.size === 1) { sameSize++; continue; }
  sizeDiff++;
  console.log(`  ${name}  ——  ${list.length} 个 sig / ${sizes.size} 种喂入尺寸`);
  for (const [sig, r] of list)
    console.log(`     ${(r.sizes || '?').padEnd(26)} ${(r.f['分档'] || '-').padEnd(20)} 底占=${r.f['底占']} 淡=${r.f['淡']} 框内=${r.f['框内']} H=${r.f['H']} 盘实=${r.f['盘实']} sig=${sig}`);
  const lv = x => { const m = (x['分档'] || '').match(/^(\d+)\//); return m ? +m[1] : NaN; };
  const sp = Math.max(...list.map(([, r]) => lv(r.f))) - Math.min(...list.map(([, r]) => lv(r.f)));
  console.log(`     ⇒ 档数跨度 ${sp}${sp ? '  **同一次加载里，尺寸真的改了层次**' : '  档数一致'}` +
    `   （原始标签：${list.map(([, r]) => r.cls).join(' / ')}）`);
}
console.log(`== 结论：${multi.length} 个双 sig 图标里，喂入尺寸真的不同的 ${sizeDiff} 个、尺寸相同（= 别的差别）的 ${sameSize} 个 ==`);

// ── 快照 / 跨轮对照 ──
const TSV = (process.argv.find(a => a.startsWith('--tsv=')) || '').split('--tsv=')[1];
const VS = (process.argv.find(a => a.startsWith('--vs=')) || '').split('--vs=')[1];
if (TSV) {
  const out = ['name\tsig\tsizes\tcls\t' + F.join('\t')];
  for (const [name, s] of by) for (const [sig, r] of s)
    out.push([name, sig, r.sizes, r.cls, ...F.map(k => r.f[k])].join('\t'));
  fs.writeFileSync(TSV, out.join('\n') + '\n');
  console.log(`\n已钉快照：${TSV}（${out.length - 1} 行 / ${by.size} 个有名字的图标）—— 下一轮同一构建再点一次加载，用 --vs= 比`);
}
if (VS && fs.existsSync(VS)) {
  const prev = new Map();
  const pl = fs.readFileSync(VS, 'utf8').split('\n').slice(1).filter(x => x);
  for (const l of pl) { const c = l.split('\t'); if (!prev.has(c[0])) prev.set(c[0], []); prev.get(c[0]).push({ sig: c[1], sizes: c[2], lv: c[4] }); }
  let changed = 0, sameSig = 0, newSize = 0;
  for (const [name, s] of by) {
    const p = prev.get(name); if (!p) continue;
    const nowSigs = new Set(s.keys()), oldSigs = new Set(p.map(x => x.sig));
    const fresh = [...nowSigs].filter(x => !oldSigs.has(x));
    if (fresh.length === 0) { sameSig++; continue; }
    changed++;
    const szNew = [...s.entries()].filter(([sg]) => fresh.includes(sg));
    const allSizes = new Set([...p.map(x => x.sizes), ...szNew.map(([, r]) => r.sizes)]);
    if (allSizes.size > 1) newSize++;
    console.log(`  ${name}：这一轮多出一张真张 sig=${fresh.join(',')}` +
      ` 喂入 ${[...allSizes].join(' | ')}  档数 ${p.map(x => x.lv).join('/')} → ${[...s.values()].map(r => r.f['分档']).join('/')}` +
      `${allSizes.size > 1 ? '  **尺寸不同 = 纯尺寸漂移样本**' : '  尺寸相同（别的差别）'}`);
  }
  console.log(`\n== 跨轮（同构建）对照 ${by.size} 个共同图标：换了真张 ${changed} 个（其中喂入尺寸不同 ${newSize}）、sig 全同 ${sameSig} ==`);
}
