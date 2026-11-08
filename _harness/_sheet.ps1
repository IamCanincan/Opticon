Add-Type -AssemblyName System.Drawing
$dir = "D:\Github Repositories\Opticon\_harness\bench\notifpreview"
function Sheet($pattern, $out, $cols) {
  $files = Get-ChildItem $dir -Filter $pattern | Where-Object { $_.Name -ne "_sheet.png" -and $_.Name -ne "_solid.png" } | Sort-Object Name
  if ($files.Count -eq 0) { Write-Output "none for $pattern"; return }
  $cw = 304; $ch = 96
  $rows = [math]::Ceiling($files.Count / $cols)
  $sheet = New-Object System.Drawing.Bitmap ($cols * $cw), ([int]$rows * $ch)
  $g = [System.Drawing.Graphics]::FromImage($sheet)
  $g.Clear([System.Drawing.Color]::Black)
  for ($i = 0; $i -lt $files.Count; $i++) {
    $img = [System.Drawing.Image]::FromFile($files[$i].FullName)
    $x = ($i % $cols) * $cw
    $y = [math]::Floor($i / $cols) * $ch
    $g.DrawImage($img, $x, $y, $cw, $ch)
    $img.Dispose()
  }
  $g.Dispose()
  $sheet.Save((Join-Path $dir $out), [System.Drawing.Imaging.ImageFormat]::Png)
  $sheet.Dispose()
  Write-Output "$out n=$($files.Count)"
}
Sheet "fw_*.png" "_sheet.png" 6
Sheet "solid_*.png" "_solid.png" 5
