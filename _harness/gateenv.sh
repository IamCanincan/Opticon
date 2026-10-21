#!/bin/sh
# **判据台的"怎么把 class 跑起来"只许有一份。** 被 `regress.sh` 与 `_semanticmut.cjs` 同时 source。
#
# 为什么单独一个文件（2026-10-21）：语义变异台想**只跑一道闸**（几十秒），而它以前只能整台重跑
# （十几分钟）—— 因为 `CPW` 那三行与 `run()` 抄在 `regress.sh` 里，别的调用方要么再抄一份、
# 要么放弃。本仓为"第二份抄本"交过两次假结论（`Capture.kt` 的正则、`FaintShotKt` 的输入构造），
# 这一条是同一个坑的构建版：**抄出来的 classpath 会漂，漂了以后两边的数比的不是同一套字节码。**
#
# ⚠ 用了它的地方：`regress.sh`（18 道闸全部）与 `node _semanticmut.cjs`。
#   改这三行等于同时改两边的量具 —— 改完必须跑一整台对照（`sh regress.sh` 要全绿，
#   且各段读数与上一轮一致），不许只测自己那一条路。
# ⚠ **`$0` 在被 `.`/`source` 的时候是调用方的名字，不是本文件的路径** —— 直接
#   `. "$(dirname "$0")/jdk.sh"` 从别的脚本里 source 这一份就变成去找 `/usr/bin/jdk.sh`
#   （2026-10-21 现量：`node _semanticmut.cjs` 的 `gateCmd` 一跑就炸在这里）。
#   所以按几个候选位置找，不用 bash 专有的 `BASH_SOURCE`（`sh` 在有些机器上是 dash）。
GD=""
for c in "$(dirname "$0")" "$PWD" "$(dirname "$0")/_harness" "."; do
  if [ -f "$c/jdk.sh" ]; then GD="$c"; break; fi
done
[ -n "$GD" ] || { echo "!! gateenv.sh 找不到同目录的 jdk.sh —— 别从别的目录 source 它"; exit 3; }
. "$GD/jdk.sh"
M=/c/Users/20653/.gradle/caches/modules-2/files-2.1
SL=$(ls $M/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar)
CPW="out;stubs_out;$(cygpath -w "$SL")"
# ⚠ `2>/dev/null` 是有意的：这些台子会把 Kotlin 桩的告警打到 stderr，几百行，混进账本就看不清。
#   代价是**真异常也一起吞**（`run` 的退码还在，但堆栈没了）—— 所以判定一律看退码与机器可读行，
#   不看它印了什么。调试单独跑时把 `2>/dev/null` 去掉。
run() { "$JAVA" -Dstdout.encoding=UTF-8 -cp "$CPW" "$@" 2>/dev/null; }
