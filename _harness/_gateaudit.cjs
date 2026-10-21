// **审 20 道闸：哪一道从来没有被证明能响。**
// 为什么要它：今天连犯两个"闸开不了火"型的错（新闸的输出覆盖了别人的账本；`args.contains("--probe")`
//   对 `--probe=…` 恒 false ⇒ 台子一声不响）。这两类**只有变异能抓**。
// 分三档证据（宁严勿宽）：
//   实测 = 有同名 `*_mut*.{cjs,sh}` 脚本 / 闸体调 `--selftest` / 驱动里有 `println("!! ` 这类自证失败串
//   注释 = 只在注释里提过"变异/正对照"，**没有可跑的证物**
//   无   = 什么都没找到
// ⚠ "无"不等于"这闸是假的"，等于：**我从没证明过它能响**。
// 跑法：`node _gateaudit.cjs`
const fs = require('fs');
const lines = fs.readFileSync('regress.sh', 'utf8').split(/\r?\n/);
const gates = [];
let cur = null;
for (const l of lines) {
// ⚠ 2026-10-21 现量：这一条原先写成 `(.+?) ==`（`==` 前**必须有空格**），于是凡是表头名字以全角
//   `）` 直接收尾的段一条都不进表 —— 漏掉的正好是 `7/7 肉眼账` 与 `8/8 没图标账` **两道有底线、会 exit 1 的真闸**
//   （`3/7 语料` 不算闸，是扫描）。"18 道闸里什么都没有 0"其实是"我数到 16 段"。改成可选空格。
  const m = /echo "== ([\d\/]+) (.+?) ?==/.exec(l);
  if (m) { cur = { no: m[1], name: m[2], body: [] }; gates.push(cur); continue; }
  if (cur) cur.body.push(l);
}
const has = f => fs.existsSync(f);
// **今天真的响过的闸，不该被记成"没有证物"。** 2026-10-20 这一天里 `6/7 兼容性台` 拦下过一次真实的
// 生产改动、`5/7` 拦下过一次"新闸覆盖别人账本" —— 那是比合成变异更强的证据（它是真事故）。
// 所以这里分四档：`脚本` > `活证据` > `注释` > `无`。
const EVID = 'bench/gate_evidence.txt';
const live = new Map();
if (has(EVID)) for (const l of fs.readFileSync(EVID, 'utf8').split(/\r?\n/)) {
  if (!l.trim() || l.startsWith('#')) continue;
  const [no, kind, why] = l.split('\t');
  if (kind === 'live' && no !== '_') live.set(no, why || '');
}
const central = has('_regressmut.cjs') ? '判定机器有 `_regressmut.cjs`（覆盖所有数值闸共用的那一步）' : '';
// **语义变异用例**（`_semanticmut.cjs`）与上面那些是两档不同的东西，别再混着引用：
// 账本级变异只证"裁判读得懂更差的数"；语义变异改的是**生产源码那一行**，证的是"这道闸还连着真刀"。
// 审计里它算最高档，因为它否证的正是本仓反复栽的那种闸——**走的输入通道比装机窄**（第九闸 2026-10-21）。
const SEM = has('bench/semanticmut.txt')
  ? new Map([...fs.readFileSync('bench/semanticmut.txt', 'utf8').split(/\r?\n/)]
    .filter(l => l.trim() && !l.startsWith('#'))
    .map(l => l.split('\t'))
    .filter(c => c.length >= 3 && /^[0-9]+$/.test(c[0]))
    .map(c => [c[0], c[1] + ' 那一版把生产源码改坏之后，红在 ' + c[2]]))
  : new Map();
