// **语料入口门会不会响** —— 跑的是 `corpusgate.sh` 里那一份（回归台调的也是它，不是抄本）。
// 三条：负对照必须绿、缺目录必须红、目录在但空必须红；最后自证真实语料一张没少。
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const sh = (c, a) => spawnSync(c, a, { cwd: __dirname, encoding: 'utf8', maxBuffer: 1 << 26 });
const run = dir => {
  const r = sh('sh', ['-c', `. ./corpusgate.sh; corpus_gate '${dir}'; echo "RC=$?"`]);
  const out = (r.stdout || '') + (r.stderr || '');
  const rc = parseInt((out.match(/RC=(\d+)\s*$/) || [])[1], 10);
  return { rc, out };
};
if (!fs.existsSync('corpusgate.sh')) { console.error('!! 没有 corpusgate.sh —— 判定本体不在，这台子什么都测不了'); process.exit(9); }

const real = process.argv[2] || (process.env.CORPUS || 'rec_model');
const TMP = 'cand/_corpusgate_probe';
let bad = 0;
const cases = [
  { name: `负对照：真实语料 ${real}/`, dir: real, want: 0 },
  { name: '目录不存在', dir: TMP + '/nope', want: 1 },
  { name: '目录在、里面 0 张 .png', dir: TMP + '/empty', want: 1 }
];
fs.mkdirSync(TMP + '/empty', { recursive: true });
try {
  for (const c of cases) {
    const { rc, out } = run(c.dir);
    const ok = rc === c.want;
    if (!ok) bad++;
    console.log(`${ok ? '✅' : '🔴'} ${c.name}：退码 ${rc}（要 ${c.want}）—— ${(out.split(/\r?\n/).find(l => l.startsWith('!!')) || out.split(/\r?\n/)[0] || '一个字都没印').slice(0, 80)}`);
  }
  // 正对照：往临时目录放一张 PNG，门就该放行（证明它认的是"有没有图"，不是"目录存不存在"）
  fs.writeFileSync(TMP + '/empty/one.png', Buffer.from([0x89, 0x50, 0x4e, 0x47]));
  const got = run(TMP + '/empty').rc;
  if (got !== 0) bad++;
  console.log(`${got === 0 ? '✅' : '🔴'} 放一张 .png 之后应放行：退码 ${got}（要 0）`);
} finally {
  fs.rmSync(TMP, { recursive: true, force: true });
}
const n = sh('sh', ['-c', `ls ${real}/*.png 2>/dev/null | wc -l`]).stdout.trim();
console.log(`收尾自证：真实语料 ${real}/ 仍有 ${n} 张（这台子只在临时目录里造样本，没动语料）`);
console.log(bad ? `🔴 ${bad} 条不对` : `全部 ${cases.length + 1} 条按预期响 —— 第 3 段的入口门有牙`);
process.exit(bad ? 1 : 0);
