package com.iamcanincan.opticon.notify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import kotlin.math.abs

/**
 * 判断一张位图是否接近单色。
 *
 * 系统决定「这个通知图标有没有做主题适配」用的就是同一套思路：
 * 先把图标压到统一的取样尺寸，再逐像素看 RGB 三通道是否足够接近。
 * 我们照搬这个结论，用来决定该不该插手 —— 单色说明人家已经适配好了，不动。
 */
object ToneCheck {

    private const val SAMPLE_EDGE = 64
    private const val CHANNEL_TOLERANCE = 20
    private const val MIN_OPAQUE_ALPHA = 50

    // 取样用的复用对象：这段代码在通知刷新的热路径上，避免每次都新建。
    //
    // ⚠ 必须**线程安全**：SystemUI 的通知处理可能并发进来（不同通知各走一条线程），
    // 而原来那种「先判空、再逐个赋值」的写法有两个真实的坏中间态：
    //   1. 另一个线程看到 `scratch != null` 但 `scratchCanvas` 还是 null → NPE；
    //   2. 像素缓冲被换成更小的数组后，本线程拿旧引用去 getPixels → 越界。
    // 所以：一次性对象交给 `lazy`（默认就是 SYNCHRONIZED）建，
    // 缓冲与取样的读写整体由 [isGrayscale] 上的 @Synchronized 串起来。
    private val scratch: Bitmap by lazy {
        Bitmap.createBitmap(SAMPLE_EDGE, SAMPLE_EDGE, Bitmap.Config.ARGB_8888)
    }
    private val scratchCanvas: Canvas by lazy { Canvas(scratch) }
    private val scratchPaint: Paint by lazy { Paint(Paint.FILTER_BITMAP_FLAG) }
    private val scratchMatrix = Matrix()

    private var pixelBuffer: IntArray? = null

    @Synchronized
    fun isGrayscale(source: Bitmap): Boolean {
        var bmp = source
        var width = bmp.width
        var height = bmp.height

        if (height > SAMPLE_EDGE || width > SAMPLE_EDGE) {
            scratchMatrix.reset()
            scratchMatrix.setScale(SAMPLE_EDGE.toFloat() / width, SAMPLE_EDGE.toFloat() / height, 0f, 0f)
            scratchCanvas.drawColor(0, PorterDuff.Mode.SRC)
            scratchCanvas.drawBitmap(bmp, scratchMatrix, scratchPaint)
            bmp = scratch
            width = SAMPLE_EDGE
            height = SAMPLE_EDGE
        }

        val pixelCount = width * height
        val buffer = bufferFor(pixelCount)
        bmp.getPixels(buffer, 0, width, 0, 0, width, height)
        for (i in 0 until pixelCount) {
            if (!isNeutral(buffer[i])) return false
        }
        return true
    }

    private fun bufferFor(size: Int): IntArray {
        var buf = pixelBuffer
        if (buf == null || buf.size < size) {
            buf = IntArray(size)
            pixelBuffer = buf
        }
        return buf
    }

    /** 近乎透明的像素直接放行，否则透明区的杂色会误判成彩色 */
    private fun isNeutral(color: Int): Boolean {
        val alpha = 0xff and (color shr 24)
        if (alpha < MIN_OPAQUE_ALPHA) return true
        val r = 0xff and (color shr 16)
        val g = 0xff and (color shr 8)
        val b = 0xff and color
        return abs(r - g) < CHANNEL_TOLERANCE &&
                abs(r - b) < CHANNEL_TOLERANCE &&
                abs(g - b) < CHANNEL_TOLERANCE
    }
}
