# Builds Codio for Windows on Windows: the web UI plus Codio.exe into dist\Codio-win64,
# then the installer dist\CodioSetup-<version>.exe with NSIS.
#
#   -ShellDir  the Codio shell checkout, built with
#              cargo build --release --target x86_64-pc-windows-gnu
#   -NodeExe   an official Node.js node.exe (win-x64); shipped as codio-runtime.exe
#   -Makensis  NSIS's makensis.exe
#   -SkipWebBuild  reuse an existing build\ folder
param(
    [Parameter(Mandatory)] [string]$ShellDir,
    [Parameter(Mandatory)] [string]$NodeExe,
    [Parameter(Mandatory)] [string]$Makensis,
    [Parameter(Mandatory)] [string]$Version,
    [switch]$SkipWebBuild
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

if (-not $SkipWebBuild) {
    & pnpm build
    if ($LASTEXITCODE) { throw 'pnpm build failed' }
}

$release = Join-Path $ShellDir 'target\x86_64-pc-windows-gnu\release'
$cargoHome = if ($env:CARGO_HOME) { $env:CARGO_HOME } else { Join-Path $env:USERPROFILE '.cargo' }
$loader = Get-ChildItem (Join-Path $cargoHome 'registry\src') -Recurse -Filter WebView2Loader.dll |
    Where-Object { $_.FullName -like '*webview2-sys-*\x64\*' } | Select-Object -First 1
if (-not $loader) { throw 'WebView2Loader.dll (x64) not found in the cargo registry' }
$mpv = Get-ChildItem $ShellDir -Recurse -Filter libmpv-2.dll | Where-Object { $_.FullName -notlike '*arm64*' } | Select-Object -First 1
if (-not $mpv) { throw 'libmpv-2.dll not found in the shell checkout (build it first)' }

$out = Join-Path $repo 'dist\Codio-win64'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null
Copy-Item -Recurse (Join-Path $repo 'build') (Join-Path $out 'web')
Copy-Item desktop\codio-web.js, desktop\codio-downloads.js, desktop\codio.ico, desktop\README.txt $out
Copy-Item (Join-Path $release 'codio.exe') (Join-Path $out 'Codio.exe')
Copy-Item $NodeExe (Join-Path $out 'codio-runtime.exe')
Copy-Item $mpv.FullName, $loader.FullName $out
# ffmpeg / ffprobe (downloads) and the runtime DLLs the player needs.
Get-ChildItem (Join-Path $ShellDir 'bin') -File | Where-Object { $_.Extension -in '.exe', '.dll' -and $_.Name -ne 'stremio-runtime.exe' } |
    Copy-Item -Destination $out

& $Makensis -V2 "-DVERSION=$Version" desktop\installer\codio.nsi
if ($LASTEXITCODE) { throw 'makensis failed' }
Move-Item -Force "desktop\installer\CodioSetup-$Version.exe" dist\
Write-Output "Created dist\CodioSetup-$Version.exe"
