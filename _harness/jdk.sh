# 判据台的 JDK 解析 —— 所有跑 JVM 的脚本先 `. jdk.sh`，**不许指望 PATH**。
#
# 2026-10-02 的翻车现场：整个 `~/.gradle` 和 PATH 上的 `java`（退回 1.8）一起没了，
# `regress.sh` 从第 0 行 `$M` 那里就断了。它断得倒是干脆，真正危险的是**下一次断得不干脆**：
# `out/` 里留着上一轮的 class，`build.sh` 一失败就 `exit`，而读的人看见的还是昨天那份
# 「编译 0 error」—— 本仓为这一类事写过不止一次：一个跑不起来的检查报"通过"，和没跑一样。
#
# 为什么必须是 17+：`-Dstdout.encoding=UTF-8` 是 18+ 才认的属性（1.8 上不认，中文直接成问号，
# 而那些中文正是判据台的输出字段名）；kotlinc 2.4 与 AGP 也都要 17+。
# 解析顺序：① $JAVA_HOME ② 本机已知的 JDK 根目录 ③ PATH —— 但 ③ 必须**当场验主版本号**，
# 不够就**硬失败**，绝不静默用 1.8 跑（那等于用一套没人核对过的量具量一天）。
#
# ⚠ **② 按通配展开，不钉小版本号**：2026-10-08 这台机器把 `D:\Java\jdk-21` 改成了
# `D:\Java\jdk-21.0.11`（装补丁版时原地换了目录名，`latest\jdk-21` 那个副本里没有 bin），
# 于是 `jdk.sh` 里那五条**写死的**路径**一条都不命中**，整套判据台当场断。
# 写死版本号就是把自己钉在这类改名上 —— 现在列根目录、通配展开、按版本从高到低试。
# 另外补上 ③：旧注释写了 PATH 回退，代码里**从来没有**这一步。
if [ -n "${_HARNESS_JDK:-}" ]; then
  return 0 2>/dev/null || exit 0
fi

_jdk_from() {
  # 接受 POSIX 或 Windows 两种写法的路径，选中则置 JAVA/JAVAC 并返回 0
  [ -n "$1" ] || return 1
  local u=$(cygpath -u "$1" 2>/dev/null || echo "$1")
  [ -x "$u/bin/java" ] && [ -x "$u/bin/javac" ] || return 1
  JAVA="$u/bin/java"; JAVAC="$u/bin/javac"; return 0
}

_jdk_ok() {
  # 主版本号 >= 17 才算可用
  local v=$("$1" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)(\.[0-9]+)?.*/\1/')
  [ -n "$v" ] || return 1
  [ "$v" -ge 17 ] 2>/dev/null
}

: ${JAVA:=}; : ${JAVAC:=}
JAVA=""

# 版本号从高到低排在最前，取第一行。空格路径用制表符分列，别用空格。
_jdk_ver() { "$1" -version 2>&1 | head -1 | sed -E 's/.*"([0-9][0-9.]*)".*/\1/'; }

# ① 主人显式指了 JAVA_HOME 就别跟他抢
if _jdk_from "$JAVA_HOME" && _jdk_ok "$JAVA"; then :; else JAVA=""; fi

# ② 本机已知的 JDK **根目录**（不是具体某个版本目录），通配展开后按版本排序
if [ -z "$JAVA" ]; then
  _list=""
  for root in /d/Java "/c/Program Files/Java" "/c/Program Files/Eclipse Adoptium" \
              "/c/Program Files/Zulu" "/c/Program Files/Amazon Corretto" /c/Users/20653/.jdks; do
    [ -d "$root" ] || continue
    # `latest/jdk-21` 这类套一层的也一起收；没匹配的 glob 会留一个字面 `*`，
    # 被 _jdk_from 当场否掉，所以不必额外判存在性。
    for d in "$root"/jdk* "$root"/*/jdk*; do
      _jdk_from "$d" || continue
      _jdk_ok "$JAVA" || continue
      # 存 JDK 根目录而不是可执行文件路径：下面要再交给 _jdk_from 一次。
      _list="${_list}$(_jdk_ver "$JAVA")	${JAVA%/bin/java}
"
    done
  done
  _pick=$(printf '%s' "$_list" | sort -Vr | head -1 | cut -f2)
  _jdk_from "$_pick"
fi

# ③ PATH —— 旧注释写了这一步，代码里从来没有；现在补上，仍然当场验主版本号
if [ -z "$JAVA" ]; then
  _pj=$(command -v java 2>/dev/null); _pc=$(command -v javac 2>/dev/null)
  if [ -n "$_pj" ] && [ -n "$_pc" ]; then
    JAVA="$_pj"; JAVAC="$_pc"
    _jdk_ok "$JAVA" || JAVA=""
  fi
fi

[ -n "$JAVA" ] || {
  echo "!! 判据台找不到 JDK 17+（JAVA_HOME=${JAVA_HOME:-未设}，PATH 上的 java 是：$(java -version 2>&1 | head -1)）" >&2
  echo "   试过：\$JAVA_HOME、/d/Java、/c/Program Files/Java、Eclipse Adoptium、Zulu、Corretto、.jdks、PATH。" >&2
  echo "   有 JDK 就 export JAVA_HOME=<JDK 根目录>；这一轮的数字**一张都没测** —— 别拿 out/ 里上一轮留下的 class 当这一轮的结论。" >&2
  exit 1
}
echo "   [jdk] $JAVA ($(_jdk_ver "$JAVA"))"
export JAVA JAVAC _HARNESS_JDK=1
