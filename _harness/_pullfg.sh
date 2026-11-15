#!/bin/sh
# 拆 APK 取「前景层」那张图，量它落在**可见区**里的幅面 —— 主人那句
# 「单色和彩色一样的几何结构」第一次有了可算的分母（之前用的是合成图，含底盘，剪影当然比它小）。
#
# 用法：sh _harness/_pullfg.sh <包名> [<包名> …]     （结果追加进 _harness/bench/reg/fg_extents.tsv）
# 链路：pm path → pull 所有 split → aapt2 badging 取 application-icon 的 xml
#       → xmltree 取 <foreground> 的资源 id（顺带看有没有 <monochrome>）
#       → resources 把 id 解成文件 → unzip 出来 → ffmpeg 转 PNG（webp 手边没有 dwebp）
#       → node 量幅面 → 删掉 APK（一台机器上别留几 GB 的临时包）
# ⚠ 矢量前景层（VectorDrawable）**这条路走不通**：AXML 里的 pathData 要自己渲染才算得出幅面，
#   这台上的桩也解不了 —— 那批只能靠装机抓取，别在这里假装量到了。
set -e
SDK=/c/Users/20653/AppData/Local/Android/Sdk
A="$SDK/build-tools/36.0.0/aapt2.exe"
T=$(cygpath -w /c/Users/20653/AppData/Local/Temp/fapk)
OUT="$(dirname "$0")/bench/reg/fg_extents.tsv"
mkdir -p "$(dirname "$OUT")"
[ -f "$OUT" ] || printf 'pkg\t前景层类型\t文件\t可见区幅面\t整幅幅面\t有没有自带单色\n' > "$OUT"
export MSYS2_ARG_CONV_EXCL='*'

for P in "$@"; do
  rm -rf /c/Users/20653/AppData/Local/Temp/fapk; mkdir -p "$T"
  PATHS=$(adb shell pm path "$P" 2>/dev/null | tr -d '\r' | sed 's/^package://')
  [ -z "$PATHS" ] && { echo "!! $P 没装或拿不到路径"; continue; }
  for f in $PATHS; do adb pull "$f" "$T/$(basename "$f")" >/dev/null 2>&1 || true; done
  BASE="$T/base.apk"
  ICONXML=$("$A" dump badging "$BASE" 2>/dev/null | grep -a "application-icon-320:" | head -1 | sed "s/.*'\(.*\)'.*/\1/")
  [ -z "$ICONXML" ] && ICONXML=$("$A" dump badging "$BASE" 2>/dev/null | grep -a "application-icon:" | head -1 | sed "s/.*'\(.*\)'.*/\1/")
  if [ -z "$ICONXML" ]; then echo -e "$P\t非 adaptive（application-icon 是位图）\t-\t-\t-\t?" >> "$OUT"; echo "  $P → 老式，图标本身就是一张位图"; continue; fi
  TREE=$("$A" dump xmltree "$BASE" --file "$ICONXML" 2>/dev/null)
  echo "$TREE" | grep -qa "monochrome" && OWN="有" || OWN="无"
  # ⚠ 要的是 `=@0x…` 那个**值**，不是属性 id：`drawable(0x01010199)=@0x7f0d0001` 里
  #   前一个 0x 是 android 命名空间的属性号，抓错它就变成"resources 里查无此物 → 报矢量"。
  FGID=$(echo "$TREE" | awk '/E: foreground/{getline; print}' | grep -aoE '@0x[0-9a-f]{8}' | head -1 | tr -d '@')
  if [ -z "$FGID" ]; then echo -e "$P\tforeground 没解出来\t-\t-\t-\t$OWN" >> "$OUT"; echo "  $P → 拆不到前景层"; continue; fi
  RES=$("$A" dump resources "$BASE" 2>/dev/null | grep -a -A8 "resource $FGID" | head -10)
  NAME=$(echo "$RES" | head -1 | awk '{print $2}')
  # ⚠ `aapt2 dump resources` 的**(file) 后面不带引号**（`badging` 才带）——
  #   上一版照 badging 的写法用 `sed 's/.*"\(.*\)".*/\1/'`，解出来是空，
  #   于是把一堆明明有位图的 webp 报成"矢量，量不了"。
  FILE=$(echo "$RES" | grep -aE "\((xxhdpi|xxxhdpi|hdpi|xhdpi)\) \(file\)" | head -1 | awk '{print $NF}')
  [ -z "$FILE" ] && FILE=$(echo "$RES" | grep -a "(file)" | head -1 | awk '{print $NF}')
  if [ -z "$FILE" ]; then echo -e "$P\t$NAME 没有文件（矢量/程序化）\t-\t-\t-\t$OWN" >> "$OUT"; echo "  $P → $NAME 没有位图文件（矢量），这台子量不了"; continue; fi
  (cd "$T" && unzip -o -q base.apk "$FILE" 2>/dev/null) || { echo "  $P → unzip 失败"; continue; }
  RAW="/c/Users/20653/AppData/Local/Temp/fapk/$FILE"
  PNG="/c/Users/20653/AppData/Local/Temp/fapk/fg.png"
  # ⚠ 这台机器上**没有 ffmpeg 也没有 dwebp**（2026-11-15 现查：`which ffmpeg` 空、
  #   `Get-Command ffmpeg` 空）—— 前景层常常是 webp，所以走 Windows 自带的 WIC（`_to_png.ps1`）。
  case "$FILE" in
    *.png) cp "$RAW" "$PNG" ;;
    *) CONV=$(powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$(dirname "$0")/_to_png.ps1")" \
          -Src "$(cygpath -w "$RAW")" -Dst "$(cygpath -w "$PNG")" 2>/dev/null | tail -1)
       case "$CONV" in OK*) : ;; *) echo "  $P → WIC 转码失败（$FILE）：$CONV"; continue ;; esac ;;
  esac
  node "$(dirname "$0")/_fgextent.cjs" "$P" "$PNG" "${DB:-D:/tmp/app_icons_34.db}" || true
  rm -rf /c/Users/20653/AppData/Local/Temp/fapk
done
echo "台账：$OUT"
