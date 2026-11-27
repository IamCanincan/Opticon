package com.iamcanincan.opticon.circle

import kotlin.math.abs

/** Separates adjacent, stable colors that would otherwise receive the same ink tone. */
internal object ChromaThreeTone {
  private const val GRID = 96
  private const val DETAIL_INK = 112

  private class Region {
    var area = 0
    var ink = 0
    val lab = IntArray(3)
    var colorSquares = 0L
    val neighbors = mutableMapOf<Int, Int>()
    fun mean(channel: Int) = lab[channel] / area / 64
    fun flat(): Boolean {
      val meanSquare = lab.sumOf { (it.toDouble() / area / 64).let { value -> value * value } }
      return colorSquares.toDouble() / area / (64 * 64) - meanSquare < 12 * 12
    }
  }

  data class Candidate(val mask: IntArray, val pairs: Int)

  fun separate(pixels: IntArray, base: IntArray, edge: Int): Candidate {
    val cells = GRID * GRID
    val ids = IntArray(cells) { -1 }
    val lab = IntArray(cells * 3)
    val ink = IntArray(cells)
    val color = IntArray(3)
    for (i in 0 until cells) {
      val x = (i % GRID * edge + edge / 2) / GRID
      val y = (i / GRID * edge + edge / 2) / GRID
      val p = y * edge + x
      val alpha = base[p] ushr 24
      if (alpha <= DETAIL_INK || pixels[p] ushr 24 < 245) continue
      ink[i] = alpha
      MonochromeMask.labInto(pixels[p], color)
      color.copyInto(lab, i * 3)
    }
    val queue = IntArray(cells)
    val regions = mutableListOf<Region>()
    for (seed in 0 until cells) {
      if (ink[seed] == 0 || ids[seed] >= 0) continue
      val id = regions.size
      val region = Region()
      regions.add(region)
      ids[seed] = id
      queue[0] = seed
      var head = 0
      var tail = 1
      while (head < tail) {
        val i = queue[head++]
        region.area++
        region.ink += ink[i]
        for (channel in 0..2) {
          val value = lab[i * 3 + channel]
          region.lab[channel] += value
          region.colorSquares += value.toLong() * value
        }
        fun visit(j: Int) {
          if (ink[j] == 0 || ids[j] >= 0) return
          val distance = (0..2).sumOf { channel ->
            val delta = (lab[i * 3 + channel] - lab[j * 3 + channel]) / 64
            delta * delta
          }
          if (distance > 9 * 9) return
          ids[j] = id
          queue[tail++] = j
        }
        if (i % GRID > 0) visit(i - 1)
        if (i % GRID < GRID - 1) visit(i + 1)
        if (i >= GRID) visit(i - GRID)
        if (i < cells - GRID) visit(i + GRID)
      }
    }
    val supported = regions.sumOf { it.area }
    val flat = BooleanArray(regions.size) { regions[it].area >= 16 && regions[it].flat() }
    val flatArea = regions.indices.sumOf { if (flat[it]) regions[it].area else 0 }
    if (flatArea * 4 < supported * 3) return Candidate(base, 0)
    for (i in 0 until cells) {
      val a = ids[i]
      if (a < 0 || !flat[a]) continue
      fun count(j: Int) {
        val b = ids[j]
        if (b < 0 || a == b || !flat[b]) return
        val neighbors = regions[minOf(a, b)].neighbors
        neighbors.merge(maxOf(a, b), 1, Int::plus)
      }
      if (i % GRID < GRID - 1) count(i + 1)
      if (i < cells - GRID) count(i + GRID)
    }
    val assignment = IntArray(regions.size) { -1 }
    var pairs = 0
    for (a in regions.indices.sortedByDescending { regions[it].area }) {
      val first = regions[a]
      for ((b, shared) in first.neighbors.entries.sortedByDescending { it.value }) {
        val second = regions[b]
        if (shared < 6 || assignment[a] >= 0 || assignment[b] >= 0) continue
        if (abs(first.ink / first.area - second.ink / second.area) >= 32) continue
        val lightness = abs(first.mean(0) - second.mean(0))
        val chromaA = first.mean(1) - second.mean(1)
        val chromaB = first.mean(2) - second.mean(2)
        if (lightness > 20 || chromaA * chromaA + chromaB * chromaB < 24 * 24) continue
        val main = if (first.area >= second.area) a else b
        assignment[main] = 255
        assignment[if (main == a) b else a] = DETAIL_INK
        pairs++
      }
    }
    if (pairs == 0) return Candidate(base, 0)
    val result = base.copyOf()
    var changed = false
    for (i in pixels.indices) {
      val alpha = base[i] ushr 24
      if (alpha <= DETAIL_INK || pixels[i] ushr 24 < 245) continue
      val x = ((i % edge * GRID + GRID / 2) / edge).coerceAtMost(GRID - 1)
      val y = ((i / edge * GRID + GRID / 2) / edge).coerceAtMost(GRID - 1)
      val id = ids[y * GRID + x]
      if (id < 0 || assignment[id] != DETAIL_INK) continue
      MonochromeMask.labInto(pixels[i], color)
      val region = regions[id]
      val distance = (0..2).sumOf { channel ->
        val delta = color[channel] / 64 - region.mean(channel)
        delta * delta
      }
      if (distance > 14 * 14) continue
      result[i] = (DETAIL_INK shl 24) or (base[i] and 0x00ffffff)
      changed = true
    }
    return if (changed) Candidate(result, pairs) else Candidate(base, 0)
  }
}
