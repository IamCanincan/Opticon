const fs = require("fs");
const src = "../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt";
const dst = "cand/MonochromeMask.trace.kt";
let s = fs.readFileSync(src, "utf8");
let bad = 0;
const rep = (a, b, t) => { if (!s.includes(a)) { console.log("MISS " + t); bad++; return; } s = s.split(a).join(b); console.log("ok   " + t); };

rep("  private const val TONAL_SPAN_MIN = 35",
  "  private const val TONAL_SPAN_MIN = 35\n\n" +
  "  /** 逐段快照 —— **只在 `cand/MonochromeMask.trace.kt` 这份候选里有，仓里那份没有这几行**。\n" +
  "   *  问的是「那几张只交一小条的图标，墨是在哪一步没的」：不再问哪块是底，只看每一步之后还剩多少墨。 */\n" +
  "  private val tr = StringBuilder()\n" +
  "  private fun snap(name: String, out: IntArray, w: Int, box: Rect) {\n" +
  "    var ink = 0\n" +
  "    val area = maxOf(box.width() * box.height(), 1)\n" +
  "    for (y in box.top until box.bottom) for (x in box.left until box.right)\n" +
  "      if (((out[y * w + x] ushr 24) and 0xff) >= 128) ink++\n" +
  "    if (tr.isNotEmpty()) tr.append(\",\")\n" +
  "    tr.append(name).append(\":\").append(ink * 100 / area)\n" +
  "  }", "acc");

rep("    val global = if (tonal == null) stretchGlobal(out, total) else null",
  "    tr.setLength(0)   // 每张开头清掉上一张的尾巴（本仓踩过：共享字段不归零，日志会串台）\n" +
  "    snap(\"出图\", out, w, box)\n" +
  "    val global = if (tonal == null) stretchGlobal(out, total) else null\n" +
  "    if (tonal == null) snap(\"抬顶\", out, w, box)", "抬顶");
rep("    if (tonal == null) assignLevels(out, pixels, w, box, s)",
  "    if (tonal == null) { assignLevels(out, pixels, w, box, s); snap(\"发档\", out, w, box) }", "发档");
rep("    val bands = if (tonal == null) propagateBands(out, pixels, w, h, box, s) else Triple(0, 0, 0)",
  "    val bands = if (tonal == null) propagateBands(out, pixels, w, h, box, s) else Triple(0, 0, 0)\n" +
  "    if (tonal == null) snap(\"接带\", out, w, box)", "接带");
rep("    val voteN = if (tonal == null) flatVote(out, pixels, w, h, box, s) else 0",
  "    val voteN = if (tonal == null) flatVote(out, pixels, w, h, box, s) else 0\n" +
  "    if (tonal == null) snap(\"平票\", out, w, box)", "平票");
rep('    val stretch = "${global?.second ?: "已满"}+${if (tonal == null) stretchMark(out, pixels, w, h, box, s) else "已满"}"',
  '    val stretch = "${global?.second ?: "已满"}+${if (tonal == null) { val sm = stretchMark(out, pixels, w, h, box, s); snap("字形贴顶", out, w, box); sm } else "已满"}"',
  "字形贴顶");
rep('          " 调残=$tonResN/${if (tonResN == 0) 0 else tonResSum / tonResN}"),',
    '          " 调残=$tonResN/${if (tonResN == 0) 0 else tonResSum / tonResN}") + " 迹=" + tr,',
    "report");
// 清零在「抬顶」那一格之前做（这里不能再插一次，否则会把前面的段抹掉）。
fs.writeFileSync(dst, s);
console.log(bad ? "有锚点没命中：$bad" : "全部命中");
