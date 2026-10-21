$procs = Get-CimInstance Win32_Process -Filter "Name='adb.exe'" |
  Where-Object { $_.CommandLine -like '*logcat -s Opticon*' }
if (-not $procs) { '没有匹配的 logcat 进程'; exit 0 }
foreach ($p in $procs) { Stop-Process -Id $p.ProcessId -Force; "killed $($p.ProcessId)" }
