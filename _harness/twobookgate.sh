# 第 19 段（两本账一本账）的**判定本体** —— 只有一份：`regress.sh` 与变异台 `_semanticmut.cjs` 都调它。
#
# 为什么单独成文件：这一段除了"与 pin 逐字相同"之外还有**两条不看基线**的当场断言
#   （96 档的 `格子分岔` 不许是 0、96 与 384 的极性归属必须一字不差）。内联在 `regress.sh` 里时，
#   变异台想复现这条判定只能再抄一遍 shell —— 而本仓为"闸测的是手抄副本"栽过两次（第十六闸、`plateSubject`）。
#   抄的那份一旦与这里不同，"变异响了"就只证明抄的那半，不证明真闸。
# 用法：`. ./twobookgate.sh; twobook_gate [输出后缀]`
#   退 0 = 这一闸过；非 0 = 红（红话说完）。后缀默认空，变异台传 `_mut` 以免覆盖活账本。
#   语料可用环境变量 `CORPUS / PLATECORPUS / SHOTCORPUS` 覆盖，默认与 `regress.sh` 同三份。
tb_own() { echo "$1" | awk '{i=index($0," 开火集="); print (i>0? substr($0,i) : "NOFIELD")}'; }

twobook_gate() {
  SUF="${1:-}"
  T_CORPUS="${CORPUS:-rec_model}"
  T_PLATE="${PLATECORPUS:-plate_model}"
  T_SHOT="${SHOTCORPUS:-shortcut_model}"
  T_BIT="${SHOTBITCORPUS:-shortcut_bit}"
  T384="bench/reg/twoacct${SUF}.txt"
  T96="bench/reg/twoacct96${SUF}.txt"
  # ⚠ 对照语料 `twobook_ctrl/`（`node _mk_twobook_ctrl.cjs` 造的，1.3 KB，**可复现所以入库**）：
  #   真语料那 132 张上极性翻 **0 张**，这道闸对"把取数退回判据那一本"这个变异**不会响** ——
  #   那一张两本账判定本来就相反（判据 55% 开火 / 彩色恒满幅不开火），`flip=1` 才是这台子的牙。
  #   缺它就等于把闸退回空跑，所以缺了**直接不跑**，不许悄悄少一份分母。
  [ -d twobook_ctrl ] || { echo "!! 缺对照语料 twobook_ctrl/ —— 第 19 闸会退回空跑（先 node _mk_twobook_ctrl.cjs）"; return 1; }
  # 第 16 闸同一条纪律：`bitmap=` 那一类（45 张，ShortcutService 自己落盘的位图）必须在分母里，
  #   否则"快捷方式"这一档只覆盖了资源 id 那一半（现量：dump 里 173 条有 45 条是 bitmap）。
  [ -d "$T_BIT" ] || {
    echo "!! 缺语料 $T_BIT/ —— 第 19 闸的快捷方式分母会少 bitmap 那一类（重建见 regress.sh 第 16 闸那句）"
    return 1; }
  TARC=0
  run harness.TwoAcctKtKt "$T_CORPUS" "$T_PLATE" "$T_SHOT" "$T_BIT" twobook_ctrl > "$T384" || TARC=$?
  grep -q '^2A ' "$T384" || {
    echo "!! 两本账没出数 —— 驱动或语料坏了（它自己那几行在下面）"; tail -6 "$T384"; return 1; }
  NEWT=$(grep '^2A ' "$T384")
  # ⚠ **96 那一档不是重跑一遍**：384 档两本账的格子本来就是同一张（`格子分岔=0`），
  #   只有把边长压到 `SCAN_EDGE` 之下，彩色那本（按 intrinsic 缩）才会和判据那本（edge² 钳）分开。
  #   现量 113 张分岔 —— 而**开火集 / 老开火集 / 三条支的差表 / 翻名单与 384 档一字不差**：
  #   这才把「取数换了本子、但不随档变」钉成一条当场可验的断言。只钉 384 = 把这条留在没被量的地方。
  TARC96=0
  run harness.TwoAcctKtKt "$T_CORPUS" "$T_PLATE" "$T_SHOT" "$T_BIT" twobook_ctrl --edge=96 > "$T96" || TARC96=$?
  grep -q '^2A ' "$T96" || {
    echo "!! 两本账 96 档没出数 —— 驱动或语料坏了（它自己那几行在下面）"; tail -6 "$T96"; return 1; }
  NEWT96=$(grep '^2A ' "$T96")
  # 两条**不看基线**的活证据断言（`--update` 也躲不过）：
  K96=$(echo "$NEWT96" | grep -o '格子分岔=[0-9]*' | cut -d= -f2)
  if [ "${K96:-0}" = "0" ]; then
    echo "!! 96 档格子分岔=0 —— 「两本账格子不同」那条路径根本没跑（scanSize/polarityOf 被动过？）"
    echo "   这一档 0 分岔分不清「不会分岔」和「没得分岔」，不许当通过"; return 1;
  fi
  if [ "$(tb_own "$NEWT")" != "$(tb_own "$NEWT96")" ]; then
    echo "!! 96 档与 384 档的极性归属不是同一批（该只有 格子分岔 / bothWin / colourNull 三个数动）"
    echo "   384: $(tb_own "$NEWT")"; echo "   96 : $(tb_own "$NEWT96")"; return 1;
  fi
  if [ -n "$UPDATE" ]; then
    echo "$NEWT" > bench/reg/twoacct.pin; echo "   --update：已把这一轮钉成新基线 $NEWT"
    echo "$NEWT96" > bench/reg/twoacct96.pin; echo "   --update：96 档一并钉成 $NEWT96"
    return 0
  fi
  OLDT=$(cat bench/reg/twoacct.pin 2>/dev/null)
  [ -z "$OLDT" ] && { echo "!! 没有 bench/reg/twoacct.pin —— 没基线不算过（先 sh regress.sh --update 钉一次）"; return 1; }
  OLD96=$(cat bench/reg/twoacct96.pin 2>/dev/null)
  [ -z "$OLD96" ] && { echo "!! 没有 bench/reg/twoacct96.pin —— 96 档没基线不算过（先 node _mk_twobook_ctrl.cjs 再 sh regress.sh --update 钉一次）"; return 1; }
  [ "$TARC" != "0" ] && { echo "!! 两本账自己判了不通过（退码 $TARC，最常见是「两本口径都没开火」）"; tail -2 "$T384"; return 1; }
  [ "$TARC96" != "0" ] && { echo "!! 两本账 96 档自己判了不通过（退码 $TARC96）"; tail -2 "$T96"; return 1; }
  [ "$NEWT" = "$OLDT" ] || {
    echo "!! 两本账的读数动了："; echo "   基线 $OLDT"; echo "   这轮 $NEWT"
    echo "   逐张对照见 $T384（开火集进出 = 极性的归属变了，是要上屏的行为变化）"; return 1; }
  [ "$NEWT96" = "$OLD96" ] || {
    echo "!! 两本账 96 档的读数动了："; echo "   基线 $OLD96"; echo "   这轮 $NEWT96"
    echo "   逐张对照见 $T96"; return 1; }
  echo "   $NEWT —— 与基线逐字相同"
  echo "   $NEWT96 —— 与基线逐字相同（格子分岔 $K96 张，而归属与 384 档一字不差）"
  return 0
}
