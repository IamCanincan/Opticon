// **桌面上那些"格子"到底是什么** —— 读 `launcher.db` 的 `favorites`，把没有资源图标的项点名。
// 立这条的因由：那把全空掩码（`? GradientDrawable intrinsic=-1x-1`）在 `app_icons.db` 里没有对应行
//   （那里 768 张 `mono_icon` 0 张全空），而唯一 4 行连 `icon` 都没有的是 **com.android.deskclock**
//   的四个组件、其中两个是 AppWidgetProvider ⇒ 候选是"小部件宿主"。
//   小部件不在图标库里存位图，所以要去**工作区表**里数：`itemType=4` 就是 widget。
// ⚠ 这张表说的是"屏幕上摆了什么"，**不是**"钩子收到了哪个对象"—— 用它下结论只到"候选"这一级。
// 跑法：node _wsitems.cjs D:/tmp/launcher_now.db
const { DatabaseSync } = require('node:sqlite');
const f = process.argv[2];
const db = new DatabaseSync(f, { readOnly: true });
const tabs = db.prepare("select name from sqlite_master where type='table'").all().map(r => r.name);
console.log('表：', tabs.join(', '));
if (!tabs.includes('favorites')) { console.error('!! 没有 favorites 表 —— 这台读不出任何东西'); process.exit(2); }
const rows = db.prepare('select * from favorites').all();
const T = { 0: '所有应用页', 1: '应用', 2: '文件夹', 4: '小部件', 6: '应用对', 99: '?' };
console.log(`\n共 ${rows.length} 项。按 itemType：`);
const by = {};
for (const r of rows) by[r.itemType] = (by[r.itemType] || 0) + 1;
for (const k of Object.keys(by)) console.log(`  ${k}（${T[k] || '?'}）= ${by[k]}`);
console.log('\n== 没有 `iconResource` 的项（这些的"图标"不是从资源里inflate出来的）==');
let n = 0;
for (const r of rows) {
  const res = r.iconResource, pkg = r.iconPackage;
  if (res || (pkg && pkg !== '')) continue;
  n++;
  console.log(`  itemType=${r.itemType}（${T[r.itemType] || '?'}） 标题=${String(r.title).padEnd(16)} ` +
    `component=${r.intent ? String(r.intent).slice(0, 76) : '-'} 位置=容器${r.container}/屏${r.screen}` +
    ` ${r.cellX},${r.cellY} 跨${r.spanX}x${r.spanY}`);
}
if (!n) console.log('  （一条都没有 —— 那"没有资源图标"这一族在这张表里不成立，别说成"屏幕上没有"）');
console.log('\n== 小部件全部名单 ==');
for (const r of rows.filter(x => x.itemType === 4))
  console.log(`  ${String(r.title).padEnd(18)} ${String(r.intent).slice(0, 90)}` +
    `  iconPackage=${r.iconPackage || '-'} iconResource=${r.iconResource || '-'}`);
console.log('\n== 出现 deskclock 的行 ==');
for (const r of rows.filter(x => JSON.stringify(x).includes('deskclock')))
  console.log(`  itemType=${r.itemType} 标题=${r.title} intent=${String(r.intent).slice(0, 80)}`);
db.close();
