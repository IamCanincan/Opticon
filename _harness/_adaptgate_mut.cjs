// `adaptgate.sh` 的**输入级**自证台：证的是那两段闸的**入口门**，不是射程。
//
// 分工（别再混着引用）：`_semanticmut.cjs --case=20` 改的是**生产源码**那一行，证"射程守卫连着真刀"；
// 这一台子改的是**闸吃的那份分母在不在**，证"缺分母时它不跑、而且说得出缺哪份"。
// 为什么单独要一台：抽本体之前，变异台抄的那份 gateCmd 直接 `run …`，**入口门压根不在它的路径上**
//   —— 那正是本仓"闸测的是手抄副本"这一族（今天第四遍）。
//
// 跑法：node _harness/_adaptgate_mut.cjs      （现场全在 D:/tmp/adaptctl，一行都不写进仓库）
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const HARNESS = __dirname;                    // 本台子与 `adaptgate.sh`、两份 pin 同在 `_harness/`
const TMP = 'D:/tmp/adaptctl';
const VPIN = fs.readFileSync(path.join(HARNESS, 'bench/reg/veto.pin'), 'utf8').trim();
const APIN = fs.readFileSync(path.join(HARNESS, 'bench/reg/adapt.pin'), 'utf8').trim();

const w = (rel, text) => {
  const p = path.join(TMP, ...rel.split('/'));
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, text);
};
// `run` 是个**假驱动**：这一台子测的是闸体自己的分支，不是 VetoKt/AdaptKt 的读数。
//   它把"自己被调到"这件事打进 stdout，控制项就靠这句话分辨"门拦住了"还是"门放行了"。
const GATEENV = `run(){ c="$1"; shift; echo "RUN 被调到 $c"; case "$c" in\n` +
  ` *VetoKtKt)  [ -n "$FAKE_VETO"  ] && printf '%s\\n' "$FAKE_VETO";;\n` +
  ` *AdaptKtKt) [ -n "$FAKE_ADAPT" ] && printf '%s\\n' "$FAKE_ADAPT";;\n` +
  ` esac; }\n`;

function setup(body) {
  fs.rmSync(TMP, { recursive: true, force: true });
  fs.mkdirSync(path.join(TMP, 'bench/reg'), { recursive: true });
  w('gateenv.sh', GATEENV);
  w('adaptgate.sh', body || fs.readFileSync(path.join(HARNESS, 'adaptgate.sh'), 'utf8'));
  w('bench/reg/veto.pin', VPIN + '\n');
  w('bench/reg/adapt.pin', APIN + '\n');
}
function call(fn, env) {
  const e = Object.assign({}, process.env, env || {});
  const r = spawnSync('sh', ['-c', `. ./gateenv.sh; . ./adaptgate.sh; ${fn}_gate _ctl`],
    { cwd: TMP, encoding: 'utf8', env: e });
  // ⚠ 驱动的输出被闸体 `> bench/reg/<name>_ctl.txt 2>&1` **整股 redirect 进文件**了 ——
  //   所以"驱动有没有被调"这句话**不在 stdout 里**。以前我在 stdout 里找它，那条断言是**恒成立**的
  //   （找不到的东西永远找不到）：读文件才算数。
  const rawFile = path.join(TMP, 'bench', 'reg', (fn === 'veto' ? 'veto' : 'adapt') + '_ctl.txt');
  let raw = null;
  try { raw = fs.readFileSync(rawFile, 'utf8'); } catch (err) { raw = null; }
  return { rc: r.status, out: (r.stdout || '') + (r.stderr || ''), raw: raw === null ? '' : raw, sawRaw: raw !== null };
}

