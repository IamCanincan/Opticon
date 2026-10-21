package harness

import com.iamcanincan.opticon.runtime.MemberLookup

/**
 * **单测（只测不改）**：按名字的 `readField` / `writeField` 会不会走到**父类**的字段。
 *
 * 为什么要有这一条：装钩期解析失败时，运行期会退回"按名字现查"那一版；而那一版过去只查
 * `target.javaClass` **自己一层**，[MemberLookup.findField] 却是沿父类链找的。
 * 于是出现过一种只有兜底会撞上的坏法：ROM 把 `mContext` 挪进父类时，主路找得到、兜底找不到 ——
 * 两条路对同一个字段给出**相反**的答案，排查时会被误导成"这个 ROM 没有这个字段"。
 *
 * 跑法：`java ... harness.ReflectKtKt`
 */

private open class GrandBase {
  @Suppress("unused") var mContext: String = "在爷爷类里"
}

private open class Base : GrandBase() {
  @Suppress("unused") var mMiddle: String = "在父类里"
}

private class Leaf : Base() {
  @Suppress("unused") var mOwn: String = "在自己这一层"
}

private var failed = 0

private fun expect(what: String, ok: Boolean, detail: String) {
  println("  ${if (ok) "PASS" else "FAIL"}  $what  [$detail]")
  if (!ok) failed++
}

fun main() {
  println("=== MemberLookup 按名字兜底：会不会走上父类链 ===")

  val leaf = Leaf()
  val own = MemberLookup.readField(leaf, "mOwn")
  expect("自己这一层的字段照旧读得到", own == "在自己这一层", "读到=$own")

  val parent = MemberLookup.readField(leaf, "mMiddle")
  expect("父类的字段读得到（改前这里是 null）", parent == "在父类里", "读到=$parent")

  val grand = MemberLookup.readField(leaf, "mContext")
  expect("爷爷类的字段也读得到", grand == "在爷爷类里", "读到=$grand")

  val missing = MemberLookup.readField(leaf, "根本没有这个字段")
  expect("不存在的字段返回 null，不抛", missing == null, "读到=$missing")

  val wrote = MemberLookup.writeField(leaf, "mContext", "写进爷爷那份")
  expect("按名字写父类字段成功", wrote && leaf.mContext == "写进爷爷那份", "返回=$wrote 值=${leaf.mContext}")

  val wroteMissing = MemberLookup.writeField(leaf, "没有这个", 1)
  expect("写不存在的字段返回 false，不是抛", !wroteMissing, "返回=$wroteMissing")

  // 按类型那一版同样要对：SystemUI 的 `NotificationEntry` 兜底就是按类型找 StatusBarNotification。
  val byType = MemberLookup.readFieldByType(leaf, String::class.java)
  expect("readFieldByType 也走上父类链", byType != null, "读到=$byType")

  println(if (failed == 0) "全部通过" else "有 $failed 条红")
  if (failed > 0) kotlin.system.exitProcess(1)
}
