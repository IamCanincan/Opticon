// `_inkwhere.cjs` 的自证台：**账本级**——它改的是"判定吃的那份读数"，不碰生产源码。
//
// 分工：这道闸的判定（"盘有没有被铺进掩码"）整个住在 `_inkwhere.cjs` 里，
//   所以它的牙只能靠喂它不同读数来证。四条 + 一条元对照，对照全用**真产物**做底（不是我自己编的样本）。
//
// 跑法：node _harness/_inkwhere_mut.cjs
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const H = __dirname;
const TMP = 'D:/tmp/inkctl';
const SH = path.join(H, 'cand/_shotbit_mb.txt');
const SM = path.join(H, 'cand/_sm_mb.txt');
const TOOL = path.join(H, '_inkwhere.cjs');
for (const f of [SH, SM]) if (!fs.existsSync(f)) { console.log(`!! 少了真产物 ${f}（先跑 MaskBench 那两次）`); process.exit(2); }

fs.rmSync(TMP, { recursive: true, force: true });
fs.mkdirSync(TMP, { recursive: true });
const shText = fs.readFileSync(SH, 'utf8');
const smText = fs.readFileSync(SM, 'utf8');
const put = (n, t) => fs.writeFileSync(path.join(TMP, n), t);
const pin = path.join(TMP, 'ink.pin');
const run = (args) => {
  const r = spawnSync('node', [TOOL, ...args], { cwd: H, encoding: 'utf8' });
  return { rc: r.status, out: (r.stdout || '') + (r.stderr || '') };
};
const args = (sh, sm, extra) => [`--sh=${sh}`, `--sm=${sm}`, `--pin=${pin}`, ...(extra || [])];

let bad = 0;
const check = (no, name, r, want, need) => {
  let why = '';
  if (r.rc !== want) why = `退码 ${r.rc} ≠ 期望 ${want}`;
  else if (need && !r.out.includes(need)) why = `没说出那句「${need}」`;
  console.log(`${why ? '!! ' : '✓  '}${no} ${name}${why ? ' —— ' + why : ''}`);
  if (why) bad++;
};

// 1 正对照：真产物 + 现钉的基线 ⇒ 必须放行（先 --update 一次把基线钉在真产物上）
put('sh.txt', shText); put('sm.txt', smText);
let r = run(args(path.join(TMP, 'sh.txt'), path.join(TMP, 'sm.txt'), ['--update']));
check(1, '真产物上钉基线（--update）', r, 0, '已把这一轮钉成新基线');
r = run(args(path.join(TMP, 'sh.txt'), path.join(TMP, 'sm.txt')));
check(2, '同一份真产物再跑 ⇒ 与基线逐字相同', r, 0, '与基线逐字相同');

// 3 把 shortcut_bit 里某张的 框内 改成 0.95 —— 这就是"盘被铺进掩码"的形状，必须红
const mutated = shText.replace(/框内=0\.(\d+)/, '框内=0.95');
if (mutated === shText) { console.log('!! 变异没落地（没找到 框内= 那一列）'); bad++; }
put('sh_bad.txt', mutated);
r = run(args(path.join(TMP, 'sh_bad.txt'), path.join(TMP, 'sm.txt')));
check(3, '把一张的框内改成 0.95 ⇒ 必须红并说"盘被铺进掩码"', r, 1, '盘被铺进掩码');

// 4 造一次"读数漂移"（中位改一位）⇒ 与基线不再逐字相同 ⇒ 必须红
put('sh_drift.txt', shText.replace(/框内=0\.(\d+)/, '框内=0.11'));
r = run(args(path.join(TMP, 'sh_drift.txt'), path.join(TMP, 'sm.txt')));
check(4, '只是中位连一位都不许飘 ⇒ 必须红', r, 1, '读数动了');

// 5 输入不在 ⇒ 退 2（不许把"一张都没量"读成"0 张满幅 ⇒ 合格"）
r = run(args(path.join(TMP, 'nope.txt'), path.join(TMP, 'sm.txt')));
check(5, '输入文件不在 ⇒ 退 2，不许当"0 张"', r, 2, '一张都没量');

// ⚑ 元对照：**证明不变量与基线是两件事**。把不变量那句摘掉、再把基线钉在"已经漏了盘"的那份读数上 ——
//   这时朴素版只能答"与基线相同"（= 放行），而真版仍必须红。要是朴素版也红，就说明第 3 条其实是 pin 抓的，
//   不变量那句话白写。
const real = fs.readFileSync(TOOL, 'utf8');
const GUARD = 'if (s.full > 0) {';
if (!real.includes(GUARD)) { console.log('!! 元对照开不了工：`_inkwhere.cjs` 里那句不变量的原文变了'); bad++; }
else {
  const naive = path.join(TMP, 'inkwhere_naive.cjs');
  fs.writeFileSync(naive, real.replace(GUARD, 'if (false) {'));
  const pin2 = path.join(TMP, 'ink2.pin');
  const A = [`--sh=${path.join(TMP, 'sh_bad.txt')}`, `--sm=${path.join(TMP, 'sm.txt')}`, `--pin=${pin2}`];
  const n1 = spawnSync('node', [naive, ...A, '--update'], { cwd: H, encoding: 'utf8' });
  const n2 = spawnSync('node', [naive, ...A], { cwd: H, encoding: 'utf8' });
  const g2 = run([...A.slice(0, 2), `--pin=${pin2}`]);
  const okNaive = n1.status === 0 && n2.status === 0;      // 基线钉在"漏了盘"的那份上 ⇒ 朴素版放行（瞎）
  const okReal = g2.rc === 1 && g2.out.includes('盘被铺进掩码');
  console.log(`${okNaive && okReal ? '✓ ' : '!!'} 元对照：把基线钉在"漏了盘"的读数上 —— ` +
    `朴素版退码 ${n2.status}（必须 0，证明 pin 看不见这件事），真版退码 ${g2.rc} 且点名（必须 1 + 那句）`);
  if (!(okNaive && okReal)) bad++;
}
console.log(bad ? `\n!! ${bad} 条不成立` : '\n全部成立（现场 D:/tmp/inkctl）');
process.exit(bad ? 1 : 0);
