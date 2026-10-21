const fs = require('fs');
// ⚠ 动态正则在这条通道上会被吞掉反斜杠（`\S` → 裸 `S`），所以一律先扫成 map。
function rows(f, tag) {
  const out = [];
  for (const blk of fs.readFileSync(f, 'utf8').split(/\n(?=--- )/)) {
    const h = blk.match(/^--- (\S+)/); if (!h) continue;
    const g = {}; for (const kv of blk.matchAll(/([^\s=]+)=([^\s]+)/g)) if (!(kv[1] in g)) g[kv[1]] = kv[2];
    const c = blk.match(/浓度\[0=(\d+) 淡1-127=(\d+) 中128-254=(\d+) 实255=(\d+)\]/);
    if (!c) continue;
    const [z, f0, m, s] = [+c[1], +c[2], +c[3], +c[4]];
    const ink = f0 + m + s; if (!ink) continue;
    const sp=(g['分档']||'0/0').split('/');const lv=+sp[0];const parts=parseInt(sp[1]||'0',10)||0;
    out.push({ tag, n: h[1], solidShare: s / ink, midShare: m / ink, faintShare: f0 / ink, lv, parts,
      cap: +(g['顶占比'] || 0), mark: +(g['字均净'] || 0), fill: +(g['框内'] || 0) });
  }
  return out;
}
const all = [...rows('bench/all_var.txt', 'ALL')];
const dev = all.filter(r => !/^icon\d+_/.test(r.n));
const pool = all.filter(r => /^icon\d+_/.test(r.n));
const q = (a, p) => a.slice().sort((x, y) => x - y)[Math.floor(a.length * p)];
const pct = v => (v * 100).toFixed(0) + '%';
for (const [name, rs] of [['真机 122', dev], ['老语料', pool]]) {
  console.log(`\n${name}：${rs.length} 张（有墨的）`);
  console.log(`  墨里钉在 255 的比例  中位 ${pct(q(rs.map(r => r.solidShare), .5))}  p90 ${pct(q(rs.map(r => r.solidShare), .9))}`);
  console.log(`  墨里是中间灰 128~254 中位 ${pct(q(rs.map(r => r.midShare), .5))}  p10 ${pct(q(rs.map(r => r.midShare), .1))}`);
  console.log(`  交付档数            中位 ${q(rs.map(r => r.lv), .5)}  p90 ${q(rs.map(r => r.lv), .9)}  ·只有一档的 ${rs.filter(r => r.lv <= 1).length} 张`);
  console.log(`  块数(最多 6)        中位 ${q(rs.map(r => r.parts), .5)}  ·块数≥3 却只有一档的 ${rs.filter(r => r.lv <= 1 && r.parts >= 3).length} 张 ← 被压平的那批`);
}
const sq = dev.filter(r => r.lv <= 1 && r.parts >= 3).sort((a, b) => b.parts - a.parts);
console.log('\n被压平最狠的 10 张（档数/块数 · 框内 · 255 占比）：');
for (const r of sq.slice(0, 10)) console.log(`  ${r.n.replace(/_[0-9a-f]{12}(\.png)?$/, '').slice(0, 46).padEnd(46)} ${r.lv}档/${r.parts}块 框内=${r.fill} 实=${pct(r.solidShare)}`);
