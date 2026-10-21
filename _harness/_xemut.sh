#!/bin/sh
# 第十七闸的**变异 + 反面对照**（2026-10-17）。一台只会绿的闸不算立起来。
# 每一步都：改生产源 → 重编 → 跑闸 → 记退码 → 还原。任何一步没还原，最后 `cmp` 会拦下来。
F=../app/src/main/kotlin/com/iamcanincan/opticon/circle/IconCropWindow.kt
SL=$(ls /c/Users/20653/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar)
J=/d/Java/jdk-21.0.11/bin/java
step() {  # $1=名字 $2=期望退码
  sh build.sh > _xe_build.log 2>&1
  rc=$?
  if [ "$rc" != 0 ] || [ "$(grep -c 'error:' _kt.log)" != 0 ]; then
    echo "!! $1：编译就失败了（BUILD_RC=$rc），下面读的是上一轮的 class，不作数"; grep error: _kt.log | head -3; return 1
  fi
  "$J" -Dstdout.encoding=UTF-8 -cp "out;stubs_out;$(cygpath -w "$SL")" harness.CrossEdgeKtKt rec_model > "_xe_$1.txt" 2>&1
  rc=$?
  echo "== $1：退码 $rc（期望 $2） $(grep '^XS' _xe_$1.txt)"
  [ "$rc" = "$2" ] || echo "   !! 与期望不符 —— 这台子的牙要重新看"
  return 0
}
restore() { sed -i 's|val shrink = 1f|val shrink = minOf(1f, SCAN_EDGE.toFloat() / maxOf(w, h))|; s|private const val SCAN_EDGE = 64|private const val SCAN_EDGE = 128|' "$F"; }

echo "--- ① 变异：去掉降采样（判据开始吃各档自己的格子）→ 期望红"
sed -i 's|val shrink = minOf(1f, SCAN_EDGE.toFloat() / maxOf(w, h))|val shrink = 1f|' "$F"
grep -n 'val shrink' "$F"
step mut_nodownsample 1
restore

echo "--- ② 反面对照：扫描边长 128→64（三档仍落到同一张格子上）→ 期望绿（这台子不是见谁都红）"
sed -i 's|private const val SCAN_EDGE = 128|private const val SCAN_EDGE = 64|' "$F"
grep -n 'const val SCAN_EDGE' "$F"
step neg_scan64 0
restore

echo "--- ③ 还原：与备份逐字节相同 + 重跑 → 期望绿"
cmp icw.orig.bak "$F" && echo "   源文件已逐字节还原"
step restored 0
rm -f icw.orig.bak
