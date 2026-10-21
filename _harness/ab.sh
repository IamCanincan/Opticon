#!/bin/sh
# A/B 台：old = git HEAD 那份 MonochromeMask.kt，new = 工作区那份，其余源文件共用。
#
# ⚠ 存在的理由是今晚那次**假 A/B**（见 `57ec6ac`）：换源文件那一步没落地时，`out/` 里的旧
#   class 还在，两边跑的是同一份字节码，结论"净收益为零"因此是假的。所以这里**强制**：
#   ① 两个独立的输出目录（不复用 out/）；② 各自把 MonochromeMask.class 的 md5 打在开头 ——
#   **两个 md5 相同就是这一轮没在比东西**，别看数字，直接查为什么。
#   ③ 每次先 rm -rf 自己的输出目录，避免"上一轮的 class 冒充这一轮"。
set -e
. "$(dirname "$0")/jdk.sh"
M=/c/Users/20653/.gradle/caches/modules-2/files-2.1
KC=$(ls $M/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.20/*/kotlin-compiler-embeddable-2.4.20.jar)
SL=$(ls $M/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar)
CO=$(ls $M/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/*/*.jar)
AN=$(ls $M/org.jetbrains/annotations/*/*/annotations-*.jar | tail -1)
win() { cygpath -w "$1"; }
KCP="$(win "$KC");$(win "$SL");$(win "$CO");$(win "$AN")"
CP="$(win "$SL");$(win stubs_out)"
CIRCLE=../app/src/main/kotlin/com/iamcanincan/opticon/circle
DRIVERS="MaskBench.kt SelfTest.kt Replay.kt Dbg.kt PolarityCheck.kt LumaCurve.kt FlipPeek.kt \
MapDump.kt GridCheck.kt PlateDump.kt PartsKt.kt ComposeKt.kt ToneKt.kt ThickKt.kt AbKt.kt \
RegionKt.kt GateKt.kt DensKt.kt NuanceKt.kt RimDbg.kt InkWhere.kt InkMixKt.kt PrevKt.kt CellDump.kt PixelDump.kt Smear.kt MaskPng.kt \
MonoKt.kt RingKt.kt DumpKt.kt DiffKt.kt StepKt.kt GlueKt.kt BridgeKt.kt"
mkdir -p ab
# ⚠ 两份必须**同名**编：class 里带 "Compiled from <文件名>"，一个叫 MonochromeMask.old.kt
#   一个叫 .new.kt 的话，两份内容一模一样 md5 也不同 —— 下面那道"md5 相同=这一轮没在比东西"
#   的闸门就**永远不会响**（2026-09-29 提交之后重跑才发现：HEAD 与工作区同一份内容，
#   指纹却报 old=c5c4e544 new=6de9ed82）。所以各自放进自己的目录，文件名都叫 MonochromeMask.kt。
mkdir -p ab/old ab/new
git -C .. show ${AB_OLD:-HEAD}:app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt > ab/old/MonochromeMask.kt
cp $CIRCLE/MonochromeMask.kt ab/new/MonochromeMask.kt
for v in old new; do
  rm -rf var_$v
  "$JAVA" -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -nowarn \
    -classpath "$CP" -d var_$v ab/$v/MonochromeMask.kt \
    ../app/src/main/kotlin/com/iamcanincan/opticon/notify/IconBitmap.kt \
    ../app/src/main/kotlin/com/iamcanincan/opticon/notify/ToneCheck.kt \
    ../app/src/main/kotlin/com/iamcanincan/opticon/runtime/MemberLookup.kt \
    $DRIVERS > /dev/null
done
A=$(md5sum var_old/com/iamcanincan/opticon/circle/MonochromeMask.class | cut -c1-8)
B=$(md5sum var_new/com/iamcanincan/opticon/circle/MonochromeMask.class | cut -c1-8)
echo "指纹  old=$A  new=$B"
if [ "$A" = "$B" ]; then echo "!! 两份字节码一模一样 —— 这一轮什么都没比，先查源文件有没有落地"; exit 1; fi
