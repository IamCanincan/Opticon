// **判定机器自己的变异自证**（2026-10-20）。
// 为什么先测它而不是测某一道闸：今天就是我**读错了它的结论** ——
//   只 grep 到某个子台子打的 "OK：2036 条全绿" 就宣布整套绿，而它最后一行写的是 `!! 1 项比基线差`。
//   那台子判绿/红、缺数、跳闸、有没有基线，如果本身没牙，18 道闸全等于没跑。
// 沙箱：只拷 `bench/reg`(832K) + `bench/baseline.json`，用 cwd 切过去跑同一份 `_regress.cjs`
//   （它读的都是相对路径，所以不用改它一行）。**绝不碰真的 baseline，也不带 --update。**
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const H = __dirname;
const SB = path.join(H, 'bench', '_regmut_sandbox');
const SCRIPT = path.join(H, '_regress.cjs');

function makeSandbox() {
  fs.rmSync(SB, { recursive: true, force: true });
  fs.mkdirSync(path.join(SB, 'bench', 'reg'), { recursive: true });
  for (const f of fs.readdirSync(path.join(H, 'bench', 'reg')))
    fs.copyFileSync(path.join(H, 'bench', 'reg', f), path.join(SB, 'bench', 'reg', f));
  fs.copyFileSync(path.join(H, 'bench', 'baseline.json'), path.join(SB, 'bench', 'baseline.json'));
}
const rd = p => fs.readFileSync(path.join(SB, p), 'utf8');
const wr = (p, s) => fs.writeFileSync(path.join(SB, p), s);
function run() {
  const r = spawnSync(process.execPath, [SCRIPT], { encoding: 'utf8', cwd: SB });
  const out = r.stdout + r.stderr;
  return { rc: r.status, last: out.trim().split(/\r?\n/).filter(Boolean).slice(-1)[0] || '', out };
}
const CASES = [
  ['原样（负对照：什么都没改）→ 必须按真基线判', () => ({ changed: false, n: 0 })],
  // ⚠ 第一版这里改的是 `faint` —— 那是**参考行**（主人 2026-10-19 判「图标其实都没什么问题」之后
  //   就不参与红绿灯），改它当然不红，于是这条断言**永远不会失败**。要测就挑**被判定**的那一列。
  ['把 `rhoBad` 的基线调严 1（ρ色<0.95 的张数，越大越坏 → 本轮就该算退步）', () => {
    const b = JSON.parse(rd('bench/baseline.json')); const was = b.rhoBad;
    b.rhoBad = (typeof was === 'number' ? was - 1 : was);
    wr('bench/baseline.json', JSON.stringify(b, null, 2));
    return { changed: JSON.parse(rd('bench/baseline.json')).rhoBad === was - 1, n: 1, why: `rhoBad ${was}→${b.rhoBad}` };
  }],
  ['删掉一份闸的输出（compat.txt）→ 缺数不许当"没有这一项"', () => {
    fs.rmSync(path.join(SB, 'bench', 'reg', 'compat.txt'));
    return { changed: !fs.existsSync(path.join(SB, 'bench', 'reg', 'compat.txt')), n: 1 };
  }],
  ['删掉基线文件 → 一个数都没比过，不许报绿', () => {
    fs.rmSync(path.join(SB, 'bench', 'baseline.json'));
    return { changed: !fs.existsSync(path.join(SB, 'bench', 'baseline.json')), n: 1 };
  }],
  ['skipped.txt 写两道"本轮没跑" → 结论必须带上它们', () => {
    wr('bench/reg/skipped.txt', '10 换支账（测试）\n11 另一道（测试）\n');
    return { changed: rd('bench/reg/skipped.txt').includes('测试'), n: 2 };
  }],
];
let bad = 0;
for (const [nm, mk] of CASES) {
  makeSandbox();
  const m = mk();
  if (m.n > 0 && !m.changed) { console.log(`✗ ${nm}\n    **变异没落地**：这条测的是空气`); bad++; continue; }
  const r = run();
  // ⚠ 结论行与"没跑"那句是**两行**（skipNote 拼在结论后面）：判绿必须看整段输出里那一行 `全绿`，
  //   第一版拿"最后一行"判，于是**这一条断言也永远不会失败**。
  const green = r.rc === 0 && /(^|\n)全绿/.test(r.out);
  const worse = /比基线差/.test(r.out);
  const missing = /没有数|拒绝|没有基线/.test(r.out);   // 缺基线时它说的是"没有基线…不许报绿"，不是"缺数"
  let ok, need;
  if (m.n === 0) { ok = green; need = '绿'; }
  else if (m.n === 2) { ok = /2 道闸/.test(r.out); need = '结论里带上"2 道闸没跑"'; }
  else { ok = r.rc !== 0 && (worse || missing); need = '非零 + 退步或缺数'; }
  console.log(`${ok ? '✓' : '✗'} ${nm}\n    要：${need}；退码 ${r.rc}，` +
    `结论行「${(r.out.match(/\n(全绿|!! [^\n]*)/g) || ['(没有结论行)']).join(' / ').slice(0, 90)}」`);
  if (!ok) bad++;
}
fs.rmSync(SB, { recursive: true, force: true });
console.log(bad ? `\n!! ${bad} 条不成立 —— 判定机器有洞` : '\n全部成立：改坏基线/缺输出/缺基线/跳过闸，四种都没有被读成"全绿"');
process.exit(bad ? 1 : 0);
