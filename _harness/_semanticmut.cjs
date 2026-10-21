// **语义变异台：把生产里那把刀故意改坏一行，看第 N 道闸会不会红。**
//
// 与 `_regressmut.cjs` / `_flatmut.cjs` 那类"变异台"的分工不同，别再混着引用：
//  - 那批改的是**账本文件**（把 `bench/reg/*.txt` 里的数改差），证的是**裁判机器读得懂更差的数**；
//  - 这一台改的是**生产源码**（`MonochromeMask.kt` 真少一行守卫 / 真多一条门槛），重编、重跑一整台，
//    证的是**那道闸还连着真刀**。红了 = 有牙；绿了 = 它守的东西在生产里已经不存在，
//    或者它走的输入通道比装机窄（2026-10-21 第九闸就是后者：3 参 `build` 顺手把 `plateIsLayer`
//    钉成 false，于是"地板"那一刀整条分支从来没被量过 —— 见 AirInkKt.kt 文件头）。
//
// 跑法：
//   node _semanticmut.cjs                 # 列出所有用例，不跑
//   node _semanticmut.cjs --case=9        # 只跑第九闸那一条
//   node _semanticmut.cjs --restore       # 只把生产还原（崩溃后用）
// ⚠ **它会临时改 `app/src/main/` 里的文件**。三条硬保险：
//   ① 目标文件有未提交改动就直接拒绝（不吃别人正在写的几百行）；
//   ② 一台跑完（含异常）在 `finally` 里还原，还原后逐字节核对；
//   ③ 每次开工先看 `cand/semanticmut/` 有没有上一具尸体，有就先还原。
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const ROOT = path.resolve(__dirname, '..');
const MASK = 'app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt';
const KEEP = path.join(__dirname, 'cand', 'semanticmut');
// ⚠ 日志**不走 stdout**：一台 18 段的输出有十几 MB，`spawnSync` 默认 maxBuffer 到顶会**杀掉子进程**，
// 而那一刀正好落在某一段中间 —— 那种退码既不是"红"也不是"绿"。落盘再读。
const TMPLOG = path.join(__dirname, 'cand', '_semanticmut_last.log');

/**
 * 每条用例：改哪一行、为什么这一改**是未来真可能犯的错**、期望哪一道闸红。
 * ⚠ `why` 会写进 `bench/semanticmut.txt` 当证物，所以要带上"这一版**曾经瞎在哪里**"，
 *   不能只写改了什么 —— 第九闸那条就漏了这一层，读起来像"这闸一直有牙，今天只是又验了一遍"，
 *   而真相是它拿 3 参 `build` 起步，`plateIsLayer` 恒 false，那条分支从来没被量过。
 */
