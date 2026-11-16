package harness

import com.iamcanincan.opticon.circle.MonochromeMask
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * **冷算能不能并行** —— 这是一条只量的台子，不改生产。
 *
 * 为什么值得量：`MonochromeMask.build` 中位 213ms / p90 344ms（`CostKt`，384 档，主机 JVM），
 * 243 张串行 ≈ 52 s，正好是他每次装机后那一趟冷算。而 `MonochromeMask.pool` 是
 * `ThreadLocal<Scratch>` ⇒ 每个线程各拿一份工作区，**算法本来就允许并发**；要回答的只剩
 * "并发跑出来的掩码是不是逐字节同一张"和"实际能快几倍"。
 *
 * ⚠ 三条自检（缺一条就非零退出）：
 *   ① 并行的掩码与串行**逐张相同**（不同就不是快慢问题，是正确性问题）；
 *   ② 两次串行之间也相同（排除"第二次被 JIT 抬走"当成了并行的功劳）；
 *   ③ 串行那一挡真的算出了掩码（不是空跑）。
 *
 * 用法：`sh run.sh harness.ParKtKt rec_model [--edge=384] [--threads=8]`
 */
fun main(args: Array<String>) {
  val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "rec_model")
  val edge = args.firstOrNull { it.startsWith("--edge=") }?.substringAfter("=")?.toInt() ?: 384
  val threads = args.firstOrNull { it.startsWith("--threads=") }?.substringAfter("=")?.toInt() ?: 8
  val cs = cases(dir, edge, inset = false, fgAll = false).toList()
  if (cs.isEmpty()) { println("!! 语料为空，台子没牙"); exitProcess(2) }

  fun one(c: Case): Long {
    val r = MonochromeMask.build(c.src, edge, edge, c.plateLayer, c.under)
    val m = r.mask ?: return 0L
    return java.util.Arrays.hashCode(m).toLong()
  }

  // 预热：让两个方向都在同一份已编译的代码上计时。
  for (c in cs) one(c)

  fun serial(): Pair<Long, List<Long>> {
    val t0 = System.nanoTime()
    val h = cs.map { one(it) }
    return (System.nanoTime() - t0) / 1_000_000 to h
  }

  fun parallel(): Pair<Long, List<Long>> {
    val pool = Executors.newFixedThreadPool(threads)
    val t0 = System.nanoTime()
    val fut = cs.map { c -> pool.submit<Long> { one(c) } }
    val h = fut.map { it.get() }
    val ms = (System.nanoTime() - t0) / 1_000_000
    pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS)
    return ms to h
  }

  val (s1ms, s1) = serial()
  val (pms, p) = parallel()
  val (s2ms, s2) = serial()

  val nonEmpty = s1.count { it != 0L }
  println("语料 ${cs.size} 张 @edge=$edge，线程 $threads")
  println("串行#1 %d ms / 并行 %d ms / 串行#2 %d ms  ⇒ 加速 %.2fx（对串行#2）".format(
    s1ms, pms, s2ms, if (pms > 0) s2ms.toDouble() / pms else 0.0))
  println("每张中位：串行 %.1f ms / 并行 %.1f ms".format(
    s1ms.toDouble() / cs.size, pms.toDouble() / cs.size))
  val sameSP = s1 == p
  val sameSS = s1 == s2
  println("自检① 并行==串行：${if (sameSP) "是" else "否（差 ${s1.zip(p).count { it.first != it.second }} 张）"}")
  println("自检② 串行两次相同：${if (sameSS) "是" else "否"}")
  println("自检③ 串行真算出掩码：$nonEmpty/${cs.size} 张非空")
  if (!sameSP || !sameSS || nonEmpty < cs.size) exitProcess(1)
}
