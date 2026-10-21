const fs = require('fs');
let t = fs.readFileSync('README.md', 'utf8');
const a = '### 未发布（v1.0.3 之后）\n';
const add = a + `- 量死（**「墨都钉在 255」不是病，别再去做"降低饱和"**）：真机语料交付的墨里\n` +
  '  **58% 落在 255**、`顶平% 中位 45`，看着像"层次被压掉了"。按**块**数就不成立：全语料 284 张里\n' +
  '  「≥3 块且**全部**贴到 250+」只有 **3 张**（WPS 的白 W、高德的白纸飞机、fcmpushviewer 的白山），\n' +
  '  而它们的 `色阶=` 两半都写着 **`已满`** —— `stretchGlobal`（p99→255）与 `stretchMark`（单色字形\n' +
  '  中位贴顶）**根本没动手**，是这些 logo 本来就是单色实心。所以"58% 在 255"绝大多数是**正确的\n' +
  '  实心**，不是被抬掉的层次；要动那两层去"留余量"只会把实心字形做淡（那两层各自都有写死的病因，\n' +
  '  见 `色阶` 那一节）。探针：`_harness/_top.cjs`（按 `分档` 括号里的每块交付浓度数）。\n';
if (t.includes('「墨都钉在 255」不是病')) { console.log('已写过'); process.exit(0); }
if (!t.includes(a)) { console.log('!! 锚点不对'); process.exit(1); }
fs.writeFileSync('README.md', t.replace(a, add));
console.log('README 已记这条负结果');
