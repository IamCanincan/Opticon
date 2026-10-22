// 哪个语料目录进了**哪几道闸** —— 一张当场算出来的表，不是文档里抄的名单。
//
// 为什么要它：本仓那条规矩是"分母覆盖哪一类要写出来"。以前这句话靠我读 `regress.sh` 记住，
//   而 `regress.sh` 里的分母是 `$CORPUS / $PLATECORPUS / $SHOTCORPUS / $SHOTBITCORPUS` 这些**变量**，
//   真正的目录名在别处赋默认值、闸体还抽进了 `*gate.sh` —— 用 grep 数一遍必然漏（今天就这么翻过一次车：
//   `grep -c shortcut_bit regress.sh` 给 0，我差点报成"新语料没接进任何闸"）。
//   这台子先把变量展开，再按段列，所以**漏了会以"某份语料 0 道闸"的形式自己叫出来**。
//
// 跑法：node _harness/_corpusmap.cjs      退码：0 = 每份在册语料都至少被一道闸吃；1 = 有语料没人吃（或变量没定义）
const fs = require('fs');
const path = require('path');

const H = __dirname;
const SOURCES = ['regress.sh', 'twobookgate.sh', 'adaptgate.sh', 'corpusgate.sh', 'namedfloor.sh', 'selftestgate.sh'];
const texts = {};
for (const f of SOURCES) {
  const p = path.join(H, f);
  if (fs.existsSync(p)) texts[f] = fs.readFileSync(p, 'utf8');
}
const all = Object.values(texts).join('\n');

// ① 变量默认值。三种写法都要认，**少认一种就会报出假警报**（第一版没认别名，`shortcut_bit`
//    被报成"没进第 19 闸"，而它其实就在那段的 `T_BIT="${SHOTBITCORPUS:-shortcut_bit}"` 里）：
//      FOO=${FOO:-bar}        直接默认值
//      FOO="${OTHER:-bar}"    **别名**：闸体里给参数起的本地名字
//      FOO=$OTHER             转发
const vars = {};
for (const m of all.matchAll(/\b([A-Z][A-Z0-9_]*)="?\$\{([A-Z][A-Z0-9_]*):-([^}"]+)\}/g)) {
  const [, name, key, def] = m;
  if (name === key || vars[name] === undefined) vars[name] = def;
}
for (const m of all.matchAll(/^\s*([A-Z][A-Z0-9_]*)=\$([A-Z][A-Z0-9_]*)\s*$/gm)) if (vars[m[2]]) vars[m[1]] = vars[m[2]];
for (const m of all.matchAll(/^\s*([A-Z][A-Z0-9_]*)=([A-Za-z0-9_.\/-]+)\s*$/gm)) if (!vars[m[1]]) vars[m[1]] = m[2];

// ② 什么算"在册语料"：**目录里有 `index.tsv`**（判据台认语料就靠这份表），
//   且这个名字在 `_harness/` 的任何一份程序（`.cjs/.kt/.sh/.java`）里出现过
//   （不然就是我历次量测留下的临时目录，把它们算进来会把真漏的淹死在噪声里 —— 第一版就这么误报了 9 个）。
const progText = fs.readdirSync(H)
  .filter((f) => /\.(cjs|kt|sh|java)$/.test(f))
  .map((f) => { try { return fs.readFileSync(path.join(H, f), 'utf8'); } catch (e) { return ''; } })
  .join('\n');
const onDisk = fs.readdirSync(H, { withFileTypes: true })
  .filter((d) => d.isDirectory() && fs.existsSync(path.join(H, d.name, 'index.tsv')))
  .map((d) => d.name);
const scratch = onDisk.filter((d) => !progText.includes(d));
const inBook = onDisk.filter((d) => progText.includes(d));

