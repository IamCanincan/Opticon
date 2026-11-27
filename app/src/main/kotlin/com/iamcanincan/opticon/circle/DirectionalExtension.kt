package com.iamcanincan.opticon.circle

import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Continues color edges through the outline using the tangent measured in the artwork. */
internal object DirectionalExtension {
  const val MIN_EDGE = 192
  private class Tangent(val x: Double, val y: Double, val coherence: Double)
  private class Path(val x: Double, val y: Double, val bend: Double) {
    fun along(step: Double): Double = if (bend == 0.0) step else sin(bend * step) / bend
    fun across(step: Double): Double = if (bend == 0.0) 0.0 else (1.0 - cos(bend * step)) / bend
  }

  private fun colorDifference(a: Int, b: Int): Int = maxOf(
    abs((a ushr 16 and 255) - (b ushr 16 and 255)),
    abs((a ushr 8 and 255) - (b ushr 8 and 255)),
    abs((a and 255) - (b and 255)),
  )

  /** Match both flanks at several positions along the same projected edge. */
  private fun hasStableFlanks(
    pixels: IntArray, width: Int, height: Int,
    x: Int, y: Int, path: Path, radius: Int,
  ): Boolean {
    fun sample(inset: Int, offset: Int): Int {
      val angle = -path.bend * inset
      val along = path.along(-inset.toDouble()) - sin(angle) * offset
      val across = path.across(-inset.toDouble()) + cos(angle) * offset
      val sx = (x + path.x * along - path.y * across).roundToInt()
      val sy = (y + path.y * along + path.x * across).roundToInt()
      return if (sx in 0 until width && sy in 0 until height) pixels[sy * width + sx] else 0
    }
    val flank = maxOf(1, radius / 2)
    for (offset in -flank..flank) {
      val left = sample(radius, offset - flank)
      val right = sample(radius, offset + flank)
      if ((left and right) ushr 24 != 255 || colorDifference(left, right) < 32) continue
      val middle = radius + flank
      val far = radius * 2
      val middleLeft = sample(middle, offset - flank)
      val middleRight = sample(middle, offset + flank)
      val farLeft = sample(far, offset - flank)
      val farRight = sample(far, offset + flank)
      if ((middleLeft and middleRight and farLeft and farRight) ushr 24 != 255) continue
      if (colorDifference(left, middleLeft) <= 20 && colorDifference(left, farLeft) <= 20 &&
        colorDifference(right, middleRight) <= 20 && colorDifference(right, farRight) <= 20 &&
        colorDifference(farLeft, farRight) >= 32
      ) return true
    }
    return false
  }

  private fun tangentAt(
    pixels: IntArray, width: Int, height: Int, x: Int, y: Int, radius: Int,
  ): Tangent? {
    var horizontal = 0.0
    var vertical = 0.0
    var cross = 0.0
    for (sy in maxOf(1, y - radius)..minOf(height - 2, y + radius)) {
      for (sx in maxOf(1, x - radius)..minOf(width - 2, x + radius)) {
        val i = sy * width + sx
        val left = pixels[i - 1]
        val right = pixels[i + 1]
        val up = pixels[i - width]
        val down = pixels[i + width]
        val upperLeft = pixels[i - width - 1]
        val upperRight = pixels[i - width + 1]
        val lowerLeft = pixels[i + width - 1]
        val lowerRight = pixels[i + width + 1]
        if ((left and right and up and down and upperLeft and upperRight and
            lowerLeft and lowerRight) ushr 24 != 255
        ) continue
        for (shift in 0..16 step 8) {
          val tl = upperLeft ushr shift and 255
          val tr = upperRight ushr shift and 255
          val bl = lowerLeft ushr shift and 255
          val br = lowerRight ushr shift and 255
          val gx = (2 * ((right ushr shift and 255) - (left ushr shift and 255)) +
            tr - tl + br - bl) * 0.25
          val gy = (2 * ((down ushr shift and 255) - (up ushr shift and 255)) +
            bl - tl + br - tr) * 0.25
          horizontal += gx * gx
          vertical += gy * gy
          cross += gx * gy
        }
      }
    }
    val strength = horizontal + vertical
    if (strength < 4096) return null
    val coherence = hypot(horizontal - vertical, 2 * cross) / strength
    if (coherence < 0.82) return null
    val angle = atan2(2 * cross, horizontal - vertical) / 2.0
    return Tangent(-sin(angle), cos(angle), coherence)
  }

