package com.iamcanincan.opticon.circle

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import java.nio.ByteBuffer

/**
 * 给「没提供单色图标的应用」凭空合成一个单色层。
 *
 * ## 为什么要有这个
 * Android 16 的「强制动态取色」下，桌面（`com.android.launcher3.icons.MonochromeIconFactory`）
 * 会为**没提供单色图标**的应用现场生成一张 ALPHA_8 掩码，而它算出来的**极性经常是反的**：
 * 深色字形 + 浅色底的图标会变成「字形是洞、底色是形」，整个反掉。
 *
 * 与其等它算错、再把输出整张翻回来（那是 [IconHooks] 里 `hookForcedThemedMono` 干的事），
 * 不如**在 `AdaptiveIconDrawable.getMonochrome()` 上直接返回一个极性正确的合成层**：
 * 桌面只在这个 getter 返回 null 时才去 new 那个 Factory，所以我们一返回非 null，
 * 那条算错的路径根本不会进。
 *
 * ## 极性怎么定 —— 两种图标必须分开处理
 * 一刀切地「按亮度反转」是错的，关键在**这张图标有没有不透明的底**：
 * - **有不透明底**（深色字形 + 浅色底，未适配应用里最常见的方角图标）：
 *   底色不透明、字形是暗的 → 掩码取 `255 - 亮度`，暗处不透明。
 * - **透明底**（字形本身就是镂空的）：背景 alpha 已经是 0，此时**不能**再按亮度反转 ——
 *   白色字形的亮度是 255，反转会把字形整个抹掉 → 掩码直接沿用原 alpha。
 *
 * 判据是「alpha 全满的像素占比过半」：不透明底的图标几乎整张都是 alpha=255，
 * 而透明底的图标只有字形那一小块不透明。这是个便宜且够用的启发式。
 *
 * ## 天然降级，不需要开关
 * 合成只在 `getMonochrome()` 原本返回 null 时才介入，且任何一步失败都返回 null ——
 * 那时桌面照旧走 Factory，而那道翻转仍然挂着兜底。两条路是**互斥**的：
 * 合成生效 → 桌面不进 Factory → 翻转自然不会被触发。所以不需要额外的开关去切。
 */
internal object MonochromeSynth {

  /** 合成尺寸。桌面会把结果缩放到它要的 bounds，所以取一个够用又不占内存的中间尺寸。 */
  private const val SIZE = 192

  /** 「有不透明底」的判据：alpha 全满的像素占比超过这个值就按不透明底算。 */
  private const val OPAQUE_BG_RATIO = 0.5f

  /**
   * 「底色是浅的」的判据：不透明像素的平均亮度超过这个值，才认为底是浅的。
   *
   * 必须判这一步 —— 一刀切地「按亮度反转」会把**深色底 + 白色字形**的图标整个抹掉：
   * 那种图里不透明的是深底、亮的是字形，反转之后字形反而成了洞。
   * 这正是桌面那条路算错的典型情形（它的浅色背景色列表在部分 ROM 上是空的，
   * 于是只能按一种极性硬来）。
   */
  private const val LIGHT_BG_LUMINANCE = 170

  /** 缓存上限。图标数量本来就有界，超过就整体清掉重来，避免长歪。 */
  private const val CACHE_LIMIT = 256

  /**
   * 按图标**实例**缓存（[IdentityHashMap] 语义：同一个对象才命中）。
   *
   * 用实例而不是内容做 key：桌面滚动时同一个图标对象会被反复取 monochrome，
   * 每次都重画一遍 192×192 太贵。图标对象由桌面的 IconCache 长期持有、
   * 数量有界，所以这里不会无限增长。
   */
  private val cache = java.util.IdentityHashMap<Drawable, Drawable>()

  /**
   * 给 [source] 合成一个单色层；合成不出来就返回 null，让调用方照原样走下去。
   *
   * @param source 原图标。通常是 `AdaptiveIconDrawable`，但也可能是别的 Drawable。
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
    val width = SIZE
    val height = SIZE

    // 1) 先把图标按 cover 画满一张 ARGB_8888（要读亮度和 alpha，必须是不透明的中间格式）
    val argb = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(argb)
    source.setBounds(0, 0, width, height)
    source.draw(canvas)

    val pixels = IntArray(width * height)
    argb.getPixels(pixels, 0, width, 0, 0, width, height)
    argb.recycle()

    // 2) 判断这张图有没有不透明的底，以及**那个底是浅的还是深的**
    var opaque = 0
    var lumSum = 0L
    for (p in pixels) {
      if (Color.alpha(p) == 0xff) {
        opaque++
        lumSum += luminance(p)
      }
    }
    val hasOpaqueBg = opaque.toFloat() / pixels.size > OPAQUE_BG_RATIO
    val lightBg = hasOpaqueBg && opaque > 0 && (lumSum / opaque) > LIGHT_BG_LUMINANCE

    // 3) 生成掩码：一个像素一字节，直接写进 ALPHA_8
    val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
    val bytes = ByteArray(mask.rowBytes * mask.height)
    var o = 0
    for (p in pixels) {
      val a = Color.alpha(p)
      bytes[o] = when {
        // 透明底：字形本身就是镂空的，沿用原 alpha，别按亮度反转
        !hasOpaqueBg -> a.toByte()
        // 底之外本来就是透明的
        a == 0 -> 0
        // 浅色底：暗的那部分才是字形 → 按亮度反转
        lightBg -> (a * (255 - luminance(p)) / 255).toByte()
        // 深色底：亮的那部分才是字形 → 亮度即掩码
        else -> (a * luminance(p) / 255).toByte()
      }
      o++
      if (o >= bytes.size) break // rowBytes 可能带 padding，别越界
    }
    val buffer = ByteBuffer.wrap(bytes, 0, o)
    mask.copyPixelsFromBuffer(buffer)

    return MonochromeLayer(mask)
  }

  /** 感知亮度（ITU-R BT.601 的权重，够用且比开 ColorMatrix 便宜）。 */
  private fun luminance(pixel: Int): Int =
    ((Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000)
      .coerceIn(0, 255)
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