// ③ 按段扫：段表头 `== N/M 名字 ==` 之下的 `run harness.X <参数>` / `node _x.cjs <参数>` 里出现的目录（变量已展开）
function expand(tok) {
  let t = tok;
  for (let i = 0; i < 4; i++) {
    const m = t.match(/^\$\{?([A-Z][A-Z0-9_]*)\}?$/);
    if (!m) break;
    if (vars[m[1]] === undefined) return { undef: m[1] };
    t = vars[m[1]];
  }
  return { dir: t.replace(/^"|"$/g, '') };
}
const perDir = new Map();
const undefVars = new Set();
for (const [file, text] of Object.entries(texts)) {
  let seg = `(${file} 共用体)`;
  for (const line of text.split(/\r?\n/)) {
    const h = line.match(/echo "== (\d+\/\d+ [^=]+) =="/);
    if (h) { seg = h[1]; continue; }
    const run = line.match(/run harness\.[A-Za-z0-9]+(.*)$/);
    const nod = line.match(/node (_[\w.\-]+\.cjs)(.*)$/);
    const argstr = run ? run[1] : nod ? nod[2] : null;
    if (argstr === null) continue;
    for (const raw0 of argstr.split(/\s+/)) {
      // ⚠ **先去引号再看 `$`**：闸里写的是 `"$SHOTBITCORPUS"`，第一版按 `raw.startsWith('$')` 判断，
      //   带引号的变量全被当成"不是变量"直接丢进目录名比对 ⇒ 报出"新语料没接进任何闸"的**假警报**。
      const raw = raw0.replace(/^"+|"+$/g, '');
      if (!raw || raw.startsWith('-')) continue;
      const cands = [raw];
      const eq = raw.indexOf('='); if (eq > 0) cands.push(raw.substring(eq + 1));
      const out = [];
      for (const c of cands) for (const part of c.split(':')) if (part) out.push(part);
      for (const p of out) {
        const e = p.startsWith('$') ? expand(p) : { dir: p.replace(/\$\{?|\}?$/g, '') };
        if (e.undef) { undefVars.add(e.undef); continue; }
        if (!e.dir || !inBook.includes(e.dir)) continue;
        if (!perDir.has(e.dir)) perDir.set(e.dir, new Set());
        perDir.get(e.dir).add(seg);
      }
    }
  }
}

const unHooked = inBook.filter((d) => !perDir.has(d));
// ⚠ 这是一张**集合**表而不是绝对数：新冒出一份"没人吃的在册语料" ⇒ 红；
//   名单里那份不在了 ⇒ 也红（那条理由作废了）。两边都查，才叫得住"覆盖到哪一类"这句话。
const KNOWN_UNHOOKED = {
  corpus_audit3: '旧 yard 口径（第 5 闸已搬到 yard_model，拿它跑会被 fg 闸当场拦掉）—— 只作旧读数复现件',
  rec3u: '2026-10-11 之前的默认分母（换成 rec_model 之后不再被任何闸吃）',
  corpus_dev: 'dev 抓的临时件，只被 ad-hoc 脚本引用',
  corpus_dev2: '上一次 `--dir=` 参数实验留下的',
};
console.log(`在册语料 ${inBook.length} 份（另有 ${scratch.length} 个带 index.tsv 的目录只在程序里没出现 = 判据台留下的临时件，不计）`);
console.log(`被至少一道闸吃：${perDir.size} 份`);
for (const [d, segs] of [...perDir.entries()].sort()) {
  console.log(`  ${d.padEnd(16)} → ${[...segs].sort().join(' ｜ ')}`.slice(0, 168));
}
// 变量没默认值 ⇒ **只有名字像分母的**才算失败（`T384 / RAW` 那种是文件路径，本来就不是语料）。
const undefBad = [...undefVars].filter((v) => /CORPUS|MODEL|SHOT|PLATE|BIT|YARDDIR|DIR$/.test(v));
if (undefVars.size) console.log(`（未解析的变量：${[...undefVars].join(' ')}｜其中算分母的 ${undefBad.length} 个）`);
const fresh = unHooked.filter((d) => !KNOWN_UNHOOKED[d]);
const vanished = Object.keys(KNOWN_UNHOOKED).filter((d) => !unHooked.includes(d));
console.log('有意不进闸的历史件（每条自带理由）：');
for (const d of Object.keys(KNOWN_UNHOOKED).sort()) {
  const here = unHooked.includes(d) ? '在' : '!! 已经不在这台机器上了';
  console.log(`  ${d.padEnd(16)} ${here} —— ${KNOWN_UNHOOKED[d]}`);
}
if (fresh.length) console.log(`!! 新冒出 ${fresh.length} 份"在册却不在任何一道闸里"的语料：${fresh.join(' ')}` +
  '\n   （接进某一段，或写清理由加进名单；留在盘上会被下一次"覆盖到哪一类"的追问骗过去）');
if (vanished.length) console.log(`!! 名单里这几份已经不在了，那条理由该随之删掉：${vanished.join(' ')}`);
process.exit(fresh.length || vanished.length || undefBad.length ? 1 : 0);
