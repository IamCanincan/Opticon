// 把每张**上墨的格**按浓度分三桶（<128 / 128-254 / 恰好 255），看"这一支交得淡不淡"。
// 为什么单独一台：`淡=` 那一列在判据台里被当过验收线，而它把 feimo（主人点名的样板）
// 判成最淡的一张 —— 也就是说它量的不是缺陷，是这一支的**目的**。用法：
//   node _faint.cjs bench/ship_rec3u.txt [bench/ship_rec_blank.txt …]
const fs = require('fs');
const want = {
  feimo: 'feimo(样板)', tmgp: 'sgame(国)', sgameGlobal: 'sgame(国际)', browndust: 'browndust',
  pinduoduo: '拼多多', aweme: '抖音', bili: 'B站', uuremote: 'UU', azure: 'azure',
  songpal: 'songpal', xbox: 'xbox', wisdomgame: '斗地主', hunyuan: '混元',
  kazumi: 'kazumi', tieba: '贴吧', cainiao: '菜鸟', webapk: 'PH(播放器)',
};
const rows = [];
for (const f of process.argv.slice(2)) {
  let cur = null;
  for (const l of fs.readFileSync(f, 'utf8').split(/\r?\n/)) {
    if (/^--- /.test(l)) { cur = l.replace(/^--- /, '').trim().split(/\s+/)[0]; continue; }
    if (!cur) continue;
    const k = Object.keys(want).find(q => cur.indexOf(q) >= 0);
    if (!k) continue;
    const m = l.match(/浓度\[0=(\d+) 淡1-127=(\d+) 中128-254=(\d+) 实255=(\d+)\]/);
    if (!m) continue;
    const ink = +m[2] + +m[3] + +m[4];
    if (ink === 0) continue;
    rows.push([want[k], ink, Math.round(m[2] * 100 / ink), Math.round(m[3] * 100 / ink), Math.round(m[4] * 100 / ink)]);
  }
}
rows.sort((a, b) => a[2] - b[2]);
console.log('图标           上墨格    α<128    中128-254  =255');
for (const r of rows) console.log(
  r[0].padEnd(13), String(r[1]).padStart(7), String(r[2] + '%').padStart(8),
  String(r[3] + '%').padStart(10), String(r[4] + '%').padStart(7));
console.log('\n⚠ 这一列**不能当验收线**：feimo 是主人点名的样板，而它是这里最淡的一张。');
