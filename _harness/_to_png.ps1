# Convert one image file (webp/png/jpg) to PNG using Windows Imaging Component.
# Why not ffmpeg/dwebp: neither is installed on this machine (checked 2026-11-15:
# `where ffmpeg` and Get-Command ffmpeg both empty), while WIC decodes WebP natively.
# Keep this file ASCII-only: Windows PowerShell reads .ps1 as the ANSI codepage (GBK here),
# so any Chinese literal turns the script into a syntax error.
param(
  [Parameter(Mandatory=$true)][string]$Src,
  [Parameter(Mandatory=$true)][string]$Dst
)
Add-Type -AssemblyName PresentationCore
try {
  $d = [System.Windows.Media.Imaging.BitmapDecoder]::Create(
    [Uri]$Src,
    [System.Windows.Media.Imaging.BitmapCreateOptions]::PreservePixelFormat,
    [System.Windows.Media.Imaging.BitmapCacheOption]::OnLoad)
  $frame = $d.Frames[0]
  $enc = New-Object System.Windows.Media.Imaging.PngBitmapEncoder
  $enc.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($frame))
  $fs = [System.IO.File]::Create($Dst)
  $enc.Save($fs)
  $fs.Close()
  Write-Output ("OK " + $frame.PixelFormat.ToString() + " " + $frame.PixelWidth + "x" + $frame.PixelHeight)
} catch {
  Write-Output ("FAIL " + $_.Exception.Message)
  exit 1
}
