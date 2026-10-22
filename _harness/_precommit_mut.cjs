// `_harness/precommit.sh` 的**自证台**：在临时 `git init` 的仓库里造出各种现场，看它拦不拦。
//
// 为什么必须单独有这台：`precommit.sh` 的默认答案是**放行**，而"放行"有两种成因 ——
//   ① 真的没碰编不到的那些；② 清单没解析出来 / 名单抓错 / 那个 mtime 锚压根没用上。
//   ②那种放行与①长得一模一样（本仓那一族：空跑当通过）。所以每条都在 `D:/tmp/pcgate` 里现造，
//   **绝不动主人的工作区**，并且专门有一条"APK 存在就放行"的反面对照（第 5 条）。
//
// 跑法：node _harness/_precommit_mut.cjs     （跑完留着现场，逐条日志在 D:/tmp/pcgate_out.txt）
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const REPO = path.join(__dirname, '..');      // 真仓库：**只读两份文件当模板**，一个字都不写进去
const TMP = 'D:/tmp/pcgate';                  // 固定死，不做成参数 —— 免得 rm -rf 指到别处
const PKG = 'app/src/main/kotlin/com/iamcanincan/opticon/circle/';
const MASK = PKG + 'MonochromeMask.kt';       // build.sh 清单**里**的（判据台编得到）
const SYNTH = PKG + 'MonochromeSynth.kt';     // 清单**外**的（要 Android 类）
const APK = 'app/build/outputs/apk/release/app-release.apk';

const sh = (cmd, args, cwd) => {
  const r = spawnSync(cmd, args, { cwd, encoding: 'utf8' });
  return { rc: r.status, out: (r.stdout || '') + (r.stderr || '') };
};
const must = (cmd, args, cwd) => {
  const r = sh(cmd, args, cwd);
  if (r.rc !== 0) throw new Error(cmd + ' ' + args.join(' ') + ' 退 ' + r.rc + '：' + r.out.slice(0, 400));
  return r.out;
};
// 带着"距今 N 分钟"的时间提交 —— 锚用的是 `%ct`（committer date），所以两个都要给。
function commit(msg, minsAgo, stageAll) {
  if (stageAll !== false) must('git', ['add', '-A'], TMP);
  const t = new Date(Date.now() - (minsAgo === undefined ? 0 : minsAgo * 60000)).toISOString();
  const env = Object.assign({}, process.env, { GIT_AUTHOR_DATE: t, GIT_COMMITTER_DATE: t });
  const r = spawnSync('git', ['-c', 'user.email=t@t', '-c', 'user.name=t', 'commit', '-q', '-m', msg],
    { cwd: TMP, encoding: 'utf8', env });
  if (r.status !== 0) throw new Error('commit 失败：' + (r.stdout || '') + (r.stderr || ''));
}
const w = (rel, text) => {
  const p = path.join(TMP, ...rel.split('/'));
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, text);
};
const touch = (rel, minsAgo) => {
  // ⚠ **一律用"距今多少分钟"**，不写死几点几分：写死的时刻可能落在时钟的**未来**，
  //   那时"APK 比改动旧"这条对照会因为日期而假通过（本仓那条：自证位要拿真顺序，不拿我编的字面量）。
  const t = new Date(Date.now() - minsAgo * 60000);
  const p = path.join(TMP, ...rel.split('/'));
  fs.utimesSync(p, t, t);
};
const gate = () => sh('sh', ['_harness/precommit.sh'], TMP);

// 造一个最小仓库。⚠ `build.sh` 是从真仓库**原样拷**来的 —— 这台子要证的是"解析真清单"这件事，
// 不是我脑补的那份名单（本仓那条：自证控制必须逐字用真产物）。
function fresh(gateText) {
  fs.rmSync(TMP, { recursive: true, force: true });
  fs.mkdirSync(TMP, { recursive: true });
  must('git', ['init', '-q'], TMP);
  must('git', ['config', 'core.autocrlf', 'false'], TMP);
  w('_harness/precommit.sh', gateText || fs.readFileSync(path.join(REPO, '_harness/precommit.sh'), 'utf8'));
  w('_harness/build.sh', fs.readFileSync(path.join(REPO, '_harness/build.sh'), 'utf8'));
  w(MASK, 'class A\n');
  w(SYNTH, 'class B\n');
  w('_harness/regress.sh', 'echo x\n');
  w('.gitignore', 'app/build/\n');            // APK 产物本来就不入库
  // ⚠ **分两次提交**：先 `app/` 再 `_harness/`。闸现在也查"最近一次提交动过名单外那些"，
  //   要是 base 那一次就把两份一起加进来，HEAD 的名单里就永远躺着 `MonochromeSynth.kt`
  //   ⇒ 第 1 条"干净仓库 ⇒ 放行"会被它自己造的现场顶掉（**假红**，比假绿更会骗人，因为它每次都响）。
  must('git', ['add', 'app', '.gitignore'], TMP);
  commit('base: 生产源码', 240, false);
  must('git', ['add', '_harness'], TMP);
  commit('base: 判据台（HEAD 不含名单外的改动）', 200, false);
}