const CASES = [
  {
    no: '9', gate: '9/9 空气上墨账', file: MASK,
    why: '把【地板】从 `× 源α / 255` 之前挪到之后（历史真出过的那版，13 张变实心方块）。' +
      '老口径 3 参 `build` ⇒ `plateLayer` 恒 false ⇒ `0 / 98`、退码 0；宽到装机那条路之后 `2/64`、退码 1',
    from: '          if (plateLayer && !ownPlate && s.plate[i] == 0.toByte())\n' +
      '            ta = PLATE_FLOOR_MIN + (255 - PLATE_FLOOR_MIN) * ta / 255\n' +
      '          ta * Color.alpha(pixels[i]) / 255\n',
    to: '          val ta0 = ta * Color.alpha(pixels[i]) / 255\n' +
      '          if (plateLayer && !ownPlate && s.plate[i] == 0.toByte())\n' +
      '            PLATE_FLOOR_MIN + (255 - PLATE_FLOOR_MIN) * ta0 / 255 else ta0\n',
    edges: '384',
    // 只跑这一道闸：`AirInkKt` 的**退码就是它的判词**（0=一张都没有，1=有），不必整台重跑。
    // ⚠ 键名不许叫 `gate` —— 那已经是"这一闸叫什么"（`9/9 空气上墨账`），JS 里后写的同名键
    //   **会静默盖掉前一个**，于是提示语里那段闸名会变成一条 shell 命令，而红没红照样判。
    gateCmd: 'run harness.AirInkKtKt rec_model rec_blank > bench/reg/airink.txt'
  },
  {
    no: '10', gate: '10/10 换支账', file: MASK,
    why: '给选支加一条按画布边长开的门槛 —— 这一闸存在的理由就是"选支不许随出图尺寸翻"',
    from: '    return r.first < s.first &&\n',
    to: '    return minOf(w, h) >= 200 && r.first < s.first &&\n',
    edges: '192,384',
    // 这一闸自己 = 两档边长各扫一遍语料，再让 `_branchstab.cjs` 判（它的退码就是判词）。
    // `|| exit 9` 不许省：扫描挂了会让 branchstab 拿着**上一轮的旧文件**判"没换支"——
    // 那是本仓写过的那类"读到上一轮中间文件当成这一轮"的假绿。
    gateCmd: 'run harness.MaskBenchKt --edge=192 rec_model > bench/reg/bench_192.txt || exit 9; ' +
      'run harness.MaskBenchKt --edge=384 rec_model > bench/reg/bench_384.txt || exit 9; ' +
      'node _branchstab.cjs bench/reg/bench_192.txt bench/reg/bench_384.txt'
  },
  {
    no: '12', gate: '12/12 中调被压账', file: MASK,
    why: '把传递曲线的指数从 0.75 抬到 1.5 —— 听着像"低段抬得太狠，压回去一点"，' +
      '实际是把**每一格交付浓度**按 `255×(t/255)^1.5` 重算：dep 不动而交出去的墨整体变淡 ⇒ ' +
      '"该有墨却只交不到六成"的块只许变多。（前一版挑的是 `EMAX 100→200`，那条动的是 dep 本身，' +
      '整台重跑时先红在第 11 闸 —— 第 12 闸自己始终没被量到。）',
    from: '    Math.round(255.0 * Math.pow(it / 255.0, 0.75)).toInt()\n',
    to: '    Math.round(255.0 * Math.pow(it / 255.0, 1.5)).toInt()\n',
    edges: '384',
    // 判定在 `_press.cjs --pin=` 里（2026-10-21 从 shell 搬过来），所以这一道闸**可以单独量**。
    gateCmd: 'run harness.PartsKtKt rec_model > bench/reg/parts.txt && ' +
      'node _press.cjs bench/reg/parts.txt --pin=bench/reg/press.pin'
  },
  {
    no: '11', gate: '11/11 源尺寸账', file: MASK,
    why: '把"算不算实心"的 `A_SOLID` 从 128 抬到 160 —— 专挑**重采样痕迹**下手：' +
      '同一张图绕到 256 再落回 384，边上那批 α≈128..160 的格会翻面，而它们是内容框、`盘刀`、`分档` 的原料。' +
      '现量 `SV 256 fields 50→51 / branch 2→3`、`SV 320 fields 43→42` ⇒ 第十一闸红、负对照绿 ✓。' +
      '（前一版挑 `A_BOX 8→64`：**没响，而且响不了是对的** —— 这一族图标最外圈远在 α≥64 之上，' +
      '那条改动没动到这次测量的任何输入。挑变异就要挑这种"必须动"的，否则测的是自己的想象力。）',
    from: '  private const val A_SOLID = 128\n',
    to: '  private const val A_SOLID = 160\n',
    edges: '384',
    // 判定在 `SizeVarKtKt --pin=` 里（2026-10-21 从 shell 搬过去，与第十二闸同一条规矩）。
    gateCmd: 'run harness.SizeVarKtKt rec_model --via=256,320 --pin=bench/reg/sizevar.pin'
  },
  {
    no: '13', gate: '13/13 对表外形账', file: MASK,
    why: '把选支的淡膜门槛 `PICK_SOFT_MAX` 从 4 收到 0（"这条口子太松，收到完全没有淡膜才搬"）⇒ ' +
      '逐张比 dump：**4 张 IoU 退让、最差一张 -22.7pp**，而两个尾部张数 `iou_low=14 over=5` **一格没动** —— ' +
      '老判据看不见这种退让，新加的"逐张不许退让"那一位红 ✓。' +
      '（前面挑的三条 —— 盘刀加 `plateLayer` 守卫、把 `tonal` 条件反过来、`PICK_INK_MIN 60→20` —— ' +
      '对 yard 语料要么**dump 逐字节相同**（那行根本没在这条路上执行），要么**净变好**（0 张退让、4 张变好）：' +
      '那些"闸没响"都是我的变异没碰到输入，不是闸的问题。**先证明这条刀在这把尺的语料上会落地，再谈它有没有牙。**）',
    from: '  private const val PICK_SOFT_MAX = 4\n',
    to: '  private const val PICK_SOFT_MAX = 0\n',
    edges: '384',
    // ⚠ **先删掉上一轮的 dump**：`_shape.cjs` 读的是 `bench/reg/yard_dump.tsv`，
    //   而那份文件是第 5 闸写的。不删就等于"YardKt 挂了也让 _shape 拿旧账判绿"——
    //   本仓写过的那一类：**读到上一轮的中间文件，当成这一轮的结论**。
    // `|| exit 9` 把"喂料的这一步没成"和"这一闸红了"分开：9 = 量具坏了，不算证过。
    gateCmd: 'rm -f bench/reg/yard_dump.tsv; ' +
      'run harness.YardKtKt yard_model --input=model --dump=bench/reg/yard_dump.tsv > bench/reg/yard.txt || exit 9; ' +
      'node _shape.cjs --ioupin=bench/reg/iou.pin'
  },
  {
    // 第八闸（`没图标账`）从来只有"阳性对照 = 拿 cand_allTonal 跑同一份"这一句，**没有可重跑的证物**
    // —— 而且这条用例是 2026-10-21 修 `_gateaudit.cjs` 的表头正则之后才**看见**的（那段名字以全角 `）`
    // 收尾 ⇒ 老正则匹配不上 ⇒ 第七、第八两道真闸长期不在审计的 16 段里）。
    no: '8', gate: '8/8 没图标账', file: MASK,
    why: '把"满幅那一支开火只看跨度"那唯一一道守卫 `TONAL_SPAN_MIN` 从 35 挪到 0（= 跨度再小也开火），' +
      'rec_blank 这 34 张真机事故捞回来的图就走进那条斜坡 —— 支付宝/钉钉/翻译那几张跨度是 0~7，' +
      '斜坡把最亮的白直接归零 = 屏幕上整个不画（2026-10-08 那次事故的本体）。' +
      '**这条测的是第八闸看不看得见它，不是"能不能这样改"。**',
    from: '  private const val TONAL_SPAN_MIN = 35\n',
    to: '  private const val TONAL_SPAN_MIN = 0\n',
    edges: '384',
    // ⚠ 输出写**自己的文件**（`*_mut`）：这个驱动以前清空过别人的活账本（平色饼 124→0，造出一次假退步）。
    // `|| exit 9` = 量具坏了，不算证过。
    gateCmd: 'run harness.MaskBenchKt --edge=384 rec_blank > bench/reg/blank_mut.txt || exit 9; ' +
      'node _blank.cjs 2 --in=bench/reg/blank_mut.txt:rec3u ' +
      '--floor="$(cat bench/blank_floor.txt 2>/dev/null || echo 0)" > bench/reg/blank_verdict_mut.txt'
  }
];

