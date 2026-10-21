// **跨线检查**：同一个应用的两次抓取，有没有哪个判据数**正好跨过一条阈值**。
//
// 为什么立这个（2026-10-09）：仓里早就写着一条规矩 —— "定完一条线，拿同一个应用的两份不同抓取
// 去试它跨不跨线；跨了就是线在噪声里，不是两张不一样"（`TONAL_FLAT_MAX` 14→16 那次记下的）。
// 那天只手动试过一张。今天把它变成一条每次都能跑的账，第一次跑就抓到：
//   · `com.android.contacts` 跨 = 35 / 34 → 一份开火一份不开
//   · `com.huawei.appmarket` 跨 = 35 / 32 → 同上
// 也就是入场闸 `TONAL_SPAN_MIN=35` **在自己的测量噪声里面**。
//
// 用法：先 `sh run.sh harness.TonalGateKtKt <语料> --top=999 > bench/tg_<语料>.txt`（**不要加 --all**，
// 加了就只印开火的那些），再 `node _straddle.cjs`。
const fs = require('fs');
const TH = { span: 35, flat: 40, tex: 30 };   // 三条会决定走不走/切不切的线
const files = fs.readdirSync('bench').filter(f => /^tg_.*\.txt$/.test(f));
const byKey = new Map();
for (const f of files) {
  const src = f.replace(/^tg_/, '').replace(/\.txt$/, '');
  for (const x of fs.readFileSync('bench/' + f, 'utf8').split(/\r?\n/)) {
    const c = x.split('\t'); if (c.length < 2) continue;
    const m = c[1].match(/^(\d+)\/(\d+)\/(\d+)$/); if (!m) continue;
    const name = c[0].replace(/^icon\d+_/, '');
    const pkg = name.replace(/_.*$/, '') || name;
    const k = src + '|' + pkg;
    if (!byKey.has(k)) byKey.set(k, []);
    byKey.get(k).push({ name, tex: +m[1], flat: +m[2], span: +m[3], fired: c.length > 2 && c[2].trim() === '1' });
  }
}
let groups = 0, hits = 0;
for (const [k, v] of byKey) {
  if (v.length < 2) continue; groups++;
  for (const th of Object.keys(TH)) {
    const vals = v.map(x => x[th]);
    const lo = Math.min(...vals), hi = Math.max(...vals);
    if (lo < TH[th] && hi >= TH[th]) {
      hits++;
      console.log(`🔴 ${k.padEnd(38)} ${th.padEnd(5)} 阈值 ${TH[th]}：${vals.join(" / ")}  开火=${v.map(x => x.fired ? 1 : 0).join("/")}`);
    }
  }
}
console.log(`\n${groups} 组"同一份语料里同一个包的多张 dump"，抓到 ${hits} 处跨线。`);
console.log('跨线 = 这条阈值线站在测量噪声里：换一次抓取就可能换一支。不是"两张不一样"。');
