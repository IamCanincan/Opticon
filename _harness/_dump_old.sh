#!/bin/sh
# 一次性：把 f28c48a(HEAD~1，第六轮之前) 的 MonochromeMask + DumpKt 编到 out_old，dump 易班旧版 alpha。
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
rm -rf out_old; mkdir -p out_old
echo "== 编旧版(f28c48a) MonochromeMask + DumpKt -> out_old =="
"$JAVA" -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -nowarn -classpath "$CP" -d out_old _mask_f28.kt DumpKt.kt
echo "== dump 易班旧版 alpha =="
"$JAVA" -Dstdout.encoding=UTF-8 -cp "out_old;stubs_out;$(win "$SL")" harness.DumpKtKt bench/_yb1 bench/_yb1_alpha_old --edge=384
