// 把一轮抓取拼成**模型运行时真正吃进去的那一张**：
// 自适应图标取 `*_fg.png`（前景层），非自适应取它本身（整张就是输入）。
// 跑法：node _mkruntime.cjs <抓取目录> <输出目录>
//
// 为什么单独一步：`CorpusCapture` 主文件一直是 `raster(整个 AdaptiveIconDrawable)` = **合成图**，
// 而 `MonochromeSynth` 只把 `source.foreground` 交给模型 —— 盘在背景层里，模型从来没见过它。
// 真机 91 个自适应图标的前景层中位 74% 是透明。2026-10-06 量定：15 张肉眼缺陷账里 **7 张**
// 两种口径交出的 `框内` 差 1.7~6 倍（驾考宝典 0.24→0.58、Google 翻译 0.36→0.06）。
// 所以审计线必须对着这里拼出来的目录跑，不然挑的是不存在于屏幕上的毛病。
//
// ⚠ **2026-10-12：上面那句前提过期了一半。** `MonochromeSynth` 在 2026-10-09 改成
//   `background !is ColorDrawable` 就交**整张合成图**（`flatten`），91 张自适应里这样的有 **57 张** ——
//   对这一批，"取 *_fg.png"就不是装机那一张了。实测 `com.sony.songpal.mdr`：
//   fg 口径 `带占=8 底占=0.48 H=3 交付墨 36317 格`，装机口径（合成图 + `_modelbg`）
//   `〔under〕实心=100% 底占=0.89 H=1 交付墨 6722 格` —— `框内 0.52 vs 0.11`，**不是通道打架，是喂了两个输入**。
//   装机口径的目录是 `_mkrec_model.cjs` 那份（`*_model.png` + `*_modelbg.png`）；
//   这一份现在只留作**对照**（看"少了背景层会判成什么样"），别再拿它当达标线。
const fs = require("fs");
const path = require("path");
const [src, dst] = process.argv.slice(2);
if (!src || !dst) { console.error("用法：node _mkruntime.cjs <抓取目录> <输出目录>"); process.exit(2); }
const idx = path.join(src, "index.tsv");
if (!fs.existsSync(idx)) { console.error("没有 " + idx + " —— 这一版抓取没写索引，拼不出运行时输入"); process.exit(1); }
const lines = fs.readFileSync(idx, "utf8").split(/\r?\n/).filter((l) => l.trim());
const head = lines[0].split("\t");
const iFile = head.indexOf("file"); const iFg = head.indexOf("fgFile");
if (iFile < 0 || iFg < 0) { console.error("索引里没有 file/fgFile 列 —— 抓取端要先加上（`facaae2` 之后的版本才有）"); process.exit(1); }
fs.rmSync(dst, { recursive: true, force: true });
fs.mkdirSync(dst, { recursive: true });
let n = 0, fromFg = 0, missing = 0;
for (const l of lines.slice(1)) {
  const c = l.split("\t");
  const f = c[iFile];
  if (!f || !f.endsWith(".png") || f.includes("_mono") || f.includes("_fg")) continue;
  const g = c[iFg];
  const useFg = g && g !== "-" && g !== "null" && g !== "err" && fs.existsSync(path.join(src, g));
  const pick = useFg ? g : f;
  if (!fs.existsSync(path.join(src, pick))) { missing++; continue }
  // 落回**合成图的文件名**：下游按包名匹配（`_named.cjs`、快照 diff），换口径不换身份。
  fs.copyFileSync(path.join(src, pick), path.join(dst, f));
  if (useFg) fromFg++;
  n++;
}
console.log(`${dst}/：${n} 张运行时输入，其中 ${fromFg} 张取自前景层` +
  (missing ? `（${missing} 张文件没抓回来，跳过）` : "") +
  `。⚠ 这一份才是屏幕上的口径；${src}/ 是合成图，只配当参照。`);
if (n === 0) process.exit(1);
