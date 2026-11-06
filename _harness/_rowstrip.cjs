// 「整行占比」这一族判据在四档边长上给的**退族集合**是否一致 —— 逐档摊出来，不靠单档下结论。
// 用法：node _rowstrip.cjs bench/reg/ps_96.txt bench/reg/ps_192.txt bench/reg/ps_384.txt bench/reg/ps_768.txt
// 判据与生产那三道门同形，只把"碰带连通块的包围盒"换成"连续整行的分数"：高≤30% 且 面积≥1% 且 ΔE>70 且 族>1。
const fs = require('fs');

function parseFile(p) {
  const map = new Map();
  let chunks = 0;
  for (const line of fs.readFileSync(p, 'utf8').split(/\r?\n/)) {
    if (!line.includes('碰带块') && !line.includes('无碰带块')) continue;
    const title = line.slice(0, 36).trim();
    // 切在 ` │ 族` 上，`族` 字被吃掉 → 块首是 `0#RRGGBB …`
    const fams = line.split(/\s│\s族/).slice(1);
    const drops = [];
    for (const f of fams) {
      const j = /^(\d+)/.exec(f); if (!j) continue;
      chunks++;
      const de = /ΔE(\d+(?:\.\d+)?)/.exec(f);
      const rows = /│行带 起(\d+)% 高(\d+)% 面积(\d+)%（最满行(\d+)%）/.exec(f);
      if (!rows) continue;                       // `行满0` = 没有任何一整行被这一族占满
      const h = +rows[2], area = +rows[3];
      if (area >= 1 && h <= 30 && de && +de[1] > 70 && fams.length > 1)
        drops.push(`族${j[1]}(高${h} 面积${area} ΔE${de[1]})`);
    }
    if (drops.length) map.set(title, drops.join(','));
  }
  return { map, chunks };
}

const files = process.argv.slice(2);
const got = files.map(f => parseFile(f));
// **开火自检**：驾考 384 那一档按上面三道门必然中（高23 面积23 ΔE110），扫不到就是解析器瞎了。
const c384 = got.find((_, i) => /ps_384|edge=384/.test(files[i]));
const ck = c384 && [...c384.map.keys()].find(k => k.includes('jiakao'));
console.log(`自检：读到 ${got.reduce((a, g) => a + g.chunks, 0)} 个族块；驾考 384 开火 = ${ck ? '✔ ' + c384.map.get(ck) : '✘（仪器没电，下面的数不算）'}`);
if (!ck) process.exit(1);

files.forEach((f, i) => console.log(`${f.replace(/^.*ps_/, '').replace('.txt', '')}: ${got[i].map.size} 张有退族`));
const all = new Set(got.flatMap(g => [...g.map.keys()]));
let drift = 0;
for (const k of [...all].sort()) {
  const vals = got.map(g => g.map.get(k) || '—');
  if (new Set(vals).size !== 1) {
    drift++;
    console.log(`漂：${k}\n    ` + files.map((f, i) => f.replace(/^.*ps_/, '').replace('.txt', '') + '=' + vals[i]).join('  '));
  }
}
console.log(`\n四档全扫里有退族的张数：${all.size}；其中随档漂的：${drift}`);
console.log('\n384 那一档的退族名单：');
for (const [k, v] of (c384 ? c384.map : new Map())) console.log(`  ${k} → ${v}`);
