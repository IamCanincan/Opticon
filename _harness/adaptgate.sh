# 第 8 段（自画层不透传账）与第 20 段（自画层接管账）的**判定本体** —— 只有一份。
#
# 为什么单独成文件（2026-10-22，与 `twobookgate.sh` 同一刀）：变异台 `_semanticmut.cjs` 的用例 20 以前
#   自己抄了一遍"跑 VetoKt/AdaptKt + 比 pin"，那证的是**抄的那半** —— 这两段里的入口门
#   （缺 `adapted_model/` 直接不跑）与"没出机器行 = 驱动坏了"那一格，它一条也没证过。
#   本仓为"闸测的是手抄副本"栽过三次（第十六闸、`plateSubject`、第 19 闸），这是第四次。
# 用法：`. ./adaptgate.sh; veto_gate [后缀]` / `adapt_gate [后缀]`
#   退 0 = 这一闸过；非 0 = 红（红话说完）。后缀默认空，变异台传 `_mut` 以免覆盖活账本。
# ⚠ 2026-10-22 换分母：原来吃 `corpus_audit3`（**不在库里**）+ 一张手做的飞鸟 `_two/…`（**也不在库里**）
#   ⇒ 干净克隆上这两闸根本跑不了，是一个静默的复现洞。现在都吃 `adapted_model`（67 张，**在库里**，
#   `_mkrec_model.cjs … --only=adapted` 建的）—— 那正是这一族该管的整类：**应用自带单色层**的那一批。
#   飞鸟这次是自己进的分母，不再靠 `--also=` 手喂。

veto_gate() {
  SUF="${1:-}"
  RAW="bench/reg/veto${SUF}.txt"
  [ -d adapted_model ] || {
    echo "!! 缺 adapted_model/ —— 自画层射程这一闸没有分母（重建：node _mkrec_model.cjs <抓取目录> adapted_model --only=adapted）"
    return 1; }
  VRC=0
  run harness.VetoKtKt adapted_model --want=org.lineageos.etar,com.fbclient.app > "$RAW" 2>&1 || VRC=$?
  grep -q '^VETO ' "$RAW" || {
    echo "!! 自画层账没出机器行 —— 驱动或语料坏了（它自己那几行在下面）"; tail -8 "$RAW"; return 1; }
  NEWV=$(grep '^VETO ' "$RAW")
  if [ -n "$UPDATE" ]; then
    echo "$NEWV" > bench/reg/veto.pin; echo "   --update：已把这一轮钉成新基线 $NEWV"
    return 0
  fi
  OLDV=$(cat bench/reg/veto.pin 2>/dev/null)
  [ -z "$OLDV" ] && { echo "!! 没有 bench/reg/veto.pin —— 没基线不算过（先 sh regress.sh --update 钉一次）"; return 1; }
  [ "$VRC" != "0" ] && { echo "!! 自画层账自己判了不通过（退码 $VRC），见 $RAW"; tail -8 "$RAW"; return 1; }
  [ "$NEWV" = "$OLDV" ] || {
    echo "!! 自画层射程动了："; echo "   基线 $OLDV"; echo "   这轮 $NEWV"
    echo "   开火集进出一张 = 有一张主题图标改由我们画（或交回去），逐张看图再认账"; return 1; }
  echo "   $NEWV —— 与基线逐字相同"
  return 0
}

adapt_gate() {
  SUF="${1:-}"
  RAW="bench/reg/adapt${SUF}.txt"
  [ -d adapted_model ] || { echo "!! 缺 adapted_model/ —— 这一闸没有分母（建法见 veto_gate 那段）"; return 1; }
  ARC=0
  run harness.AdaptKtKt adapted_model > "$RAW" 2>&1 || ARC=$?
  grep -q '^ADAPT ' "$RAW" || {
    echo "!! 自画层接管账没出机器行 —— 驱动或语料坏了"; tail -6 "$RAW"; return 1; }
  NEWA=$(grep '^ADAPT ' "$RAW")
  if [ -n "$UPDATE" ]; then
    echo "$NEWA" > bench/reg/adapt.pin; echo "   --update：已把这一轮钉成新基线 $NEWA"
    return 0
  fi
  OLDA=$(cat bench/reg/adapt.pin 2>/dev/null)
  [ -z "$OLDA" ] && { echo "!! 没有 bench/reg/adapt.pin —— 没基线不算过（先 sh regress.sh --update 钉一次）"; return 1; }
  [ "$ARC" != "0" ] && { echo "!! 这一闸自己判了不通过（退码 $ARC，最常见是分母空或 veto 一张都不开火）"; tail -4 "$RAW"; return 1; }
  [ "$NEWA" = "$OLDA" ] || {
    echo "!! 自画层接管的读数动了："; echo "   基线 $OLDA"; echo "   这轮 $NEWA"
    echo "   接管名单进出 = 有图标从'交回桌面'变成'我们画'（或反过来）；中位动 = 浓度/笔画被改"; return 1; }
  echo "   ${NEWA%% 接管名单=*} —— 与基线逐字相同"
  return 0
}
