#!/bin/sh
# 离线判据台的构建脚本。仓在 _harness/（.gitignore 的 /_*/ 已经盖住，不进仓库）。
# 为什么不用 gradle：只要把「真 MonochromeMask.kt / IconBitmap.kt + 一组桩」喂给 kotlinc
# 就够，起一份 Android 构建反而慢一个数量级。
#
# 编的是**仓里那两份源文件**，不是副本 —— 台子上的数字和真机上跑的必须是同一套代码，
# 否则这里量出来的结论一条都不能信（attic/ 里那几个旧驱动就是死在这条上的）。
set -e
. "$(dirname "$0")/jdk.sh"   # JDK 17+ 由脚本自己钉，不靠 PATH（见 jdk.sh 头部那段翻车记录）
M=/c/Users/20653/.gradle/caches/modules-2/files-2.1
KC=$(ls $M/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.20/*/kotlin-compiler-embeddable-2.4.20.jar)
SL=$(ls $M/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar)
CO=$(ls $M/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/*/*.jar)
AN=$(ls $M/org.jetbrains/annotations/*/*/annotations-*.jar | tail -1)
win() { cygpath -w "$1"; }
"$JAVAC" -nowarn -d stubs_out $(find stubs -name "*.java")
KCP="$(win "$KC");$(win "$SL");$(win "$CO");$(win "$AN")"
CP="$(win "$SL");$(win stubs_out)"
SRC="../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt \
     ../app/src/main/kotlin/com/iamcanincan/opticon/circle/SolidBg.kt \
     ../app/src/main/kotlin/com/iamcanincan/opticon/circle/PlateFog.kt \
     ../app/src/main/kotlin/com/iamcanincan/opticon/circle/IconCropWindow.kt \
     ../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonoKey.kt \
     ../app/src/main/kotlin/com/iamcanincan/opticon/circle/MonoVeto.kt \
     ../app/src/main/kotlin/com/iamcanincan/opticon/circle/IconLabel.kt \
     ../app/src/main/kotlin/com/iamcanincan/opticon/circle/IconRough.kt \
     ../app/src/main/kotlin/com/iamcanincan/opticon/notify/IconBitmap.kt \
     ../app/src/main/kotlin/com/iamcanincan/opticon/notify/ToneCheck.kt \
     ../app/src/main/kotlin/com/iamcanincan/opticon/runtime/MemberLookup.kt"
# ⚠ **编译输出必须落在 `_kt.log` 里**：`regress.sh` 第 1 道闸门读的就是这个文件，而这个脚本
#   以前**从来不写它**（`set -e` 让编译失败时直接退出，日志留在上一轮的旧内容上）。
#   后果是那道闸门今天全天报的都是"0 error"，而它读的是一个 23:40 的过期文件 ——
#   加了 StrokeKt 之后第一次暴露：它有类型错，`out/` 里根本没这个 class，闸门却照样绿。
#   这条正是本仓反复踩的那一类：**一个跑不起来的检查报"通过"，和没跑一样。**
rm -f _kt.log
if "$JAVA" -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -nowarn -classpath "$CP" -d out $SRC MaskBench.kt SelfTest.kt Replay.kt Dbg.kt PolarityCheck.kt LumaCurve.kt MidKt.kt FamilyKt.kt PairProbe.kt WashKt.kt LineProbe.kt BandThick.kt GoalKt.kt SizeVarKt.kt DevOutKt.kt PlateInkKt.kt PlateSplitKt.kt FlipPeek.kt MapDump.kt GridCheck.kt PlateDump.kt PartsKt.kt ComposeKt.kt ToneKt.kt ThickKt.kt AbKt.kt RegionKt.kt GateKt.kt DensKt.kt NuanceKt.kt RimDbg.kt Rimwhere.kt ToneSplitKt.kt InkWhere.kt CellDump.kt FieldMixKt.kt RefPassKt.kt PixelDump.kt Smear.kt MaskPng.kt CompatKt.kt PartAreaKt.kt ReflectKt.kt ThinKt.kt RingKt.kt RampKt.kt MonoKt.kt LineDbg.kt QualKt.kt StrokeKt.kt EraseKt.kt BandKt.kt DumpKt.kt DiffKt.kt OutKt.kt CoverKt.kt CropKt.kt GapKt.kt FieldKt.kt LocalKt.kt HugKt.kt StepKt.kt LutKt.kt ChainKt.kt BarKt.kt SiloKt.kt GlueKt.kt BridgeKt.kt MontageKt.kt ABSheetKt.kt BlendKt.kt RingKt.kt YardKt.kt ObjKt.kt LumaKt.kt TonalKt.kt TonalGateKt.kt InkTopKt.kt PxDump.kt TwoPlateKt.kt EncloseKt.kt BreakKt.kt TailKt.kt SheetKt.kt CutKt.kt Plate2Kt.kt Plate3Kt.kt EdgeKt.kt SeamKt.kt ScarKt.kt VoteKt.kt SlabKt.kt DustKt.kt LevelsKt.kt PlateKt.kt RampShapeKt.kt ChromaKt.kt TransKt.kt DeliverKt.kt ChanKt.kt RectKt.kt PolarKt.kt InkSpanKt.kt EndKt.kt AirInkKt.kt PairSheetKt.kt DistKt.kt SizeKt.kt PlateBgKt.kt ColourInkKt.kt FilmAreaKt.kt HaloKt.kt BgPlateKt.kt FgPlateKt.kt Band2Kt.kt LayerKt.kt FrameKt.kt OutSheetKt.kt TerrKt.kt BoardKt.kt PlateGapKt.kt LedgerKt.kt ZoomKt.kt BoundaryMockKt.kt BoundaryKindKt.kt YbDiag.kt BumpScan.kt VoteScan.kt VoteScan2.kt YiKt.kt GeomKt.kt SilKt.kt CostKt.kt PerimKt.kt TintInKt.kt PlateKindKt.kt PlateKind2Kt.kt PlateSetKt.kt PlateSubKt.kt MonoKeyKt.kt WinKt.kt BgExtendKt.kt GeomPairKt.kt DeckKt.kt MonoShotKt.kt FaintKt.kt PlateFeatKt.kt ReframeDiffKt.kt ReframeCorpusKt.kt WinCacheKt.kt CrossEdgeKt.kt EdgeMergeKt.kt EdgeFlatKt.kt EdgeRegionKt.kt HoleProbeKt.kt VoteWhyKt.kt VoteScaleKt.kt InkMixKt.kt FragKt.kt RimKt.kt RimRefKt.kt LabChk.kt AsciiKt.kt LineSegKt.kt PrevKt.kt ArcGateKt.kt PlateCutProbeKt.kt BridgeProbeKt.kt KeepBigKt.kt FaintShotKt.kt ResistKt.kt OutFieldKt.kt VetoKt.kt BurrKt.kt Capture.kt GhostSweepKt.kt TwoAcctKt.kt AdaptKt.kt Feet.kt RegionKt.kt Inputs.kt > _kt.log 2>&1
then
  echo "RUN: $JAVA -Dstdout.encoding=UTF-8 -cp out;stubs_out;$(win "$SL") harness.MaskBenchKt [--inset] [--shot] [--edge=N] <图标目录>"
else
  echo "!! 编译失败 —— out/ 里还是上一轮的 class，别拿它们的数字当这一轮的结论"
  grep -E "error:" _kt.log | head -12
  exit 1
fi
