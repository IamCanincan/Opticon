// 把一轮抓取（`corpus/`）里**非适配**那批的「模型实际吃进去的那一张」拼成一个回归台语料。
//
// 为什么要它：`rec3u` 是真机 dump（口径对、素材旧），`corpus_runtime` 是合成图（素材新、口径错）。
// 抓取通道 2026-10-11 补上 `_model.png` / `_modelbg.png` 之后，第一次能有
// **“素材新 + 口径对”** 的那一份 —— 这一份才是十闸该钉基线的分母。
//
// 用法：node _mkrec_model.cjs <抓取目录> <输出目录>
const fs = require('fs'), path = require('path');
const src = process.argv[2], out = process.argv[3];
if (!src || !out) { console.log('用法: node _mkrec_model.cjs <抓取目录> <输出目录>'); process.exit(2); }
fs.mkdirSync(out, { recursive: true });
const lines = fs.readFileSync(path.join(src, 'index.tsv'), 'utf8').trim().split(/\r?\n/);
const head = lines[0].split('\t');
const col = (n) => head.indexOf(n);
const iFile = col('file'), iMono = col('mono'), iModel = col('modelFile'), iModelBg = col('modelBgFile');
if (iModel < 0) { console.log('!! 这一轮抓取还没有 modelFile 列 —— 装的是旧包，抓的是旧口径'); process.exit(1); }
let kept = 0, adapted = 0, missing = 0, withUnder = 0;
const rows = [head.map((c, i) => (i === iFile ? 'file' : c)).join('\t')];
for (const l of lines.slice(1)) {
  const c = l.split('\t');
  if (c.length < head.length) continue;
  if (c[iMono] === '有') { adapted++; continue; }          // 直通，根本不上模型
  const mf = c[iModel] === 'same' ? c[iFile] : c[iModel];
  if (!mf || mf === 'null' || mf === 'err' || !fs.existsSync(path.join(src, mf))) { missing++; continue; }
  // 名字口径：`_model.png` 去掉后缀 = 这一张的 stem；`same` 那批的 `file` 本身就带 `.png`，
  // 所以先剥掉扩展名再拼，别写出 `xxx.png.png`（第一版就是这么错的）。
  const stem = mf.replace(/_model\.png$/, '').replace(/\.png$/, '');
  const dst = path.join(out, stem + '.png');
  if (!fs.existsSync(dst)) fs.copyFileSync(path.join(src, mf), dst);
  let bg = '-';
  if (c[iModelBg] && c[iModelBg] !== '-' && fs.existsSync(path.join(src, c[iModelBg]))) {
    bg = stem + '_modelbg.png';
    if (!fs.existsSync(path.join(out, bg))) fs.copyFileSync(path.join(src, c[iModelBg]), path.join(out, bg));
    withUnder++;
  }
  const r = c.slice();
  r[iFile] = path.basename(dst);
  // ⚠ `modelFile` 这一列必须**跟着改成搬过来之后的名字**：驱动按它去找文件，而这里已经把
  //   `_model.png` 改名成 `<stem>.png`。不重写的话 `--input=model` 只认得那 32 张 `same`，
  //   30 张自适应全被 `isFile` 挡掉 —— 分母自己缩水了还照样印"中位 0.06%"。（第一版就是这样。）
  r[iModel] = path.basename(dst);
  if (iModelBg >= 0) r[iModelBg] = bg;
  rows.push(r.join('\t'));
  kept++;
}
fs.writeFileSync(path.join(out, 'index.tsv'), rows.join('\n') + '\n');
console.log(`写出 ${out}：非适配 ${kept} 张（其中 ${withUnder} 张带 under）｜ 直通剔除 ${adapted} ｜ 缺图跳过 ${missing}`);
