#!/bin/sh
# **提交前后都要跑的编译 gate**：碰了「判据台编不到的那些生产源码」，就必须有一次比它们**更新**的 gradle 成品。
#
# 为什么要它（不是洁癖，是记过账的）：`_harness/build.sh` 只把 `SRC=` 名单里那几份 `.kt` 喂给 kotlinc
#   （数目它会当场印出来，别信注释），而 `app/src` 下多得多 —— 名单外那些（`MonochromeSynth.kt` /
#   `CircleIconDrawable.kt` / `IconHooks.kt` / `debug/CorpusCapture.kt` …）要 Android 类，判据台编不动，
#   于是**全套回归跑绿也证明不了它们能编译**。2026-10-17 就因此提交过一次编译不过的改动
#   （`plateLayer` 与另一位撞名 + 缺 `GradientDrawable` 的 import），装机时才被抓出来。
#   那条教训以前只是 `docs/DEVELOPMENT.md` 表格里一句"记得先跑 gradle"，靠我记得 —— 现在是一条命令、一个退码。
#
# 它管**两种**现场（第二种是 2026-10-22 自己抓出来的：写完这道闸一小时，就发现 HEAD 里躺着一份
#   00:51 提交、00:04 之后从来没编过的生产源码 —— 只看未提交的工作树会漏掉这种"已提交但没编"）：
#     ① 工作树里未提交 / 未跟踪 / 删除的名单外文件；
#     ② **最近一次提交**动过名单外文件（提交时间当作锚）。
#
# 跑法：
#   sh _harness/precommit.sh     # 提交之前跑；绿了再 commit（装机之后再来跑一次它也管不着屏上，见边界）
# 退码：
#   0 = 放行（没碰编不到的那些，或那些确实有一次比它们新的 gradle 成品）
#   1 = **缺一次编译证据**（点名是哪几份、锚是哪一刻，并给出要跑的那条命令）
#   2 = 这台子自己看不见（`build.sh` 的清单解析不出来）⇒ 不许把 2 读成"没碰生产源码"
ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "!! 不在 git 仓库里 —— 这台子没法判"; exit 2; }
BUILD_SH="$ROOT/_harness/build.sh"
APK="app/build/outputs/apk/release/app-release.apk"

if [ ! -f "$BUILD_SH" ]; then
  echo "!! 没有 $BUILD_SH —— 判据台的编译清单找不到，这一闸不算过（这不是「没碰生产源码」，是仪器没电）"
  exit 2
fi

# 判据台编得到的那份清单 = `build.sh` 里 `SRC=` 写的那些 `../app/src/...kt`（**唯一真源**，不在这里抄第二份）
COVERED=$(grep -o '\.\./app/src[^ ]*\.kt' "$BUILD_SH" | sed 's#^\.\./##' | sort -u)
NCOV=$(printf '%s\n' "$COVERED" | grep -c .)
NALL=$(find "$ROOT/app/src" -name '*.kt' | wc -l | tr -d ' ')
# ⚠ **先证这台仪器看得见**（本仓那条：拿"0 命中"下结论之前，先证明它本来可以命中）：
#   清单要是解析成空，"没碰到编不到的"就恒真 ⇒ 这一闸会永远绿，而永远绿最像通过。
if [ "$NCOV" -lt 5 ]; then
  echo "!! 从 build.sh 只抠到 $NCOV 份编译清单（<5）—— 清单格式变了，这一闸现在是瞎的，不算过"
  exit 2
fi
if ! printf '%s\n' "$COVERED" | grep -q 'circle/MonochromeMask.kt'; then
  echo "!! 清单里没有 circle/MonochromeMask.kt（判据台的心脏）—— 是解析错了，不算过"
  exit 2
fi
if printf '%s\n' "$COVERED" | grep -q 'circle/MonochromeSynth.kt'; then
  echo "!! 清单里出现了 circle/MonochromeSynth.kt —— 它要 Android 类、kotlinc 编不动。"
  echo "   要么 build.sh 真改了（那这台子的名单要重看），要么我在骗自己"
  exit 2
fi

# 名单外的那些文件，谁碰的？① 工作树（含未跟踪、含删除）② 最近一次提交
untouched() { printf '%s\n' "$COVERED" | grep -qx "$1"; }
UNCOVERED=""
NTOUCH=0
for f in $( { git -C "$ROOT" diff --name-only HEAD -- app; \
              git -C "$ROOT" ls-files --others --exclude-standard -- app; } 2>/dev/null | grep -v '^$' | sort -u ); do
  NTOUCH=$((NTOUCH + 1))
  untouched "$f" || UNCOVERED="$UNCOVERED $f"