const SRC = 120;        // 改动发生在两小时前
const APK_NEW = 60;       // 打包在一小时前（比改动新 ⇒ 该放行）
const APK_OLD = 180;      // 打包在三小时前（比改动旧 ⇒ 该拦）

const CASES = [
  {
    no: 1, name: '干净仓库 ⇒ 放行', want: 0,
    set() {}, why: '默认答案必须是"能走"，否则没人会用它。'
  },
  {
    no: 2, name: '只碰清单里那份 MonochromeMask.kt ⇒ 放行', want: 0,
    set() { w(MASK, 'class A2\n'); touch(MASK, SRC); },
    why: '证"名单"真在起作用：编得到的那些，回归台绿就够了，不该逢改动就拦。'
  },
  {
    no: 3, name: '碰了编不到的 MonochromeSynth.kt、一个 APK 都没有 ⇒ 拦 + 点名', want: 1, need: 'MonochromeSynth.kt',
    set() { w(SYNTH, 'class B2\n'); touch(SYNTH, SRC); },
    why: '2026-10-17 那次的形状：判据台全绿、装机才发现编译不过。'
  },
  {
    no: 4, name: '同上，但 APK 比改动**新** ⇒ 放行', want: 0,
    set() { w(SYNTH, 'class B3\n'); touch(SYNTH, SRC); w(APK, 'apk\n'); touch(APK, APK_NEW); },
    why: '改过之后真跑过一次 gradle ⇒ 该放。这条否掉"逢改 synth 就拦"的另一种瞎。'
  },
  {
    no: 5, name: '同上，但 APK 比改动**旧** ⇒ 拦（反面对照）', want: 1, need: '不比它新',
    set() { w(SYNTH, 'class B4\n'); touch(SYNTH, SRC); w(APK, 'apk\n'); touch(APK, APK_OLD); },
    why: '⚠ 这条是**专门用来抓"只看 APK 在不在"的假实现**的：APK 明明存在，但它是上一版的。'
  },
  {
    no: 6, name: '**未跟踪**的新生产源码 + 旧 APK ⇒ 拦', want: 1, need: 'BrandNew.kt',
    set() { w(PKG + 'BrandNew.kt', 'class C\n'); touch(PKG + 'BrandNew.kt', SRC); w(APK, 'apk\n'); touch(APK, APK_OLD); },
    why: '新文件还没进 git 也要算 —— 漏掉未跟踪那半，闸就正好在最需要的时候不响。'
  },
  {
    no: 7, name: '删掉 MonochromeSynth.kt + 旧 APK ⇒ 拦（删除也是改动）', want: 1, need: 'MonochromeSynth.kt',
    set() { fs.rmSync(path.join(TMP, ...SYNTH.split('/'))); w(APK, 'apk\n'); touch(APK, APK_OLD); },
    why: '删一份编不到的源，编译一样会炸；没有 mtime 可用时锚退到 .git/index。'
  },
  {
    no: 8, name: '清单被掏空（build.sh 里那几行没了）⇒ 退 2，不许读成放行', want: 2, need: '瞎',
    set() { w('_harness/build.sh', '# 清单被删掉的 build.sh\nkotlinc 什么都不编\n'); },
    why: '这条证的是**仪器自己**：解析成 0 份时那句"没碰到编不到的"会恒真 ⇒ 必须报 2，不能报 0。'
  },
  {
    // 2026-10-22 自己抓出来的第二种现场：今天写完这道闸，回头一看 HEAD 里正躺着一份
    //   00:51 提交、00:04 之后从来没进过 gradle 的生产源码（`debug/CorpusCapture.kt`）—— 只看工作树会漏掉它。
    no: 9, name: '**已经提交**的那次改了名单外那份，APK 比那次提交旧 ⇒ 拦（并说"最近一次提交"）', want: 1, need: '最近一次提交',
    set() { w(SYNTH, 'class B5\n'); commit('改了编不到的那份', 120); w(APK, 'apk\n'); touch(APK, 180); },
    why: '工作树这时是**干净**的（改动已提交），只看 dirty 文件的闸会放行 ⇒ 这条证第二种现场真被看着。'
  },
  {
    no: 10, name: '同上，但 APK 比那次提交**新** ⇒ 放行', want: 0,
    set() { w(SYNTH, 'class B6\n'); commit('改了编不到的那份', 120); w(APK, 'apk\n'); touch(APK, 60); },
    why: '提交之后真跑过一次打包 ⇒ 该放。这条否掉"逢提交过编不到的就永远拦"那种会把自己饿死的闸。'
  },
];