  fun apply(
    pixels: IntArray, distance: IntArray, fill: IntArray, width: Int, height: Int,
    ink: IntArray? = null,
  ) {
    val edge = minOf(width, height)
    // At small raster sizes a stair-stepped outline can dominate the tangent estimate.
    if (edge < MIN_EDGE || distance.none { it >= 4 }) return
    val radius = (edge / 45).coerceIn(2, 8)
    val limit = (edge * 0.16).toInt()
    val weights = FloatArray(fill.size)
    val inwardX = FloatArray(fill.size)
    val inwardY = FloatArray(fill.size)
    val inwardBend = FloatArray(fill.size)
    for (index in distance.indices) {
      if (distance[index] != -1 || pixels[index] ushr 24 != 255) continue
      val x = index % width
      val y = index / width
      val tangent = tangentAt(pixels, width, height, x, y, radius) ?: continue
      for (sign in -1..1 step 2) {
        val vx = tangent.x * sign
        val vy = tangent.y * sign
        // Confirm that the direction continues farther inside the artwork.
        val support = 2 * radius
        val sx = (x - vx * support).roundToInt()
        val sy = (y - vy * support).roundToInt()
        if (sx !in 0 until width || sy !in 0 until height ||
          pixels[sy * width + sx] ushr 24 != 255
        ) continue
        val inner = tangentAt(pixels, width, height, sx, sy, radius) ?: continue
        val dot = vx * inner.x + vy * inner.y
        val cross = vx * inner.y - vy * inner.x
        val turn = atan2(if (dot < 0) -cross else cross, abs(dot))
        if (abs(turn) > 0.36) continue
        // Small differences are raster noise. Follow a curve only when a third station
        // agrees with the change of direction observed between the other two.
        val bend = if (abs(turn) < 0.12) 0.0 else -turn / support
        if (bend == 0.0 && abs(dot) < 0.96) continue
        val path = Path(vx, vy, bend)
        if (bend != 0.0) {
          val along = path.along(-radius.toDouble())
          val across = path.across(-radius.toDouble())
          val mx = (x + vx * along - vy * across).roundToInt()
          val my = (y + vy * along + vx * across).roundToInt()
          val middle = tangentAt(pixels, width, height, mx, my, radius) ?: continue
          val angle = -bend * radius
          val dx = vx * cos(angle) - vy * sin(angle)
          val dy = vy * cos(angle) + vx * sin(angle)
          if (abs(dx * middle.x + dy * middle.y) < 0.99) continue
        }
        if (!hasStableFlanks(pixels, width, height, x, y, path, radius)) continue
        val coherence = minOf(tangent.coherence, inner.coherence).toFloat()
        var reachedOutside = false
        val reach = if (bend == 0.0) limit else minOf(limit, (0.55 / abs(bend)).toInt())
        for (step in 1..reach) {
          val along = path.along(step.toDouble())
          val across = path.across(step.toDouble())
          val tx = x + vx * along - vy * across
          val ty = y + vy * along + vx * across
          if (tx < 0 || ty < 0 || tx > width - 1 || ty > height - 1) break
          val ix = tx.toInt()
          val iy = ty.toInt()
          val nearest = (ty + 0.5).toInt() * width + (tx + 0.5).toInt()
          val depth = distance[nearest]
          // A continuation must leave the artwork close to its seed.
          if (depth <= 0) {
            if (reachedOutside || step > radius) break
            continue
          }
          reachedOutside = true
          val fade = (1f - step.toFloat() / reach) * coherence
          val angle = bend * step
          val dxAlong = vx * cos(angle) - vy * sin(angle)
          val dyAlong = vy * cos(angle) + vx * sin(angle)
          // Cover both sides of the projected edge. A narrow stroke of directional
          // samples leaves a second copy of the edge in the underlying color fill.
          val spread = if (edge >= 224) {
            2.0 + minOf(radius.toDouble(), step * 0.45)
          } else {
            2.0
          }
          val footprint = spread.toInt()
          for (dy in -footprint..footprint + 1) for (dx in -footprint..footprint + 1) {
            if (ix + dx !in 0 until width || iy + dy !in 0 until height) continue
            val target = (iy + dy) * width + ix + dx
            if (distance[target] <= 0 || fill[target] == 0) continue
            val weight = (fade * (1.0 - abs(tx - ix - dx) / spread).coerceAtLeast(0.0) *
              (1.0 - abs(ty - iy - dy) / spread).coerceAtLeast(0.0)).toFloat()
            weights[target] += weight
            inwardX[target] -= (dxAlong * weight).toFloat()
            inwardY[target] -= (dyAlong * weight).toFloat()
            inwardBend[target] -= (bend * weight).toFloat()
          }
        }
      }
    }
    for (i in fill.indices) {
      val weight = weights[i]
      if (weight == 0f) continue
      val vx = inwardX[i] / weight
      val vy = inwardY[i] / weight
      if (vx * vx + vy * vy < 0.81f) continue
      val path = Path(vx.toDouble(), vy.toDouble(), (inwardBend[i] / weight).toDouble())
      val x = i % width
      val y = i / width
      for (step in 1..limit) {
        val along = path.along(step.toDouble())
        val across = path.across(step.toDouble())
        val sx = x + vx * along - vy * across
        val sy = y + vy * along + vx * across
        if (sx < 0 || sy < 0 || sx >= width - 1 || sy >= height - 1) break
        val ix = sx.toInt()
        val iy = sy.toInt()
        val index = iy * width + ix
        // An enclosed gap must never become a bridge to a different part of the artwork.
        val nearest = sy.roundToInt() * width + sx.roundToInt()
        if (distance[nearest] == 0 && pixels[nearest] ushr 24 <= ALPHA_MIN) break
        if ((pixels[index] and pixels[index + 1] and pixels[index + width] and
            pixels[index + width + 1]) ushr 24 != 255
        ) continue
        val source = ink ?: pixels
        val tl = source[index]
        val tr = source[index + 1]
        val bl = source[index + width]
        val br = source[index + width + 1]
        val fx = sx - ix
        val fy = sy - iy
        val mix = weight.coerceAtMost(1f) * (1f - step.toFloat() / limit)
        var color = 255 shl 24
        val channels = if (ink == null) 3 else 1
        for (channel in 0 until channels) {
          val shift = channel * 8
          val sourceShift = if (ink == null) shift else 24
          val top = (tl ushr sourceShift and 255) * (1.0 - fx) + (tr ushr sourceShift and 255) * fx
          val bottom = (bl ushr sourceShift and 255) * (1.0 - fx) + (br ushr sourceShift and 255) * fx
          val sample = top * (1.0 - fy) + bottom * fy
          val base = fill[i] ushr shift and 255
          color = color or ((base * (1f - mix) + sample * mix + 0.5f).toInt() shl shift)
        }
        fill[i] = color
        break
      }
    }
  }
}
