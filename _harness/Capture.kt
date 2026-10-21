package harness

import java.security.MessageDigest
import java.util.Base64

/**
 * logcat 里的 `Mono: dump#N` 分片 → 拼齐的图（2026-10-20 从 [Replay] 里剥出来的共用一份)。
 *
 * 为什么单独一个文件：判据台有过一次"台子里手抄的副本，改生产它不响"。
 * 解析这条通道也一样 —— 只要 `Replay` 和 `GhostSweep` 各拿一套正则，
 * `MonoDump` 换字段时其中一台会**静默读到旧格式**，出的数字看着正常、其实复放的不是那张。
 */

/**
 * 键必须是 **(进程, dump 序号, 哪一份)**。
 *
 * `dump#N` 是**每进程各自从 1 数起**的，而一次抓取的日志里从来不只一个进程（桌面重启前后各一段、
 * SystemUI / 设置也都在模块作用域里）。旧版只按 `(序号, 哪一份)` 分组，于是同号的不同图标
 * 撞进同一个桶，每个片号取"最后到的那片"—— 真机四份日志实测：`live2` 3 个进程 102 组，旧键
 * 只认得出 36 组，其中 **7 组跨进程混拼**；`live` 4 个进程 122 组，旧键 44 组、混拼 10。
 * 上一轮"约 15% 的 PNG 被 logd 吞掉"就是这么来的 —— **通道一片没丢，丢的是我自己的键**。
 *
 * 新包每行带 `p=<十六进制 pid>`；旧日志没这个字段，退回 logcat 前缀里的 `( pid)`。
 */
data class Cap(
  val key: String,
  val iconId: String,
  val what: String,
  /** BEGIN 行里 label 之后那一段 —— 就是**设备自己那一趟的报告行**，`层底=` 和 `底占=` 都在里面。 */
  val note: String,
  val bytes: ByteArray,
)

private val PID_PREFIX = Regex("""\(\s*(\d+)\):""")
private val BEGIN = Regex("""Mono: dump#(\d+)(?: p=(\w+))? BEGIN id=(\d+) what=(\w+) parts=(\d+)(?: (.*))?""")
private val PART = Regex("""Mono: dump#(\d+)(?: p=(\w+))? (\w+) P(\d+)/(\d+) (\S+)""")

/** 这一行是谁发的：新日志读 `p=`，旧日志退回 logcat 前缀的 pid，都没有就当同一个进程。
 *  ⚠ 没匹配上的可选组在 `groupValues` 里是**空串而不是 null** —— 直接 elvis 会把整份日志读成一个进程。 */
private fun procOf(line: String, field: String?) =
  field?.takeIf { it.isNotEmpty() } ?: PID_PREFIX.find(line)?.groupValues?.get(1) ?: "0"

/** 拼齐的每键一份 + 一句统计。片号冲突的整组丢弃：混拼会写出一张谁也认不出的图。 */
fun capture(lines: List<String>): Pair<List<Cap>, String> {
  val parts = HashMap<String, HashMap<Int, String>>()
  val meta = LinkedHashMap<String, Triple<String, String, String>>()  // key -> (iconId, what, note)
  val expect = HashMap<String, Int>()
  val clashed = HashSet<String>()
  val procs = HashSet<String>()
  for (line in lines) {
    BEGIN.find(line)?.let { m ->
      val proc = procOf(line, m.groupValues[2]); procs += proc
      val key = "$proc:" + m.groupValues[1] + ":" + m.groupValues[4]
      meta[key] = Triple(m.groupValues[3], m.groupValues[4], m.groupValues[6])
      expect[key] = m.groupValues[5].toInt()
      parts[key] = HashMap()
    }
    PART.find(line)?.let { m ->
      val proc = procOf(line, m.groupValues[2]); procs += proc
      val key = "$proc:" + m.groupValues[1] + ":" + m.groupValues[3]
      val idx = m.groupValues[4].toInt()
      val prev = parts.getOrPut(key) { HashMap() }[idx]
      if (prev != null && prev != m.groupValues[6]) clashed += key
      parts[key]!![idx] = m.groupValues[6]
    }
  }
  val out = ArrayList<Cap>()
  var short_ = 0
  for ((key, m) in meta) {
    val (iconId, what, note) = m
    val count = expect[key] ?: continue
    val got = parts[key] ?: continue
    if (key in clashed) continue
    if (got.size != count) { short_++; continue }
    val b64 = StringBuilder()
    for (p in 0 until count) b64.append(got[p])
    out += Cap(key, iconId, what, note, Base64.getDecoder().decode(b64.toString()))
  }
  return Pair(out, "进程 ${procs.size} 个 / ${meta.size} 组 / 拼齐 ${out.size} / 缺片 $short_ / 冲突 ${clashed.size}")
}

fun md5(bytes: ByteArray): String =
  MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

/** 设备报告行里的一个字段，取不到返回 null（别默认成 0 —— 那会把"没这一栏"读成"读到一个 0"）。 */
fun field(note: String, name: String): Double? =
  Regex(Regex.escape(name) + """=(-?\d+(?:\.\d+)?)""").find(note)?.groupValues?.get(1)?.toDoubleOrNull()
