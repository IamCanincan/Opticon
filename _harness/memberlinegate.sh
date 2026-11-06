#!/bin/sh
# 第 24 闸「两档退族一致账」的判定本体（**只有一份**：`regress.sh` 与 `_semanticmut.cjs` 都 source 这里）。
#
# 为什么要有这一道（2026-11-07）：横幅那一族的成员线以前借的是**选族**的互异线 18，代价是驾考宝典那张
# 在 384 不开火、在 768 开火 —— 同一张图两档给相反判据，被四档全扫的漂移账记成 `driftN 6`。
# 换到 45 之后两档一致，可这件事以前**只有一台 15 分钟的全扫**看得见；全扫太贵，贵到改一行常数没人跑它。
# 这一道只跑 384 与 768 两档、只问退族，一分钟级。
#
# ⚠ 它自带**两条自证**（都是本仓记过账的形状）：
#   ① 分母：两份输出必须有同名图标（`n=0` 就是台子/语料坏了，不许读成"一格都没漂"）；
#   ② 候选：**必须看见至少一张在两档都开火**（`两档都开火=0` ⇒ 这是一把没有候选的尺，
#      与一把没有牙的尺输出完全一样）。
#
# 跑法：`. ./memberlinegate.sh; memberline_gate [输出后缀]`
memberline_gate() {
  sfx=${1:-}
  # 变异台那侧只 source `gateenv.sh`，那里没有 CORPUS ⇒ 默认值写在这里，不靠调用方
  ml_corpus=${CORPUS:-rec_model}
  ml_lo=${ML_LO:-384}
  ml_hi=${ML_HI:-768}
  run harness.MaskBenchKt --edge=$ml_lo "$ml_corpus" > "bench/reg/ml_${ml_lo}${sfx}.txt"
  run harness.MaskBenchKt --edge=$ml_hi "$ml_corpus" > "bench/reg/ml_${ml_hi}${sfx}.txt"
  node _memberline.cjs "bench/reg/ml_${ml_lo}${sfx}.txt" "bench/reg/ml_${ml_hi}${sfx}.txt" \
    --lo=$ml_lo --hi=$ml_hi
}