const arg = p => process.argv.find(a => a.startsWith(p + '='))?.substring(p.length + 1);
const want = arg('--case');
const RESTORE = process.argv.includes('--restore');

function sh(cmd, args, opts) {
  return spawnSync(cmd, args, Object.assign({ cwd: __dirname, encoding: 'utf8' }, opts || {}));
}
/**
 * 这台子**停在哪一段** = 日志里最后一个 `== X ==` 表头。
 * ⚠ 以前这里找的是"第一处 `!!`"，而那**永远命中 `2/7 性质台` 里 SelfTest 自己那句 `!! 1 条不成立`**
 *   （底线名单容忍的常规报告，全绿的机器上也照印）。归因错一段 = 把"第九闸有牙"记成"第二闸接住了"，
 *   而这正是本仓写过的那类：**比字符串的闸会让后面所有闸静默停摆**。
 * 真正的失败一定是"`echo !! …` 紧跟 `exit 1`"，所以停机点后面必然紧跟着最后一条 `!!`。
 */
function stopStage(log) {
  const L = log.split(/\r?\n/);
  let at = -1;
  for (let i = 0; i < L.length; i++) if (/^== (.+?) ==/.test(L[i])) at = i;
  if (at < 0) return { stage: null, line: null };
  const stage = /^== (.+?) ==/.exec(L[at])[1];
  const after = L.slice(at + 1).filter(l => /^!!/.test(l));
  return { stage, line: after[after.length - 1] || L[L.length - 1] || '' };
}
function dirty(rel) {
  const r = spawnSync('git', ['status', '--porcelain', '--', rel], { cwd: ROOT, encoding: 'utf8' });
  return (r.stdout || '').trim().length > 0;
}
/**
 * 把"这一道闸被一次真·生产变异证出有牙"记进账本，供 `_gateaudit.cjs` 读。
 * ⚠ **审计读的是这份账，不是本文件的用例名单** —— 用例写了没跑不等于证过了。
 * 每道闸只留最近一条（这是量具的读数表，不是变更历史；历史写在 devlog 里）。
 */
