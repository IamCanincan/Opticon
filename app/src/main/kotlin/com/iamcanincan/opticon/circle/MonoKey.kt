package com.iamcanincan.opticon.circle

/**
 * [MonoCache] 的两件**纯计算**：内容键、掩码文件的字节格式。
 *
 * 单独一个文件只为一个理由：这两件事错了都是「屏幕上出现别的图标的掩码」或「半个文件上一张
 * 破图标」，而它们**不需要桌面、不需要 Bitmap、不需要真机**就能验 —— 所以把它们从
 * [MonoCache]（要 Context、要文件、要位图）里剥出来，判据台 `SelfTest` 就能逐条钉住。
 * 装机的账永远量不到"错的那一条"，这里能。
 */
internal object MonoKey {

  /** FNV-1a 的素数；只求快与分布，不求密码学强度。 */
  private const val FNV_PRIME = 1099511628211L

  /**
   * 文件头：`rowBytes` 与 `edge` 各 4 字节（大端）+ 1 字节口径版本。见 [decode] 里为什么不能按算的。
   *
   * ⚠ **口径版本是缓存失效的唯一开关**（2026-10-23 Pinduoduo 白心）：键只覆盖六个输入，
   *   表达不了"同样的输入、掩码该换一种"。9 字节头让上一版写下的 8 字节文件在 [decode] 的
   *   长度检查处直接落空 —— 不读坏数据、也不用清桌面缓存。
   */
  const val HEADER_BYTES = 9

  /** 掩码口径版本。**凡是"同样六样输入、交出的掩码该变"的改动都要 +1。** */
  private const val FORMAT = 2

  /**
   * 内容键。**六个输入一个都不能少**：掩码是这六样的纯函数，少一样就是「换了设置还给旧答案」。
   *
   * 全像素扫，不抽样：抽样的漏检代价是**交出别的图标的掩码**，而这一趟只在省掉三百多毫秒的那
   * 一刻才跑，扫 147456 个 int 的钱（判据台量 ≤1 ms）与它比可以忽略。
   * 64 位哈希在一个抽屉的 209 个条目上撞一次的机会约 1e-15，比内存里一个位翻转还低。
   *
   * ⚠ `or (1L shl 32)` 的括号是必需的：Kotlin 里 `or` / `shl` 都是中缀、**同优先级左结合**，
   *   写成 `x or 1L shl 32` 读作 `(x or 1) shl 32` —— 那是另一个函数，而且编译不报错。
   *   键的两位标志位（1/2 与 4）就靠这两个"长度种子"隔开，写错了会让 `under` 为空的
   *   两张与别的组合撞在一起。
   */
  fun keyOf(
    px: IntArray, under: IntArray?, edge: Int, adaptive: Boolean, flatten: Boolean, circle: Boolean,
  ): Long {
    var h = edge.toLong() or (1L shl 32)
    h = mix(h, (if (adaptive) 1L else 0L) or (if (flatten) 2L else 0L) or (if (circle) 4L else 0L))
    h = fold(h, px, 2L)
    h = fold(h, under, 3L)
    return final(h)
  }

  private fun mix(h: Long, v: Long): Long = (h xor v) * FNV_PRIME

  /**
   * 键的写法：日志里的 `键=` 与磁盘上的文件名**必须是同一个字符串**，
   * 否则拿着日志去数目录时对不上（"这一张到底写没写出去"又要靠推演回答）。
   */
  fun hex(key: Long): String = java.lang.Long.toHexString(key)

  /** 逐像素 FNV，先钉进长度与这一路的编号 —— 前缀相同、长度不同的两张，或「同一组像素挂在
   *  `px` 还是挂在 `under` 上」，都要分开。 */
  private fun fold(h0: Long, px: IntArray?, tag: Long): Long {
    if (px == null) return mix(h0, tag shl 40)
    var h = mix(h0, (px.size.toLong() or (tag shl 48)))
    for (p in px) h = (h xor p.toLong()) * FNV_PRIME
    return h
  }

  /** murmur3 的 fmix64：把高位也搅进来，别让相邻 edge 的两张只靠低位区分。 */
  private fun final(h: Long): Long {
    var x = h
    // 这两个常数超过 Long.MAX_VALUE，Kotlin 不让写成裸十六进制字面量，得走 ULong 转。
    x = (x xor (x ushr 33)) * 0xff51afd7ed558ccdUL.toLong()
    x = (x xor (x ushr 33)) * 0xc4ceb9fe1a85ec53UL.toLong()
    return x xor (x ushr 33)
  }

  /**
   * 打包一张 ALPHA_8 掩码：`[rowBytes][edge][body]`。
   *
   * ⚠ `rowBytes` 存**当时那个真值**，读的人不许按 `edge` 算：ALPHA_8 的行步长补不补到 4 的倍数，
   * 在不同实现上不一样（本仓已经在缩略图那里踩过一次「按 `w` 读、每行挪 `(stride-w)` 字节，
   * 看着像掩码碎了」）。写的人按算的、读的人按算的，两处一旦不同就是**永远不命中** ——
   * 而那种失败在日志里只长得像"这台桌面不缓存"。
   */
  fun encode(rowBytes: Int, edge: Int, body: ByteArray): ByteArray {
    val out = ByteArray(HEADER_BYTES + body.size)
    putInt(out, 0, rowBytes)
    putInt(out, 4, edge)
    out[8] = FORMAT.toByte()
    body.copyInto(out, HEADER_BYTES)
    return out
  }

  /**
   * 解包。[haveRowBytes] 是**读的人**手上那张位图的实际行步长。
   *
   * 任何一条对不上都返回 null（调用方当没命中并把文件删掉）：长度、边长、行步长、
   * 以及"存的行步长比边长还小"这种自相矛盾的头部。**宁可慢，不可给坏的那一张。**
   */
  fun decode(bytes: ByteArray, wantEdge: Int, haveRowBytes: Int): ByteArray? {
    if (bytes.size < HEADER_BYTES) return null
    // 旧口径的文件（8 字节头）先在这里落空 —— 别让一版前的掩码借同一个键回来。
    if (bytes[8].toInt() != FORMAT) return null
    val rowBytes = getInt(bytes, 0)
    val edge = getInt(bytes, 4)
    if (edge != wantEdge || edge <= 0 || rowBytes < edge) return null
    val need = HEADER_BYTES.toLong() + rowBytes.toLong() * edge
    if (bytes.size.toLong() != need) return null
    if (haveRowBytes != rowBytes) return null
    return bytes.copyOfRange(HEADER_BYTES, bytes.size)
  }

  private fun putInt(a: ByteArray, at: Int, v: Int) {
    a[at] = (v ushr 24).toByte()
    a[at + 1] = (v ushr 16).toByte()
    a[at + 2] = (v ushr 8).toByte()
    a[at + 3] = v.toByte()
  }

  private fun getInt(a: ByteArray, at: Int): Int =
    ((a[at].toInt() and 0xff) shl 24) or ((a[at + 1].toInt() and 0xff) shl 16) or
      ((a[at + 2].toInt() and 0xff) shl 8) or (a[at + 3].toInt() and 0xff)
}
