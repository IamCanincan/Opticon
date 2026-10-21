// 第 18 闸的**变异自证**（照 `_grey_mut.cjs` / `_notifyguardmut.cjs` 的规矩：
// 每条变异都要自证"恰好命中 N 行 + 文件真的变了"，否则"全部通过"里可能藏着一条在测空气）。
// 跑法：`node _flatmut.cjs`
const fs = require('fs');
const { spawnSync } = require('child_process');
const IN = 'bench/reg/flatin.txt';
const FLOOR = fs.readFileSync('bench/reg/flatin.pin', 'utf8').trim();
const raw = fs.readFileSync(IN, 'utf8').split(/\r?\n/);
const TMP = 'bench/reg/_flat_mut.txt';
const LINE = /坡=(\d+)块\/(\d+)格/;
const rows = raw.map((l, i) => (LINE.test(l) ? i : -1)).filter(i => i >= 0);
if (rows.length < 3) { console.error(`!! 只有 ${rows.length} 行带 〔平块〕 —— 变异台子没有可改的行，先查 ${IN}`); process.exit(2); }

function run(text, floor) {
  fs.writeFileSync(TMP, text);
  const r = spawnSync(process.execPath, ['_flat.cjs', `--in=${TMP}`, `--floor=${floor === undefined ? FLOOR : floor}`],
    { encoding: 'utf8', cwd: __dirname });
  return { rc: r.status, out: (r.stdout + r.stderr).split(/\r?\n/).filter(l => /!!|PRESS-FLAT/.test(l)).join(' | ') };
}
/** 在第 `which` 个可改行上，把 坡块 加 dB、坡格 加 dC。 */
function mutate(which, dB, dC) {
  const out = raw.slice();
  const i = rows[which];
  const before = out[i];
  out[i] = before.replace(LINE, (m, b, c) => `坡=${+b + dB}块/${+c + dC}格`);
  return { text: out.join('\n'), hits: out[i] !== before ? 1 : 0, changed: out.join('\n') !== raw.join('\n') };
}
const CASES = [
  ['原表（负对照：什么都没改）', null, 0, null, undefined],
  ['正对照：块与格一起涨', () => mutate(0, 3, 500), 1, 'blocks', undefined],
  ['只涨格、块不动（证明 cells 那一半有人守）', () => mutate(1, 0, 500), 1, 'cells', undefined],
  ['只涨块、格不动（证明 blocks 那一半有人守）', () => mutate(2, 3, 0), 1, 'blocks', undefined],
  ['零行（删掉所有 〔平块〕 行）= 装置坏了，不许读成"干净"',
    () => ({ text: raw.filter(l => !l.includes('坡=')).join('\n'), hits: 1, changed: true }), 2, '一行', undefined],
  ['坏地板（缺 pics= 那一半）', null, 2, '没有 pics', 'blocks=21 cells=31001'],
];
let bad = 0;
for (const [nm, mk, wantRC, wantMsg, floor] of CASES) {
  if (mk) {
    const m = mk();
    if (!m.changed || (nm.startsWith('正') || nm.startsWith('只')) && m.hits !== 1) {
      console.log(`✗ ${nm} —— **变异没落地**（命中 ${m.hits}、changed=${m.changed}）：这条测的是空气`);
      bad++; continue;
    }
  }
  const r = run(mk ? mk().text : raw.join('\n'), floor);
  const ok = r.rc === wantRC && (!wantMsg || r.out.includes(wantMsg));
  console.log(`${ok ? '✓' : '✗'} ${nm}\n    退码 ${r.rc}（要 ${wantRC}）  ${r.out.slice(0, 130)}`);
  if (!ok) bad++;
}
const miss = spawnSync(process.execPath, ['_flat.cjs', '--in=bench/reg/_does_not_exist.txt'],
  { encoding: 'utf8', cwd: __dirname });
const okMiss = miss.status === 2 && (miss.stdout + miss.stderr).includes('没跑');
console.log(`${okMiss ? '✓' : '✗'} 缺文件 → 退 2（不许把"没有读数"读成"这一族干净"）  退码 ${miss.status}（要 2）`);
if (!okMiss) bad++;
if (fs.existsSync(TMP)) fs.unlinkSync(TMP);
console.log(bad ? `\n!! ${bad} 条不成立` : '\n全部成立：两个数各自独立能响；零行 / 缺文件 / 坏地板一律拒绝判绿');
process.exit(bad ? 1 : 0);