function noteEvidence(no, stage, why) {
  const f = path.join(__dirname, 'bench', 'semanticmut.txt');
  fs.mkdirSync(path.dirname(f), { recursive: true });
  const rest = (fs.existsSync(f) ? fs.readFileSync(f, 'utf8').split(/\r?\n/) : []).filter(l => !l.startsWith(no + '\t'));
  const d = new Date();
  const day = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  rest.push([no, day, stage, why].join('\t'));
  fs.writeFileSync(f, rest.filter(Boolean).join('\n') + '\n');
}
function guardLock() {
  const f = path.join(__dirname, 'bench', 'reg', '.running');
  if (!fs.existsSync(f)) return;
  const pid = parseInt(fs.readFileSync(f, 'utf8').trim(), 10);
  if (!pid) return;
  try { process.kill(pid, 0); console.error(`!! 已经有一台 regress 在跑（pid ${pid}）—— 语义变异台会改生产源码，不能同时`); process.exit(2); } catch (e) { /* 尸体，继续 */ }
}
/**
 * 尸体 = 上一轮被杀在变异里时留在 `cand/semanticmut/<号>.orig` 的那份**原样字节**。
 * 🔴 **`.orig` 里只许存内容，一行都不许多。** 以前我在头上加了一行"这是哪个文件"，
 *   于是"还原"= 把**那行路径**一起抄进生产源码 —— 生产文件第一行变成它自己的路径，
 *   `package` 被挤到第二行。这一版今天正在跑第 10 段时被另一支 `--redge` 触发，
 *   差一点就让第 12 段拿这份文件去编译（那样连"变异编不过"都分不清是谁的锅）。
 *   ⇒ 路径由**用例**决定（`c.file`），不由文件内容决定；还原之后**必须再验一次**树。
 */
function sweepCorpses() {
  if (!fs.existsSync(KEEP)) return;
  for (const n of fs.readdirSync(KEEP)) {
    if (!n.endsWith('.orig')) continue;
    const c = CASES.find(x => x.no === n.replace('.orig', ''));
    if (!c) { console.error(`!! 认不出的尸体 ${n} —— 手工检查，别由这台子乱抄`); process.exit(2); }
    fs.copyFileSync(path.join(KEEP, n), path.join(ROOT, c.file));
    fs.rmSync(path.join(KEEP, n));
    console.log(`   还原了上一具尸体：${c.file}`);
    if (dirty(c.file)) {
      console.error(`!! 还原之后 ${c.file} 仍与 HEAD 不同 —— 这里躺着**别人的改动**，这台子拒绝继续（先手工核对）`);
      process.exit(2);
    }
  }
}

const active = CASES.filter(c => !want || c.no === want);
if (!active.length) {
  console.log('用例：'); CASES.forEach(c => console.log(`  --case=${c.no}  ${c.gate}  ${c.why}`));
  process.exit(0);
}
if (RESTORE) { sweepCorpses(); console.log('   （--restore）生产已还原，本轮不跑台子'); process.exit(0); }

