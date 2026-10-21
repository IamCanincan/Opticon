/*
 * 读数量具：一次重启里 `state=`（ConstantState 身份）与 `键=`（内容键）的关系。
 * 为什么单独一个文件：这轮我已经把 positional group 的正则写错**三次**（丢小时、组号错位、
 * 反斜杠多一层），每一次读数都看着完全健康。所以这里只按字段单独抓，并在开头自检：
 * 带键的行数必须 > 0、state 必须解出来、pid 必须能数出来 —— 任一为空就非零退出。
 * 用法：node _statekey.cjs <日志文件>
 */
const fs = require('fs');
const file = process.argv[2];
if (!file || !fs.existsSync(file)) { console.log('!! 没有文件：' + file); process.exit(1); }
const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);

const grab = (l, name) => {
  const i = l.indexOf(name + '=');
  if (i < 0) return null;
  const j = l.indexOf(' ', i);
  return j < 0 ? l.slice(i + name.length + 1) : l.slice(i + name.length + 1, j);
};
const rows = [];
for (const l of lines) {
  if (l.indexOf('Mono: diag#') < 0) continue;
  const h = l.match(/^(\d\d-\d\d) (\d\d):(\d\d):(\d\d)\.(\d\d\d)\s+(\d+)\s+(\d+)/);
  if (!h) continue;
  // ⚠ 组号：1=日期 2=时 3=分 4=秒 5=毫秒 **6=PID 7=TID**。这里第四次踩同一个坑（把毫秒当 pid），
  //   所以把口径写死在代码旁边：pid 分布那行必须出现一个几百行的大进程，否则就是又读串了。
  rows.push({
    pid: h[6], tid: h[7],
    t: +h[2] * 3600000 + +h[3] * 60000 + +h[4] * 1000 + +h[5],
    edge: grab(l, 'edge'), sig: grab(l, 'sig'), key: grab(l, '键'), state: grab(l, 'state'),
    hit: l.indexOf('缓存=') >= 0,
    from: grab(l, '缓存'),
    pkg: (grab(l, 'edge') ? l.slice(l.indexOf('edge=')).split(' ')[0] : '') &&
      (l.match(/ (?:sig=[0-9a-f]{12} )?(?:键=[0-9a-f]+ )?(\S+?\/\S+)/) || [, null])[1],
  });
}

// ── 自检三道 ──
if (rows.length === 0) { console.log('!! 一行 Mono: diag# 都没抓到 —— 格式又变了，别看下面的数'); process.exit(1); }
const withKey = rows.filter(r => r.key !== null).length;
const withState = rows.filter(r => r.state !== null).length;
if (withKey === 0) { console.log('!! 没有任何 键= 字段 —— 这一版没带上读数（或解析没对上）'); process.exit(1); }
if (withState === 0) { console.log('!! 没有任何 state= 字段 —— 同上'); process.exit(1); }
console.log(`读数：diag 行 ${rows.length} | 带键 ${withKey} | 带 state ${withState}`);

const pidCount = {};
for (const r of rows) pidCount[r.pid] = (pidCount[r.pid] || 0) + 1;
const pid = Object.entries(pidCount).sort((a, b) => b[1] - a[1])[0][0];
const R = rows.filter(r => r.pid === pid);
console.log(`主进程 pid=${pid}（${R.length} 行）；其他 pid：` +
  Object.entries(pidCount).filter(([p]) => p !== pid).map(([p, n]) => p + ':' + n).join(' '));

// 命中/现算
const hits = R.filter(r => r.hit), cold = R.filter(r => !r.hit && r.sig);
console.log(`现算 ${cold.length} | 命中 ${hits.length}（内存 ${hits.filter(h => h.from === '内存').length}` +
  ` + 磁盘 ${hits.filter(h => h.from === '磁盘').length}）`);

// ① 省钱的一面：一个内容键下面有几个不同的 state
const keyStates = new Map(), stateKeys = new Map(), stateReqs = new Map();
for (const r of R) {
  if (!r.key) continue;
  if (!keyStates.has(r.key)) keyStates.set(r.key, new Set());
  keyStates.get(r.key).add(r.state);
  if (!stateKeys.has(r.state)) stateKeys.set(r.state, new Set());
  stateKeys.get(r.state).add(r.key);
  stateReqs.set(r.state, (stateReqs.get(r.state) || 0) + 1);
}
const hist = {};
for (const s of keyStates.values()) hist[s.size] = (hist[s.size] || 0) + 1;
console.log(`\n内容键 ${keyStates.size} 个，state ${stateKeys.size} 个，请求 ${R.filter(r => r.key).length} 次`);
console.log('每个键对应几个 state：' +
  Object.entries(hist).sort((a, b) => a[0] - b[0]).map(([n, c]) => `${n}个→${c}键`).join('  '));
const shared = [...stateReqs.values()].reduce((a, b) => a + b - 1, 0);
console.log(`若按 state 命中可省掉的重复 = ${shared} / ${R.filter(r => r.key).length} 次请求`);

// ② 否决票：一个 state 对出几个不同的键
const veto = [...stateKeys.entries()].filter(([, s]) => s.size > 1).sort((a, b) => b[1].size - a[1].size);
console.log(`\n一个 state 对出多个内容键的（否决票）：${veto.length} 个`);
for (const [st, ks] of veto.slice(0, 8)) console.log(`   state=${st} → ${ks.size} 个键`);
// 这些分叉是不是**同一个包**？跨包的分叉才危险
if (veto.length) {
  const detail = new Map();
  for (const r of R) {
    if (!r.key) continue;
    const k = r.state;
    if (!detail.has(k)) detail.set(k, new Map());
    const m = detail.get(k);
    if (!m.has(r.key)) m.set(r.key, new Set());
    m.get(r.key).add(r.pkg);
  }
  let crossPkg = 0, samePkg = 0;
  for (const [k, m] of detail) {
    if (m.size < 2) continue;
    const pkgs = new Set();
    for (const s of m.values()) for (const p of s) pkgs.add(p);
    const perKey = [...m.values()].map(s => s.size);
    if ([...m.values()].some(s => s.size > 1)) crossPkg++; else samePkg++;
  }
  console.log(`   其中"同一个 state 里出现同一包的两个键"= ${samePkg}，"键下带多个包"= ${crossPkg}`);
}
console.log(`\n结论口径：state 数远小于键数 → 有东西可省；否决票 > 0 → 那一刀不能按 state 直接命中。`);
