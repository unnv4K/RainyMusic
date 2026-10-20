# Rainy: Audio player (Windows)
# Starts a local server on 127.0.0.1 only and opens an Edge (or Chrome) app window without tabs.
$ErrorActionPreference = 'SilentlyContinue'
$port = 47831
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$url  = "http://127.0.0.1:$port/"
$prof = Join-Path $env:LOCALAPPDATA 'RainyPlayer\browser'
New-Item -ItemType Directory -Force -Path $prof | Out-Null

$cands = @(
  "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
  "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
  "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
  "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
  "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe"
)
$browser = $cands | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $browser) {
  Add-Type -AssemblyName PresentationFramework
  [System.Windows.MessageBox]::Show('Microsoft Edge or Google Chrome was not found.', 'Rainy') | Out-Null
  exit 1
}

$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add($url)
$serving = $false
try { $listener.Start(); $serving = $true } catch { }

$bargs = @("--app=$url", "--user-data-dir=`"$prof`"", '--window-size=480,880', '--no-first-run', '--no-default-browser-check')
Start-Process -FilePath $browser -ArgumentList $bargs
if (-not $serving) { exit 0 }   # server is already running in another instance

$html = Join-Path $root 'index.html'
$started = Get-Date
$ar = $listener.BeginGetContext($null, $null)
while ($true) {
  if ($ar.AsyncWaitHandle.WaitOne(3000)) {
    $ctx = $listener.EndGetContext($ar)
    $ar = $listener.BeginGetContext($null, $null)
    $res = $ctx.Response
    try {
      $p = $ctx.Request.Url.AbsolutePath
      if ($p -eq '/' -or $p -eq '/index.html') {
        $bytes = [System.IO.File]::ReadAllBytes($html)
        $res.ContentType = 'text/html; charset=utf-8'
        $res.Headers.Add('Cache-Control', 'no-cache')
        $res.ContentLength64 = $bytes.Length
        $res.OutputStream.Write($bytes, 0, $bytes.Length)
      } else {
        $res.StatusCode = 404
      }
    } catch { }
    try { $res.Close() } catch { }
  }
  elseif (((Get-Date) - $started).TotalSeconds -gt 45) {
    # exit when the player window is closed
    $alive = Get-CimInstance Win32_Process -Filter "Name='msedge.exe' OR Name='chrome.exe'" |
             Where-Object { $_.CommandLine -like '*RainyPlayer*' }
    if (-not $alive) { break }
  }
}
$listener.Stop()