// **`--redge`：不重跑台子，只把已经落盘的 `cand/semanticmut/<号>.log` 重新归因一遍。**
// 要它是因为归因规则改过（旧规则命中的是 SelfTest 那句常规报告，不是闸的失败回响）——
// 那几台**真的跑过、真的红了**，只是记错了段。重跑一遍要 8 分钟，而重读一份日志不要。
// ⚠ **这一支绝不碰生产源码，也绝不 `sweepCorpses`**：2026-10-21 我正是在另一台变异跑到第 10 段时
//   敲了 `--redge`，它把"上一具尸体"（= **正在生效的那份变异**）还原回了原样 ——
//   那次没弄坏结论（`out/` 里的 class 早编好了），但"磁盘上的源码与正在跑的台子不一致"这种状态
//   本身就是下一个错案的入口。**归因只读日志，不改世界。**
if (process.argv.includes('--redge')) {
  const lk = path.join(__dirname, 'bench', 'reg', '.running');
  if (fs.existsSync(lk)) {
    try { process.kill(parseInt(fs.readFileSync(lk, 'utf8').trim(), 10), 0);
      console.error('!! 有一台 regress 正在跑 —— 归因台虽然只读日志，但此刻 `cand/semanticmut/*.log` 正在被写，等它跑完');
      process.exit(2);
    } catch (e) { /* 死锁文件，继续 */ }
  }
  let n = 0;
  for (const c of CASES) {
    const f = path.join(KEEP, c.no + '.log');
    if (!fs.existsSync(f)) { console.log(`   用例 ${c.no}：没有 ${f}，跳过`); continue; }
    const red = stopStage(fs.readFileSync(f, 'utf8'));
    if (red.stage && red.stage.indexOf(c.no + '/') === 0) {
      console.log(`   用例 ${c.no} ✅ 红在该红的那一段：${red.stage}\n       ${red.line}`);
      noteEvidence(c.no, red.stage, c.why); n++;
    } else console.log(`   用例 ${c.no} ⚠ 这台子停在「${red.stage || '(没有表头)'}」，不是 ${c.gate}`);
  }
  console.log(`   重新归因：${n} 条记进 bench/semanticmut.txt`);
  process.exit(0);
}
guardLock();