// ⚠ 账本里的键是**闸号**（`9`、`10`、`12`），而 `g.no` 是表头里那个 `10/10` —— 直接 `SEM.has(g.no)`
//   恒 false，三条刚证完的变异在审计里全部显示成"什么都没有"（2026-10-21 现量）。查之前先剥掉那半。
const semOf = g => {
  const v = SEM.get(g.no.split('/')[0]);
  if (!v) return null;
  // ⚠ **同一个号可以有两段**：`8/8 没图标账` 与 `8/8 自画层不透传账`（回归台里 `8/8` 出现了两次）。
  //   只按键认领会让一条证据同时记到两段头上 —— 2026-10-21 现量：语义那档因此报成 7 而实际 6。
  //   所以要**名字也对得上**才认领。
  const stem = g.name.split(/[（( 　]/)[0];
  return stem.length >= 3 && v.includes(stem) ? v : null;
};
/** `run harness.SelfTestKt` → SelfTest.kt；`harness.MonoKtKt` → MonoKt.kt */
const ktFile = cls => {
  const bare = cls.replace(/^harness\./, '').replace(/Kt$/, '');
  return [bare + '.kt', bare + 'Kt.kt'].find(has) || null;
};
for (const g of gates) {
  const body = g.body.join('\n');
  const cls = [...new Set([...body.matchAll(/run harness\.([A-Za-z0-9]+)/g)].map(x => x[1]))];
  const scripts = [...new Set([...body.matchAll(/node (_?[\w.\-]+\.cjs)/g)].map(x => x[1]))];
  const hard = [], soft = [];
  if (/--selftest/.test(body)) hard.push('闸体调 --selftest');
  for (const s of scripts) {
    const stem = s.replace(/\.cjs$/, '');
    for (const cand of [stem + '_mut.cjs', '_' + stem.replace(/^_/, '') + '_mut.cjs', stem + 'mut.cjs'])
      if (has(cand)) { hard.push(cand + ' 存在'); break; }
  }
  // ⚠ 2026-10-21 补的一档：**判定本体被抽成 `.sh` 文件**的那些段（`namedfloor.sh` / `selftestgate.sh` /
  //   `corpusgate.sh`），它的变异台叫 `_Xmut.cjs`，而闸体里只有一行 `. ./X.sh` —— 只扫 `node …` 就永远配不上，
  //   于是"刚给它做过四条变异证明"的段又被列成"什么都没有"。所以顺 source 关系也配一次。
  for (const m of body.matchAll(/(?:^|\s)\.\s+\.\/([A-Za-z0-9_-]+)\.sh/g)) {
    const stem = m[1];
    for (const cand of [`_${stem}_mut.cjs`, `${stem}_mut.cjs`])
      if (has(cand)) { hard.push(cand + ' 存在（配 ' + stem + '.sh）'); break; }
  }
  for (const c of cls) {
    const f = ktFile(c);
    if (!f) { soft.push(`${c} 源文件没找到(?)`); continue; }
    const src = fs.readFileSync(f, 'utf8');
    if (/println\("!! |自检失败|正对照失败/.test(src)) hard.push(`${f} 内含自证失败串`);
    else if (/自检|正对照/.test(src)) soft.push(`${f} 注释里提过自证`);
  }
  if (!hard.length && /变异|正对照/.test(body)) soft.push('只有闸体注释');
  // `语义` 排在最前一档：它否证的正是本仓反复栽的那种瞎 —— **闸走的输入通道比装机窄**
  // （第九闸 2026-10-21：3 参 `build` 顺手把 `plateIsLayer` 钉成 false，地板那一刀从没被量过）。
  // "驱动里有自证失败串"和"今天真响过"都盖不住那一种。
  // ⚠ 别拿 `语义 N` 去和账本行数对：账本 `bench/semanticmut.txt` 是 6 条，而这里数的是**段** ——
  //   `8/8` 有两个号、`9/9` 有一个号，加错看着像"多证了一条"。
  if (semOf(g)) g.tier = '语义', g.ev = semOf(g);
  else if (hard.length) g.tier = '脚本', g.ev = hard.join('、');
  else if (live.has(g.no)) g.tier = '活证据', g.ev = '今天真的响过：' + live.get(g.no);
  else if (!/exit 1/.test(body)) g.tier = '扫描';   // 这一段**不判定**（`3/7 语料` 就是它：跑扫描、不 exit）
  else if (soft.length) g.tier = '注释';
  else g.tier = '无';
  g.d = (cls.map(ktFile).filter(Boolean).concat(scripts).join(', ')) || '—';
}
const by = tier => gates.filter(g => g.tier === tier);
// ⚠ 分母要说得清：`gates.length` 是**段数**，里头有扫描段（`3/7 语料`）不判定，`8/8` 有两段同号。
//   2026-10-21 之前这行印的是"16 段"，而回归台报 18 道 —— 差的那两段（`7/7 肉眼账`、`8/8 没图标账`）
//   是表头正则漏的（见上面那条注释），**两道真闸长期不在审计里**。
console.log(`regress.sh 里 ${gates.length} 段 = 判定闸 ${gates.length - by('扫描').length} + 扫描段 ${by('扫描').length}：` +
  `改过生产源码的变异 ${by('语义').length} / 有可重跑脚本 ${by('脚本').length} / ` +
  `有活证据 ${by('活证据').length} / 只有注释 ${by('注释').length} / **什么都没有 ${by('无').length}**`);
if (central) console.log(`   ${central}`);
for (const tier of ['无', '注释', '活证据', '脚本', '语义'])
  for (const g of by(tier))
    console.log(`${tier.padEnd(4)} ${g.no.padEnd(7)} ${g.name.slice(0, 20).padEnd(22)} ${(g.ev || '—').slice(0, 74)}\n${' '.repeat(11)}跑：${g.d.slice(0, 96)}`);
