package com.iamcanincan.opticon.circle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import com.iamcanincan.opticon.notify.IconBitmap
import java.nio.ByteBuffer
import java.util.IdentityHashMap

/**
 * 给「没提供单色图标的应用」合成一个单色层 —— **用的是通知那半边同一套剪影算法**。
 *
 * ## 为什么要有这个
 * Android 16 的「强制动态取色」下，桌面（`com.android.launcher3.icons.MonochromeIconFactory`）
 * 会为**没提供单色图标**的应用现场生成一张掩码，而它算出来的**极性经常是反的**：
 * 深色字形 + 浅色底的图标会变成「字形是洞、底色是形」，整个反掉。
 *
 * 与其等它算错、再把输出整张翻回来（那是 [IconHooks] 里 `hookForcedThemedMono` 干的事），
 * 不如**在 `AdaptiveIconDrawable.getMonochrome()` 上直接返回一个极性正确的合成层**：
 * 桌面只在这个 getter 返回 null 时才去 new 那个 Factory，所以我们一返回非 null，
 * 那条算错的路径根本不会进。
 *
 * ## 算法与通知侧共用，不另写一套
 * 剪影生成用的是 [IconBitmap.foregroundOf] + [IconBitmap.monochrome] —— 和通知小图标
 * 黑白模式（`NotificationIconPatch.launcherIconMonochrome` / `forceMonochrome`）**同一条链**：
 * 先取自适应图标的前景层（「透明底 + 图形」那一层，整张光栅化是不透明色块、压不出剪影），
 * 再由 `monochrome()` 用 **Otsu 自动阈值**二值化，并用**连通性**判断哪一侧才是图形
 * （背景通常与图标外部相连、图形是被包住的内部块）—— 这比任何固定阈值都稳，
 * 也省得同一个问题在仓库里有两份实现、修一处漏一处。
 *
 * ⚠ **和通知侧唯一的差别：这里不做 `fill()`**。通知要的是填满状态栏那点小尺寸，
 * 所以裁掉四周空白再放大；而桌面单色层**必须与自适应图标的坐标系对齐**
 * （图形落在中间约 61% 的可见区，外面是 inset），一 fill 图形就会撑出可见区。
 *
 * ## 天然降级，不需要开关
 * 合成只在 `getMonochrome()` 原本返回 null 时才介入，且任何一步失败都返回 null ——
 * 那时桌面照旧走 Factory，而那道翻转仍然挂着兜底。两条路是**互斥**的：
 * 合成生效 → 桌面不进 Factory → 翻转自然不会被触发。
 */
internal object MonochromeSynth {

  /**
   * 合成尺寸。桌面图标比状态栏图标大得多（96 那张是给通知用的），
   * 这里取 192 免得放大后糊掉；最终存成 ALPHA_8，一张只占 36 KB。
   */
  private const val SIZE = 192

  /** 缓存上限。图标数量本来就有界，超过就整体清掉重来，避免长歪。 */
  private const val CACHE_LIMIT = 256

  /**
   * 按图标**实例**缓存（[IdentityHashMap] 语义：同一个对象才命中）。
   *
   * 用实例而不是内容做 key：桌面滚动时同一个图标对象会被反复取 monochrome，
   * 每次都重压一遍 192×192 太贵。图标对象由桌面的 IconCache 长期持有、
   * 数量有界，所以这里不会无限增长。
   */
  private val cache = IdentityHashMap<Drawable, Drawable>()

  /**
   * 给 [source] 合成一个单色层；合成不出来就返回 null，让调用方照原样走下去。
   *
   * @param source 原图标。通常是 `AdaptiveIconDrawable`，也可能是别的 Drawable。
   */
  @Synchronized
  fun forIcon(source: Drawable): Drawable? {
    cache[source]?.let { return it }
    val synth = runCatching { synthesize(source) }.getOrNull() ?: return null
    if (cache.size >= CACHE_LIMIT) cache.clear()
    cache[source] = synth
    return synth
  }

  private fun synthesize(source: Drawable): Drawable? {
    // 1) 取前景层（「透明底 + 图形」），交给通知侧那套压剪影的算法
    val foreground = IconBitmap.foregroundOf(source, SIZE)
    val silhouette = IconBitmap.monochrome(foreground) ?: return null
    // 2) 转成 ALPHA_8：桌面只认 alpha 通道，转过去省 4 倍内存
    val mask = toAlphaMask(silhouette) ?: return null
    if (mask !== silhouette) silhouette.recycle()
    return MonochromeLayer(mask)
  }

  /**
   * ARGB_8888（白色 + alpha）→ ALPHA_8。
   *
   * `monochrome()` 交出来的是 ARGB_8888，但桌面取色只读 alpha，
   * 所以留着三个颜色通道是白占内存（每张 147 KB → 36 KB）。
   */
  private fun toAlphaMask(source: Bitmap): Bitmap? {
    val w = source.width
    val h = source.height
    if (w <= 0 || h <= 0) return null
    val pixels = IntArray(w * h)
    source.getPixels(pixels, 0, w, 0, 0, w, h)
    val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
    val bytes = ByteArray(mask.rowBytes * h)
    var o = 0
    for (p in pixels) {
      bytes[o++] = Color.alpha(p).toByte()
      if (o >= bytes.size) break // rowBytes 可能带 padding，别越界
    }
    mask.copyPixelsFromBuffer(ByteBuffer.wrap(bytes, 0, o))
    return mask
  }
}

/**
 * 单色层的载体：一张 ALPHA_8 掩码，画的时候由调用方（桌面的取色逻辑）上色。
 *
 * 不直接用 `BitmapDrawable` 是因为构造它要 `Resources`，而在被注入的进程里
 * 拿 Resources 要么拿不到、要么拿到的是宿主的 —— 自己画一个更稳，也少一层依赖。
 */
private class MonochromeLayer(private val mask: Bitmap) : Drawable() {

  private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

  override fun draw(canvas: Canvas) {
    canvas.drawBitmap(mask, null, bounds, paint)
  }

  override fun setAlpha(alpha: Int) {
    paint.alpha = alpha
  }

  override fun setColorFilter(colorFilter: ColorFilter?) {
    paint.colorFilter = colorFilter
  }

  @Suppress("OVERRIDE_DEPRECATION")
  override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

  override fun getIntrinsicWidth(): Int = mask.width

  override fun getIntrinsicHeight(): Int = mask.height
}
