const fs = require('fs');
// ⚠ 这里**不能**用 `new RegExp(k + '=(\S+)')`：写文件的通道会把 `\` 收成一个 `\`，
//   于是 JS 字符串里 `\S` 变成裸 `S`，模式成了 `盒布=(S+)` —— 一个字段都匹配不上，
//   而脚本照样"跑通了、报了 0 张"。改成一次全局扫成 map，再按 key 取。
function parse(f, tag) {
  const out = [];
  for (const blk of fs.readFileSync(f, 'utf8').split(/\n(?=--- )/)) {
    const m = blk.match(/^--- (\S+)/); if (!m) continue;
    const g = {};
    for (const kv of blk.matchAll(/([^\s=]+)=([^\s]+)/g)) if (!(kv[1] in g)) g[kv[1]] = kv[2];
    const num = (k) => (g[k] === undefined ? NaN : parseFloat(g[k]));
    out.push({ n: m[1], tag, H: g['H'], box: num('盒布'), fill: num('框内'), zero: num('零'),
      solid: num('实'), ground: g['底占'], fam: g['族'], plate: g['盘实'], mark: num('字均'),
      sep: g['分档'], veto: g['拦'], h1: g['H1被否'], h2: g['H2被否'] });
  }
  return out;
}
const rows = [...parse(process.argv[2], 'dev'), ...parse(process.argv[3], 'pool')];
const ok = rows.filter(r => !isNaN(r.box));
console.log(`行 ${rows.length}（解出字段 ${ok.length}）dev=${rows.filter(r=>r.tag==='dev').length} pool=${rows.filter(r=>r.tag==='pool').length}`);
const fb = ok.filter(r => r.box === 100);
console.log(`满幅(盒布=100)：${fb.length} 张 · dev ${fb.filter(r=>r.tag==='dev').length} / pool ${fb.filter(r=>r.tag==='pool').length}`);
const cl = fb.filter(r => r.fill <= 0.15);
console.log(`\n满幅且刻完只剩 ≤15% 墨：${cl.length} 张`);
for (const r of cl.sort((a, b) => a.fill - b.fill))
  console.log(`  ${r.tag} ${r.n.slice(0,42).padEnd(42)} H=${r.H} 框内=${r.fill} 零=${r.zero}/${r.solid} 底占=${r.ground} 族=${r.fam} 盘实=${r.plate} 分档=${r.sep}`);
const hz = ok.filter(r => r.zero >= 100);
console.log(`\n零≥100（底参照被推高）：${hz.length} 张`);
for (const r of hz.sort((a, b) => b.zero - a.zero))
  console.log(`  ${r.tag} ${r.n.slice(0,42).padEnd(42)} 零=${r.zero} 框内=${r.fill} 盒布=${r.box} H=${r.H} 族=${r.fam} 拦=${r.veto}`);
