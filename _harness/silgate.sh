#!/bin/sh
# 装机后那道「剪影幅面账」的跑法：拉库 → 跑 `_fgpair.cjs --pin` → 退码说话。
#
# 为什么它不在 `regress.sh` 里：这道闸吃的是**设备产物**（桌面的 `app_icons.db` + 抓取回来的前景层），
# 离线判据台编不动 `MonochromeSynth`，也就产不出这张库 —— 硬塞进 regress 只会得到一把
# "每次都读同一份旧库"的假闸（本仓那一族：没跑却报绿）。
#
# 用法：sh _harness/silgate.sh            # 拉库并判
#       sh _harness/silgate.sh --db=<文件> # 用现成的库（变异测试、复算旧账）
#       sh _harness/silgate.sh --pinwrite  # 认账（人的决定，不是默认动作）
# ⚠ 拉库必须**重试到可读**：这库桌面一直在写，直接 pull 常拿到半截（`database disk image is malformed`），
#   而那份"读不出行"的库喂给闸 = 覆盖 0 张 = 假绿。
SELF=$(dirname "$0")
DBARG=$(echo "$@" | tr ' ' '\n' | grep -m1 '^--db=')
DB=${DBARG#--db=}
if [ -z "$DB" ]; then
  DB=/d/tmp/silgate_icons.db
  WDB=$(cygpath -w "$DB")
  ok=0
  i=0
  while [ "$i" -lt 4 ]; do
    i=$((i + 1))
    MSYS2_ARG_CONV_EXCL='*' adb pull /data/data/com.android.launcher3/databases/app_icons.db "$WDB" >/dev/null 2>&1
    if node -e 'const{DatabaseSync}=require("node:sqlite");const d=new DatabaseSync(process.argv[1],{readOnly:true});const n=d.prepare("SELECT COUNT(*) n FROM icons").get().n; if(!(n>100))process.exit(1)' "$WDB" 2>/dev/null; then ok=1; break; fi
    sleep 1
  done
  [ "$ok" = "1" ] || { echo "!! 拉了 $i 次都拿不到可读的库（不是没问题，是这台子没吃到数据）"; exit 1; }
  echo "   库已拉到（第 $i 次成功）：$DB"
fi
# ⚠ 身份表**每次现算**，不许吃上一轮的临时文件（`/d/tmp/iconclass.json` 那种）：
#   它是"哪些包归我们管"的唯一依据，吃过期一轮就把透传算成我们的、造出假故障（`com.termux.x11` 那次就是）。
LOG=${LOG:-/d/tmp/geom_fix1.txt}
# 行钉表与身份表同待遇（设备状态，不入库）：缺了就现查；`--refresh-rows` 强制现查。
#   库首行可能是小组件/快捷方式/占位行（现量 65/137）⇒ 不钉行，两条腿都判在错的行上。
ROWS="$SELF/bench/reg/launcher_rows.tsv"
if [ ! -f "$ROWS" ] || echo "$@" | grep -q -- '--refresh-rows'; then
  node "$SELF/_rowsresolve.cjs" "$DB" "$ROWS" || { echo "!! 钉表没查出来 —— 行钉不住，这道闸不判（不是没问题）"; exit 1; }
else
  echo "   钉表沿用：$ROWS（要现查加 --refresh-rows）"
fi
CLS="$SELF/bench/reg/iconclass.json"
node "$SELF/_unadapted.cjs" "$DB" "$SELF/corpus_runtime/index.tsv" "$LOG" --dump="$CLS" >/dev/null || {
  echo "!! 身份表没算出来 —— 这道闸不判（不是没问题）"; exit 1; }
echo "   身份表：$CLS（日志 $LOG）"
node "$SELF/_fgpair.cjs" "$SELF/corpus" "$DB" "$SELF/corpus_runtime/index.tsv" \
  --cls="$CLS" --pin="$SELF/bench/reg/sil.pin" "$@"
