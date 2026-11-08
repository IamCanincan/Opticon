Add-Type -AssemblyName System.Drawing
$dir = "D:\Github Repositories\Opticon\_harness\notif_model"
$srcs = @("fw_stat_notify_chat.png","fw_stat_notify_gmail.png","fw_stat_sys_warning.png","fw_stat_sys_vp_phone_call.png")
$bgs = @(
  @("blue",  37, 99,235),
  @("red",  220, 38, 38),
  @("green", 22,163, 74),
  @("dark",  31, 41, 55)
)
foreach ($s in $srcs) {
  $img = [System.Drawing.Image]::FromFile((Join-Path $dir $s))
  $stem = ($s -replace '^fw_','' -replace '\.png$','')
  # White glyph on a solid background: binarize branch, shape must be the white side.
  foreach ($bg in $bgs) {
    $bmp = New-Object System.Drawing.Bitmap 96,96
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::FromArgb(255,$bg[1],$bg[2],$bg[3]))
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($img, 16, 16, 64, 64)
    $g.Dispose()
    $name = "solid_" + $bg[0] + "_" + $stem + ".png"
    $bmp.Save((Join-Path $dir $name), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
  }
  # Dark glyph on a light opaque background: shape must be the dark side.
  $inv = New-Object System.Drawing.Bitmap 96,96
  for ($y = 0; $y -lt 96; $y++) {
    for ($x = 0; $x -lt 96; $x++) {
      $r = $x - 16
      $c = $y - 16
      $dark = $false
      if ($r -ge 0 -and $r -lt 64 -and $c -ge 0 -and $c -lt 64) {
        $sx = [int]($r * $img.Width / 64)
        $sy = [int]($c * $img.Height / 64)
        if ($sx -lt $img.Width -and $sy -lt $img.Height) {
          if ($img.GetPixel($sx, $sy).A -gt 96) { $dark = $true }
        }
      }
      if ($dark) {
        $inv.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, 16, 16, 16))
      } else {
        $inv.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, 244, 244, 244))
      }
    }
  }
  $inv.Save((Join-Path $dir ("solid_light_" + $stem + ".png")), [System.Drawing.Imaging.ImageFormat]::Png)
  $inv.Dispose()
  $img.Dispose()
}
Write-Output "done"
