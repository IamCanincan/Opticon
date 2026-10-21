#!/bin/sh
# 一次性 A/B：编两个**任意 git 版本**的模型（默认 HEAD~1 与 HEAD），跑同一套驱动。
# 为什么另外写一份：ab.sh 的 old 永远是 HEAD，而"上一版有没有把话说错"也要能查
# （今天发现按名字 join 之前那几轮聚合数作废，就得拿旧的两轮重算）。
set -e
. "$(dirname "$0")/jdk.sh"
M=/c/Users/20653/.gradle/caches/modules-2/files-2.1
KC=$(ls $M/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.20/*/kotlin-compiler-embeddable-2.4.20.jar)
SL=$(ls $M/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar)
CO=$(ls $M/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/*/*.jar)
AN=$(ls $M/org.jetbrains/annotations/*/*/annotations-jar 2>/dev/null | tail -1)
AN=$(ls $M/org.jetbrains/annotations/*/*/annotations-*.jar | tail -1)
win() { cygpath -w "$1"; }
KCP="$(win "$KC");$(win "$SL");$(win "$CO");$(win "$AN")"
CP="$(win "$SL");$(win stubs_out)"
CIRCLE=../app/src/main/kotlin/com/iamcanincan/opticon/circle
DRIVERS="MaskBench.kt SelfTest.kt NuanceKt.kt RimDbg.kt"
A=${1:-HEAD~1}; B=${2:-HEAD}
mkdir -p pair
git -C .. show $A:app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt > pair/MonochromeMask.a.kt
git -C .. show $B:app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt > pair/MonochromeMask.b.kt
for v in a b; do
  rm -rf pair_$v
  "$JAVA" -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -nowarn \
    -classpath "$CP" -d pair_$v pair/MonochromeMask.$v.kt \
    ../app/src/main/kotlin/com/iamcanincan/opticon/notify/IconBitmap.kt \
    ../app/src/main/kotlin/com/iamcanincan/opticon/notify/ToneCheck.kt \
    ../app/src/main/kotlin/com/iamcanincan/opticon/runtime/MemberLookup.kt \
    $DRIVERS > /dev/null
done
X=$(md5sum pair_a/com/iamcanincan/opticon/circle/MonochromeMask.class | cut -c1-8)
Y=$(md5sum pair_b/com/iamcanincan/opticon/circle/MonochromeMask.class | cut -c1-8)
echo "指纹  $A=$X  $B=$Y"
[ "$X" = "$Y" ] && { echo "!! 两份字节码一样"; exit 1; } || true
