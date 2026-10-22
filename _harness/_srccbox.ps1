# 量**源位图**的内容框：alpha>0 的像素包围盒 / 画布（与判据台 `框内` 同义，但那一位量的是**交付**）。
#
# 为什么要它：第 22 闸那条"结构性不变量"写的是"这 45 张的**源**框内最大 0.69"，
#   而那个 0.69 是我从 MaskBench 读的 —— 它量的是**模型交付的那张掩码**，不是源。
#   拿交付的数去证明"源里没有底"是循环论证。这一把尺补的就是源这一侧。
#
# 用法： powershell -NoProfile -File _srccbox.ps1 -Dir <目录> [-Only f1.png,f2.png]
# 输出： 每行 `<文件名>\t<W>x<H>\tbbox=<w>x<h>\tfrac0=<alpha>0 的占比>\tfrac128=<alpha>=128 的占比>`
param(
  [Parameter(Mandatory = $true)][string]$Dir,
  [string]$Only = ""
)
Add-Type -AssemblyName System.Drawing
$files = Get-ChildItem -Path $Dir -Filter *.png | Sort-Object Name
if ($Only -ne "") { $want = $Only.Split(","); $files = $files | Where-Object { $want -contains $_.Name } }
foreach ($f in $files) {
  $bmp = [System.Drawing.Bitmap]::FromFile($f.FullName)
  try {
    $w = $bmp.Width; $h = $bmp.Height
    $minX = $w; $minY = $h; $maxX = -1; $maxY = -1; $n0 = 0; $n128 = 0
    $b128x0 = $w; $b128y0 = $h; $b128x1 = -1; $b128y1 = -1
    for ($y = 0; $y -lt $h; $y++) {
      for ($x = 0; $x -lt $w; $x++) {
        $a = $bmp.GetPixel($x, $y).A
        if ($a -gt 0) {
          $n0++
          if ($x -lt $minX) { $minX = $x }; if ($x -gt $maxX) { $maxX = $x }
          if ($y -lt $minY) { $minY = $y }; if ($y -gt $maxY) { $maxY = $y }
        }
        if ($a -ge 128) {
          $n128++
          if ($x -lt $b128x0) { $b128x0 = $x }; if ($x -gt $b128x1) { $b128x1 = $x }
          if ($y -lt $b128y0) { $b128y0 = $y }; if ($y -gt $b128y1) { $b128y1 = $y }
        }
      }
    }
    if ($maxX -lt 0) { "{0}`t{1}x{2}`tbbox0=0x0`tfrac0=0`tfrac128=0`tbbox128=0x0" -f $f.Name, $w, $h; continue }
    $bw = $maxX - $minX + 1; $bh = $maxY - $minY + 1
    $tot = [double]($w * $h)
    $b128 = "0x0"
    if ($b128x1 -ge 0) { $b128 = "{0}x{1}" -f ($b128x1 - $b128x0 + 1), ($b128y1 - $b128y0 + 1) }
    "{0}`t{1}x{2}`tbbox0={3}x{4}`tfrac0={5:F3}`tfrac128={6:F3}`tbbox128={7}" -f $f.Name, $w, $h, $bw, $bh,
      ($bw * $bh / $tot), ([double]$n128 / $tot), $b128
  }
  finally { $bmp.Dispose() }
}
