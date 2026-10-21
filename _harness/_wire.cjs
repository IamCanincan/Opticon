const fs = require('fs');
let s = fs.readFileSync('regress.sh', 'utf8');
const a = 'echo "== 5/6 对表（人画的单色层）=="';
const c = 'grep -q "^YARD n=[1-9]" bench/reg/yard.txt';
if (!s.includes(a) || s.indexOf('ObjKtKt') >= 0) { console.log('!! regress.sh 锚点不对或已接入'); process.exit(1); }
s = s.replace(a, 'echo "== 5/6 对表（人画的单色层）+ 平色饼 =="');
const i = s.indexOf(c);
const end = s.indexOf('\n', s.indexOf('exit 1; }', i));
const add = '\n' +
  '# 平色饼：**源里本来有 ≥4 档亮度、交出去却塌到 Neff ≤2.5** —— 只数模型真会处理的那些\n' +
  '# （语料带 index.tsv 时按 mono 列排除已适配的）。这条**有阳性对照**：拿两刀之前的源码跑\n' +
  '# 同一个探针报 23 / 49，现在 6 / 4 —— 量具抓得住被修掉的那个病，不是又一个「看起来有数」的指标。\n' +
  'run harness.ObjKtKt corpus_dev2 | grep "^FLAT" > bench/reg/flat.txt\n' +
  'run harness.ObjKtKt rec3 | grep "^FLAT" >> bench/reg/flat.txt\n' +
  'cat bench/reg/flat.txt\n' +
  '[ "$(grep -c "^FLAT n=[1-9]" bench/reg/flat.txt)" = "2" ] || { echo "!! 平色饼台没数出两条 —— 语料或驱动坏了"; exit 1; }';
s = s.slice(0, end) + add + s.slice(end);
fs.writeFileSync('regress.sh', s);

let r = fs.readFileSync('_regress.cjs', 'utf8');
const e = 'const now = {';
const blk = '// —— 平色饼：源有层次而交付塌了。只数模型真会处理的那些（ObjKt 按 index.tsv 排除已适配）——\n' +
  'const fl = (fs.existsSync(`${R}/flat.txt`) ? fs.readFileSync(`${R}/flat.txt`, "utf8") : "").trim();\n' +
  'const flines = fl.split("\n").filter(l => l.startsWith("FLAT "));\n' +
  'const flat = { flatN: 0, flatDen: 0 };\n' +
  'for (const l of flines) { flat.flatN += num(l, "flat"); flat.flatDen += num(l, "n"); }\n\n' + e;
if (!r.includes(e)) { console.log('!! _regress 锚点不对'); process.exit(1); }
r = r.replace(e, blk);
r = r.replace('  yardN: yard.n, yardMed: yard.med, yardMean: yard.mean,',
  '  yardN: yard.n, yardMed: yard.med, yardMean: yard.mean,\n  flatN: flat.flatN, flatDen: flat.flatDen,');
r = r.replace('  ["对表 配对数（人画的层）", "yardN", 1, "少了就是对岸被动过 —— 不许悄悄变 0"],',
  '  ["对表 配对数（人画的层）", "yardN", 1, "少了就是对岸被动过 —— 不许悄悄变 0"],\n' +
  '  ["平色饼（源有层次而交付塌了）", "flatN", -1, "越小越好（只数未适配的那半）"],\n' +
  '  ["平色饼分母", "flatDen", 1, "少了就是语料被动过"],');
fs.writeFileSync('_regress.cjs', r);
console.log('已接入 regress.sh 与判分器');
