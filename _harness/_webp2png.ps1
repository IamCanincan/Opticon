Add-Type -AssemblyName PresentationCore
$src = 'D:\Github Repositories\Opticon\_harness\gR.webp'
$dst = 'D:\Github Repositories\Opticon\_harness\_src_hook.png'
$d = [System.Windows.Media.Imaging.BitmapDecoder]::Create(
  [Uri]$src,
  [System.Windows.Media.Imaging.BitmapCreateOptions]::PreservePixelFormat,
  [System.Windows.Media.Imaging.BitmapCacheOption]::OnLoad)
$e = [System.Windows.Media.Imaging.PngBitmapEncoder]::new()
$e.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($d.Frames[0]))
$f = [System.IO.File]::Create($dst)
$e.Save($f)
$f.Close()
Write-Output ("OK " + $d.Frames[0].PixelWidth + "x" + $d.Frames[0].PixelHeight)
