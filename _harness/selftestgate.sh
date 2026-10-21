# 第 2 段（性质台）的**判定本体** —— 只有一份：`regress.sh` 与变异台 `_semanticmut.cjs` 都调它。
#
# 为什么单独成文件：这一段的判据是"**不许出现底线名单之外的新红**"（`全部通过` 那个字符串早就废弃了 ——
#   它曾让 3~8 闸四天没跑）。内联在 `regress.sh` 里时，任何想复现这条判定的台子只能再抄一遍 shell，
#   而本仓为"闸测的是手抄副本"栽过两次（第十六闸、`plateSubject`）。
# 用法：`. ./selftestgate.sh; selftest_gate <raw 日志> [输出后缀]`
#   退 0 = 无新增红；非 0 = 这一闸红（并把红话说完）。后缀默认空，变异台传 `_mut` 以免覆盖活账本。
selftest_gate() {
  RAW="$1"
  SUF="${2:-}"
  if [ ! -f "$RAW" ]; then echo "!! 没有 $RAW —— 性质台一张都没测，这一闸不算过"; return 1; fi
  if [ ! -f bench/selftest_floor.txt ]; then
    echo "!! 没有 bench/selftest_floor.txt —— 不知道哪几条红是**记过的**，这一闸不算过"
    return 1
  fi
  tail -1 "$RAW" | tee "bench/reg/selftest${SUF}.txt"
  # ⚠ 取红的方式：`FAIL  <名字>  [<读数>]` → 只留名字。名字漂了就等于换了判据，这里不认账。
  grep -oE "FAIL  [^ ]+.*" "$RAW" | sed -E 's/^FAIL  //; s/  \[.*//' | sort > "bench/reg/selftest_red${SUF}.txt"
  sort bench/selftest_floor.txt > "bench/reg/selftest_floor_sorted${SUF}.txt"
  NEW=$(comm -23 "bench/reg/selftest_red${SUF}.txt" "bench/reg/selftest_floor_sorted${SUF}.txt")
  if [ -n "$NEW" ]; then
    echo "!! 性质台有**新增**的红（不在底线名单里）："
    echo "$NEW"
    return 1
  fi
  if [ ! -s "bench/reg/selftest_red${SUF}.txt" ]; then
    # 全绿是这一闸能给出的最好答案，**不是装置故障**（2026-10-11 就是把最好的结果判成红，
    # 于是最后两条红修掉之后这一闸会永远 exit 1，再把后面的闸挡在门外）。
    if ! grep -q "全部通过" "$RAW"; then
      echo "!! 性质台一条 FAIL 都没报，也没报「全部通过」—— 驱动坏了，不算过"
      return 1
    fi
    echo "   红 0 条 —— 全绿（底线名单里那 $(wc -l < bench/selftest_floor.txt) 条也不欠了）"
  else
    echo "   红 $(wc -l < "bench/reg/selftest_red${SUF}.txt") 条 = 底线 $(wc -l < bench/selftest_floor.txt) 条，无新增"
  fi
  return 0
}
