// 读 GhostSweepKt 那张表，用**源锚定、与极性无关**的一把尺回答：
// 「源里确实是实的那一格，我们交付了多少墨？」= `芯α`
//
// 为什么前两把尺都不能当裁判（都在这张表上量死的）：
//   ① `实255`：start_snapshot `实255=10` 看着像被抹平，其实 21245 格全在 α=249 —— 屏幕上是一张
//      正常的细线图标。它数的是"顶档有没有正好打到 255"，那是量纲边角。
//   ② `均α / (2.55×暗墨%)`：假设"暗的就是墨"。表上 `暗% ≥ 70` 的 6 张里 5 张是**深底浅字**
//      （华为市场 / 微博 / B 站 / 网易云 / 索尼），那 90% 的暗就是盘子本身，尺子把它们报成
//      "擦了九成墨"。**判据一猜"哪块是墨"就整族误报** —— 这条和第十二段那四处误报同族。
//
// `芯α` 不猜：只统计源自己 α≥250 的格（图上确实有东西），并**跳过分母里那块最大平色**
// （占画布 ≥15% 才算盘，它本来就该被擦掉）。剩下的是"不管这张是深底还是浅底，画的东西
// 落在屏上有多实"。`芯%` 是它的分母，太小（<0.8%）就不算量到。
const fs = require('fs');
const p = process.argv[2] || 'D:/tmp/ghost.tsv';
const MIN = parseFloat(process.env.MINCORE || '0.8');
const lines = fs.readFileSync(p, 'utf8').trim().split(/\r?\n/);
const head = lines[0].split('\t');
const rows = lines.slice(1).map(l => {
  const c = l.split('\t'); const o = {}; head.forEach((k, i) => o[k] = c[i]);
  o.n = k => { const v = parseFloat(o[k]); return Number.isFinite(v) ? v : null; };
  o.core = o.n('芯α'); o.corePct = o.n('芯%'); o.solidCore = o.n('芯实%');
  o.tr = o.n('透明%'); o.mean = o.n('均α');
  o.usable = o.core !== null && o.core >= 0 && o.corePct >= MIN;
  return o;
});
console.log(`表上 ${rows.length} 张；芯格 ≥ ${MIN}% 画布的 ${rows.filter(r => r.usable).length} 张可量，` +
  `${rows.filter(r => r.n('透明%') >= 50).length} 张是空气底，${rows.filter(r => !r.usable).length} 张没有芯可量`);

const show = (t, g, k = 20) => {
  console.log(`\n### ${t}（${g.length} 张）`);
  for (const r of g.slice(0, k)) console.log(
    `   芯α=${String(r.core.toFixed(0)).padStart(3)} 芯实${r.solidCore.toFixed(0)}% 芯${r.corePct}% 画布 | ` +
    `透${r.tr}% 均α=${r.mean} 底占备${r.备底占}/离${r.离底占} H备${r.备H}/离${r.离H} 框内${r.离框内} | ` +
    `${r.log} ${r.label.slice(0, 40)}`);
};
const ok = rows.filter(r => r.usable).sort((a, b) => a.core - b.core);
show('芯α 最低的一批（这些是"画的东西落在屏上不实"）', ok);
show('空气底（透明% ≥ 50）里芯α 最低的', ok.filter(r => r.tr >= 50), 12);
show('有盘子里芯α 最低的', ok.filter(r => r.tr < 50), 12);

const bins = [0, 40, 80, 120, 160, 200, 230, 250, 999];
console.log('\n### 芯α 分布（全量）');
for (let i = 0; i < bins.length - 1; i++) {
  const g = ok.filter(r => r.core >= bins[i] && r.core < bins[i + 1]);
  if (g.length) console.log(`   ${String(bins[i]).padStart(3)}~${String(bins[i + 1]).padStart(3)}: ${'#'.repeat(Math.min(g.length, 50))} ${g.length}`);
}
// 对照：主人点名的两张 + 上一轮我判过的那张，看这把尺认不认得出差别
for (const pat of ['pinduoduo/mipmap/pdd', 'libchecker_start_snapshot', 'start_chart', 'contacts', 'fbclient']) {
  const r = ok.find(x => x.label.includes(pat));
  if (r) console.log(`对照 ${pat.padEnd(26)} 芯α=${r.core.toFixed(0)} 芯${r.corePct}% 均α=${r.mean} 透${r.tr}%`);
}
