#!/bin/sh
# A/B 台：old = git HEAD 那份 MonochromeMask.kt，new = 工作区那份，其余源文件共用。
#
# ⚠ 存在的理由是今晚那次**假 A/B**（见 `57ec6ac`）：换源文件那一步没落地时，`out/` 里的旧
#   class 还在，两边跑的是同一份字节码，结论"净收益为零"因此是假的。所以这里**强制**：
#   ① 两个独立的输出目录（不复用 out/）；② 各自把 MonochromeMask.class 的 md5 打在开头 ——
#   **两个 md5 相同就是这一轮没在比东西**，别看数字，直接查为什么。
#   ③ 每次先 rm -rf 自己的输出目录，避免"上一轮的 class 冒充这一轮"。
set -e
. "$(dirname "$0")/jdk.sh"
M=/c/Users/20653/.gradle/caches/modules-2/files-2.1
KC=$(ls $M/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.20/*/kotlin-compiler-embeddable-2.4.20.jar)
SL=$(ls $M/org.jetbrains.kotlin/kotlin-stdlib/2.4.20/*/kotlin-stdlib-2.4.20.jar)
CO=$(ls $M/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/*/*.jar)
AN=$(ls $M/org.jetbrains/annotations/*/*/annotations-*.jar | tail -1)
win() { cygpath -w "$1"; }
KCP="$(win "$KC");$(win "$SL");$(win "$CO");$(win "$AN")"
CP="$(win "$SL");$(win stubs_out)"
CIRCLE=../app/src/main/kotlin/com/iamcanincan/opticon/circle
# ⚠ **源清单与驱动清单只有一份**（2026-11-06）：这两行是从 `build.sh` 里**现读**的，不再自己抄。
#   原先这里抄了一份 32 个驱动的名单，那份抄本后来缺了两样：
#     · `UncropStub.kt`（`IconBitmap.kt` 调的 `uncroppedIcon` 靠它顶）
#     · `Inputs.kt`（`MaskBench.kt` 调的 `cases()` 在那里面）
#   ⇒ `sh ab.sh` **两臂都编译不过**，而这台子存在的唯一理由就是防"两边跑的是同一份字节码"。
#   从 build.sh 现读之后，判据台加一份驱动、ab.sh 不用跟着改也不会烂。
eval "$(awk '/^SRC="/{f=1} f{print} f&&/"$/{f=0}' build.sh)"
SRC_NO_MASK=$(printf '%s' "$SRC" | sed 's|\.\./app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask\.kt *||')
DRIVERS=$(sed -n 's/.*-d out \$SRC \(.*\) > _kt\.log.*/\1/p' build.sh)
[ -n "$SRC_NO_MASK" ] || { echo "!! 从 build.sh 里没抠出 SRC 清单 —— 这台 A/B 不许空跑"; exit 1; }
[ -n "$DRIVERS" ] || { echo "!! 从 build.sh 里没抠出驱动清单（那一行的形状变了）—— 不许空跑"; exit 1; }
mkdir -p ab
# ⚠ 两份必须**同名**编：class 里带 "Compiled from <文件名>"，一个叫 MonochromeMask.old.kt
#   一个叫 .new.kt 的话，两份内容一模一样 md5 也不同 —— 下面那道"md5 相同=这一轮没在比东西"
#   的闸门就**永远不会响**（2026-09-29 提交之后重跑才发现：HEAD 与工作区同一份内容，
#   指纹却报 old=c5c4e544 new=6de9ed82）。所以各自放进自己的目录，文件名都叫 MonochromeMask.kt。
mkdir -p ab/old ab/new
git -C .. show ${AB_OLD:-HEAD}:app/src/main/kotlin/com/iamcanincan/opticon/circle/MonochromeMask.kt > ab/old/MonochromeMask.kt
cp $CIRCLE/MonochromeMask.kt ab/new/MonochromeMask.kt
for v in old new; do
  rm -rf var_$v
  if ! "$JAVA" -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -nowarn \
    -classpath "$CP" -d var_$v ab/$v/MonochromeMask.kt $SRC_NO_MASK $DRIVERS > "ab/$v.log" 2>&1; then
    # ⚠ 失败不许只丢一堆编译器输出（2026-11-06 那次就是这样被当成"ab.sh 坏了"放掉的）。
    #   这台 A/B 的前提是**两臂共用同一份判据台**，所以它有一类**结构性编不过**的情况：
    echo "!! $v 臂编译不过（全文在 ab/$v.log）。两种原因，分法不一样："
    echo "   ① 报 no parameter with name 『X』 ⇒ 判据台里有用例引用了 $v 那一版**还没有**的生产参数"
    echo "      （2026-11-06 这轮就是 SelfTest/MaskBench 用了 dropStrip，old 臂自然没有）。"
    echo "      这一轮的 HEAD 对照改走**同一份二进制里的反臂**：跑 MaskBench 时把那一刀关掉"
    echo "      （本轮是 --keep-strip），与改动前的产物逐字节比 —— 它比两臂二进制更干净，"
    echo "      因为除这一刀以外的一切都是同一份代码、同一份输入构造。"
    echo "   ② 报 unresolved reference ⇒ 是源/驱动清单缺文件；清单现在从 build.sh 现读，改那里（只有一份）。"
    exit 1
  fi
done
A=$(md5sum var_old/com/iamcanincan/opticon/circle/MonochromeMask.class | cut -c1-8)
B=$(md5sum var_new/com/iamcanincan/opticon/circle/MonochromeMask.class | cut -c1-8)
echo "指纹  old=$A  new=$B"
if [ "$A" = "$B" ]; then echo "!! 两份字节码一模一样 —— 这一轮什么都没比，先查源文件有没有落地"; exit 1; fi
