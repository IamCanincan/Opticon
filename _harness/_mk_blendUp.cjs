const fs = require("fs");
const p = "cand/MonochromeMask.blendUp.kt";
let s = fs.readFileSync(p, "utf8");
const rep = (from, to, tag) => {
  if (!s.includes(from)) { console.log("MISS  " + tag); return; }
  s = s.split(from).join(to); console.log("ok    " + tag);
};
// 1) 门槛归零：所有非适配图标都进这一支
rep("  private const val TONAL_SPAN_MIN = 35", "  private const val TONAL_SPAN_MIN = 0", "span=0");
// 2) 参照腿的表与刀一律照跑（原来被 tonal != null 短路）
rep("    if (tonal == null) for (y in box.top until box.bottom) {",
    "    for (y in box.top until box.bottom) {", "cDep table");
rep("        if (tonal == null)\n          a = (a + a * (u * 100 / amp).coerceIn(-down, up) / 100).coerceIn(0, 255)",
    "        a = (a + a * (u * 100 / amp).coerceIn(-down, up) / 100).coerceIn(0, 255)", "明度序");
rep("    val global = if (tonal == null) stretchGlobal(out, total) else null",
    "    val global = stretchGlobal(out, total)", "stretchGlobal");
rep("    if (tonal == null) assignLevels(out, pixels, w, box, s)",
    "    assignLevels(out, pixels, w, box, s)", "assignLevels");
rep("    val bands = if (tonal == null) propagateBands(out, pixels, w, h, box, s) else Triple(0, 0, 0)",
    "    val bands = propagateBands(out, pixels, w, h, box, s)", "propagateBands");
rep("    val voteN = if (tonal == null) flatVote(out, pixels, w, h, box, s) else 0",
    "    val voteN = flatVote(out, pixels, w, h, box, s)", "flatVote");
rep('    val stretch = "${global?.second ?: "已满"}+${if (tonal == null) stretchMark(out, pixels, w, h, box, s) else "已满"}"',
    '    val stretch = "${global.second}+${stretchMark(out, pixels, w, h, box, s)}"', "stretchMark");
rep("    val hollow = if (tonal != null || knockCand > keep) 0 else HOLLOW * body.weight / 255",
    "    val hollow = if (knockCand > keep) 0 else HOLLOW * body.weight / 255", "hollow");
// 3) 形状修补按"这张有没有连续调子"决定，不再按"走没走这一支"
rep("    val colourDriven = ground.meanCov >= 128 && tonal == null",
    "    val colourDriven = ground.meanCov >= 128 && (tonal == null || tonal.span < 35)", "colourDriven");
// 4) 逐像素：转录值另存一张表，out 走参照那条
rep(`        var a = if (tonal != null) {
          // 这一支的浓度 = 这张图自己的亮度，**暗=墨**，端点是它自己的 p5/p95。
          // 透明像素照样不给墨（乘上源 alpha，与下面 \`tl\` 同一条规矩）。
          val l64 = s.labL[i].toInt() and 0xffff
          val ramp = if (tonalBright) l64 - tonal.lo else tonal.hi - l64
          (ramp * 255 / maxOf(tonal.hi - tonal.lo, 1 shl 6)).coerceIn(0, 255) *
            Color.alpha(pixels[i]) / 255
        } else (td * (100 - q) + tl * q) / 100`,
`        if (tonal != null) {
          val l64 = s.labL[i].toInt() and 0xffff
          val ramp = if (tonalBright) l64 - tonal.lo else tonal.hi - l64
          tbuf[i] = (ramp * 255 / maxOf(tonal.hi - tonal.lo, 1 shl 6)).coerceIn(0, 255) *
            Color.alpha(pixels[i]) / 255
        }
        var a = (td * (100 - q) + tl * q) / 100`, "per-pixel");
// 5) 缓冲表
rep("    val tonalBright = tonal != null",
    "    val tbuf = IntArray(total)\n    val tonalBright = tonal != null", "tbuf");
// 6) 混轴：挪到整条饱和链**之后**
rep('    val stretch = "${global.second}+${stretchMark(out, pixels, w, h, box, s)}"',
    '    val stretch = "${global.second}+${stretchMark(out, pixels, w, h, box, s)}"\n' +
`    if (tonal != null) {
      val ww = (tonal.span * 100 / 35).coerceAtMost(100)
      for (i in 0 until total) {
        val ta = tbuf[i]
        val ra = (out[i] ushr 24) and 0xff
        val m = if (ww >= 100) ta else (ta * ww + ra * (100 - ww)) / 100
        out[i] = if (m == 0) 0 else (m shl 24) or 0x00FFFFFF
      }
    }`, "mix");
fs.writeFileSync(p, s);