let bad = 0;
for (const c of active) {
  const abs = path.join(ROOT, c.file);
  if (dirty(c.file)) { console.error(`!! ${c.file} 有未提交改动 —— 语义变异台拒绝在它上面动刀`); process.exit(2); }
  const before = fs.readFileSync(abs, 'utf8');
  const hits = before.split(c.from).length - 1;
  // 自证命中：命中 0 处 = 这一条已经在测空气（本仓写过：一条变异必须自证落地）。
  if (hits !== 1) { console.error(`!! 用例 ${c.no} 的锚点命中 ${hits} 处（应恰好 1）—— 生产那一行改了形，先修用例`); bad++; continue; }
  fs.mkdirSync(KEEP, { recursive: true });
  fs.writeFileSync(path.join(KEEP, c.no + '.orig'), before);
  try {
    console.log(`\n=== 用例 ${c.no}：${c.gate}\n    ${c.why}`);
    const build = () => {
      sh('sh', ['build.sh']);
      return (fs.readFileSync(path.join(__dirname, '_kt.log'), 'utf8').match(/error:/g) || []).length;
    };
    if (build()) { console.error('!! 原样就编不过 —— 先修树，别在这上面跑变异'); bad++; continue; }
    // **先跑负对照**：不改任何东西时，这一道闸必须是绿的。少了这一步，"变异之后红了"这句话
    // 可能只是"这条命令本来就是红的"（`gateCmd` 写错、语料缺失、classpath 漂了，都会红）。
    if (c.gateCmd) {
      const ctl = sh('sh', ['-c', '. ./gateenv.sh; mkdir -p bench/reg; ' + c.gateCmd + '; echo $?']);
      const crc = parseInt(String(ctl.stdout || '').trim().split(/\r?\n/).pop(), 10);
      if (crc !== 0) {
        console.error(`!! 用例 ${c.no} 的**负对照**就没过（原样跑这一道闸退码 ${crc}）—— ` +
          '要么这条闸本来就红，要么 gateCmd 写错了；先修，别谈变异');
        bad++; continue;
      }
      console.log('    负对照（原样、重编之后）：退码 0 ✓');
    }
    // **对照过了才上变异**，再重编一次。把对照放在变异之后 = 量到的是变异后的 class，
    // 那句"原样也红"什么也不证（今天真错过一次这个顺序）。
    fs.writeFileSync(abs, before.replace(c.from, c.to));
    if (fs.readFileSync(abs, 'utf8') === before) { console.error('!! 写进去又读回一模一样 —— 变异没落地'); bad++; continue; }
    if (build()) { console.error('!! 变异编不过 —— 这一条测不了'); bad++; continue; }
    // ⚠ 日志路径必须是**相对 `cwd` 的**：这台仓库在 `D:/Github Repositories/…` 这种带空格的路径下，
    //   绝对路径塞进 `sh -c` 的重定向目标会被空格切开（本仓那条老规矩的又一个形态）。
    let rc, red, log;
    if (c.gateCmd) {
      const g = sh('sh', ['-c', '. ./gateenv.sh; mkdir -p bench/reg; ' + c.gateCmd + '; echo $?']);
      rc = parseInt(String(g.stdout || '').trim().split(/\r?\n/).pop(), 10);
      log = (g.stdout || '') + (g.stderr || '');
      // ⚠ 这里的 `stage` **不是从日志归因来的**：这条命令只跑了那一道闸，退码就是它的判词。
      //   账本里要带上这一点，否则读的人以为整台停在那一段。
      red = { stage: rc !== 0 ? c.gate + '（只跑这一道闸，退码即判词）' : null, line: '' };
    } else {
      const g = sh('sh', ['-c', 'EDGES=' + c.edges + ' sh regress.sh > cand/_semanticmut_last.log 2>&1; echo $?']);
      rc = parseInt(String(g.stdout || '').trim().split(/\r?\n/).pop(), 10);
      log = fs.existsSync(TMPLOG) ? fs.readFileSync(TMPLOG, 'utf8') : '';
      red = stopStage(log);
    }
    if (Number.isNaN(rc)) { console.error('!! 拿不到退码 —— 这一条不算证过'); bad++; continue; }
    fs.mkdirSync(KEEP, { recursive: true });
    fs.writeFileSync(path.join(KEEP, c.no + '.log'), log);
    if (rc === 0) { console.log(`    ❌ **退码 0（全绿）** —— 第 ${c.no} 道闸对这一改没有牙`); bad++; }
    else if (rc === 2 || rc === 9) {
      // 2/9 都是**量具自己的退出码**（2 = `_shape.cjs` 读不到新 dump；9 = 喂料那步没成），不是判词。
      console.log(`    ⚠ 退码 ${rc} = 量具自己没跑成（不是这一闸红了）—— 先看上面的报错，再重跑`);
      bad++;
    }
    else if (red.stage && red.stage.indexOf(c.no + '/') === 0) {
      console.log(`    ✅ 红了，而且红在该红的那一段：${red.stage}\n       ${red.line}`);
      noteEvidence(c.no, red.stage, c.why);
    } else {
      // ⚠ **这一支以前不算 `bad`**，于是末尾那句"全部用例都让对应的闸红了"是**假的**。
      //   2026-10-21 那一轮三条用例没有一条真正证到位（9 归因错段、10 量具被我自己编辑打断退了 127、
      //   12 红在第 11 闸），而汇总行写的是"全部红了 / EXIT=0"。
      //   "改动被回归台接住了"与"这一道闸有牙"是**两个问题**，后者才是这台子存在的理由。
      console.log(`    ⚠ 退码 ${rc}，但这台子停在「${red.stage || '(没有表头)'}」而不是 ${c.gate}`);
      if (rc === 127 || rc === 126) console.log('       （127/126 = 命令本身没跑成 —— 先查量具，不许读成"闸没牙"）');
      bad++;
    }
    if (rc !== 0) console.log(`       完整日志：${path.join(KEEP, c.no + '.log')}`);
  } finally {
    fs.writeFileSync(abs, before);
    if (fs.readFileSync(abs, 'utf8') !== before) { console.error(`!! 还原没成功：${c.file} 请手工检查`); process.exit(3); }
    fs.rmSync(path.join(KEEP, c.no + '.orig'), { force: true });
    console.log('    （生产已逐字节还原）');
  }
}
console.log(bad ? `\n!! ${bad} 条用例没能证明那一道闸有牙` : '\n全部用例都让对应的闸红了');
process.exit(bad ? 1 : 0);
