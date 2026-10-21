// 一次性手术脚本：把 CircleIconDrawable 里那条窗口判据换成对 IconCropWindow 的调用。
// 为什么写文件而不是 sed：多段 hunk 的 sed 在这个仓库翻过车（见 memory 里那条"行号范围编辑只适合小改"）。
// 用完即可弃；跑完必须 git diff 逐行看过再编译。
const fs = require("fs");
const P = "D:/Github Repositories/Opticon/app/src/main/kotlin/com/iamcanincan/opticon/circle/CircleIconDrawable.kt";
let src = fs.readFileSync(P, "utf8");
const nl = s => s; // 仓库强制 LF（.gitattributes: eol=lf），原样插入
let cuts = 0;

function splice(startMarker, endMarker, replacement, what) {
  const a = src.indexOf(startMarker);
  if (a < 0) throw new Error(`找不到起点：${what}`);
  const b = src.indexOf(endMarker, a);
  if (b < 0) throw new Error(`找不到终点：${what}`);
  src = src.slice(0, a) + replacement + src.slice(b);
  cuts++;
  console.log(`  ✓ ${what}：删 ${b - a} 字符，换入 ${replacement.length}`);
}

// ── 1) 字段：FloatArray → Window ──
splice(
  "  /** 图案外框（画布比例 0..1，`l,t,r,b`）；null = 量不出来 —— 按整块画布内切。 */",
  "  /**\n   * 这张走的是",
  nl(`  /**
   * 图案窗口（[IconCropWindow] 的三条出口里的两条非空者）；null = 满幅或"宁可不动"。
   * 存的是**比例**，所以同一张图换画布尺寸不用重量（真机同一张会以 79/95/96/192/283 被画好几遍）。
   */
  private var boxMeasured = false
  private var boxWin: IconCropWindow.Window? = null

`),
  "窗口字段",
);

// ── 2) contentBox + scanBoxFraction + keepCounts + extents + coveredRadius → 一次调用 ──
splice(
  "  /**\n   * 图案实际有像素的外框，换算到图标自身坐标系",
  "  override fun setAlpha(alpha: Int) {",
  nl(`  /**
   * 图案实际占的那块，换算到图标自身坐标系（= \`intrinsicWidth/Height\`）。
   *
   * 判据本身在 [IconCropWindow] —— 那里同时是彩色与染色两条路的唯一来源，本文件只负责
   * "把图标光栅化到量测格子"和"把日志打出来"。**只量一次**（[boxMeasured]）：同一张图的
   * 窗口与画多大无关，所以存成比例，换尺寸时按比例还原。
   *
   * 两处保守都在 [IconCropWindow] 那侧：① 一行/一列要有足够像素才算数；② 任何异常
   * （画不出来、量出空框、尺寸非正）→ 不动。这里再兜一层 \`runCatching\`，因为光栅化
   * 那一步会抛（宿主的 drawable 可能画不出尺寸）。
   */
  private fun contentBox(iw: Int, ih: Int, canonical: Boolean): Rect? {
    if (!boxMeasured) {
      boxMeasured = true
      val t0 = System.nanoTime()
      boxWin = runCatching { measureWindow(iw, ih, canonical) }.getOrNull()
      plateWhite = boxWin?.plate == true
      // 这张小图是**每个图标多出来的一次绘制**，所以成本要能看见（本仓吃过"只报数不量毫秒"的亏）。
      if (boxLogLeft.decrementAndGet() >= 0) {
        Log.d(TAG, "裁圆外框: 量一张 %.2f ms → %s".format(
          (System.nanoTime() - t0) / 1_000_000.0,
          if (boxWin == null) "满幅（不放大）" else "有留白",
        ))
      }
    }
    val win = boxWin ?: return null
    return Rect(
      (win.l * iw).roundToInt(), (win.t * ih).roundToInt(),
      (win.r * iw).roundToInt(), (win.b * ih).roundToInt(),
    )
  }

  /**
   * 光栅化到 [IconCropWindow.scanEdge] 那张格子，交给共用判据，然后把落到哪一档打进日志。
   *
   * ⚠ 这两行日志的**格式是装机后的唯一读数**（README 记的排查路径、以及"哪一档开火几张"的
   *   语料账都按字段 grep），所以字段一个都不能少、也不能改名。
   * ⚠ 整条消息一次成型：\`"…%.0f…" + "…%.2f…".format(…)\` 会把格式符留在前半段、参数全错位
   *   （2026-10-14 真机上打出来就是 \`裁了只剩 %.0f%%\` + \`body/far=50.00 放大 60.00×\`）。
   */
  private fun measureWindow(iw: Int, ih: Int, canonical: Boolean): IconCropWindow.Window? {
    if (iw <= 0 || ih <= 0) return null
    // 固有尺寸未知的（自适应图标）一律量到那个边长：与"这次画多大"解耦
    val edge = IconCropWindow.scanEdge
    val shrink = if (canonical) edge.toFloat() / maxOf(iw, ih) else minOf(1f, edge.toFloat() / maxOf(iw, ih))
    val sw = maxOf(1, (iw * shrink).roundToInt())
    val sh = maxOf(1, (ih * shrink).roundToInt())
    val scratch = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
    Canvas(scratch).also {
      icon.setBounds(0, 0, sw, sh)
      icon.draw(it)
    }
    val px = IntArray(sw * sh)
    scratch.getPixels(px, 0, sw, 0, 0, sw, sh)
    scratch.recycle()
    val win = IconCropWindow.of(px, sw, sh) ?: return null
    if (win.plate) {
      Log.d(TAG, "裁圆外框: 画布 ${iw}x$ih 图案 ${win.boxW}x${win.boxH} 裁了只剩 ${win.keepPct}%" +
        "（<${IconCropWindow.keepRatioPercent}%）→ 不裁、缩进圆里" +
        " body/far=${"%.2f".format(win.bodyOverFar)} 放大 ${"%.2f".format(win.zoom)}×，圆内补白")
    } else {
      Log.d(TAG, "裁圆外框: 画布 ${iw}x$ih 图案 ${win.boxW}x${win.boxH}（扫描 ${win.scanW}x${win.scanH}）裁了还剩 " +
        "${win.keepPct}% 外框内切圆 r=${"%.1f".format(win.rBox)}" +
        " 不透明圆盘 r=${"%.1f".format(win.rCover)} → 放大 ${"%.2f".format(win.zoom)}×")
    }
    return win
  }

`),
  "contentBox + 那五条私有函数",
);

// ── 3) 文件底部那堆常数：已经整体搬进 IconCropWindow.kt ──
splice(
  "/**\n * 量图案外框时把图标光栅化到的边长",
  "/** 外框测量日志只给前 [BOX_LOG_LIMIT] 次",
  "",
  "搬走的常数块",
);

fs.writeFileSync(P, src);
console.log(`写回完成，共 ${cuts} 处`);
