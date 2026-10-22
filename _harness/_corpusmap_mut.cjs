// `_corpusmap.cjs` 的自证台：它默认答案是"全绿"，所以必须先证明**它能红**。
//
// 为什么单独一台：这台子的用途就是回答"覆盖到哪一类了"，而它读的是 `regress.sh` 与 `*gate.sh` 的**文本**。
//   我第一版就被自己的正则坑过：`T_BIT="${SHOTBITCORPUS:-shortcut_bit}"` 里 `=` 后面有个引号，
//   正则不认 ⇒ `shortcut_bit` 被报成"没进第 19 闸"—— **一个假警报**，
//   反过来它也会把真漏了的说成"进了"。所以这里的控制项就是这两头的：改名要红、齐全要绿。
//
// 跑法：node _harness/_corpusmap_mut.cjs     （现场全在 D:/tmp/cmapctl，一行不写进仓库）
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const TOOL = path.join(__dirname, '_corpusmap.cjs');
const TMP = 'D:/tmp/cmapctl';
const KNOWN = ['corpus_audit3', 'rec3u', 'corpus_dev', 'corpus_dev2'];   // 与工具里那张名单同义

const w = (rel, text) => {
  const p = path.join(TMP, ...rel.split('/'));
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, text);
};
const mkCorpus = (name) => w(name + '/index.tsv', 'pkg\tlabel\tfile\n');
function setup(conf) {
  fs.rmSync(TMP, { recursive: true, force: true });
  fs.mkdirSync(TMP, { recursive: true });
  fs.copyFileSync(TOOL, path.join(TMP, '_corpusmap.cjs'));
  for (const d of conf.corpora) mkCorpus(d);
  w('regress.sh', 'echo "== 1/1 测试段 ==\n' + (conf.regress || '') + '\n');
  w('adhoc.cjs', conf.progs || '');            // 一份"程序"，决定谁算在册
}
const run = () => {
  const r = spawnSync('node', ['_corpusmap.cjs'], { cwd: TMP, encoding: 'utf8' });
  return { rc: r.status, out: (r.stdout || '') + (r.stderr || '') };
};

const CASES = [
  {
    no: 1, name: '一份在册语料没进任何闸 ⇒ 必须红并点名', want: 1, need: '新冒出 1 份',
    conf: {
      corpora: ['alpha', 'beta', ...KNOWN],
      regress: 'CORPUS=${CORPUS:-alpha}\nrun harness.X "$CORPUS"',
      progs: '// beta 也被程序引用（否则它算临时件，不算在册）\nconst d = "beta";',
    },
    why: '这是这台子存在的理由：少接一份分母要当场看得见'
  },
  {
    no: 2, name: '同一份**没有任何程序引用** ⇒ 降为临时件、不再红', want: 0, need: '不计',
    conf: { corpora: ['alpha', 'beta', ...KNOWN], regress: 'CORPUS=${CORPUS:-alpha}\nrun harness.X "$CORPUS"', progs: '' },
    why: '证"在册"这个判据真在起作用（不是"盘上有目录就报"），也否掉噪声淹死真漏那种写法'
  },
  {
    no: 3, name: '该有的都有、每条都被吃 ⇒ 必须放行', want: 0, need: 'beta',
    conf: {
      corpora: ['alpha', 'beta', ...KNOWN],
      regress: 'CORPUS=${CORPUS:-alpha}\nBETA="${BETA:-beta}"\nrun harness.X "$CORPUS" "$BETA"',
      progs: 'const k = ["beta","corpus_audit3","rec3u","corpus_dev","corpus_dev2"];',
    },
    why: '否掉"恒红"。这一条同时验别名 `$BETA` 必须被展开 —— 第一版没认 `="…:-…}"` 里那个引号，报了个假警报'
  },
  {
    no: 4, name: '名单里那几条**已经不在这台机器上** ⇒ 必须红（另一边也要查）', want: 1, need: '已经不在了',
    conf: {
      corpora: ['alpha', 'beta'],
      regress: 'CORPUS=${CORPUS:-alpha}\nBETA="${BETA:-beta}"\nrun harness.X "$CORPUS" "$BETA"',
      progs: 'const d = "beta";',
    },
    why: '只查"新冒出来"不查"理由作废"的表，会把删掉的语料当成自由文本'
  },
];

let bad = 0;
for (const c of CASES) {
  setup(c.conf);
  const r = run();
  let why = '';
  if (r.rc !== c.want) why = `退码 ${r.rc} ≠ 期望 ${c.want}`;
  else if (c.need && !r.out.includes(c.need)) why = `退码对，但没点名「${c.need}」`;
  else if (c.absent && r.out.includes(c.absent)) why = `不该再出现的那句还在：「${c.absent}」`;
  console.log(`${why ? '!! ' : '✓  '}${c.no} ${c.name}${why ? ' —— ' + why : ''}\n     ${c.why}`);
  if (why) bad++;
}
// ⚑ 元对照：把"没进闸就红"那一句摘掉（`process.exit(... ? 1 : 0)` 改成恒 0），第 1 条必须变成放行。
const real = fs.readFileSync(TOOL, 'utf8');
const GUARD = 'process.exit(fresh.length || vanished.length || undefBad.length ? 1 : 0);';
if (!real.includes(GUARD)) {
  console.log('!! 元对照开不了工：_corpusmap.cjs 那句退码的原文变了 —— 先改这台子');
  bad++;
} else {
  setup(CASES[0].conf);
  w('_corpusmap.cjs', real.replace(GUARD, 'process.exit(0);'));
  const n = run();
  const ok = n.rc === 0;
  console.log(`${ok ? '✓ ' : '!!'} 元对照：把"没进闸就红"摘掉之后第 1 条退码 ${n.rc}（必须 0）`);
  if (!ok) bad++;
}
console.log(bad ? `\n!! ${bad} 条不成立` : '\n全部成立（现场 D:/tmp/cmapctl）');
process.exit(bad ? 1 : 0);
