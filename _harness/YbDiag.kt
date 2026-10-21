package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.lang.reflect.Field
import javax.imageio.ImageIO

/**
 * 易班疙瘩诊断（只读不改）：build 之后用反射把 [MonochromeMask] 的 `pool`（ThreadLocal<Scratch>）
 * 挖出来，回答两个问题——
 *  1. 青带渐变的淡端（RGB≈109,176,250）那些格子，`plate` 是不是非 0（被判成了底）？
 *  2. 渐变的每个源色分箱落在哪个 `bucketOf` 粗桶、交付 out 是多少？
 * 用法：`sh run.sh harness.YbDiag [源图路径]`
 */
object YbDiag {
  @JvmStatic
  fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: "bench/_yb1/com.yiban.app_yiban_icon_8e21581a9e86.png"
    val img = ImageIO.read(File(path))
    val edge = img.width
    require(img.width == img.height) { "期望方图，得到 ${img.width}x${img.height}" }
    val px = IntArray(edge * edge)
    img.getRGB(0, 0, edge, edge, px, 0, edge)
    val r = MonochromeMask.build(px, edge, edge)
    println("report: ${r.report}")
    val mask = r.mask ?: run { println("没有掩码"); return }

    val cls = MonochromeMask::class.java
    val instance = cls.getField("INSTANCE").get(null)
    val poolF: Field = cls.getDeclaredField("pool").also { it.isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val pool = poolF.get(instance) as ThreadLocal<Any>
    val s = pool.get() ?: error("scratch 为空")
    val plate = s.javaClass.getDeclaredField("plate").also { it.isAccessible = true }.get(s) as ByteArray
    val bucketM = cls.declaredMethods.first { it.name == "bucketOf" }.also { it.isAccessible = true }
    fun bucket(i: Int): Int = bucketM.invoke(instance, i, s) as Int

    println("\n== 采样点 ==")
    val pts = listOf(
      "带尾亮边(被吃)" to listOf(290 to 265, 300 to 268, 285 to 262, 305 to 261, 306 to 264),
      "带尾中档(158)" to listOf(295 to 257, 285 to 255),
      "带尖亮边(被吃)" to listOf(10 to 97, 6 to 99),
      "带尖中档(179)" to listOf(14 to 95),
      "带身主体(130)" to listOf(18 to 97, 19 to 80, 170 to 230, 185 to 236),
      "绕熊头亮边(被吃)" to listOf(180 to 247, 175 to 240, 190 to 250)
    )
    for ((name, list) in pts) {
      print("$name : ")
      for ((x, y) in list) {
        val i = y * edge + x
        val p = px[i]
        print("(x=$x,y=$y rgb=${(p shr 16) and 0xff},${(p shr 8) and 0xff},${p and 0xff}" +
          " plate=${plate[i].toInt()} bucket=${bucket(i)} out=${(mask[i] ushr 24) and 0xff}) ")
      }
      println()
    }

    println("\n== 青族源色分箱（RGB/12，n≥40 才列；plateN=被判成底的格数，zero=交付 0 的格数）==")
    class Agg(var n: Int = 0, var plateN: Int = 0, var zero: Int = 0,
      var tMin: Int = Int.MAX_VALUE, var tMax: Int = -1, var bk: Int = -1, var exX: Int = -1, var exY: Int = -1)
    val aggs = HashMap<String, Agg>()
    for (i in 0 until edge * edge) {
      val p = px[i]
      if (((p ushr 24) and 0xff) < 255) continue
      val rr = (p shr 16) and 0xff; val gg = (p shr 8) and 0xff; val bb = p and 0xff
      if (!(gg > 150 && bb > 140 && rr < 160 && gg > rr + 40)) continue   // 青/蓝青族
      val key = "${rr / 12}_${gg / 12}_${bb / 12}"
      val ag = aggs.getOrPut(key) { Agg() }
      ag.n++
      if (plate[i].toInt() != 0) ag.plateN++
      val o = (mask[i] ushr 24) and 0xff
      if (o == 0) ag.zero++ else { if (o < ag.tMin) ag.tMin = o; if (o > ag.tMax) ag.tMax = o }
      if (ag.bk == -1) { ag.bk = bucket(i); ag.exX = i % edge; ag.exY = i / edge }
    }
    for ((k, v) in aggs.entries.sortedByDescending { it.value.n }) {
      if (v.n < 40) continue
      val rng = if (v.tMax < 0) "-" else "${v.tMin}..${v.tMax}"
      println("  key=$k n=${v.n} plate=${v.plateN} zero=${v.zero} out=[$rng] bucket=${v.bk}(例 ${v.exX},${v.exY})")
    }

    // 左上角凸起区：逐格看「源色类别 / plate / 交付 out」，回答"那块到底算底还是算墨"。
    println("\n== 左上凸起区逐格（S=源色类别 C青/B淡蓝/W白/g灰/?其他；plate 0/1；out 0..255）==")
    fun srcCls(p: Int): Char {
      val rr = (p shr 16) and 0xff; val gg = (p shr 8) and 0xff; val bb = p and 0xff
      return when {
        gg - rr >= 110 && bb - rr >= 90 && gg >= 200 -> 'C'
        bb >= 235 && rr >= 200 && gg >= 200 -> 'W'
        bb - rr >= 60 && bb >= 200 -> 'B'
        maxOf(rr, gg, bb) - minOf(rr, gg, bb) < 30 -> 'g'
        else -> '?'
      }
    }
    val x0 = 0; val x1 = 28; val y0 = 64; val y1 = 105
    print("      src ")
    for (x in x0 until x1) print(if (x % 10 == 0) (x / 10).toString() else " ")
    println()
    for (y in y0 until y1) {
      val sc = StringBuilder(); val ot = StringBuilder()
      for (x in x0 until x1) {
        val i = y * edge + x
        val srcA = (px[i] ushr 24) and 0xff
        sc.append(if (srcA < 128) '.' else srcCls(px[i]))
        val o = (mask[i] ushr 24) and 0xff
        ot.append(when {
          plate[i].toInt() != 0 && o == 0 -> 'P'
          plate[i].toInt() != 0 && o > 0 -> 'p'
          o == 0 -> '.'
          o >= 128 -> '#'
          else -> '-'
        })
      }
      println("  $y  src[$sc] out[$ot]")
    }
  }
}
