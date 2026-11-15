// 两份桌面库里，指定包的彩色位图 / 单色位图指纹是否变了（用来判断"彩色幅动了"是真变了还是量具漂）。
// 用法：node _harness/_blobpair.cjs <before.db> <after.db> <包名|组件名> […]
const crypto = require('crypto'), { DatabaseSync } = require('node:sqlite');
const [bp, ap, ...needles] = process.argv.slice(2);
function load(p) {
  const d = new DatabaseSync(p, { readOnly: true });
  const m = new Map();
  for (const r of d.prepare('SELECT componentName c, icon i, mono_icon m FROM icons').all()) {
    const k = r.c.split('/')[0];
    if (!m.has(k)) m.set(k, r);                            // 与 _subj 同一条"每包取第一行"
  }
  return m;
}
const md5 = (b) => (b == null ? '—' : crypto.createHash('md5').update(Buffer.from(b)).digest('hex').slice(0, 10));
const A = load(bp), B = load(ap);
const list = needles.length ? needles : [...A.keys()].filter((k) => B.has(k) && (md5(A.get(k).i) !== md5(B.get(k).i) || md5(A.get(k).m) !== md5(B.get(k).m)));
if (!needles.length) {
  const only = [...A.keys()].filter((k) => B.has(k));
  const mDiff = only.filter((k) => md5(A.get(k).m) !== md5(B.get(k).m));
  const cDiff = only.filter((k) => md5(A.get(k).i) !== md5(B.get(k).i));
  const newIn = [...B.keys()].filter((k) => !A.has(k)), gone = [...A.keys()].filter((k) => !B.has(k));
  console.log(`共同 ${only.length} 包：单色变 ${mDiff.length}、彩色变 ${cDiff.length}（两边共 ${newIn.length} 个新行 / ${gone.length} 行没了）`);
  for (const k of mDiff) console.log(`  单色变  ${k}${cDiff.includes(k) ? '（彩色也变）' : ''}`);
  for (const k of cDiff.filter((k) => !mDiff.includes(k))) console.log(`  仅彩色变 ${k}`);
  process.exit(0);
}
console.log(`${'包'.padEnd(32)} ${'彩色 md5 前→后'.padEnd(26)} 单色 md5 前→后`);
for (const k of needles) {
  const a = A.get(k), b = B.get(k);
  if (!a || !b) { console.log(`${k.padEnd(32)} 有一边不在库里`); continue; }
  const csame = md5(a.i) === md5(b.i), msame = md5(a.m) === md5(b.m);
  console.log(`${k.padEnd(32)} ${(md5(a.i) + '→' + md5(b.i)).padEnd(26)} ${md5(a.m)}→${md5(b.m)}  ${csame ? '彩色同' : '彩色变'} / ${msame ? '单色同' : '单色变'}`);
}