const CASES = [
  {
    no: 1, fn: 'veto', dir: false, want: 1, need: '缺 adapted_model/', noRun: true,
    why: '没分母 ⇒ 不跑、点名缺哪份，而且**账本文件都不该被创建**（创建了就说明驱动被调过 = 门没拦住）'
  },
  {
    no: 2, fn: 'adapt', dir: false, want: 1, need: '缺 adapted_model/', noRun: true,
    why: '第 20 闸同一道入口门，同一个说法'
  },
  {
    no: 3, fn: 'veto', dir: 'empty', want: 1, need: '没出机器行', ran: true,
    why: '**目录在但没有图**不算过关：门放行、驱动跑了、没出机器行 ⇒ 仍非零（否则"空目录"就是一张免费的绿）'
  },
  {
    no: 4, fn: 'veto', dir: 'empty', want: 0, fake: { FAKE_VETO: VPIN }, ran: true,
    why: '该有的都有（目录在、机器行与 pin 逐字相同）⇒ 必须放行。这条否掉"恒红"的写法'
  },
  {
    no: 5, fn: 'adapt', dir: 'empty', want: 0, fake: { FAKE_ADAPT: APIN }, ran: true,
    why: '第 20 闸同样要能绿'
  },
];

let bad = 0;
const log = [];
for (const c of CASES) {
  setup();
  if (c.dir === 'empty') fs.mkdirSync(path.join(TMP, 'adapted_model'), { recursive: true });
  const r = call(c.fn, c.fake);
  let why = '';
  if (r.rc !== c.want) why = `退码 ${r.rc} ≠ 期望 ${c.want}`;
  else if (c.need && !r.out.includes(c.need)) why = `退码对，但没说出那句「${c.need}」`;
  else if (c.noRun && r.sawRaw) why = '门没拦住：账本文件被创建了（里面有 ' + r.raw.split(/\r?\n/)[0] + '）';
  else if (c.ran && !r.raw.includes('RUN 被调到')) why = `门该放行，可驱动没被调（文件${r.sawRaw ? '在但没那句话' : '根本没创建'}）`;
  console.log(`${why ? '!! ' : '✓  '}${c.no} ${c.why}${why ? ' —— ' + why : ''}`);
  log.push(`=== ${c.no} ${c.why}\n退码 ${r.rc}\n--- stdout ---\n${r.out}\n--- 账本文件 ---\n${r.raw}\n`);
  if (why) bad++;
}

// ⚑ **元对照**：把入口门整条摘掉（`[ -d adapted_model ] || { … return 1; }` → `:`），
//   第 1 条必须变样（要么退 0、要么驱动被调）—— 它要是还拦住，第 1 条测的就不是那道门。
const real = fs.readFileSync(path.join(HARNESS, 'adaptgate.sh'), 'utf8');
const GUARD = `  [ -d adapted_model ] || {\n    echo "!! 缺 adapted_model/ —— 自画层射程这一闸没有分母（重建：node _mkrec_model.cjs <抓取目录> adapted_model --only=adapted）"\n    return 1; }`;
if (!real.includes(GUARD)) {
  console.log('!! 元对照开不了工：adaptgate.sh 里那道入口门的原文变了 —— 先改这台子');
  bad++;
} else {
  setup(real.replace(GUARD, '  :'));
  const n = call('veto');
  const meta = n.rc !== 1 || !n.out.includes('缺 adapted_model/');
  console.log(`${meta ? '✓ ' : '!!'} 元对照：摘掉入口门之后第 1 条退码 ${n.rc}、还有没有那句点名 = ${n.out.includes('缺 adapted_model/')}`);
  log.push(`=== 元对照（摘掉入口门）\n退码 ${n.rc}\n--- 输出 ---\n${n.out}\n`);
  if (!meta) bad++;
}
fs.writeFileSync('D:/tmp/adaptctl_out.txt', log.join('\n'));
console.log(bad ? `\n!! ${bad} 条不成立 —— 逐条输出 D:/tmp/adaptctl_out.txt` : '\n全部成立（现场 D:/tmp/adaptctl，日志 D:/tmp/adaptctl_out.txt）');
process.exit(bad ? 1 : 0);
