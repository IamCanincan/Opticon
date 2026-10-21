// **第七闸那三段判定，每一段都得自己证明自己会响**（跑的是 `namedfloor.sh` 里那**一份**代码，不是抄本）。
//
// 为什么要有它：2026-10-21 修 `_gateaudit.cjs` 的表头正则之后，第七道真闸才第一次进审计表，
//   而它当时是「什么都没有」。顺手量出一件更糟的：**底线 15、分母已经长到 16** ⇒
//   `_namedmut.cjs` 删掉**整张浙江在线**（覆盖 15/16）**照样放行**。
//   ⇒ 一个跟着分母走的阈值写成绝对数，就会随分母漂（与本仓那条"绝对格数门槛"同族）。
//   修法是把判定改成**底线必须等于分母**，而改完必须有变异证明四个分支都会响 —— 就是这一台。
// 跑法：`node _namedfloor_mut.cjs`
const { spawnSync } = require('child_process');
const fs = require('fs');
const sh = (cmd, args) => spawnSync(cmd, args, { cwd: __dirname, encoding: 'utf8', maxBuffer: 1 << 26 });
const run = got => sh('sh', ['-c', `. ./namedfloor.sh; namedfloor_verdict '${got}'; echo "RC=$?"`]);
const out = r => (r.stdout || '').trim();

const F = 'bench/named_floor.txt';
if (!fs.existsSync('namedfloor.sh')) { console.error('!! 没有 namedfloor.sh —— 判定本体不在，这台子什么都测不了'); process.exit(9); }
const hadFloor = fs.existsSync(F), keep = hadFloor ? fs.readFileSync(F, 'utf8') : null;

const cases = [
  { name: '负对照：覆盖 == 底线 == 分母', got: '16 16', floor: '16', want: 0 },
  { name: '读不到合计（分母没了）', got: '读不到', floor: '16', want: 1,
    why: '原先这里退成写死的 "0 15" —— 表坏了会以"判据红"的样子出现，其实是量具坏' },
  { name: '底线 ≠ 分母（**今天真实的那一格盲区**）', got: '16 16', floor: '15', want: 1,
    why: '这一条就是今天量到的：分母 16 而底线 15 ⇒ 少认一张照样过' },
  { name: '少认一张（覆盖 15 < 底线 16）', got: '15 16', floor: '16', want: 1 },
  { name: '没有底线文件', got: '16 16', floor: null, want: 1 }
];

let bad = 0;
for (const c of cases) {
  if (c.floor === null) fs.rmSync(F, { force: true }); else fs.writeFileSync(F, c.floor);
  const r = run(c.got);
  const rc = parseInt((out(r).match(/RC=(\d+)$/) || [])[1], 10);
  const ok = rc === c.want;
  if (!ok) bad++;
  const said = out(r).replace(/\s*RC=\d+$/, '').split(/\r?\n/)[0] || '(一个字都没印)';
  console.log(`${ok ? '✅' : '🔴'} ${c.name}：退码 ${rc}（要 ${c.want}）—— ${said}`);
  if (!ok && c.why) console.log(`    ⚠ 而这一条要防的正是：${c.why}`);
  if (c.want === 0 && rc !== 0) console.log('    🔴 负对照就红 —— 后面的读数都不算数');
}
// 收尾：底线文件必须回到原来那份（**不许把别人的账本留在改过的状态**）。
if (hadFloor) fs.writeFileSync(F, keep); else fs.rmSync(F, { force: true });
const now = fs.existsSync(F) ? fs.readFileSync(F, 'utf8') : '(没有)';
console.log(`收尾自证：${F} = ${now.trim()}${(keep !== null && now === keep) ? '（逐字节还原 ✓）' : ''}`);
if (keep !== null && now !== keep) { console.error('!! 底线没还原，这台子自己就是那个会清别人账本的家伙'); process.exit(9); }
console.log(bad ? `🔴 ${bad} 条不对` : `全部 ${cases.length} 条按预期响 —— 第七闸四段判定都有牙`);
process.exit(bad ? 1 : 0);
