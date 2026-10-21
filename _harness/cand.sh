#!/bin/sh
# 把**候选那一份** MonochromeMask 编进独立目录跑一遍，仓里那份一行不动。
# 为什么不用 ab.sh：ab.sh 的 new 永远取工作区那份，而"候选"不该进工作区（进了就想提交）。
# 跑法：sh cand.sh <候选文件> <输出目录名> [语料目录 …]
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
SRC=$1; V=$2; shift 2
# `PickKt` 要 `probeRamp/probeRef/probePick` 那三个探子口 —— 只有**带探子口的候选**才编得动它。
# 仓里那份（和按它改出来的交付候选）没有，硬编就是三个 unresolved reference。
PICK=; grep -q probeRamp "$SRC" 2>/dev/null && PICK=PickKt.kt

#  LayerRefKt 要 probeRef 那个探子口（仓里那份没有）—— 同样只在候选带着它时才编。
LREF=; grep -q probeRef "$SRC" 2>/dev/null && LREF=LayerRefKt.kt
CORPUS=${*:-rec3}
rm -rf cand_$V
"$JAVA" -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -nowarn -classpath "$CP" \
  -d cand_$V "$SRC" \
  ../app/src/main/kotlin/com/iamcanincan/opticon/notify/IconBitmap.kt \
  ../app/src/main/kotlin/com/iamcanincan/opticon/notify/ToneCheck.kt \
  ../app/src/main/kotlin/com/iamcanincan/opticon/runtime/MemberLookup.kt \
  MaskBench.kt SelfTest.kt Replay.kt Dbg.kt PolarityCheck.kt LumaCurve.kt FlipPeek.kt MapDump.kt SizeVarKt.kt \
  RefPassKt.kt FieldMixKt.kt GridCheck.kt PlateDump.kt PartsKt.kt ComposeKt.kt ToneKt.kt ThickKt.kt AbKt.kt RegionKt.kt \
  GateKt.kt DensKt.kt NuanceKt.kt RimDbg.kt InkWhere.kt CellDump.kt PixelDump.kt Smear.kt MaskPng.kt CompatKt.kt MonoKt.kt YardKt.kt ObjKt.kt DeliverKt.kt RampShapeKt.kt ColourInkKt.kt FilmAreaKt.kt HaloKt.kt LayerKt.kt $LREF LayerPlateKt.kt BgPlateKt.kt Band2Kt.kt TerrKt.kt BridgeKt.kt LedgerKt.kt AirInkKt.kt EncloseKt.kt $PICK
A=$(md5sum out/com/iamcanincan/opticon/circle/MonochromeMask.class | cut -c1-8)
B=$(md5sum cand_$V/com/iamcanincan/opticon/circle/MonochromeMask.class | cut -c1-8)
echo "指纹  仓里=$A  候选$V=$B"
[ "$A" = "$B" ] && { echo "!! 候选与仓里那份字节码一样 —— 这一轮没在比东西"; exit 1; }
for d in $CORPUS; do
  "$JAVA" -Dstdout.encoding=UTF-8 -cp "cand_$V;stubs_out;$(win "$SL")" harness.MaskBenchKt $d > cand_$V.txt 2>/dev/null
  echo "--- $d → cand_$V.txt"
done
"$JAVA" -Dstdout.encoding=UTF-8 -cp "cand_$V;stubs_out;$(win "$SL")" harness.SelfTestKt > /tmp/st_$V.txt 2>/dev/null
tail -1 /tmp/st_$V.txt
# ⚠ **判候选要"只算新增"，不能数 `FAIL` 行**（2026-10-13 我自己在这儿栽过一次：把
#   `bench/selftest_floor.txt` 里的既有底线 `平涂底 + 白字 → 不许走这一支` 当成了候选新增的）。
#   口径与 `regress.sh` 第二闸逐字一致。
grep -oE "FAIL  [^ ]+.*" /tmp/st_$V.txt | sed -E 's/^FAIL  //; s/  \[.*//' | sort > /tmp/stred_$V.txt
sort bench/selftest_floor.txt > /tmp/stfloor_$V.txt
NEWR=$(comm -23 /tmp/stred_$V.txt /tmp/stfloor_$V.txt)
if [ -n "$NEWR" ]; then echo "!! 候选新增的红（不在 bench/selftest_floor.txt 里）："; echo "$NEWR"
else echo "   无新增红（既有底线 $(wc -l < bench/selftest_floor.txt) 条）"; fi
