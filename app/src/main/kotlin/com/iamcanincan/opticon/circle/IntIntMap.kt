package com.iamcanincan.opticon.circle

/**
 * 开放寻址的 **primitive Int→Int 表**（键 = 24 位源色，值 = 计数/和）。
 *
 * 为什么要有它（2026-10-23 第二轮 JFR）：`族抬` 那段用四个 `HashMap<Int,Int>` 做逐像素计数，
 * 冷路径上占 ~13% —— 每次读写都要把键/值装箱、每次扩容都要重建。这段的聚合**与遍历顺序无关**
 * （逐色计数、逐色求和、逐色阈值，没有 max/平票），所以换 primitive 表是**逐字节等价**的；
 * 有平票的 `fam`（plate 主色）不走这里，仍用原来的 HashMap。
 *
 * 键存 `key + 1`（0 = 空槽）：源色可以是纯黑 `0x000000`，不能用 0 当空哨兵。
 * 值 0 在调用方语义里就是"没有"（计数/墨格数/墨和都 ≥1；tone 的均值 ≥128）。
 */
internal class IntIntMap(initial: Int = 1024) {
  private var mask: Int
  private var keys: IntArray
  private var vals: IntArray
  private var used: Int

  init {
    var cap = 16
    while (cap < initial) cap = cap shl 1
    mask = cap - 1
    keys = IntArray(cap)
    vals = IntArray(cap)
    used = 0
  }

  fun get(key: Int): Int {
    val k = key + 1
    var i = mix(key) and mask
    while (true) {
      val cur = keys[i]
      if (cur == 0) return 0
      if (cur == k) return vals[i]
      i = (i + 1) and mask
    }
  }

  fun put(key: Int, value: Int) {
    if ((used + 1) * 10 >= keys.size * 7) grow()
    val k = key + 1
    var i = mix(key) and mask
    while (true) {
      val cur = keys[i]
      if (cur == 0) { keys[i] = k; vals[i] = value; used++; return }
      if (cur == k) { vals[i] = value; return }
      i = (i + 1) and mask
    }
  }

  /** `key` 的计数 +1（一次探测；原 `(map[key] ?: 0) + 1` 是两次）。 */
  fun inc(key: Int) {
    if ((used + 1) * 10 >= keys.size * 7) grow()
    val k = key + 1
    var i = mix(key) and mask
    while (true) {
      val cur = keys[i]
      if (cur == 0) { keys[i] = k; vals[i] = 1; used++; return }
      if (cur == k) { vals[i]++; return }
      i = (i + 1) and mask
    }
  }

  /** `key` 的值加 [v]（一次探测）。 */
  fun add(key: Int, v: Int) {
    if ((used + 1) * 10 >= keys.size * 7) grow()
    val k = key + 1
    var i = mix(key) and mask
    while (true) {
      val cur = keys[i]
      if (cur == 0) { keys[i] = k; vals[i] = v; used++; return }
      if (cur == k) { vals[i] += v; return }
      i = (i + 1) and mask
    }
  }

  fun forEach(action: (Int, Int) -> Unit) {
    for (i in keys.indices) {
      val k = keys[i]
      if (k != 0) action(k - 1, vals[i])
    }
  }

  private fun grow() {
    val oldKeys = keys
    val oldVals = vals
    val cap = keys.size shl 1
    keys = IntArray(cap)
    vals = IntArray(cap)
    mask = cap - 1
    used = 0
    for (i in oldKeys.indices) {
      val k = oldKeys[i]
      if (k == 0) continue
      val key = k - 1
      var j = mix(key) and mask
      while (keys[j] != 0) j = (j + 1) and mask
      keys[j] = k; vals[j] = oldVals[i]; used++
    }
  }

  /** RGB 的 24 位键：乘法散列 + 高低位混合（与 `HashMap` 的扰动同目的，不追密码学强度）。 */
  private fun mix(k: Int): Int {
    var h = k * -0x61c88647
    h = h xor (h ushr 16)
    return h
  }
}
