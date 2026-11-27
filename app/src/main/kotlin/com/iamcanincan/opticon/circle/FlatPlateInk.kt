package com.iamcanincan.opticon.circle

import kotlin.math.abs

/**
 * Recover a contrasting mark from a flat backing when the existing mask is nearly
 * invisible or mostly fills the backing. Works on the source aligned to the output.
 */
internal fun recoverFlatPlateInk(pixels: IntArray, ink: IntArray, width: Int, height: Int): Boolean {
  var solid = 0
  var transparent = 0
  var strongInk = 0
  var solidInk = 0
  var glyphColor = 0
  var uniformGlyph = true
  for (i in pixels.indices) {
    val pixel = pixels[i]
    if (pixel ushr 24 <= ALPHA_MIN) transparent++
    if (pixel ushr 24 < 245) continue
    val rgb = pixel and 0xffffff
    if (solid == 0) glyphColor = rgb
    if (rgb != glyphColor) uniformGlyph = false
    solid++
    if (ink[i] ushr 24 >= 96) strongInk++
    if (ink[i] ushr 24 >= 192) solidInk++
  }
  if (solid == 0) return false
  if (solidInk * 100 <= solid && uniformGlyph && transparent * 2 >= pixels.size &&
    solid * 25 >= pixels.size
  ) {
    for (i in pixels.indices) ink[i] = pixels[i] and 0xff000000.toInt()
    return true
  }
  if (strongInk * 100 > solid && strongInk * 4 < solid * 3) return false

  val histogram = IntArray(4096)
  for (pixel in pixels) {
    if (pixel ushr 24 < 245) continue
    val bucket = ((pixel ushr 12) and 0xf00) or ((pixel ushr 8) and 0xf0) or
      ((pixel ushr 4) and 0xf)
    histogram[bucket]++
  }
  val dominant = histogram.indices.maxBy { histogram[it] }
  if (histogram[dominant] * 5 < solid * 3) return false
  val red = (dominant ushr 8) * 16 + 8
  val green = (dominant ushr 4 and 15) * 16 + 8
  val blue = (dominant and 15) * 16 + 8

  fun contrast(pixel: Int): Int = maxOf(
    abs((pixel ushr 16 and 255) - red),
    abs((pixel ushr 8 and 255) - green),
    abs((pixel and 255) - blue),
  )

  var perimeter = 0
  var backingPerimeter = 0
  val inset = (minOf(width, height) / 20).coerceAtLeast(1)
  fun sampleOutline(first: Int, stride: Int, available: Int) {
    perimeter++
    for (step in 0..minOf(inset, available)) {
      val pixel = pixels[first + stride * step]
      if (pixel ushr 24 >= 245 && contrast(pixel) <= 20) {
        backingPerimeter++
        return
      }
    }
  }
  for (y in 0 until height) {
    val row = y * width
    (0 until width).firstOrNull { pixels[row + it] ushr 24 >= 245 }
      ?.let { sampleOutline(row + it, 1, width - 1 - it) }
    (width - 1 downTo 0).firstOrNull { pixels[row + it] ushr 24 >= 245 }
      ?.let { sampleOutline(row + it, -1, it) }
  }
  for (x in 0 until width) {
    (0 until height).firstOrNull { pixels[it * width + x] ushr 24 >= 245 }
      ?.let { sampleOutline(it * width + x, width, height - 1 - it) }
    (height - 1 downTo 0).firstOrNull { pixels[it * width + x] ushr 24 >= 245 }
      ?.let { sampleOutline(it * width + x, -width, it) }
  }
  if (perimeter == 0 || backingPerimeter * 4 < perimeter * 3) return false

  val backingLightness = (red * 3 + green * 6 + blue) / 10
  fun lightnessDifference(pixel: Int): Int =
    (3 * (pixel ushr 16 and 255) + 6 * (pixel ushr 8 and 255) + (pixel and 255)) / 10 -
      backingLightness

  var darker = 0
  var lighter = 0
  var backing = 0
  var inkedBacking = 0
  for (i in pixels.indices) {
    val pixel = pixels[i]
    if (pixel ushr 24 < 245) continue
    val difference = contrast(pixel)
    if (difference <= 20) {
      backing++
      if (ink[i] ushr 24 >= 96) inkedBacking++
    }
    if (lightnessDifference(pixel) <= -80) darker++
    if (lightnessDifference(pixel) >= 80) lighter++
  }
  val polarity = if (lighter > darker) 1 else -1
  val detail = maxOf(lighter, darker)
  if (detail * 25 < solid || detail * 5 > solid * 2) return false
  if (minOf(lighter, darker) * 5 > detail) return false
  if (strongInk * 100 > solid && inkedBacking * 10 < backing * 9) return false

  for (i in pixels.indices) {
    val pixel = pixels[i]
    val coverage = (lightnessDifference(pixel) * polarity - 20).coerceIn(0, 60)
    ink[i] = ((coverage * (pixel ushr 24) + 30) / 60) shl 24
  }
  return true
}
