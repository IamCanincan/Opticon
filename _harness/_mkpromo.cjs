// 重建促销皮肤语料 `rec_promo`：名字从**当时那轮 A/B 日志**里取（`--- <名>  752ms` 那些行），
// 文件只准从**输入级**的来源拷（抓取目录 / 已入库的语料），`_shot_on` `_shot_off` 那种 A/B **输出**
// 一律不许当输入 —— 本仓为"语料里混进判据台自己的产物"交过一次假结论（regress.sh 文件头第 ① 条）。
//
// 用法：node _mkpromo.cjs <A/B日志> <输出目录> [--src=<目录> [--src=<目录> …]]
// 不给 --src 时用下面这个优先级：抓取目录在前（带 index.tsv，口径全），仓内语料在后。
const fs = require('fs'), path = require('path');

const log = process.argv[2], out = process.argv[3];
if (!log || !out) { console.log('用法: node _mkpromo.cjs <A/B日志> <输出目录> [--src=…]'); process.exit(2); }
const srcs = process.argv.filter(a => a.startsWith('--src=')).map(a => a.substring(6));
if (!srcs.length) srcs.push('/d/tmp/corpus_model/corpus', 'corpus_taobao_new', 'plate_model', 'shortcut_model', 'yard_model', 'rec_model');

const names = [...new Set(
  fs.readFileSync(log, 'utf8').split(/\r?\n/).map(l => /^--- (\S+)/.exec(l)?.[1]).filter(Boolean)
)];
console.log(`名单来自 ${log}：${names.length} 张`);

// 排除的目录名前缀：这些是量具自己的产物，不是任何人交的图
const BAD = /(_shot_|_ab_|_prev|_out_|_fix_|_d_new|_d_old|_eg_|_b_try|_pure|_one|_geom_|_mb_|_chg|captured)/;

fs.mkdirSync(out, { recursive: true });
const idx = [];
let copied = 0; const missing = [];
for (const n of names) {
  let hit = null;
  for (const d of srcs) {
    if (BAD.test(d + '/')) continue;
    const p = path.join(d, n + '.png');
    if (fs.existsSync(p)) { hit = d; break; }
  }
  if (!hit) { missing.push(n); continue; }
  // 同名前缀的全部文件一起搬（`_model` `_modelbg` `_bg` `_fg` `_mono`），少一个 sidecar 就等于换口径
  let side = 0;
  for (const f of fs.readdirSync(hit)) {
    if (!f.startsWith(n) || !f.endsWith('.png')) continue;
    fs.copyFileSync(path.join(hit, f), path.join(out, f)); side++;
  }
  // index.tsv：从命中的那份抓取目录里把这一行接上（表头按 `file` 列名找，不按位置猜）
  const ip = path.join(hit, 'index.tsv');
  if (fs.existsSync(ip)) {
    const lines = fs.readFileSync(ip, 'utf8').trim().split(/\r?\n/);
    const head = lines[0].split('\t');
    const iFile = head.indexOf('file');
    if (iFile < 0) { console.log(`!! ${ip} 没有 file 列 —— 这一行的归属没法认`); }
    else {
      if (!idx.length) idx.push(lines[0]);
      const row = lines.slice(1).find(l => path.basename((l.split('\t')[iFile] || '')) === n + '.png');
      if (row) idx.push(row); else console.log(`   index 里没有这一行：${n}`);
    }
  }
  copied++;
}
if (idx.length > 1) fs.writeFileSync(path.join(out, 'index.tsv'), idx.join('\n') + '\n');
console.log(`拷进 ${out}：${copied} 张（含 sidecar 共 ${fs.readdirSync(out).filter(f => f.endsWith('.png')).length} 个 .png），index 行 ${idx.length - 1}`);
console.log(`没有输入级来源的：${missing.length}${missing.length ? ' → ' + missing.join(', ') : ''}`);
console.log('\n⚠ 来源：' + srcs.filter(s => !BAD.test(s + '/')).join(' → '));
if (!copied) { console.log('!! 一张都没拷到 —— 这台子没电，别把"0 张"读成"这一族没了"'); process.exit(1); }
