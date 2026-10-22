#!/bin/sh
# **提交前的编译 gate**：碰了「判据台编不到的那几份生产源码」，就必须有一次比它们新的 gradle 成品。
#
# 为什么要它（不是洁癖，是记过账的）：`_harness/build.sh` 只把 `SRC=` 名单里那几份 `.kt` 喂给 kotlinc
#   （数目它会当场印出来，别信注释），而 `app/src` 下多得多 —— 名单外那些（`MonochromeSynth.kt` /
#   `CircleIconDrawable.kt` / `IconHooks.kt` / `MonoCache.kt` …）要 Android 类，判据台根本编不动，
#   于是**全套回归跑绿也证明不了它们能编译**。2026-10-17 就因此提交过一次编译不过的改动
#   （`plateLayer` 与另一位撞名 + 缺 `GradientDrawable` 的 import），装机时才被抓出来。
#   那条规矩以前只写在 `docs/DEVELOPMENT.md` 的一行表格里、靠我记得去跑 —— 现在它是一条命令、一个退码。
#
# 跑法：
#   sh _harness/precommit.sh     # 提交之前跑；绿了再 commit
# 退码：
#   0 = 放行（没碰编不到的那些，或那些确实有一次比它们新的 gradle 成品）
#   1 = **缺一次编译证据**（点名是哪几份，并给出要跑的那条命令）
#   2 = 这台子自己看不见（`build.sh` 的清单解析不出来）⇒ 不许把 2 读成"没碰生产源码"
ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "!! 不在 git 仓库里 —— 这台子没法判"; exit 2; }
BUILD_SH="$ROOT/_harness/build.sh"
APK="app/build/outputs/apk/release/app-release.apk"

if [ ! -f "$BUILD_SH" ]; then
  echo "!! 没有 $BUILD_SH —— 判据台的编译清单找不到，这一闸不算过（这不是「没碰生产源码」，是仪器没电）"
  exit 2
fi

# 判据台编得到的那份清单 = `build.sh` 里 `SRC=` 写的那些 `../app/src/...kt`（**唯一真源**，别在这里再抄一份名单）
COVERED=$(grep -o '\.\./app/src[^ ]*\.kt' "$BUILD_SH" | sed 's#^\.\./##' | sort -u)
NCOV=$(printf '%s\n' "$COVERED" | grep -c .)
NALL=$(find "$ROOT/app/src" -name '*.kt' | wc -l | tr -d ' ')
# ⚠ **先证这台仪器看得见**（本仓那条：拿「0 命中」下结论之前，先证明它本来可以命中）：
#   清单要是解析成空，下面那句"没碰到编不到的"就恒真 ⇒ 这一闸会永远绿、而永远绿最像通过。
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

# 本轮碰了 app/ 下哪些文件（相对 HEAD：已暂存 + 未暂存 + 未跟踪 + 删除）
TOUCHED=$( { git -C "$ROOT" diff --name-only HEAD -- app; \
             git -C "$ROOT" ls-files --others --exclude-standard -- app; } 2>/dev/null | grep -v '^$' | sort -u)
UNCOVERED=""
NTOUCH=0
for f in $TOUCHED; do
  NTOUCH=$((NTOUCH + 1))
  printf '%s\n' "$COVERED" | grep -qx "$f" || UNCOVERED="$UNCOVERED $f"
done
if [ -z "$UNCOVERED" ]; then
  echo "编译 gate ✓ 判据台编得到 $NCOV/$NALL 份生产源码；本轮碰的 app/ 文件（$NTOUCH 份）全在那份清单里 ⇒ 回归台绿就够了"
  exit 0
fi
NCOUNT=$(printf '%s\n' $UNCOVERED | grep -c .)

# 需要一次 gradle 证据：APK 存在，且**不比本轮改动旧**。锚 = 碰到的那些里最新的那份 mtime；
# 全是删除时没有 mtime 可用 ⇒ 退到 `.git/index`（它随任何暂存/检出刷新，比"忽略删除"诚实）。
NEWEST_TS=0
NEWEST=""
for f in $UNCOVERED; do
  p="$ROOT/$f"
  [ -f "$p" ] || continue
  ts=$(find "$p" -printf '%T@\n' | cut -d. -f1)
  if [ "$ts" -gt "$NEWEST_TS" ]; then NEWEST_TS="$ts"; NEWEST="$p"; fi
done
if [ -z "$NEWEST" ]; then
  NEWEST="$ROOT/.git/index"
  NEWEST_TS=$(find "$NEWEST" -printf '%T@\n' | cut -d. -f1)
fi
APK_TS=$(find "$ROOT/$APK" -printf '%T@\n' 2>/dev/null | cut -d. -f1)

if [ -z "$APK_TS" ]; then
  echo "!! 碰了 $NCOUNT 份判据台**编不到**的生产源码，而 release APK 一个都没有："
  for f in $UNCOVERED; do echo "     $f"; done
  echo "   跑：./gradlew :app:assembleRelease    （编不过就在这里发现，别等装机）"
  exit 1
fi
if [ "$APK_TS" -gt "$NEWEST_TS" ]; then
  echo "编译 gate ✓ 碰了 $NCOUNT 份编不到的（判据台只编 $NCOV/$NALL），而 $APK 比它们都新 ⇒ 改过的那些已被 gradle 编过一次"
  exit 0
fi
echo "!! 碰了判据台**编不到**的生产源码，而 gradle 成品比改动旧 —— 这一版从来没被编译验证过："
for f in $UNCOVERED; do echo "     $f"; done
echo "   锚（改动侧最新）：${NEWEST#$ROOT/}  $(date -d "@$NEWEST_TS" '+%m-%d %H:%M')"
echo "   APK：            $APK  $(date -d "@$APK_TS" '+%m-%d %H:%M')  —— 判据台全绿也盖不住上面那几份"
echo "   跑：./gradlew :app:assembleRelease    然后再回来跑我"
exit 1
