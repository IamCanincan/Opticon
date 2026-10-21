#!/bin/sh
# 实验：把生产里的粗量化 `plateKey` 从 **每通道 24 一档** 改成 **8 一档**
# （= 主人那句「相邻像素颜色不同要有区分」最直接的实现），跑完整 17 道闸，看换来什么。
# ⚠ 这是**实验不是落地**：跑完必须还原并逐字节核对（trap 兜底，中途失败也还原）。
F=../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt
cp "$F" mm.bak
restore() { cp mm.bak "$F"; sh build.sh > _pk8_build_back.log 2>&1; echo "== 已还原，编译退码 $?"; }
trap restore EXIT

echo "== ① 造变异：plateKey 24 → 8"
sed -i 's|((((p shr 16) and 0xff) / 24) shl 10) or ((((p shr 8) and 0xff) / 24) shl 5) or ((p and 0xff) / 24)|((((p shr 16) and 0xff) / 8) shl 10) or ((((p shr 8) and 0xff) / 8) shl 5) or ((p and 0xff) / 8)|' "$F"
grep -c "/ 8) shl 10" "$F"
sh build.sh > _pk8_build.log 2>&1
if [ "$?" != 0 ] || [ "$(grep -c 'error:' _kt.log)" != 0 ]; then echo "!! 编译失败，实验不作数"; grep error: _kt.log | head -3; exit 1; fi
ls out/harness/EdgeMergeKtKt.class >/dev/null 2>&1 || { echo "!! class 不在，实验不作数"; exit 1; }

echo "== ② 量化账（应为 8 档那一行的口径）"
. ./jdk.sh >/dev/null
SL=$(ls /c/Users/20653/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar)
"$JAVA" -Dstdout.encoding=UTF-8 -cp "out;stubs_out;$(cygpath -w "$SL")" harness.EdgeMergeKtKt rec_model > _em_pk8.txt 2>&1
head -1 _em_pk8.txt

echo "== ③ 跑 17 道闸（不 --update，只看它红不红、数怎么动）"
sh regress.sh > _regress_pk8.log 2>&1
echo "SUITE_RC=$?"
grep -E "^全绿|^!! " _regress_pk8.log | head -8
