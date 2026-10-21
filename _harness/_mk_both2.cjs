const fs = require("fs");
let t = fs.readFileSync("cand/Both.kt", "utf8");
const A = [
"  private fun softAndInk(out: IntArray, w: Int, h: Int): Pair<Int, Int> {",
"    val b = w * 3 / 4; val o = (w - b) / 2; var soft = 0; var ink = 0",
"    for (y in o until o + b) for (x in o until o + b) {",
"      val a = (out[y * w + x] ushr 24) and 0xff",
"      if (a > 0) { ink++; if (a < 128) soft++ }",
"    }",
"    val n = b.toLong() * b / 100",
"    return if (n == 0L) Pair(0, 0) else Pair((soft * 100 / n).toInt(), (ink * 100 / n).toInt())",
"  }"].join("\n");
if (!t.includes(A)) { console.log("找不到 softAndInk 全文"); process.exit(1); }
const B = [
"  // ⚠ **先把两张成品降到固定 96 网格再量**（2026-10-11）：不降的话选支决定随抓图尺寸翻 ——",
"  //   四档边长实测 6/47 张换支（易班 满/满/满/参、闲鱼、京东、farsee、华为）。",
"  //   降采样把「AA 占的面积按 1/边长 缩」这一项直接抹平：两条支的成品在判据眼里从此是同一张图。",
"  //   ⚠ 这只治**决定**，不改任何一张的交付。百分数分母 = 那一格的格数（不是 /100，上一版在这里错过一次）。",
"  private fun softAndInk(out: IntArray, w: Int, h: Int): Pair<Int, Int> {",
"    val n = minOf(96, w); val sm = downsample(out, w, h, n) ?: return Pair(0, 0)",
"    val b = n * 3 / 4; val o = (n - b) / 2; var soft = 0; var ink = 0",
"    for (y in o until o + b) for (x in o until o + b) {",
"      val a = (sm[y * n + x] ushr 24) and 0xff",
"      if (a > 0) { ink++; if (a < 128) soft++ }",
"    }",
"    val cells = (b.toLong() * b).toInt()",
"    if (cells == 0) return Pair(0, 0)",
"    return Pair(soft * 100 / cells, ink * 100 / cells)",
"  }"].join("\n");
fs.writeFileSync("cand/Both2.kt", t.replace(A, B));
console.log("已写出 cand/Both2.kt");
