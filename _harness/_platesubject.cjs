// 一次性手术：让染色的**极性**跟着裁圆的**档位**走 —— 裁圆判"这块大色块是要整块保住的图案"
// （裁不动、缩进圆里、还剩 ≥ 一半）时，染色不许把它当底摘掉。主人 2026-10-15 定：
// 「就是整本书成墨、书签是镂空，继续吧」。
// 为什么用脚本：多段 hunk 的 sed 在这个仓库翻过车。跑完必须 git diff 逐行看。
const fs = require("fs");
const R = "D:/Github Repositories/Opticon/app/src/main/kotlin/com/iamcanincan/opticon/circle/";
let cuts = 0;
function edit(file, pairs) {
  const P = R + file;
  let src = fs.readFileSync(P, "utf8");
  for (const [a, b, what] of pairs) {
    const n = src.split(a).length - 1;
    if (n !== 1) throw new Error(file + " → " + (n === 0 ? "找不到" : "匹配到 " + n + " 处，不敢猜") + "：" + what);
    src = src.split(a).join(b); cuts++;
    console.log("  ok " + what);
  }
  fs.writeFileSync(P, src);
}

edit("MonochromeMask.kt", [
  // ① 对外入口加一个参数（默认 false，所有老调用方与判据台都不必改）
  [`  fun build(pixels: IntArray, w: Int, h: Int, plateIsLayer: Boolean = false,
    under: IntArray? = null): Output {`,
   `  fun build(pixels: IntArray, w: Int, h: Int, plateIsLayer: Boolean = false,
    under: IntArray? = null, plateIsSubject: Boolean = false): Output {`,
   "build 收 plateIsSubject"],

  ["    val ramp = buildOnce(pixels, w, h, plateIsLayer, false, under)",
   "    val ramp = buildOnce(pixels, w, h, plateIsLayer, false, under, plateIsSubject = plateIsSubject)",
   "第一遍带上"],
  ["    val ref0 = buildOnce(pixels, w, h, plateIsLayer, true, under, sepOff = true)",
   "    val ref0 = buildOnce(pixels, w, h, plateIsLayer, true, under, sepOff = true, plateIsSubject = plateIsSubject)",
   "参照那遍也带上（两遍必须同一套主张）"],
  ["    return buildOnce(pixels, w, h, plateIsLayer, true, under)",
   "    return buildOnce(pixels, w, h, plateIsLayer, true, under, plateIsSubject = plateIsSubject)",
   "第三处调用"],

  [`  private fun buildOnce(pixels: IntArray, w: Int, h: Int, plateIsLayer: Boolean,
    forceRef: Boolean, under: IntArray? = null, sepOff: Boolean = false
  ): Output {`,
   `  private fun buildOnce(pixels: IntArray, w: Int, h: Int, plateIsLayer: Boolean,
    forceRef: Boolean, under: IntArray? = null, sepOff: Boolean = false,
    plateIsSubject: Boolean = false
  ): Output {`,
   "buildOnce 签名"],

  ["    val ground = groundPass(pixels, w, h, box, band, g, s, plateLayer)",
   "    val ground = groundPass(pixels, w, h, box, band, g, s, plateLayer, plateIsSubject)",
   "往下传到 groundPass"],

  [`  private fun groundPass(
    pixels: IntArray, w: Int, h: Int, box: Rect, band: Int, g: Int, s: Scratch,
    plateIsLayer: Boolean = false,
  ): Pass {`,
   `  private fun groundPass(
    pixels: IntArray, w: Int, h: Int, box: Rect, band: Int, g: Int, s: Scratch,
    plateIsLayer: Boolean = false, plateIsSubject: Boolean = false,
  ): Pass {`,
   "groundPass 签名"],

  // ② H1 的验收：这块底如果是裁圆要整块保住的图案，就不许当底
  ["      if ((p.share < PLATE_ALL_SHARE || (p.families <= 1 && !island)) && !vacuous(s, p, box)) return p",
   `      // ⚠ **极性跟着裁圆的档位走**（2026-10-15 主人定，为 \`org.mewx.wenku8\` 那本蓝书）：
      //   彩色那支判这张是"裁不动、整幅缩进圆里、还剩 55%" —— 也就是说**这块大色块是要被整块
      //   保住的图案**。那染色就没有资格把它当"承载图形的板"摘掉：同一个模块里两套主张，
      //   屏幕上就是主人那句「应该蓝色部分上色」（当时只剩一条书签当墨，\`框内=0.07\`）。
      //   退掉 H1 之后的效果量过（今晚 \`周空\` 那一版顺手否掉 H1 时看到的）：wenku8
      //   \`底占=0.00 H=3 框内=0.93\` —— 整本书成墨、书签是镂空。
      //   线摆在哪、为什么只有这一张过线，见 [MonochromeSynth.PLATE_SUBJECT_MIN]。
      if (plateIsSubject) s.veto = s.veto or 128
      else if ((p.share < PLATE_ALL_SHARE || (p.families <= 1 && !island)) && !vacuous(s, p, box)) return p`,
      "H1 让位给裁圆的档位"],
]);

edit("MonochromeSynth.kt", [
  ["      val out = MonochromeMask.build(pxIn, edge, edge, plateLayer, underIn)",
   `      // 裁圆把这张判成"裁不动、整幅缩进圆里、还剩一半以上" → 那块大色块是**图案**，
      // 不是板。极性必须与彩色同意的档位一致，否则同一个模块对同一张图给两个答案。
      val plateSubject = win != null && win.plate && win.keepPct >= PLATE_SUBJECT_MIN
      val out = MonochromeMask.build(pxIn, edge, edge, plateLayer, underIn, plateSubject)`,
   "把档位告诉模型"],

  [`  /**
   * 染色几何搬动的**上限**`,
   `  /**
   * 裁圆判"这块大色块是要整块保住的图案"的那条线：**缩进圆里那一档，且按圆裁下去还剩到这么多%**。
   *
   * 为什么需要它（2026-10-15 主人定，\`org.mewx.wenku8\`）：彩色那支把蓝书整块缩进圆里保护起来，
   * 染色那支却把同一块蓝当底摘掉、只剩书签当墨（\`框内=0.07\`）—— 一个模块两套主张，屏幕上就是
   * 「应该蓝色部分上色」。这条一开，极性跟着档位走：wenku8 \`底占 0.91→0.00\`、\`框内 0.07→0.93\`，
   * **整本书成墨、书签是镂空**（主人原话：「就是整本书成墨、书签是镂空，继续吧」）。
   *
   * ⚠ 线摆在 **50**，依据是真机 plate 档那四张"裁了只剩"：
   *   wenku8 **55%** / contacts 那三张 **49%** / mipush **32%** / easytier **0%**。
   *   也就是说 50 上面只有 wenku8 一张，下面第一张差 **6 个点** —— **空档很薄**，
   *   摆在这里是因为它是**裁圆自己的档位**（不是我又找一个形状量去拟合），
   *   但下一次真机多出一张 50~55% 的，这条就会连它一起翻，届时必须重新摊分布。
   */
  private const val PLATE_SUBJECT_MIN = 50

  /**
   * 染色几何搬动的**上限**`,
   "PLATE_SUBJECT_MIN 与它的账"],
]);
console.log("写回完成，共 " + cuts + " 处");
