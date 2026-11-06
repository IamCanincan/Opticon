// 只把**过了前三道门**的那一族（探子在 `--dmin=0` 那一臂打出的 `⇒横幅`）的色差列摊出来，
// 顺带点名是谁 —— 色差线摆在 70 的全部辩护就是"两边各几个样本、中间没人站"，样本数不许靠记忆。
// 用法：node _deacct.cjs _tmp_ac/de_promo.txt _tmp_ac/de_tao.txt bench/reg/set_t45_384.txt …
const fs = require('fs');
const rows = [];
for (const f of process.argv.slice(2)) {
  for (const line of fs.readFileSync(f, 'utf8').split(/\r?\n/)) {
    if (!/⇒横幅/.test(line)) continue;
    const who = line.slice(0, 36).trim();
    for (const seg of line.split(/\s│\s族/).slice(1)) {
      if (!seg.includes('⇒横幅')) continue;
      const de = /ΔE(\d+(?:\.\d+)?)/.exec(seg);
      const col = /^(\d+)#([0-9A-F]{6})/.exec(seg);
      const g = /碰带块 宽(\d+)% 高(\d+)% 面积(\d+)%/.exec(seg);
      if (!de || !col || !g) { console.log(`!! 这一段的数读不全（解析器要跟着报告行一起改）：${who}`); continue; }
      rows.push({ de: +de[1], who, fam: col[1], color: col[2], w: +g[1], h: +g[2], a: +g[3] });
    }
  }
}
rows.sort((x, y) => x.de - y.de);
console.log(`过了前三道门的族：${rows.length} 条（色差列整串，含 70 两侧）`);
for (const r of rows) console.log(`  ΔE ${String(r.de).padStart(3)}  ${r.who}  族${r.fam}#${r.color} 宽${r.w}% 高${r.h}% 面积${r.a}%${r.de > 70 ? '' : '   ← 在线左（不退）'}`);
const lo = rows.filter(r => r.de <= 70).length, hi = rows.filter(r => r.de > 70).length;
console.log(`\n线左 ${lo} 条 ｜ 线右 ${hi} 条；离 70 最近的左侧读数 ${lo ? Math.max(...rows.filter(r => r.de <= 70).map(r => r.de)) : '无'}、最近右侧 ${hi ? Math.min(...rows.filter(r => r.de > 70).map(r => r.de)) : '无'}`);
if (!rows.length) console.log('!! 一条都没摊到 —— 这台子没电，别把"0 条"读成"这一族没样本"');