done
HFILES=$(git -C "$ROOT" show --name-only --format= HEAD -- app 2>/dev/null | grep -v '^$' | sort -u)
HUNCOV=""
for f in $HFILES; do untouched "$f" || HUNCOV="$HUNCOV $f"; done
HEAD_TS=$(git -C "$ROOT" log -1 --format=%ct HEAD 2>/dev/null)
HEAD_ID=$(git -C "$ROOT" log -1 --format=%h HEAD 2>/dev/null)

if [ -z "$UNCOVERED" ] && [ -z "$HUNCOV" ]; then
  echo "编译 gate ✓ 判据台编得到 $NCOV/$NALL 份生产源码；工作树碰的 app/ 文件（$NTOUCH 份）与最近一次提交都在那份清单里 ⇒ 回归台绿就够了"
  exit 0
fi

# 锚 = 改动侧最新的一刻：未提交那些里最新的 mtime，与"最近一次提交动过名单外"的提交时间，取大者。
# （全是删除时没有 mtime 可用 ⇒ 退到 `.git/index`，它随任何暂存/检出刷新，比"忽略删除"诚实。）
NEWEST_TS=0
NEWEST=""
for f in $UNCOVERED; do
  p="$ROOT/$f"
  [ -f "$p" ] || continue
  ts=$(find "$p" -printf '%T@\n' | cut -d. -f1)
  if [ "$ts" -gt "$NEWEST_TS" ]; then NEWEST_TS="$ts"; NEWEST="$p"; fi
done
if [ -z "$NEWEST" ] && [ -n "$UNCOVERED" ]; then
  NEWEST="$ROOT/.git/index"; NEWEST_TS=$(find "$NEWEST" -printf '%T@\n' | cut -d. -f1)
fi
WHY_ANCHOR="工作树里最新的那份"
if [ -n "$HUNCOV" ] && [ "${HEAD_TS:-0}" -gt "$NEWEST_TS" ]; then
  NEWEST_TS="$HEAD_TS"; NEWEST="提交 $HEAD_ID"; WHY_ANCHOR="最近一次提交（它动过名单外那些）"
fi
if [ -z "$NEWEST" ]; then NEWEST="$ROOT/.git/index"; NEWEST_TS=$(find "$NEWEST" -printf '%T@\n' | cut -d. -f1); fi
APK_TS=$(find "$ROOT/$APK" -printf '%T@\n' 2>/dev/null | cut -d. -f1)

say_targets() {
  [ -n "$UNCOVERED" ] && { echo "   工作树里碰了（判据台编不到）："; for f in $UNCOVERED; do echo "     $f"; done; }
  [ -n "$HUNCOV" ] && { echo "   最近一次提交 $HEAD_ID 动过（编不到）："; for f in $HUNCOV; do echo "     $f"; done; }
}

if [ -z "$APK_TS" ]; then
  echo "!! 有判据台**编不到**的生产源码被碰过，而 release APK 一个都没有："
  say_targets
  echo "   跑：./gradlew :app:assembleRelease    （编不过就在这里发现，别等装机）"
  exit 1
fi
if [ "$APK_TS" -gt "$NEWEST_TS" ]; then
  echo "编译 gate ✓ 编不到的那些被碰过（判据台只编 $NCOV/$NALL；锚 = $WHY_ANCHOR $(date -d "@$NEWEST_TS" '+%m-%d %H:%M')），"
  echo "   而 $APK 是 $(date -d "@$APK_TS" '+%m-%d %H:%M') 比它新 ⇒ 改过的已被 gradle 编过一次"
  exit 0
fi
echo "!! 有判据台**编不到**的生产源码被碰过，而 gradle 成品不比它新 —— 这一版从来没被编译验证过："
say_targets
echo "   锚（改动侧最新）：$NEWEST  $(date -d "@$NEWEST_TS" '+%m-%d %H:%M')"
echo "   APK：            $APK  $(date -d "@$APK_TS" '+%m-%d %H:%M')"
echo "   判据台全绿也盖不住上面那几份 —— 跑：./gradlew :app:assembleRelease    然后再回来跑我"
exit 1
