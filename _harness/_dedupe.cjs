// 按**像素**给语料去重：同一个 drawable 每轮 dump 换一个后缀，于是逐字节相同的图在语料里
// 占好几行（2026-10-04 量到 rec3 的 113 个文件里 51 个是重复的）。
// 跑法：node _dedupe.cjs <源目录> <目标目录>     例：node _dedupe.cjs rec3 rec3u
const fs = require("fs");
const crypto = require("crypto");
const [src, dst] = process.argv.slice(2);
if (!src || !dst) { console.error("用法：node _dedupe.cjs <源目录> <目标目录>"); process.exit(2); }
// 按名字排序后保留**第一个**，保证同一份源每次跑出同一个目标目录（不然基线会随枚举顺序漂）。
const files = fs.readdirSync(src).filter((f) => f.endsWith(".png")).sort();
fs.mkdirSync(dst, { recursive: true });
const seen = new Set();
let kept = 0, dropped = 0;
for (const f of files) {
  const h = crypto.createHash("md5").update(fs.readFileSync(`${src}/${f}`)).digest("hex");
  if (seen.has(h)) { dropped++; continue }
  seen.add(h);
  fs.copyFileSync(`${src}/${f}`, `${dst}/${f}`);
  kept++;
}
for (const f of fs.readdirSync(src).filter((x) => !x.endsWith(".png"))) {
  if (fs.statSync(`${src}/${f}`).isFile()) fs.copyFileSync(`${src}/${f}`, `${dst}/${f}`);
}
console.log(`${src} → ${dst}：留 ${kept} 张、去掉 ${dropped} 张逐字节重复（共 ${files.length} 个 png）`);
