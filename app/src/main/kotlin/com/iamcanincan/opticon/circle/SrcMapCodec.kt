package com.iamcanincan.opticon.circle

import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * 「源内容快键表」（srcKey → 内容键）的磁盘格式。**纯函数**，判据台编得动、`SelfTest` 钉得住。
 *
 * 为什么要拆出来（2026-10-26）：这张表跨进程复用后，**半份表 = 把一张图指到另一张掩码上**
 * （错，不是慢）。所以格式里带 magic / version / 条数 / 逐字节长度 + CRC32 —— 任何一处对不上
 * 整份丢掉，调用方退回慢路。`MonoCache` 只负责文件 I/O，坏文件的判定全在这里。
 *
 * 布局：`magic:int | version:int | n:int | n × (srcKey:long, contentKey:long) | crc:long`
 * CRC 覆盖头部 + 表体（不含自己）。条数上限 [MAX] 同时挡住坏文件让 `HashMap` 预分配爆内存。
 */
internal object SrcMapCodec {

  private const val MAGIC = 0x4F50534B
  private const val VERSION = 1

  /** 一次能装多少条。生产写得比这少（`MonochromeSynth.SRC_MAP_MAX` = 4096），这里只做上限。 */
  const val MAX = 8192

  private const val HEAD = 12
  private const val TAIL = 8

  fun encode(map: Map<Long, Long>): ByteArray {
    val n = map.size
    require(n <= MAX) { "src map too large: $n" }
    val bb = ByteBuffer.allocate(HEAD + n * 16 + TAIL)
    bb.putInt(MAGIC)
    bb.putInt(VERSION)
    bb.putInt(n)
    for ((k, v) in map) {
      bb.putLong(k)
      bb.putLong(v)
    }
    val crc = CRC32()
    crc.update(bb.array(), 0, HEAD + n * 16)
    bb.putLong(crc.value)
    return bb.array()
  }

  /** 解不开 / 对不上 / 超上限 → null（调用方当"没有这张表"）。 */
  fun decode(bytes: ByteArray): HashMap<Long, Long>? {
    if (bytes.size < HEAD + TAIL) return null
    val bb = ByteBuffer.wrap(bytes)
    if (bb.int != MAGIC || bb.int != VERSION) return null
    val n = bb.int
    if (n < 0 || n > MAX || bytes.size != HEAD + n * 16 + TAIL) return null
    val crcAt = HEAD + n * 16
    val crc = CRC32()
    crc.update(bytes, 0, crcAt)
    // ⚠ 必须绝对定位：读完头部时 position 还在 12，直接 `bb.long` 会把表体第一项当 CRC
    //   （空表能过、n>0 全挂 —— 写下界时只测了空表就看不出来；SelfTest 的往返用例就是抓它的）。
    if (crc.value != bb.getLong(crcAt)) return null
    bb.position(HEAD)
    return HashMap<Long, Long>(n * 2).also { m ->
      repeat(n) {
        val k = bb.long
        val v = bb.long
        m[k] = v
      }
    }
  }
}
