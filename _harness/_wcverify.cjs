// 缓存前后的**同一批图标**，裁圆自己报的读数必须逐字相同 —— 这条才是"缓存没串"的凭据。
//
// 命中率只证明"少算了"，不证明"算的是同一件事"。所以要比的是每张图的
// `裁了只剩 N%` / `裁了还剩 N%` / 倍数 `缩|放 X.XX×` / `入口=…` / `接管=…` 这些字段，
// 拿两个进程（装缓存前 vs 装缓存后）各自量过的那些行对着看。
//
// 🔴 **键必须带尺寸**（14:51 那一读就是被这条咬出来的）：同一个
// `youtube/drawable/ic_shortcut_search` 在 384 那联判「满幅」、在 192 那联判「量到没搬」，
// `sig` 还相同（它是 8×8 降采样指纹，**跨尺寸会撞车**）。只按名字 join 就会把"输入本来不同"
// 报成"缓存串了"。所以：
//  - 彩色那行取 `画布 WxH`（详情行）；
//  - 染色那行取 `edge=`；
//  - 没有尺寸的 `量一张` 摘要行**不按名字硬 join**，改成按图标统计「满幅 / 有留白」各几次
//    （多重集合对照 —— 它不需要知道尺寸，也就不可能因为不知道尺寸而说谎）。
//
// ⚠ 无包名的快捷方式（标签是 `?`）一律丢：几百张不同的快捷方式会全被归成同一个键。
// ⚠ `档=` 必须带前缀锚点，否则匹配到 **`分档=`**（它每次渲染本来就会动 ±1~5）。
// 用法：node _wcverify.cjs <日志> <基线pid> <对照pid>
const fs = require('fs');
const FILE = process.argv[2] || '_wc2.txt';
const OLD = process.argv[3], NEW = process.argv[4];
if (!OLD || !NEW) { console.log('用法：node _wcverify.cjs <日志> <基线pid> <对照pid>'); process.exit(2); }

const L = fs.readFileSync(FILE, 'utf8').split('\n');
const pidOf = (l) => l.split(/\s+/)[2];
const F = ['剩', '倍数', '入口', 'body', '图案', '扫描'];
const grab = (s, k) => {
  switch (k) {
    case '剩': return (s.match(/裁了只剩 (\d+)%/) || s.match(/裁了还剩 (\d+)%/) || [])[1] || '-';
    case '倍数': return (s.match(/(缩|放)(\d\.\d\d)×/) || []).slice(1).join('') || '-';
    case '入口': return (s.match(/入口=(\S+)/) || [])[1] || '-';
    case 'body': return (s.match(/body\/far=(\S+)/) || [])[1] || '-';
    case '图案': return (s.match(/图案 (\d+x\d+)/) || [])[1] || '-';
    case '扫描': return (s.match(/（扫描 (\d+x\d+)）/) || [])[1] || '-';
  }
  return '-';
};
const JF = ['几何', '档', '接管', '判剩', '分档', '底占', '框内', '淡', '拦', '盘实', 'sig'];
const jgrab = (s, k) => {
  if (k === '几何') return (s.match(/几何=(\S+)/) || [])[1] || '-';
  if (k === '档') return (s.match(/(?:^| )档=(\S+)/) || [])[1] || '-';
  if (k === 'sig') return (s.match(/sig=(\S+)/) || [])[1] || '-';
  const m = s.match(new RegExp('(?:^| )' + k + '=([^ ]*)'));
  return m ? m[1] : '-';
};
const whoOf = (l) => {
  const w = l.includes('裁圆外框:')
    ? (l.match(/裁圆外框: (\S+)/) || [])[1]
    : (l.match(/键=\S+ (\S+)\s/) || [])[1];
  return w && w.includes('/') ? w : null;      // `?` 那种解不出包名的直接丢
};

const detail = new Map(), summary = new Map(), judge = new Map();
for (const l of L) {
  const p = pidOf(l);
  if (p !== OLD && p !== NEW) continue;
  const who = whoOf(l);
  if (!who) continue;
  if (l.includes('裁圆外框:') && l.includes('画布 ')) {
    const canvas = (l.match(/画布 (\d+x\d+)/) || [])[1];
    const f = {}; F.forEach(k => (f[k] = grab(l, k)));
    put(detail, p, `${who} @${canvas}`, f);
  } else if (l.includes('裁圆外框:') && l.includes('量一张')) {
    const v = l.includes('满幅') ? '满幅' : '有留白';
    const m = summary.get(p) || new Map();
    summary.set(p, m);
    m.set(who + ' ' + v, (m.get(who + ' ' + v) || 0) + 1);
  } else if (l.includes('Mono: diag#') && l.includes('几何=')) {
    const edge = jgrab(l, 'edge');
    const f = {}; JF.forEach(k => (f[k] = jgrab(l, k)));
    put(judge, p, `${who} @edge${edge}`, f);
  }
}
const selfBad = [];
function put(map, p, key, f) {
  const m = map.get(p) || new Map();
  map.set(p, m);
  const prev = m.get(key);
  if (prev) {
    let hit = false;
    for (const k of Object.keys(f)) if (prev[k] !== f[k]) { prev[k] = '≠自身'; hit = true; }
    if (hit) selfBad.push(`${p} ${key}`);   // 同键同尺寸重复出现却读数不同 = 这台量具自己不确定
  } else m.set(key, f);
}

