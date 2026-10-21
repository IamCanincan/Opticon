# 第七闸（肉眼账）的**判定本体** —— 只有这一份，`regress.sh` 与变异台都调它。
#
# 为什么要单独成文件：本仓栽过两次"闸测的是手抄副本"（第十六闸、以及 `plateSubject` 那一刀）。
#   这段判定原先内联在 `regress.sh` 里，任何变异台想复现它就只能再抄一遍 shell —— 那正是那种
#   "改了生产、副本不响"的形状。现在它在这里，两边都是**同一段代码**。
# 用法：`. ./namedfloor.sh; namedfloor_verdict "$GOT"`（`GOT` = "覆盖 分母"，或字面量 `读不到`）
#   退码 0 = 过；非 0 = 这一闸红（并把红话说完）。
namedfloor_verdict() {
  GOT="$1"
  if [ "$GOT" = "读不到" ]; then
    echo "!! 肉眼账读不到「合计 n/m」那一行 —— 判定没有分母，这一闸不算过"; return 1
  fi
  if [ ! -f bench/named_floor.txt ]; then
    echo "!! 没有 bench/named_floor.txt —— 底线不明，这一闸不算过（要认账就先钉一次）"; return 1
  fi
  FLOOR=$(cat bench/named_floor.txt)
  DEN="${GOT#* }"
  # 🔴 底线必须等于分母（这张表的定义就是"点名的每一张都要数得出一个数"，余量就是盲区）。
  #   现量过的一次失败：`named.txt` 从 15 行长到 16 行而底线还钉在 15 ⇒ 删掉整张浙江在线（15/16）照样放行。
  if [ "$FLOOR" != "$DEN" ]; then
    echo "!! 底线 $FLOOR ≠ 这一轮的分母 $DEN —— 中间那几格余量就是盲区"
    echo "   要么把 bench/named_floor.txt 钉到 $DEN（承认每张都得数得出数），要么写清哪一张允许数不出数"
    return 1
  fi
  if [ "${GOT%% *}" -lt "$FLOOR" ]; then
    echo "!! 肉眼账覆盖率 $GOT < 底线 $FLOOR —— 这一刀让台子对主人的账更瞎了"; return 1
  fi
  echo "   覆盖 主口径 ${GOT%% *}/${GOT#* }（底线 $FLOOR，与分母同钉 ⇒ **少认一张就红**）"
  return 0
}
