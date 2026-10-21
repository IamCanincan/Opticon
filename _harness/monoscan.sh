#!/bin/sh
# 扫桌面上真实存在的每个包：它的 adaptive-icon XML 里 <monochrome> 是不是就是 <foreground> 那一份。
# 为什么现在才扫：上一轮我拿"为一个包扫两百个 APK 不值"拒了这条，那是**我编的成本** ——
# 真正要的答案不是"飞鸟有没有"，是**这条规则的射程有几张**。射程量不出来就没有资格说值不值。
# 输出 TSV：包 \t icon文件 \t fg \t mono \t bg \t 判定
AAPT=/c/Users/20653/AppData/Local/Android/Sdk/build-tools/36.0.0/aapt2.exe
OUT=D:/tmp/_mono_same.tsv
: > "$OUT"
while read -r PKG; do
  P=$(adb shell "pm path $PKG" </dev/null 2>/dev/null | head -1 | tr -d '\r' | sed 's/^package://')
  [ -z "$P" ] && continue
  F=/d/tmp/_apkscan/base.apk
  mkdir -p /d/tmp/_apkscan && rm -f "$F"
  MSYS2_ARG_CONV_EXCL='*' adb pull "$P" "D:/tmp/_apkscan/base.apk" >/dev/null 2>&1 || continue
  B=$("$AAPT" dump badging D:/tmp/_apkscan/base.apk 2>/dev/null | grep "^application:" | head -1)
  IC=$(echo "$B" | sed -n "s/.*icon='\([^']*\)'.*/\1/p")
  [ -z "$IC" ] && { printf "%s\t- - - -\t无 icon\n" "$PKG" >> "$OUT"; continue; }
  case "$IC" in *.xml) ;; *) printf "%s\t%s\t非 XML 图标\n" "$PKG" "$IC" >> "$OUT"; continue ;; esac
  T=$("$AAPT" dump xmltree D:/tmp/_apkscan/base.apk --file "$IC" 2>/dev/null)
  FG=$(echo "$T" | grep -A1 "E: foreground" | grep -oE "@0x[0-9a-f]+" | head -1)
  MO=$(echo "$T" | grep -A1 "E: monochrome" | grep -oE "@0x[0-9a-f]+" | head -1)
  BG=$(echo "$T" | grep -A1 "E: background" | grep -oE "@0x[0-9a-f]+" | head -1)
  V="-"
  [ -n "$MO" ] && [ "$MO" = "$FG" ] && V="mono==fg"
  [ -n "$MO" ] && [ "$MO" = "$BG" ] && V="$V mono==bg"
  printf "%s\t%s\t%s\t%s\t%s\t%s\n" "$PKG" "$IC" "${FG:-无}" "${MO:-无单色层}" "${BG:-无}" "$V" >> "$OUT"
done < /d/tmp/_pkgs.txt
echo "== 扫完 $(grep -c '' $OUT) 行"
grep -c "mono==fg" "$OUT"
