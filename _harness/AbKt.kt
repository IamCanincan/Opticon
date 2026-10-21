package harness

import java.io.File
import kotlin.math.abs

/**
 * 两个版本的逐张判据**按名字对齐**比较（MaskBench `--tsv=` 写出来的那份）。
 *
 * 为什么单独有个驱动：上一轮我用 shell `join` 拼改前/改后，判据行里有空格、两边字段数还不一样，
 * 拼出来的表错位了 —— 我拿那张错位的表去换判据，被自己抓回来才没发出去。
 * 这里只做一件事：按文件名建表，逐字段比，**任何一列不同就打出来**，按 |Δ框内| 排序。
 *
 * 用法：`AbKt 改前.tsv 改后.tsv [--only-changed]`
 */
fun main(args: Array<String>) {
  val a = read(File(args[0])); val b = read(File(args[1]))
  val onlyChanged = args.contains("--only-changed")
  val names = (a.keys + b.keys).sorted()
  val rows = ArrayList<Triple<String, List<String>, List<String>>>()
  for (n in names) {
    val x = a[n]; val y = b[n]
    if (x == null || y == null) { println("只在一边出现：$n"); continue }
    if (x == y && onlyChanged) continue
    if (x == y) continue
    rows.add(Triple(n, x, y))
  }
  println("%-52s %-14s %-14s %s".format("图标", "改前 H/底占/框内", "改后 H/底占/框内", "分档 改前 → 改后"))
  for ((n, x, y) in rows.sortedByDescending { abs(it.second[5].f - it.third[5].f) }) {
    println("%-52s %-14s %-14s %s → %s".format(
      n.take(52), "${x[1]}/${x[2]}/${x[5]}", "${y[1]}/${y[2]}/${y[5]}", x[6], y[6]))
  }
  println("=== ${a.size} 张里变了 ${rows.size} 张；框内平均 ${mean(a)} → ${mean(b)}")
}

private val String.f: Double get() = toDoubleOrNull() ?: 0.0

private fun read(f: File): LinkedHashMap<String, List<String>> {
  val m = LinkedHashMap<String, List<String>>()
  f.readLines().filter { it.isNotBlank() }.forEach { m[it.substringBefore('\t')] = it.split('\t') }
  return m
}

private fun mean(m: Map<String, List<String>>): String {
  val v = m.values.map { it[5].f }
  return if (v.isEmpty()) "-" else "%.3f".format(v.average())
}
