#!/bin/sh
# 覆盖率差集：**设备桌面看得见的包** − **语料里抓过的包**。
#
# 为什么需要它：语料是一张快照（`_harness/*/index.tsv` 里出现过的包），而他装新应用的速度
# 永远比"想起来了再抓一遍"快。2026-11-16 第一次量的结果是 132/137（96.4%），缺的 5 个
# 全部装于 11-08~11-10 —— **缺口是时间造成的，不是类别漏洞**；这条命令就是让下一次
# 只花 5 秒就知道又漏了谁。
#
# 用法：`sh cover.sh`（只读：`cmd package query-activities` + 本地 index.tsv，不碰设备状态）
#       `sh cover.sh --list` 只打差集（给脚本吃）
#
# ⚠ 分母选的是**桌面可见**（MAIN/LAUNCHER），不是 `pm list packages` —— 后者含一堆
#   没有任何图标的服务，拿它当分母会把覆盖率算成 60%，那是尺子选错，不是覆盖不足。
# ⚠ 语料列里 `pm:pkg/...` 与 `pkg/类型/入口` 两种写法都要认，取第一段；只认一种会漏包。
set -e
cd "$(dirname "$0")"
LIST_ONLY=0
[ "$1" = "--list" ] && LIST_ONLY=1

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

adb shell "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER" \
  | sed 's/^[[:space:]]*[0-9]*[[:space:]]*//' | grep "/" | cut -d/ -f1 | tr -d '\r' | sort -u > "$TMP/dev.txt"

for d in "${COVER_ROOT:-.}"/*/; do
  [ -f "$d/index.tsv" ] || continue
  awk -F'\t' 'NR>1 && length($1)>0 {p=$1; sub(/^pm:/,"",p); sub(/\/.*/,"",p); print p}' "$d/index.tsv"
done | sort -u > "$TMP/corpus.txt"

comm -13 "$TMP/corpus.txt" "$TMP/dev.txt" > "$TMP/miss.txt"
total=$(wc -l < "$TMP/dev.txt" | tr -d ' ')
miss=$(wc -l < "$TMP/miss.txt" | tr -d ' ')
hit=$((total - miss))

if [ "$LIST_ONLY" = "1" ]; then
  cat "$TMP/miss.txt"
  exit 0
fi
echo "桌面可见 $total 个，语料抓过 $hit 个（$(awk -v h="$hit" -v t="$total" 'BEGIN{printf "%.1f", h*100/t}')%）"
if [ "$miss" -gt 0 ]; then
  echo "从没抓过的 $miss 个："
  sed 's/^/  /' "$TMP/miss.txt"
  echo "（新装的应用在这里出现是正常的；它们的图标要等一次冷算 dump 才拿得到像素）"
fi