const KEYS = F.concat(JF);
for (const [tag, map, fields] of [
  ['彩色详情(带画布)', detail, F],
  ['染色判据(带edge)', judge, JF],
]) {
  const a = map.get(OLD) || new Map(), b = map.get(NEW) || new Map();
  const both = [...a.keys()].filter(k => b.has(k));
  console.log(`\n== ${tag}：pid ${OLD} ${a.size} 条、pid ${NEW} ${b.size} 条、同键同尺寸 ${both.length} 条 ==`);
  if (both.length === 0) { console.log('   !! 共同键 0 条 —— 没对照，这条结论不成立'); continue; }
  let same = 0, oneSide = 0, cmp = 0;
  const diffs = [];
  for (const k of both) {
    const x = a.get(k), y = b.get(k);
    const shared = fields.filter(f => x[f] !== '-' && y[f] !== '-');
    oneSide += fields.filter(f => (x[f] !== '-') !== (y[f] !== '-')).length;
    cmp += shared.length;
    const bad = shared.filter(f => x[f] !== y[f]);
    if (bad.length === 0 && shared.length > 0) same++;
    else if (bad.length) diffs.push({ k, x, y, bad });
  }
  console.log(`   逐字相同的键 ${same}/${both.length}（比了 ${cmp} 个字段）；有差异 ${diffs.length}；单边缺字段 ${oneSide} 处（不算差异，也不算通过）`);
  for (const d of diffs) {
    console.log(`   !! ${d.k}`);
    for (const f of d.bad) console.log(`        ${f}: ${OLD}=${d.x[f]}  ${NEW}=${d.y[f]}`);
  }
  console.log(`   只在一边出现：${OLD} 独有 ${a.size - both.length}、${NEW} 独有 ${b.size - both.length}` +
    `（屏不同/绑的次数不同 —— 但**「一边多一条详情行」正是缓存把满幅错判成开窗的形状**，所以这些键要能对上号）`);
  const onlyA = [...a.keys()].filter(k => !b.has(k)), onlyB = [...b.keys()].filter(k => !a.has(k));
  for (const [lbl, arr] of [[OLD, onlyA], [NEW, onlyB]]) {
    const c = new Map();
    for (const k of arr) { const n = k.split(' @')[0]; c.set(n, (c.get(n) || 0) + 1); }
    if (c.size) console.log(`     ${lbl} 独有涉及 ${c.size} 个图标：` + [...c].slice(0, 12).map(([n, m]) => `${n}×${m}`).join('、') + (c.size > 12 ? ' …' : ''));
  }
}

// `量一张` 摘要行不要尺寸：按图标比「满幅 / 有留白」各出现几次
const sa = summary.get(OLD) || new Map(), sb = summary.get(NEW) || new Map();
const names = new Set([...sa.keys(), ...sb.keys()].map(k => k.split(' ')[0]));
let vm = 0, vd = 0;
const vdiffs = [];
for (const n of names) {
  const x = `${n} 满幅`, y = `${n} 有留白`;
  const A = [sa.get(x) || 0, sa.get(y) || 0], B = [sb.get(x) || 0, sb.get(y) || 0];
  if (A[0] + A[1] === 0 || B[0] + B[1] === 0) continue;   // 一边根本没加载过这个图标
  vm++;
  // 只比**比例形状**？不比 —— 直接比次数：加载次数本来就随屏变，所以只报「一边全满幅、一边出现了有留白」这种**翻档**
  if ((A[1] > 0) !== (B[1] > 0)) { vd++; vdiffs.push(`${n}: ${OLD} 满幅${A[0]}/有留白${A[1]} → ${NEW} 满幅${B[0]}/有留白${B[1]}`); }
}
console.log(`\n== 量一张摘要（不比尺寸，只比这一张有没有从「满幅」翻成「有留白」）：两边都加载过的图标 ${vm} 个，翻档 ${vd} 个 ==`);
console.log('   ⚠ 这一条**只是线索不是判据**：同一张在 384/192 两联本来就允许不同档，而摘要行没有尺寸可对（所以它只能按图标聚合）。');
vdiffs.forEach(s => console.log('   ~ ' + s));
console.log(`\n== 自对照：同 pid 同键同尺寸重复出现却读数不同 = ${selfBad.length} 处 ==`);
selfBad.slice(0, 10).forEach(s => console.log('   !! ' + s));