let bad = 0;
const log = [];
for (const c of CASES) {
  fresh();
  c.set();
  const r = gate();
  let why = '';
  if (r.rc !== c.want) why = `退码 ${r.rc} ≠ 期望 ${c.want}`;
  else if (c.need && !r.out.includes(c.need)) why = `退码对，但输出里没有那句「${c.need}」（点名失败 = 拦了也不知道拦谁）`;
  console.log(`${why ? '!! ' : '✓  '}${c.no} ${c.name}${why ? ' —— ' + why : ''}`);
  log.push(`=== ${c.no} ${c.name}\n期望退码 ${c.want}\n实际退码 ${r.rc}\n--- 输出 ---\n${r.out}\n`);
  if (why) bad++;
}
// 收尾自证：**还原成干净之后必须回到 0**（防止闸被改坏成"永远红"，那也是一种假绿的对偶）
fresh();
const back = gate();
console.log(`${back.rc === 0 ? '✓ ' : '!!'} 收尾：还原干净 ⇒ 退码 ${back.rc}（必须 0）`);
if (back.rc !== 0) bad++;
log.push(`=== 收尾 干净\n实际退码 ${back.rc}\n--- 输出 ---\n${back.out}\n`);
// ⚑ **元对照**：把闸**自己**改坏一次（只看 APK 在不在、不比新旧），第 5 条必须**假通过**才行 ——
//   它要还拦得住，说明第 5 条测的压根不是"新旧"这件事，整台自证就是白做的（本仓那条：
//   "闸门比字符串就会让后面所有闸静默停摆"；控制也要被控制一次）。
const realGate = fs.readFileSync(path.join(REPO, '_harness/precommit.sh'), 'utf8');
const ANCHOR = 'if [ "$APK_TS" -gt "$NEWEST_TS" ]; then';
if (!realGate.includes(ANCHOR)) {
  console.log('!! 元对照开不了工：precommit.sh 里那句"APK 比锚新"的原文变了 —— 先改这台子再来跑');
  bad++;
} else {
  fresh(realGate.replace(ANCHOR, 'if [ -n "$APK_TS" ]; then'));
  CASES[4].set();
  const n = gate();
  const okMeta = n.rc === 0;
  console.log(`${okMeta ? '✓ ' : '!!'} 元对照：闸被改成"APK 存在就放行"之后，第 5 条退码 ${n.rc}` +
    `（必须 0；不是 0 就说明第 5 条没在测"新旧"）`);
  log.push(`=== 元对照 朴素闸（只看 APK 在不在）\n实际退码 ${n.rc}\n--- 输出 ---\n${n.out}\n`);
  if (!okMeta) bad++;
}
// ⚑ **元对照之二**：把"最近一次提交也查"那一半摘掉，第 9 条必须**假通过** ——
//   今天这道闸本来就是为那种现场补的，它要是靠别的东西碰巧拦下来，第 9 条同样白测。
const HEADLOOP = 'for f in $HFILES; do untouched "$f" || HUNCOV="$HUNCOV $f"; done';
if (!realGate.includes(HEADLOOP)) {
  console.log('!! 元对照之二开不了工：precommit.sh 里那句扫 HEAD 的循环原文变了 —— 先改这台子');
  bad++;
} else {
  fresh(realGate.replace(HEADLOOP, ':'));
  CASES[8].set();
  const n2 = gate();
  const ok2 = n2.rc === 0;
  console.log(`${ok2 ? '✓ ' : '!!'} 元对照之二：摘掉"也查最近一次提交"之后，第 9 条退码 ${n2.rc}（必须 0）`);
  log.push(`=== 元对照之二（不查 HEAD）\n实际退码 ${n2.rc}\n--- 输出 ---\n${n2.out}\n`);
  if (!ok2) bad++;
}
fs.writeFileSync('D:/tmp/pcgate_out.txt', log.join('\n'));
console.log(bad ? `\n!! ${bad} 条不成立 —— 完整现场：D:/tmp/pcgate、逐条输出 D:/tmp/pcgate_out.txt`
                : '\n全部成立（现场留在 D:/tmp/pcgate，日志 D:/tmp/pcgate_out.txt）');
process.exit(bad ? 1 : 0);
