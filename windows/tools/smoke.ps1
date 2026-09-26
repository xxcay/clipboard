# Smoke test on a real Windows machine (GitHub Actions windows-latest):
# starts clipd, launches the app, checks it stays alive and connects,
# and takes screenshots. Usage: smoke.ps1 -Exe path\to\SharedClipboard.exe -Clipd path\to\clipd.exe -Out dir
param([string]$Exe, [string]$Clipd, [string]$Out, [string]$Name = "app")
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force $Out | Out-Null
Add-Type -AssemblyName System.Windows.Forms, System.Drawing

function Shot($file) {
    $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size)
    $bmp.Save((Join-Path $Out $file))
}

$data = Join-Path $env:TEMP "clipd-smoke"
Remove-Item -Recurse -Force $data -ErrorAction SilentlyContinue
$env:CLIPD_TOKEN = "tok"
$clipdProc = Start-Process $Clipd -ArgumentList "-listen 127.0.0.1:8765 -data `"$data`"" -PassThru -WindowStyle Hidden
Start-Sleep 2
$h = @{ Authorization = "Bearer tok"; "X-Device" = "s24" }
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8765/api/clip -Headers $h -Body "https://example.com/link" | Out-Null
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8765/api/clip -Headers $h -Body "Привет с телефона" | Out-Null
$f = Join-Path $env:TEMP "photo.bin"; [IO.File]::WriteAllBytes($f, (1..300000 | ForEach-Object { [byte]($_ % 251) }))
Invoke-RestMethod -Method Put -Uri "http://127.0.0.1:8765/api/files?name=IMG_2031.jpg" -Headers $h -InFile $f | Out-Null

$appData = Join-Path $env:APPDATA "SharedClipboard"
$local = Join-Path $env:LOCALAPPDATA "SharedClipboard"
Remove-Item -Recurse -Force $appData, $local -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $appData | Out-Null
'{ "serverUrl": "http://127.0.0.1:8765", "token": "tok", "deviceName": "CI", "autostart": false }' |
    Set-Content -Encoding utf8 (Join-Path $appData "settings.json")

$started = Get-Date
$p = Start-Process $Exe -PassThru
Start-Sleep 15
Shot "$Name-1.png"
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8765/api/clip -Headers $h -Body "Новая запись" | Out-Null
Start-Sleep 3
Shot "$Name-2.png"

$ok = $true
Write-Host "=== process"
if ($p.HasExited) { Write-Host "EXITED with code $($p.ExitCode)"; $ok = $false } else { Write-Host "running, pid $($p.Id)" }
Write-Host "=== hub"
$health = Invoke-RestMethod http://127.0.0.1:8765/healthz
$health | ConvertTo-Json | Write-Host
if ($health.devices -notcontains "CI") { Write-Host "app is NOT connected"; $ok = $false }
Write-Host "=== app log"
Get-Content (Join-Path $local "log.txt") -ErrorAction SilentlyContinue | Write-Host
Get-Content (Join-Path $local "crash.txt") -ErrorAction SilentlyContinue | Write-Host
Write-Host "=== cache"
Get-ChildItem -Recurse (Join-Path $local "cache") -ErrorAction SilentlyContinue | Select-Object FullName, Length | Format-Table | Out-String | Write-Host
Write-Host "=== Windows event log (.NET / crashes since start)"
Get-WinEvent -FilterHashtable @{ LogName = "Application"; StartTime = $started } -ErrorAction SilentlyContinue |
    Where-Object { $_.ProviderName -in ".NET Runtime", "Application Error", "Windows Error Reporting" } |
    ForEach-Object { Write-Host "[$($_.ProviderName)] $($_.Message)" }

if (-not $p.HasExited) { Stop-Process $p -Force }
Stop-Process $clipdProc -Force
if (-not $ok) { exit 1 }
