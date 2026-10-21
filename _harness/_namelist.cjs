// 把真机"未标记→已包装"名单摊开，按**候选判据**逐条数会不会翻。
//
// 起因（2026-10-17）：主人报「强制裁圆把快速设置的应用图标也改了」。日志里 SystemUI 被包装的是
// `org.localsend.localsend_app/mipmap/ic_launcher_quicktile_foreground` —— 一个 QS 磁贴的**前景图层**，
// 只因它在 `mipmap/` 里、名字又含 `ic_launcher`，就被现判据（`contains("/mipmap/") ||
//  contains("ic_launcher") || contains("appicon")`）当成应用图标裁了。
//
// 这一台不算账、只回答一件事：**把判据换成下面几种各会挡住名单里的哪几张**，
// 好让"收紧"不是拍脑袋 —— 本仓的规矩是挪阈值之前先摊分布。
// 用法：node _namelist.cjs [放行日志…]
const fs = require('fs');
const FILES = (process.argv.slice(2).length ? process.argv.slice(2) : ['_pass.txt'])
  .filter(f => fs.existsSync(f));

const names = new Map();   // 资源名 -> {次数, 进程集合}
let pmOnly = 0;
for (const f of FILES) {
  for (const l of fs.readFileSync(f, 'utf8').split('\n')) {
    if (!l.includes('放行(未标记)')) continue;
    const m = l.match(/: (\S+ \|)/);
    const pid = l.split(/\s+/)[2];
    let nm = m ? m[1].replace(/ \|\s*$/, '') : null;
    if (!nm) continue;
    if (nm.startsWith('pm:')) { pmOnly++; continue; }        // ApplicationInfo.icon == rid，硬证据，不参与改名
    if (!names.has(nm)) names.set(nm, { n: 0, pids: new Set() });
    const a = names.get(nm); a.n++; a.pids.add(pid);
  }
}

// 资源名形状： `<包名>/<类型>/<条目名>`
const entry = nm => nm.split('/').pop();
const typeOf = nm => (nm.split('/').length >= 3 ? nm.split('/')[nm.split('/').length - 2] : '?');

const RULES = [
  ['现判据（mipmap 或含 ic_launcher 或含 appicon）',
    nm => nm.includes('/mipmap/') || nm.includes('ic_launcher') || nm.includes('appicon')],
  ['① 条目名必须**恰好**是启动图标那一族',
    nm => /^(ic_launcher|ic_launcher_round|icon|app_icon|appicon|main_icon|launcher|logo)$/i.test(entry(nm))],
  ['② ①加上"不许是图层/磁贴衍生名"（后缀挡）',
    nm => RULES[1][1](nm) === true && !/(foreground|background|tile|round_)/.test(entry(nm))],
  ['③ 条目名以 ic_launcher / appicon **开头**（仍挡不住 _quicktile 那种）',
    nm => /^(ic_launcher|appicon|app_icon)/i.test(entry(nm))],
];

console.log(`== 真机"未标记→已包装"名单：${names.size} 个不同资源名（另有 ${pmOnly} 行是 pm: 硬证据，不参与）==`);
for (const [label, f] of RULES) {
  const kept = [...names].filter(([nm]) => f(nm));
  const cut = [...names].filter(([nm]) => !f(nm));
  console.log(`\n  ${label}\n    仍裁 ${kept.length} 个名字 / 挡住 ${cut.length} 个`);
  for (const [nm, a] of cut) console.log(`      挡住: ${nm}  （${a.n} 次，pid ${[...a.pids].join(',')}）`);
}
console.log('\n== 名单全文（次数 | 进程 | 类型 | 条目名）==');
for (const [nm, a] of [...names].sort((x, y) => y[1].n - x[1].n))
  console.log(`  ${String(a.n).padStart(3)}  pid ${[...a.pids].join(',').padEnd(12)} ${typeOf(nm).padEnd(7)} ${entry(nm).padEnd(30)} ${nm.split('/')[0]}`);
